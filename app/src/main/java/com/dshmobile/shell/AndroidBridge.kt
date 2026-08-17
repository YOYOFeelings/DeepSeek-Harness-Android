package com.dshmobile.shell

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import java.io.File
import org.json.JSONObject

/**
 * JS bridge injected as window.androidBridge (protocol v1, see
 * docs/apk-shell-design.md). All methods are callable from the page; results
 * that arrive asynchronously are delivered back through
 * window.__dshBridge.onDirectoryPicked(callbackId, path) on the main thread.
 */
class AndroidBridge(
  private val context: android.content.Context,
  private val onPickRequest: (callbackId: String) -> Unit,
  private val onKeepScreen: (enable: Boolean) -> Unit,
  private val onNotify: (title: String, text: String) -> Unit,
  private val onAllFilesAccessRequest: () -> Unit = {},
  private val onDebugLogsRequest: () -> Unit = {},
  private val pickToken: String? = null,
) {

  @JavascriptInterface
  fun version(): String = "1.0"

  @JavascriptInterface
  fun checkEngine(): String = EngineProbe.check().toString()

  @JavascriptInterface
  fun keepScreenOn(enable: Boolean) {
    onKeepScreen(enable)
  }

  @JavascriptInterface
  fun showNotification(title: String, text: String) {
    onNotify(title, text)
  }

  @JavascriptInterface
  fun pickDirectory(callbackId: String) {
    onPickRequest(callbackId)
  }

  /** 调试日志导出：引擎日志 + 环境信息打包 zip（走会话导出同款下载/弹窗链路）。 */
  @JavascriptInterface
  fun downloadDebugLogs() {
    onDebugLogsRequest()
  }

  /** True when the app holds All Files Access (external workspace requirement). */
  @JavascriptInterface
  fun hasAllFilesAccess(): Boolean {
    // isExternalStorageManager 仅 API 30+ 存在；低版本无该权限模型。
    if (android.os.Build.VERSION.SDK_INT < 30) return false
    return android.os.Environment.isExternalStorageManager()
  }

  /** Open the system screen granting All Files Access (special permission). */
  @JavascriptInterface
  fun requestAllFilesAccess() {
    onAllFilesAccessRequest()
  }

  /** 目录选择桥的一次性会话 token（引擎侧 pick 端点校验；null = 未启用）。 */
  @JavascriptInterface
  fun getPickToken(): String? = pickToken

  /** 配置桥：把 key/value 持久化到 dsh_shell SharedPreferences，返回是否写盘成功。
   *  对 key 做注入防护：长度 ≤ 128、仅允许 [A-Za-z0-9._-]、拒绝 `..`/`/` 与空键，
   *  防止路径注入/越权键（基线四改法之一）。 */
  @JavascriptInterface
  fun saveConfig(key: String, value: String): Boolean {
    if (!isSafeConfigKey(key)) {
      Logs.logE(context, "bridge", "saveConfig 拒绝非法键: " + (key ?: "null"))
      return false
    }
    return context.getSharedPreferences("dsh_shell", android.content.Context.MODE_PRIVATE)
      .edit().putString(key, value).commit()
  }

  /** 配置桥：读取 dsh_shell SharedPreferences 中的字符串配置（缺失返回空串）。
   *  与 [saveConfig] 同款键名校验，防止构造路径型键越权读取。 */
  @JavascriptInterface
  fun readConfig(key: String): String {
    if (!isSafeConfigKey(key)) return ""
    return context.getSharedPreferences("dsh_shell", android.content.Context.MODE_PRIVATE)
      .getString(key, "") ?: ""
  }

  /** 键名校验：非空、长度 ≤ 128、仅允许 [A-Za-z0-9._-]，不含 `..` 或 `/`。
   *  BUG-12 修复：拦截以 `__dshc__` 开头的键，防止与 storageShimScript 内部元数据键
   *    （`__dshc__m:`、`__dshc__p:`）冲突导致数据错乱。 */
  private fun isSafeConfigKey(key: String): Boolean {
    if (key.isEmpty() || key.length > 128) return false
    if (!Regex("^[A-Za-z0-9._-]+$").matches(key)) return false
    if (key.contains("..") || key.contains('/')) return false
    if (key.startsWith("__dshc__")) return false
    return true
  }

  /** 配置桥：把 settings.yaml 全文写入 DSH_HOME（失败返回 false）。 */
  @JavascriptInterface
  fun saveSettingsYaml(yamlContent: String): Boolean {
    return try {
      val dshHome = File(File(context.filesDir, "home"), ".dsh")
      dshHome.mkdirs()
      File(dshHome, "settings.yaml").writeText(yamlContent)
      true
    } catch (_: Throwable) {
      false
    }
  }

  // ── 槽位配置桥（Web 端 localStorage 宿主化落盘）────────────────────────────
  // 插件的设置保存依赖浏览器 localStorage，在 WebView 中可能静默失败（存储被
  // 禁用/配额超限），导致「打开配置文件显示无法打开 / 保存不生效」。这些方法把
  // 值落到 DSH_HOME/.dsh/web-slots/<slot>.json（应用私有、免权限、引擎可见），
  // Web 页注入的 localStorage 代理把 setItem/getItem 路由到这里。

  /** 槽位配置桥：写入 DSH_HOME/.dsh/web-slots/<slot>.json，成功返回 true。 */
  @JavascriptInterface
  fun saveSlotConfig(slot: String, content: String): Boolean {
    return try {
      val dir = slotDir()
      dir.mkdirs()
      File(dir, sanitizeSlot(slot) + ".json").writeText(content)
      true
    } catch (_: Throwable) {
      false
    }
  }

  /** 槽位配置桥：读取 DSH_HOME/.dsh/web-slots/<slot>.json；文件缺失/异常返回 null。 */
  @JavascriptInterface
  fun readSlotConfig(slot: String): String? {
    return try {
      val f = File(slotDir(), sanitizeSlot(slot) + ".json")
      if (f.isFile) f.readText() else null
    } catch (_: Throwable) {
      null
    }
  }

  /** 槽位配置桥：删除 DSH_HOME/.dsh/web-slots/<slot>.json。 */
  @JavascriptInterface
  fun removeSlotConfig(slot: String): Boolean {
    return try {
      val f = File(slotDir(), sanitizeSlot(slot) + ".json")
      f.exists() && f.delete()
    } catch (_: Throwable) {
      false
    }
  }

  /** 槽位配置桥：清空所有宿主化槽位文件（对应 localStorage.clear）。 */
  @JavascriptInterface
  fun clearSlotConfigs(): Boolean {
    return try {
      val dir = slotDir()
      if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
      true
    } catch (_: Throwable) {
      false
    }
  }

  private fun slotDir(): File = File(File(context.filesDir, "home"), ".dsh/web-slots")

  /** 槽位名 → 安全文件名：仅保留 [A-Za-z0-9._-]，其余替换为下划线（防路径穿越）。 */
  private fun sanitizeSlot(slot: String): String {
    val safe = slot.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (safe.isEmpty()) "unnamed" else safe
  }

  companion object {
    /**
     * Map an ACTION_OPEN_DOCUMENT_TREE result onto a Termux-visible real path
     * when possible: "primary:rel/path" -> /storage/emulated/0/rel/path.
     * Non-primary volumes fall back to the raw content:// tree URI (the page
     * can still use it as an opaque handle).
     * @param uri the tree URI from the system picker.
     * @returns the mapped real path or the original URI string.
     */
    fun resolvePickedPath(uri: Uri): String {
      return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val idx = docId.indexOf(':')
        val volume = if (idx > 0) docId.substring(0, idx) else ""
        val rel = if (idx > 0) docId.substring(idx + 1) else docId
        if (volume == "primary" && rel.isNotEmpty()) "/storage/emulated/0/$rel" else uri.toString()
      } catch (_: Exception) {
        uri.toString()
      }
    }
  }
}

/** JSON string literal escaping for evaluateJavascript payloads. */
internal fun jsString(value: String): String = JSONObject.quote(value)
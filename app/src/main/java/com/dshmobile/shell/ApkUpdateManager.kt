package com.dshmobile.shell

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** APK 自更新管理器（独立于 usr 运行时 UpdateManager，两者互不干扰）。
 *  功能：GitHub 最新 Release 版本检查 → 更新弹窗（普通/强制）→ DownloadManager 下载 → FileProvider 安装。 */
class ApkUpdateManager(private val context: Context) {

  companion object {
    const val REPO_URL = "https://github.com/kcln243107/DeepSeek-Harness-Android"
    const val RELEASE_API = "https://api.github.com/repos/kcln243107/DeepSeek-Harness-Android/releases/latest"
    const val APK_FILE_NAME = "deepseek-harness-update.apk"

    private const val PREFS = "dsh_shell"
    private const val KEY_DOWNLOAD_ID = "apk_update_download_id"
  }

  /** 更新信息。 */
  data class UpdateInfo(
    val hasNew: Boolean,
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val isForced: Boolean,
  )

  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** 更新源管理器（镜像测速/URL 解析复用）。 */
  private val updateManager by lazy { UpdateManager.forPrefs(context) }

  /** 最近一次下载任务的 downloadId（-1 = 无）。 */
  fun lastDownloadId(): Long = prefs.getLong(KEY_DOWNLOAD_ID, -1L)

  /** 当前 APK 下载的待尝试加速源 URL 队列（用于下载失败时自动换源回退）。 */
  private val pendingDownloadUrls = java.util.concurrent.ConcurrentLinkedQueue<String>()

  /** 解析 GitHub 直链为加速地址（经镜像前缀；无前缀源原样返回）。 */
  fun resolveApkUrl(apkUrl: String, mirror: Mirror? = null): String =
    updateManager.resolveForDownload(apkUrl, mirror ?: updateManager.activeMirror)

  /** 逐源测速并返回最快可用源（用于 APK 下载前选最快镜像；全失败返回 null）。 */
  fun fastestMirror(onEach: (Mirror, Long?) -> Unit = { _, _ -> }): Mirror? =
    updateManager.speedTestAll(onEach)

  /** 查询 DownloadManager 任务状态（STATUS_* 常量；未找到返回 null）。 */
  fun downloadStatus(id: Long): Int? {
    if (id < 0) return null
    return try {
      val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      val cursor = dm.query(DownloadManager.Query().setFilterById(id))
      try {
        if (cursor.moveToFirst()) {
          cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        } else null
      } finally {
        cursor.close()
      }
    } catch (_: Throwable) {
      null
    }
  }

  /** APK 有效性检测：存在、非空、长度 > 1MB 且带 ZIP（PK）头。 */
  fun isApkValid(file: File?): Boolean {
    if (file == null || !file.exists() || file.length() < 1024 * 1024) return false
    return try {
      file.inputStream().use { input ->
        val head = ByteArray(4)
        if (input.read(head) != 4) return false
        val pk = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        // BUG-17 修复：ZIP 规范版本字节有效范围 0x00-0x33（51 个版本），
        //   原代码仅接受 0x01-0x07 可能误拒新版 APK。放宽为 0x00-0x50 覆盖所有已知版本。
        val ver = (head[2].toInt() and 0xff) in 0x00..0x50
        pk && ver
      }
    } catch (_: Throwable) {
      false
    }
  }

  /** 最近一次下载完成的 APK（存在性；有效性由调用方校验）。 */
  fun lastDownloadedApk(): File? = findDownloadedApk()

  /** 检查更新：后台拉取 GitHub 最新 Release，与本地 versionName 语义化比较。
   *  调用方应处于协程；回调在调用方协程上下文（主线程）执行。 */
  suspend fun checkUpdate(
    onResult: (UpdateInfo) -> Unit,
    onError: (String) -> Unit,
  ) {
    try {
      val info = withContext(Dispatchers.IO) { fetchLatest() }
      onResult(info)
    } catch (t: Throwable) {
      onError(t.message ?: t.javaClass.simpleName)
    }
  }

  private fun fetchLatest(): UpdateInfo {
    // 候选：官方 API 优先，失败后经激活源/默认源镜像代理重试
    val candidates = mutableListOf(RELEASE_API)
    val mirror = updateManager.activeMirror ?: updateManager.mirrorById(UpdateManager.DEFAULT_MIRROR_ID)
    if (mirror != null) candidates.add(updateManager.resolveForDownload(RELEASE_API, mirror))
    var lastErr: Throwable? = null
    var json: JSONObject? = null
    for (c in candidates) {
      try {
        val conn = URL(c).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "DeepSeek-Harness-Android")
        val code = conn.responseCode
        if (code in 200..299) {
          val text = conn.inputStream.bufferedReader().use { it.readText() }
          if (text.isNotBlank() && !looksLikeHtml(text)) {
            json = JSONObject(text)
            break
          }
          lastErr = RuntimeException("GitHub API 返回非 JSON 内容")
        } else {
          lastErr = RuntimeException("GitHub API $code")
        }
      } catch (t: Throwable) {
        lastErr = t
      }
    }
    val resp = json ?: throw (lastErr ?: RuntimeException("无法获取更新信息"))
    val tag = resp.optString("tag_name", "")
    val remoteVersion = tag.removePrefix("v")
    val body = resp.optString("body", "")
    var apkUrl = ""
    val assets = resp.optJSONArray("assets")
    if (assets != null) {
      for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name", "")
        if (name.endsWith(".apk", ignoreCase = true)) {
          apkUrl = asset.optString("browser_download_url", "")
          break
        }
      }
    }
    val localVersion = localVersionName()
    val hasNew = remoteVersion.isNotBlank() && apkUrl.isNotBlank() && compareVersions(remoteVersion, localVersion) > 0
    val isForced = body.contains("强制更新") || body.contains("FORCE", ignoreCase = true)
    return UpdateInfo(hasNew, remoteVersion, body, apkUrl, isForced)
  }

  /** 响应是否为 HTML/垃圾页（镜像可能返回 404/挑战页而非 JSON）。 */
  private fun looksLikeHtml(body: String): Boolean {
    val trimmed = body.trim()
    return trimmed.startsWith("<!doctype", ignoreCase = true) ||
      trimmed.startsWith("<html", ignoreCase = true) ||
      trimmed.startsWith("<!--") ||
      (trimmed.startsWith("<") && (trimmed.contains("<head", ignoreCase = true) ||
        trimmed.contains("<body", ignoreCase = true) || trimmed.contains("<title", ignoreCase = true)))
  }

  private fun localVersionName(): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
  } catch (_: Throwable) {
    ""
  }

  /** 语义化版本比较：大于返回 1，等于 0，小于 -1。 */
  private fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
      val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
      val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
      if (x != y) return if (x > y) 1 else -1
    }
    return 0
  }

  /** Android 8+ 是否已授权安装未知来源。 */
  fun canInstall(): Boolean =
    Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

  /** 更新弹窗（统一美化，全部可关闭）。forced=true 时提供「退出应用」出口避免死锁；
   *  非强制可「稍后提醒」。onDownload：用户点击立即更新（传入下载 URL）；
   *  onOpenInstallSettings：跳转安装权限设置页；onDismissed：非强制模式下弹窗被关闭后回调
   *  （用于恢复被暂停的启动流程；返回键/点外部/稍后提醒均会触发，立即更新不触发）。 */
  fun showUpdateDialog(
    info: UpdateInfo,
    forced: Boolean,
    onDownload: (String) -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismissed: (() -> Unit)? = null,
  ) {
    val notes = if (info.notes.isBlank())
      I18n.t(context, "请更新到最新版本体验新功能。", "Please update to the latest version to enjoy new features.")
    else info.notes
    val actions = mutableListOf(
      DialogUi.Action(I18n.t(context, "立即更新", "Update now"), accent = true) {
        if (!canInstall()) {
          onOpenInstallSettings()
        } else {
          onDownload(info.downloadUrl)
        }
      },
    )
    if (forced) {
      actions.add(
        DialogUi.Action(I18n.t(context, "退出应用", "Exit app")) {
          try { (context as? android.app.Activity)?.finishAffinity() } catch (_: Throwable) {}
        },
      )
    } else {
      actions.add(
        DialogUi.Action(I18n.t(context, "稍后提醒", "Remind me later")) {
          onDismissed?.invoke()
        },
      )
    }
    DialogUi.show(
      context,
      title = I18n.t(context, "发现新版本 v", "New version v") + info.version,
      message = notes,
      iconRes = R.drawable.ic_update,
      actions = actions,
      // BUG-10 修复：强制更新不可取消（返回键/点外部均无效）；普通更新保持可取消。
      cancelable = !forced,
      onCancel = { if (!forced) onDismissed?.invoke() },
    )
  }

  /** 使用系统 DownloadManager 下载 APK 到外部缓存目录，通知栏显示进度；返回 downloadId。
   *  默认（未显式选择源时）以内置默认加速源兜底，并按「激活源优先 + 其余源 + 官方直连」构造
   *  多源回退队列（对 GitHub 直链做镜像解析），首个候选立即 enqueue；
   *  下载失败或产物无效时由 retryWithNextSource() 自动换源接力。 */
  fun startDownload(downloadUrl: String): Long {
    val um = updateManager
    val active = um.activeMirror ?: um.mirrorById(UpdateManager.DEFAULT_MIRROR_ID)
    val all = um.allMirrors()
    val ordered = listOfNotNull(active) + all.filter { it.id != active?.id }
    pendingDownloadUrls.clear()
    for (m in ordered) pendingDownloadUrls.offer(um.resolveForDownload(downloadUrl, m))
    // 镜像全失败后的最后兜底：官方直连
    pendingDownloadUrls.offer(downloadUrl)
    val url = pendingDownloadUrls.poll() ?: downloadUrl
    return enqueueDownload(url)
  }

  /** 下载失败/产物无效时调用：换下一个候选加速源重新下载；无候选返回 null。 */
  fun retryWithNextSource(): Long? {
    val url = pendingDownloadUrls.poll() ?: return null
    return enqueueDownload(url)
  }

  /** 真正 enqueue 下载任务并记录 downloadId（供下载完成广播匹配）。 */
  private fun enqueueDownload(url: String): Long {
    val destDir = context.getExternalCacheDir() ?: context.cacheDir
    val dest = File(destDir, APK_FILE_NAME)
    if (dest.exists()) dest.delete()
    val request = DownloadManager.Request(Uri.parse(url))
      .setTitle(I18n.t(context, "deepseek HARNESS 更新", "deepseek HARNESS update"))
      .setDescription(I18n.t(context, "正在下载新版本 APK…", "Downloading new APK…"))
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationUri(Uri.fromFile(dest))
      .setAllowedOverMetered(true)
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val id = dm.enqueue(request)
    prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
    return id
  }

  /** 通过 FileProvider 触发系统安装。 */
  fun installApk(apkFile: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apkFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
  }

  /** 定位已下载完成的 APK（外部缓存目录）。 */
  fun findDownloadedApk(): File? {
    val dir = context.getExternalCacheDir() ?: context.cacheDir
    val f = File(dir, APK_FILE_NAME)
    return if (f.exists()) f else null
  }
}
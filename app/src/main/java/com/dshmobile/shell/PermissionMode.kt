package com.dshmobile.shell

import android.content.Context
import java.io.File

/**
 * 权限模式模型：普通 / adb / ROOT / adb(shizuku) 四档。
 * 按所选模式决定命令执行通道（容器调用方式）：
 *  - NORMAL：应用沙箱内直接 exec（默认，无需授权）；
 *  - ADB：经 adb shell（无线调试）执行；
 *  - ROOT：经 su -c 执行；
 *  - SHIZUKU：经 Shizuku binder 以 shell 权限执行。
 * 每种模式带 available() 可行性探测与前置依赖说明；不可用时调用方优雅降级到 NORMAL。
 * 持久化到 dsh_shell prefs（key [PREFS_KEY]，默认 [DEFAULT_ID]）。
 */
enum class PermissionMode(
  val id: String,
  val label: String,
  val desc: String,
  val prereq: String,
) {
  NORMAL("normal", "普通权限", "应用沙箱内直接执行命令", "无需额外授权"),
  ADB("adb", "adb 权限", "经 adb shell（无线调试）执行", "需开启「无线调试」并配对授权"),
  ROOT("root", "ROOT 权限", "经 su 以 root 权限执行", "需设备已 root 且 su 可用"),
  SHIZUKU("shizuku", "adb(shizuku) 权限", "经 Shizuku binder 以 shell 权限执行", "需安装 Shizuku 并授权本应用");

  /** 当前设备上该模式是否可用（探测无副作用、不触发授权弹窗）。 */
  fun available(context: Context): Boolean = when (this) {
    NORMAL -> true
    ADB -> adbAvailable()
    ROOT -> rootAvailable()
    SHIZUKU -> ShizukuSupport.isAvailable()
  }

  /** 状态文案（可用/不可用 + 原因），供引导页/设置页展示。
   *  注意：必须轻量、不阻塞主线程——ROOT 只用路径快速判断（真实提权探测见 [probeRoot]，
   *  供后台线程/设置页异步刷新用，避免主线程 exec 卡顿）。 */
  fun status(context: Context): String = when (this) {
    NORMAL -> "始终可用"
    ADB -> if (adbAvailable()) "可用（已开启无线调试）" else "不可用：需开启无线调试"
    ROOT -> if (suPresent()) "可用（检测到 su，点击选择后生效）" else "不可用：未检测到 su"
    SHIZUKU -> if (ShizukuSupport.isAvailable()) "可用（已授权）" else "不可用：需安装并授权 Shizuku"
  }

  /** 状态文案的真实探测版本（ROOT 会实际执行 `su -c id`），仅供后台线程调用
   *  （设置页异步刷新用），避免在主线程阻塞 exec。 */
  fun probeStatus(context: Context): String = when (this) {
    NORMAL -> "始终可用"
    ADB -> if (adbAvailable()) "可用（已开启无线调试）" else "不可用：需开启无线调试"
    ROOT -> if (probeRoot()) "可用（已授权 root）" else "不可用：su 未授权、拒绝或超时"
    SHIZUKU -> if (ShizukuSupport.isAvailable()) "可用（已授权）" else "不可用：需安装并授权 Shizuku"
  }

  companion object {
    const val PREFS_KEY = "settings_perm_mode"
    const val DEFAULT_ID = "normal"

    fun fromId(id: String?): PermissionMode = entries.firstOrNull { it.id == id } ?: NORMAL

    /** 读取持久化的权限模式（缺省 NORMAL）。 */
    fun load(context: Context): PermissionMode = fromId(
      context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
        .getString(PREFS_KEY, DEFAULT_ID),
    )

    /** 持久化所选权限模式。 */
    fun save(context: Context, mode: PermissionMode) {
      context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
        .edit().putString(PREFS_KEY, mode.id).apply()
    }

    /**
     * adb 可用性：沙箱内一般无系统 adb 二进制（无线调试由系统设置提供），
     * 此处探测 adb 是否在 PATH 且可执行；不可用即提示「开启无线调试」。
     */
    private fun adbAvailable(): Boolean = try {
      val p = ProcessBuilder("adb", "version").redirectErrorStream(true).start()
      val ok = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
      if (!ok) p.destroyForcibly()
      ok
    } catch (_: Throwable) {
      false
    }

    /**
     * ROOT 可用性：真实提权探测。先快速路径扫描（PATH/常见路径下无 su 直接 false，
     * 不再 exec），有 su 再执行 `su -c id`，输出含 `uid=0` 才算可用——
     * 修复「明明有 su 文件/权限位，但 su 未授权、拒绝或超时，导致提权工具无法使用」
     * 的误判（旧实现只看 su.canExecute() 权限位）。
     * 注意：会实际拉起 su（首次可能在 root 管理器弹授权框），不要在主线程调用。
     */
    private fun rootAvailable(): Boolean = probeRoot()

    /** su 是否存在于 PATH/常见路径且可执行（不提权、不弹授权框，主线程安全）。 */
    private fun suPresent(): Boolean {
      val paths = (System.getenv("PATH") ?: "/system/bin:/system/xbin")
        .split(':').filter { it.isNotBlank() }
      val candidates = paths.map { File(it, "su") } + listOf(
        File("/system/xbin/su"), File("/system/bin/su"), File("/sbin/su"),
        File("/su/bin/su"), File("/system/bin/.ext/.su"), File("/vendor/bin/su"),
      )
      return candidates.any { it.canExecute() }
    }

    /** 真实提权探测（必须后台线程调用）：`su -c id` 输出含 `uid=0` 即 root 可用。
     *  超时自动 kill，避免 su 授权弹窗/挂起卡死调用方。 */
    fun probeRoot(timeoutMs: Long = 2000): Boolean {
      if (!suPresent()) return false
      return try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) {
          p.destroyForcibly()
          false
        } else {
          val out = p.inputStream.bufferedReader().use { it.readText() }
          out.contains("uid=0")
        }
      } catch (_: Throwable) {
        false
      }
    }

    /** 供 ShellExecutor/设置页「测试提权」用：等价于 [probeRoot]（后台线程调用）。 */
    fun testRoot(context: Context): Boolean = probeRoot()
  }
}

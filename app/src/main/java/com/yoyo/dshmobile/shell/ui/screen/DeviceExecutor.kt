package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.onboarding.MODE_NORMAL
import com.yoyo.dshmobile.shell.onboarding.currentMode
import com.yoyo.dshmobile.shell.onboarding.RootHelper
import com.yoyo.dshmobile.shell.onboarding.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 命令执行统一结果。 */
data class ExecResult(val ok: Boolean, val output: String, val engine: String)

/**
 * 设备命令共享执行器：Shizuku 优先，失败/不可用时回退 Root(su)。
 * 对话页与终端页共用；命令在 IO 线程执行。
 * 越权控制：读取当前权限模式，普通用户只能执行安全白名单命令，危险命令直接拦截不执行。
 */
object DeviceExecutor {

  /** 普通用户允许执行的安全命令（只读查询为主，白名单之外的均拦截）。 */
  private val SAFE_COMMANDS = setOf(
    "ls", "pwd", "echo", "id", "whoami", "date", "uname", "getprop", "printenv",
    "hostname", "uptime", "free", "df", "du", "cat", "head", "tail", "stat",
    "ps", "pgrep", "which", "wc", "env", "ip", "ping", "getenforce",
  )

  /** 危险关键字：命中即视为越权，普通用户拦截（匹配小写后的子串）。 */
  private val BLOCKED_FRAGMENTS = listOf(
    "su ", "sudo", "bash", "ksh", "/sh ", "sh -c", "shell s", "reboot", "shutdown",
    " pm ", " am ", "settings ", "dumpsys ", "cmd activity", "cmd package", "cmd notification",
    "service ", "mount ", "format ", "mkfs", "flash", "wipe", "dd ",
    "rm -rf", "rm -fr", "chown ", "chmod 777", "@", ">/", "fstrim", "fastboot",
  )

  private fun shizukuReady(): Boolean =
    ShizukuHelper.isRunning && ShizukuHelper.isGranted

  /** 普通用户是否允许执行该命令（忽略首尾空白；小写匹配判断）。 */
  private fun allowedForNormal(cmd: String): Boolean {
    val trimmed = cmd.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (BLOCKED_FRAGMENTS.any { lower.contains(it) }) return false
    // 首词（去路径前缀）必须在安全白名单内
    val first = lower.substringBefore(' ').substringBefore('\n')
      .trimStart('/').substringBeforeLast('/')
    return SAFE_COMMANDS.contains(first)
  }

  /**
   * 执行命令并返回统一结果。
   * - 普通权限用户：白名单沙箱，危险命令拦截不执行；
   * - 高级 / Shizuku：Shizuku 可用且已授权优先，否则回退 `su`(Root)×成功为 Root；
   * - 两者皆不可用返回友好提示；执行与拦截均写入 app-events.log。
   */
  suspend fun run(command: String, context: Context): ExecResult = withContext(Dispatchers.IO) {
    val cmd = command.trim()
    if (cmd.isEmpty()) {
      Logs.logEvent(context, "Exec", "empty command skipped")
      return@withContext ExecResult(ok = false, output = "", engine = "none")
    }

    val mode = currentMode(context)
    // 普通用户沙箱：未在白名单的（或含危险关键字的）一律拦截
    if (mode == MODE_NORMAL && !allowedForNormal(cmd)) {
      Logs.logEvent(context, "Exec", "DENIED mode=$mode cmd=$cmd")
      return@withContext ExecResult(
        ok = false,
        output = "当前为普通权限用户，此命令需要高级 / Shizuku 权限。\n请先在「设置-权限模式」切换，或使用安全命令（ls/pwd/echo/id/cat 等）。",
        engine = "denied",
      )
    }

    if (shizukuReady()) {
      val out = ShizukuHelper.runCommand(cmd)
      Logs.logEvent(context, "Exec", "mode=$mode engine=Shizuku allowed=true cmd=$cmd")
      return@withContext ExecResult(ok = true, output = out, engine = "Shizuku")
    }
    val root = RootHelper.exec(cmd)
    if (root.success) {
      Logs.logEvent(context, "Exec", "mode=$mode engine=Root allowed=true cmd=$cmd")
      ExecResult(ok = true, output = root.output, engine = "Root")
    } else {
      Logs.logEvent(
        context, "Exec",
        "mode=$mode engine=none allowed=true cmd=$cmd deniedBy=no-engine",
      )
      ExecResult(
        ok = false,
        output = "无可用执行引擎（Shizuku 未授权且无 Root）。\nRoot: ${root.output}",
        engine = "none",
      )
    }
  }
}
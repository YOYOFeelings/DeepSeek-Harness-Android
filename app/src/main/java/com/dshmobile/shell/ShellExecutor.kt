package com.dshmobile.shell

import android.content.Context
import java.io.File

/**
 * 命令执行器：按所选权限模式路由命令执行通道，为「不同权限给不同容器」提供统一入口。
 *
 * 通道映射：
 *  - NORMAL：应用沙箱内直接 exec（bash -c，注入引擎同款 env）；
 *  - ROOT：经 `su -c` 以 root 执行；
 *  - ADB：经 `adb shell` 执行；
 *  - SHIZUKU：当前 shizuku-api 13.1.5 无公开 shell-exec（Shizuku.newProcess 不可用），
 *    通道不可用 → 记录日志并降级 NORMAL，不阻断（见 ShizukuSupport）。
 *
 * 任何通道执行失败都返回 Result(exit=-1)，绝不向调用方抛异常；模式不可用自动降级 NORMAL。
 */
object ShellExecutor {

  private const val TAG = "dsh-shell"

  data class Result(val exit: Int, val output: String) {
    val ok: Boolean get() = exit == 0
  }

  /**
   * 执行单条命令并等待完成（阻塞）。
   * @param mode 目标权限模式；不可用时自动降级 NORMAL。
   * @param cmd 完整命令字符串（bash -c 语义；ROOT/ADB 由对应 shell 包装）。
   * @param env 追加环境变量（叠加在通道默认 env 之上）。
   */
  fun run(
    context: Context,
    mode: PermissionMode,
    cmd: String,
    env: Map<String, String> = emptyMap(),
  ): Result {
    val usrDir = File(context.filesDir, "usr")
    val homeDir = File(context.filesDir, "home")
    val effective = if (mode == PermissionMode.NORMAL || mode.available(context)) {
      mode
    } else {
      Logs.logE(context, TAG, "权限模式 ${mode.id} 不可用，降级 NORMAL 执行: $cmd")
      PermissionMode.NORMAL
    }
    return try {
      val argv = when (effective) {
        PermissionMode.ADB -> listOf("adb", "shell", cmd)
        PermissionMode.ROOT -> listOf("su", "-c", cmd)
        // SHIZUKU 无公开 shell-exec：走 NORMAL 通道（bash 沙箱内执行）。
        else -> listOf(File(usrDir, "bin/bash").absolutePath, "-c", cmd)
      }
      val pb = ProcessBuilder(argv)
      pb.environment().putAll(runtimeEnv(usrDir, homeDir))
      pb.environment().putAll(env)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      // ROOT 通道可能因 su 授权弹窗/超时挂起：加超时兜底，避免卡死调用方。
      if (effective == PermissionMode.ROOT) {
        val ok = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) {
          proc.destroyForcibly()
          Logs.logE(context, TAG, "su 执行超时（可能等待授权）: $cmd")
          return Result(-1, "su 执行超时（可能等待授权）")
        }
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        return Result(proc.exitValue(), out.trim())
      }
      val out = proc.inputStream.bufferedReader().use { it.readText() }
      val exit = proc.waitFor()
      Result(exit, out.trim())
    } catch (t: Throwable) {
      Logs.logE(context, TAG, "命令执行失败（mode=${effective.id}）: $cmd", t)
      Result(-1, t.message ?: t.javaClass.simpleName)
    }
  }

  /** 与引擎同款运行时 env（保证快照内命令可 exec；无 termux-exec 时自动降级）。 */
  private fun runtimeEnv(usrDir: File, homeDir: File): Map<String, String> {
    val preload = RuntimePermissions.resolveTermuxExecPreload(usrDir)
    val env = mutableMapOf(
      "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
      "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
      "HOME" to homeDir.absolutePath,
      "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
      "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
      "TERMUX__ROOTFS" to (usrDir.parentFile?.absolutePath ?: usrDir.parent ?: ""),
      "TERMUX__PREFIX" to usrDir.absolutePath,
      "TERMUX_VERSION" to "0.118.3",
    )
    if (preload != null) env["LD_PRELOAD"] = preload.absolutePath
    return env
  }
}

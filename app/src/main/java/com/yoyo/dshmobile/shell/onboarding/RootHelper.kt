package com.yoyo.dshmobile.shell.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 环境封装：检测 su 可用性并执行 Root 命令提取输出。
 *
 * 兼容 Magisk 与 KernelSU：su 通过 argv 形式调用（`su -c <cmd>`），
 * 不再把参数写入 stdin（KernelSU 的 su 只解析 argv、不消费 stdin，
 * 旧写法会导致已授权但仍检测不到 Root，见 PITFALLS）。
 */
object RootHelper {

  /** 是否为可执行命令（surrounding function 命名）。 */
  data class RootResult(val success: Boolean, val output: String)

  /** 超时：防止 su 等待 stdin/tty 导致 waitFor 永久阻塞。 */
  private const val TIMEOUT_MS = 10_000L

  /** 常见 su 可执行文件候选路径（覆盖系统 su / Magisk / KernelSU）。 */
  private val SU_PATHS = arrayOf(
    "su",
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/vendor/bin/su",
    "/data/adb/ksu/bin/su",
    "/sbin/.magisk/mirror/system/bin/su",
  )

  /**
   * 检测 Root：执行 `su -c exit`，退出码 0 且无异常视为有 Root。
   */
  suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
    runSuCommand(arrayOf("su", "-c", "exit")).success
  }

  /** 执行 `su -c <command>` 并返回输出。 */
  suspend fun exec(command: String): RootResult = withContext(Dispatchers.IO) {
    runSuCommand(arrayOf("su", "-c", command))
  }

  /** 返回第一个存在且可执行的 su 路径；找不到返回 null。 */
  private fun findSu(): String? = SU_PATHS.firstOrNull { ensureExecutable(it) }

  private fun ensureExecutable(path: String): Boolean = try {
    File(path).canExecute()
  } catch (_: Throwable) {
    false
  }

  /**
   * 底层：通过 su 执行参数（argv 形式，兼容 Magisk 与 KernelSU）。
   * KernelSU 的 su 只解析 argv、不消费 stdin，故不再把参数写入 stdin，
   * 否则即便已在 KernelSU 授权也会被判定为「无 Root」。
   */
  private fun runSuCommand(parts: Array<String>): RootResult {
    val su = findSu() ?: return RootResult(false, "su: command not found")
    // argv = [su] + 剩余参数（如 "-c", "exit"）
    val argv = ArrayList<String>(parts.size + 1).apply {
      add(su)
      addAll(parts.drop(1))
    }
    return try {
      val process = ProcessBuilder(argv).start()
      val deadline = System.currentTimeMillis() + TIMEOUT_MS
      while (process.isAlive) {
        if (System.currentTimeMillis() > deadline) {
          process.destroyForcibly()
          return RootResult(false, "su: command timed out")
        }
        Thread.sleep(50)
      }
      val code = process.waitFor()
      val outStr = process.inputStream.bufferedReader().use { it.readText() }
      val errStr = process.errorStream.bufferedReader().use { it.readText() }
      val text = if (outStr.isNotBlank()) outStr.trim() else errStr.trim()
      RootResult(code == 0, text)
    } catch (t: Throwable) {
      RootResult(false, t.message ?: "Unknown error")
    }
  }
}
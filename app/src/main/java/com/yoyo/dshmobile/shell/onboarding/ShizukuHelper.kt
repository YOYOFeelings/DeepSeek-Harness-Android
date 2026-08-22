package com.yoyo.dshmobile.shell.onboarding

import rikka.shizuku.Shizuku

/**
 * Shizuku 封装：检测服务/权限状态、请求授权、执行命令并提取输出。
 * 所有方法应避免在严格主线程长时间阻塞；命令执行走 IO 协程。
 */
object ShizukuHelper {

  /** 服务版本（未运行返回 -1）。 */
  val serverVersion: Int
    get() = if (Shizuku.pingBinder()) Shizuku.getVersion() else -1

  /** 服务是否运行（Binder 存在）。binder 缺失/未绑定时不抛异常，返回 false。 */
  val isRunning: Boolean
    get() = try {
      Shizuku.pingBinder()
    } catch (_: Throwable) {
      false
    }

  /** 是否已授权（仅当服务运行时有意义）。binder 瞬断时返回 false 而不抛异常。 */
  val isGranted: Boolean
    get() = try {
      Shizuku.checkSelfPermission() == PackageManagerCompat.APPROVED
    } catch (_: Throwable) {
      false
    }

  /** 当前状态描述文本。 */
  fun statusText(): String {
    if (!isRunning) return "Shizuku 服务未运行"
    return if (isGranted) "已授权" else "未授权"
  }

  /**
   * 发起授权请求（该回调需配合 listener 接收结果）。
   *
   * 若 Shizuku 服务未运行，直接调用 `Shizuku.requestPermission` 会抛
   * `IllegalStateException` 导致闪退，因此这里不抛异常，改用返回值表达结果：
   * - true：已成功发起授权请求；
   * - false：服务未运行，未发起请求。调用方应提示「请先启动 Shizuku 服务」。
   */
  fun requestPermission(): Boolean {
    if (!isRunning) return false
    // 已授权：无需再次弹窗，直接按已授权处理
    if (isGranted) return true
    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    return true
  }

  /**
   * 注册一次授权结果监听。返回的 [Recipient] 应被持有以便注销。
   *
   * Shizuku 服务未运行/未绑定时调用 `Shizuku.addRequestPermissionResultListener` 会抛
   * `IllegalStateException`，与 `requestPermission` 同类，因此这里加守卫并返回可空：
   * - 返回 [Recipient]：注册成功；
   * - 返回 null：服务未运行或注册失败。调用方应容忍空值（Shizuku 守卫约定，见 PITFALLS §5）。
   */
  fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener): Recipient? {
    if (!isRunning) return null
    return try {
      Shizuku.addRequestPermissionResultListener(listener)
      Recipient(listener)
    } catch (_: Throwable) {
      null
    }
  }

  /** 封装一个可注销的监听器。 */
  class Recipient(private val listener: Shizuku.OnRequestPermissionResultListener) {
    fun unregister() = Shizuku.removeRequestPermissionResultListener(listener)
  }

  /**
   * 在已授权前提下执行命令并把输出拼成字符串。
   * Shizuku api 13.1.5 将 `newProcess` 设为 private，故通过反射调用其私有静态
   * `newProcess(String[], String[], String)`（cmd, envp=null, dir=null）以在系统进程上下文
   * 运行命令。release 关闭了 minify，方法名稳定，可安全反射。
   *
   * 本方法不抛异常：服务未运行返回「Shizuku 服务未运行」，未授权返回「Shizuku 未授权」，
   * 执行过程中抛出的异常（含反射失败）统一转换为「执行失败: <message>」文案。
   */
  fun runCommand(vararg args: String): String {
    if (!isRunning) return "Shizuku 服务未运行"
    if (!isGranted) return "Shizuku 未授权"
    return try {
      val process = newProcessReflect(*args)
      try {
        process.waitFor()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      val out = process.inputStream.bufferedReader().use { it.readText() }
      val err = process.errorStream.bufferedReader().use { it.readText() }
      if (out.isNotBlank()) out else err
    } catch (e: Exception) {
      "执行失败: ${e.message}"
    }
  }

  /** 反射调用 Shizuku 私有静态 `newProcess(String[],String[],String)`。 */
  private fun newProcessReflect(vararg cmd: String): java.lang.Process {
    val method = Shizuku::class.java.getDeclaredMethod(
      "newProcess",
      Array<String>::class.java,
      Array<String>::class.java,
      String::class.java,
    )
    method.isAccessible = true
    val raw = try {
      method.invoke(null, cmd, null, null)
    } catch (e: java.lang.reflect.InvocationTargetException) {
      throw RuntimeException(e.cause ?: e)
    }
    return raw as java.lang.Process
  }

  /** 列出第三方应用包（演示命令）。 */
  suspend fun listThirdPartyPackages(): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCommand("pm", "list", "packages", "-3")
    }

  private object PackageManagerCompat {
    const val APPROVED = 1
  }

  const val SHIZUKU_REQUEST_CODE = 5011
}
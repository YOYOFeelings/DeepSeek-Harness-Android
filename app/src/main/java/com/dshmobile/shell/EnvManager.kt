package com.dshmobile.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行时环境管理（M2）：
 *  - 检测已装 node / python 版本（快照自带 node，python 默认未装）；
 *  - 通过快照内置的 apt/dpkg（Termux rootfs，sources.list 已配国内镜像
 *    mirrors.pku.edu.cn）在线安装/升级「最新 node.js + Python」，输出实时
 *    回流到终端。安装结果写入 usr（与引擎同一运行时，重启引擎即生效）。
 */
class EnvManager(private val context: Context) {

  private val usrDir = File(context.filesDir, "usr")
  private val homeDir = File(context.filesDir, "home")

  /** 环境信息：node / python 版本字符串；未安装为 null。 */
  data class EnvInfo(val node: String?, val python: String?) {
    val nodeLabel: String get() = node ?: "未安装"
    val pythonLabel: String get() = python ?: "未安装"
  }

  /** 只做文件存在性 + 快速版本探测（后台线程由调用方决定）。 */
  fun installed(): EnvInfo {
    val nodeBin = File(usrDir, "bin/node")
    val pyBin = File(usrDir, "bin/python3").let { if (it.exists()) it else File(usrDir, "bin/python") }
    return EnvInfo(
      node = if (nodeBin.exists()) readVersion(nodeBin) ?: "已安装" else null,
      python = if (pyBin.exists()) readVersion(pyBin) ?: "已安装" else null,
    )
  }

  /**
   * 用快照运行时环境跑 `bin --version`，读首行返回（超时/失败返回 null）。
   * 复用引擎同款 env（LD_PRELOAD termux-exec 等），保证能真正 exec 快照内二进制。
   */
  private fun readVersion(bin: File): String? = try {
    val pb = ProcessBuilder(bin.absolutePath, "--version")
    pb.environment().putAll(runtimeEnv())
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val line = proc.inputStream.bufferedReader().use { it.readLine() }
    val finished = proc.waitFor(5, TimeUnit.SECONDS)
    if (!finished) proc.destroyForcibly()
    line?.takeIf { it.isNotBlank() }
  } catch (_: Throwable) {
    null
  }

  /** 是否正在安装（防并发重复安装）。 */
  private val installing: Boolean get() = INSTALLING.get()

  /**
   * 在线安装/升级最新 node.js + Python（后台线程）。
   * @param onLine 每行输出实时回调（任意线程）。
   * @param onDone 结束回调 (ok, message)。
   */
  fun installLatest(onLine: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
    if (!INSTALLING.compareAndSet(false, true)) {
      onDone(false, "安装已在运行中")
      return
    }
    Thread {
      try {
        val bash = File(usrDir, "bin/bash")
        if (!bash.exists()) {
          onDone(false, "运行时未解压，请先完成安装引导")
          return@Thread
        }
        onLine("===== 安装最新 Node.js / Python =====")
        onLine("更新软件源…")
        // 非交互：-o Acquire::Retries 提高国内镜像拉取成功率；apt-get update
        // 输出的 99% 进度行是\r 回车刷新，逐行读可能产生大量行，按行透传即可。
        val cmd =
          "apt-get update -o Acquire::Retries=3 2>&1; " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
            "python nodejs 2>&1; " +
            "echo __ENV_EXIT__:$?"
        val pb = ProcessBuilder(bash.absolutePath, "-c", cmd)
        pb.environment().putAll(runtimeEnv())
        pb.redirectErrorStream(true)
        // 跨设备自愈：bash 无 exec 权限（Permission denied）时补设权限并重试一次。
        var proc = try {
          pb.start()
        } catch (e: java.io.IOException) {
          if (e.message?.contains("Permission denied") != true) throw e
          onLine("检测到 bash 无执行权限，正在修复并重试…")
          RuntimePermissions.ensureExecutable(usrDir)
          try {
            pb.start()
          } catch (e2: java.io.IOException) {
            onLine("权限修复后仍无法执行 bash（" + (e2.message ?: e2.javaClass.simpleName) + "）")
            onLine("排查建议：")
            onLine("1. 在设置页重新「安装/更新运行时」以重解压")
            onLine("2. 或重启设备后重试（某些设备需重启才应用 exec 属性）")
            throw e2
          }
        }
        var exit = -1
        val errors = mutableListOf<String>()
        proc.inputStream.bufferedReader().use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (line.startsWith("__ENV_EXIT__:")) {
              exit = line.substringAfter(':').trim().toIntOrNull() ?: -1
              onLine("安装结束（exit $exit）")
            } else {
              if (
                line.startsWith("E:") ||
                line.startsWith("W:") ||
                line.contains("Unable to fetch") ||
                line.contains("Failed to fetch") ||
                line.contains("is not available") ||
                line.contains("has no installation candidate") ||
                line.contains("not found")
              ) {
                errors.add(line)
              }
              onLine(line)
            }
          }
        }
        val code = if (exit >= 0) exit else proc.waitFor()
        if (code == 0) {
          Log.i(TAG, "env install ok")
          recordEnvInstall(true, "已安装/升级到最新版")
          onDone(true, "Node.js / Python 已安装/升级到最新版，重启引擎生效")
        } else {
          if (errors.isNotEmpty()) {
            onLine("----- 关键错误摘要 -----")
            errors.take(8).forEach { onLine(it) }
            onLine("----- 排查建议 -----")
            onLine("1. 请检查网络连接是否正常")
            onLine("2. 请检查更新源与软件源 mirrors.pku.edu.cn 是否可达")
            onLine("3. 请检查磁盘空间是否充足，可稍后重试")
          }
          recordEnvInstall(false, "安装失败（exit $code）")
          onDone(false, "安装失败（exit $code），详见上方日志")
        }
      } catch (t: Throwable) {
        Log.e(TAG, "env install failed", t)
        recordEnvInstall(false, t.message ?: t.javaClass.simpleName)
        onDone(false, t.message ?: t.javaClass.simpleName)
      } finally {
        INSTALLING.set(false)
      }
    }.start()
  }

  /** 环境安装结果写入下载记录（供「主页 → 下载记录」展示；失败静默）。 */
  private fun recordEnvInstall(ok: Boolean, detail: String) {
    DownloadHistory.add(
      context,
      DownloadHistory.Record(
        time = System.currentTimeMillis(),
        name = "Node.js + Python 环境",
        size = "",
        source = "apt（国内镜像）",
        status = if (ok) "成功" else "失败",
        detail = detail,
      ),
    )
  }

  /** 与 EngineManager.startEngine 同款运行时 env（保证快照内命令可 exec）。 */
  private fun runtimeEnv(): Map<String, String> {
    val preload = RuntimePermissions.resolveTermuxExecPreload(usrDir)
    val env = mutableMapOf(
      "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
      "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
      "HOME" to homeDir.absolutePath,
      "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
      "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
      "TERMUX__ROOTFS" to (usrDir.parentFile?.absolutePath ?: usrDir.parent ?: context.filesDir.absolutePath),
      "TERMUX__PREFIX" to usrDir.absolutePath,
      "TERMUX_VERSION" to "0.118.3",
    )
    if (preload != null) {
      env["LD_PRELOAD"] = preload.absolutePath
    }
    return env
  }

  companion object {
    private const val TAG = "dsh-env"
    private val INSTALLING = java.util.concurrent.atomic.AtomicBoolean(false)
  }
}

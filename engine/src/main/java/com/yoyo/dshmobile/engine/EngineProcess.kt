package com.yoyo.dshmobile.engine

import android.content.Context
import android.os.Build
import com.yoyo.dshmobile.shell.log.Logs
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 引擎进程句柄：进程 + 输出读取线程。
 */
data class EngineHandle(val process: Process, val thread: Thread)

/**
 * 直接启动 node 引擎（去 proot：rootfs 快照不含 proot 二进制）+ 端口探活 + 优雅停止。
 * 端口统一 3080，地址 127.0.0.1。
 */
object EngineProcess {

  const val ENGINE_HOST = "127.0.0.1"
  const val ENGINE_PORT = 3080

  /** 校验引擎启动前关键文件：node、入口脚本、termux-exec preload 均应存在且非空（node 另需可执行，shared lib 只需可读）。返回问题列表，空=通过。对外暴露供启动失败时 UI 提示「环境/工具缺失」。 */
  fun verifyCriticalFiles(usrDir: File): List<String> {
    val issues = mutableListOf<String>()
    val node = File(usrDir, "bin/node")
    val binJs = File(usrDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
    val preload = RuntimePermissions.resolveTermuxExecPreload(usrDir)
    fun check(label: String, f: File?, needExec: Boolean) {
      val ok = f != null && f.isFile && f.length() > 0L && (!needExec || f.canExecute())
      if (!ok) issues.add("$label missing/invalid")
    }
    check("usr/bin/node", node, true)
    check("lib/bin.js", binJs, false)
    check("termux-exec-preload", preload, false)
    return issues
  }

  /** 清理残留引擎进程：优先 pkill -f lib/bin.js，再兜底扫描 /proc/<pid>/cmdline 逐个 kill 含 lib/bin.js 的 PID，防御 3080 端口 EADDRINUSE。全部静默。 */
  private fun cleanupStaleEngine() {
    runCatching {
      Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "lib/bin.js"))
        .waitFor(3000, TimeUnit.MILLISECONDS)
    }
    // 兜底：扫描 /proc/<pid>/cmdline，对含 "lib/bin.js" 的进程逐个 kill（pkill 不可用或匹配失败时仍可清理）
    runCatching {
      File("/proc").listFiles()?.forEach { entry ->
        if (entry.isDirectory && entry.name.matches(Regex("[0-9]+"))) {
          runCatching {
            val cmdline = File(entry, "cmdline").readBytes()
            if (cmdline.isNotEmpty() && String(cmdline).contains("lib/bin.js")) {
              Runtime.getRuntime().exec(arrayOf("/system/bin/kill", entry.name)).waitFor()
            }
          }
        }
      }
    }
  }

  /** 组装 node 命令行：node 与引擎脚本均在 rootfs 内（不再使用不存在的 proot，入口不再写死 /opt/dsh/web）。 */
  fun buildArgs(rootfsDir: File): List<String> {
    val nodeBin = File(rootfsDir, "usr/bin/node").absolutePath
    val binJs = File(rootfsDir, "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js").absolutePath
    return listOf(
      nodeBin,
      "--expose-internals",
      binJs,
      "web",
      "--port", ENGINE_PORT.toString(),
    )
  }

  /** 启动引擎：补设 exec 位/打 exec 属性 -> ProcessBuilder(directory=rootfsDir/home) + 后台线程读输出进日志。
   *  直接 exec 被拒（Android 15+）时回退 system linker（linker64 / linker）。 */
  fun start(context: Context, rootfsDir: File): EngineHandle {
    val usrDir = File(rootfsDir, "usr")
    // 已装旧 rootfs 兜底：符号链接实体化（规避 FUSE 对符号链接读取限制），无需重装/重更新即自愈
    RuntimePermissions.materializeSymlinks(usrDir)
    // 真文件化 node 精确依赖库：兜底「链接不可读/文件缺失」，确保 linker 找到 libz.so.1 等
    val nodeDeps = RuntimePermissions.ensureNodeLibsReal(usrDir)
    val missing = nodeDeps.filterValues { !it }.keys
    if (missing.isEmpty()) {
      Logs.logEvent(context, "Engine", "node-deps real=${nodeDeps.size}/${nodeDeps.size} ok")
    } else {
      Logs.logEvent(context, "Engine", "node-deps real=${nodeDeps.size - missing.size}/${nodeDeps.size} MISSING=${missing.joinToString(",")}")
      throw IllegalStateException("engine libs missing: " + missing.joinToString(","))
    }
    // 关键文件校验+自愈：发现问题则补设 exec 位并重解析 preload，再记录 self-heal 前后摘要
    val verifyIssues = verifyCriticalFiles(usrDir)
    if (verifyIssues.isNotEmpty()) {
      val before = verifyIssues.joinToString(";")
      RuntimePermissions.ensureExecutable(usrDir)
      RuntimePermissions.resolveTermuxExecPreload(usrDir)
      val afterIssues = verifyCriticalFiles(usrDir)
      val after = afterIssues.joinToString(";")
      Logs.logEvent(context, "Engine", "start-verify before=$before after=$after")
      if (afterIssues.isNotEmpty()) throw IllegalStateException("engine env incomplete: " + afterIssues.joinToString(";"))
    }
    // 残留引擎清理：pkill 旧的 lib/bin.js 进程，防 3080 端口 EADDRINUSE
    cleanupStaleEngine()
    val preload = RuntimePermissions.resolveTermuxExecPreload(usrDir)
    // home/tmp 目录创建幂等化：rootfs 已解压时目录已存在，mkdirs() 会返回 false；已存在目录视为成功，仅当尝试后仍非目录才抛。
    // 容错：目标存在但非目录时先删除重建，重建成功记一条 home-rebuild 自愈日志。
    fun ensureDir(f: File): Boolean {
      if (f.isDirectory) return true
      if (!f.mkdirs()) {
        if (!f.exists()) return false
        if (!f.deleteRecursively()) return false
        if (!f.mkdirs()) return false
        Logs.logEvent(context, "Engine", "home-rebuild " + f.absolutePath)
      }
      return f.isDirectory
    }
    val homeDir = File(rootfsDir, "home")
    if (!ensureDir(homeDir)) throw IllegalStateException("cannot create dir: " + homeDir.absolutePath)
    val tmpDir = File(homeDir, "tmp")
    if (!ensureDir(tmpDir)) throw IllegalStateException("cannot create dir: " + tmpDir.absolutePath)
    val env = mutableMapOf(
      "PATH" to "${usrDir.absolutePath}/bin:/system/bin",
      "LD_LIBRARY_PATH" to "${usrDir.absolutePath}/lib",
      "HOME" to homeDir.absolutePath,
      "DSH_HOME" to File(homeDir, ".dsh").absolutePath,
      "TMPDIR" to tmpDir.absolutePath,
      "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
      "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to (if (preload != null) "1" else "0"),
      "TERMUX__ROOTFS" to rootfsDir.absolutePath,
      "TERMUX__PREFIX" to usrDir.absolutePath,
      "TERMUX_APP__DATA_DIR" to (context.filesDir.parentFile?.absolutePath ?: context.applicationInfo.dataDir),
      "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/com.yoyo.dshmobile.shell",
      "TERMUX_VERSION" to "0.118.3",
    )
    if (preload != null) env["LD_PRELOAD"] = preload.absolutePath

    // spawn 前统一补设 exec 位/exec 属性（在 env 组装之后、startWithArgs 之前）
    RuntimePermissions.ensureExecutable(usrDir)
    // shell-termux 自愈：若应用私有目录下另存一层 usr/bin（bash 等 shell 二进制）而非引擎 rootfs，
    // 一并补设 exec 位 + exec 属性，避免 shell-termux 报 “bash is not executable”（幂等，目录不存在则静默跳过）。
    runCatching {
      val shellUsr = File(context.filesDir, "usr")
      if (shellUsr.exists() && File(shellUsr, "bin").isDirectory) {
        RuntimePermissions.ensureExecutable(shellUsr)
        Logs.logEvent(context, "Engine", "shell-usr-selfheal exec-perm set")
      }
    }
    val process = startWithArgs(context, buildArgs(rootfsDir), env, homeDir)
    val thread = Thread {
      runCatching {
        process.inputStream.bufferedReader().useLines { lines ->
          for (line in lines) {
            if (line.isNotBlank()) Logs.logEvent(context, "Engine", "proc: " + line)
          }
        }
      }
    }.apply {
      name = "dsh-engine-output"
      isDaemon = true
      start()
    }
    Logs.logEvent(context, "Engine", "engine-started")
    return EngineHandle(process, thread)
  }

  /** 直接 exec 被拒（Android 15+ 对 app 数据 ELF 限制）时，回退 system linker 加载（等同 native 库机制）。 */
  private fun startWithArgs(context: Context, args: List<String>, env: Map<String, String>, dir: File): Process {
    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.directory(dir)
      }
    fun systemLinker(): String =
      if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "/system/bin/linker64" else "/system/bin/linker"
    return try {
      build(args).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Logs.logEvent(context, "Engine", "direct-exec-denied-fallback-linker")
      build(listOf(systemLinker()) + args).start()
    }
  }

  /** 对 127.0.0.1:3080 探活：TCP 连接成功即 true；循环重试直到 timeoutMs。 */
  fun probe(context: Context, timeoutMs: Int): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val ok = runCatching {
        Socket().also { socket ->
          socket.connect(InetSocketAddress(ENGINE_HOST, ENGINE_PORT), 1000)
          socket.close()
        }
        true
      }.getOrDefault(false)
      if (ok) return true
      try {
        Thread.sleep(500)
      } catch (_: InterruptedException) {
        return false
      }
    }
    return false
  }

  /** 优雅停止：先 destroy -> 3s 未退则 destroyForcibly；之后再中断读线程。 */
  fun stop(handle: EngineHandle?) {
    if (handle == null) return
    runCatching {
      handle.process.destroy()
      if (!handle.process.waitFor(3000, TimeUnit.MILLISECONDS)) {
        handle.process.destroyForcibly()
      }
    }
    runCatching { handle.thread.interrupt() }
  }
}
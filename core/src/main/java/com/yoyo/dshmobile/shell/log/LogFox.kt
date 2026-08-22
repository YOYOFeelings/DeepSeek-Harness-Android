package com.yoyo.dshmobile.shell.log

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogFox - 内置增强日志采集（精简版，yoyo shell 用）。
 *
 * 目标：无 Shizuku/root 依赖，在私有目录 `filesDir/logs/` 采集日志以便分析闪退：
 * - [trackUser] 用户行为轨迹 → user-actions.log
 * - [startCapture] 抓取本进程 logcat → logcat.log（普通应用可读本 PID，无需权限，失败优雅降级）
 * - [installCrashHandler] 挂载全局未捕获异常处理 → 崩溃快照 crash-snapshot.txt 并设崩溃标记
 * - 崩溃/异常摘要 → exceptions.log（经 [Logs.logE]）
 *
 * 不提供导出 UI；日志全部位于私有目录，root 可直接读取。
 */
object LogFox {

  private const val USER_ACTIONS = "user-actions.log"
  private const val LOGCAT = "logcat.log"
  private const val CRASH_SNAP = "crash-snapshot.txt"

  /** 崩溃标记 sharedPreferences key（下次启动进程内可读，本次仅落盘记录）。 */
  const val PREF_CRASH_MARKER = "logfox_crash_marker"

  private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  private var logcatProcess: java.lang.Process? = null
  private var logcatThread: Thread? = null
  private var captureStarted = false

  fun userActionsLog(context: Context): File = File(Logs.dir(context), USER_ACTIONS)
  fun logcatLog(context: Context): File = File(Logs.dir(context), LOGCAT)
  fun crashSnapshot(context: Context): File = File(Logs.dir(context), CRASH_SNAP)

  /** 启动 logcat 抓取 + 挂载崩溃处理器（Application 尽早调用）。 */
  fun start(context: Context) {
    startCapture(context)
    installCrashHandler(context)
  }

  /** 抓取本进程 logcat 到私有目录 logcat.log。 */
  fun startCapture(context: Context) {
    if (captureStarted) return
    captureStarted = true
    try {
      val pid = Process.myPid()
      val pb = ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "--pid=$pid")
        .redirectErrorStream(true)
      val logFile = logcatLog(context)
      logFile.parentFile?.mkdirs()
      logFile.delete()
      logFile.createNewFile()
      logcatProcess = pb.start()
      logcatThread = Thread {
        try {
          val br = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
          val pw = PrintWriter(logFile.bufferedWriter())
          var line: String?
          while (br.readLine().also { line = it } != null) {
            if (line != null) {
              pw.println(line)
              pw.flush()
            }
          }
          pw.flush()
          pw.close()
        } catch (_: Throwable) {
        }
      }
      logcatThread?.start()
      Logs.logE(context, "LogFox", "logcat capture started (pid=$pid)")
    } catch (e: Throwable) {
      Logs.logE(context, "LogFox", "logcat start failed (OEM may deny, app-level logs still available)", e)
    }
  }

  /** 停止抓取（进程退出后即可用，必要时调用）。 */
  fun stopCapture() {
    captureStarted = false
    logcatProcess?.destroy()
    logcatProcess = null
    logcatThread = null
  }

  /** 记录用户行为：category=nav/page/button/other。 */
  @Synchronized
  fun trackUser(context: Context, category: String, detail: String) {
    val line = "[${df.format(Date())}] [$category] $detail"
    Logs.append(userActionsLog(context), line)
  }

  /** 挂载全局未捕获异常处理：写崩溃快照后交给原处理器（显示系统崩溃对话框并终止进程）。 */
  fun installCrashHandler(context: Context) {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      writeCrashSnapshot(context, thread, throwable)
      prev?.uncaughtException(thread, throwable)
    }
  }

  /** 写崩溃快照到 crash-snapshot.txt（私有目录，root 可读）。 */
  fun writeCrashSnapshot(context: Context, thread: Thread, throwable: Throwable) {
    try {
      val snap = crashSnapshot(context)
      snap.parentFile?.mkdirs()
      snap.delete()
      val pw = PrintWriter(snap.bufferedWriter())

      pw.println("===== Crash Snapshot ${df.format(Date())} =====")
      pw.println("thread: ${thread.name} id=${thread.id} priority=${thread.priority}")
      pw.println("message: ${throwable.message ?: "(no message)"}")
      pw.println()

      pw.println("--- System Info ---")
      pw.println("SDK=${Build.VERSION.SDK_INT} RELEASE=${Build.VERSION.RELEASE}")
      pw.println("ABI=${Build.SUPPORTED_ABIS.joinToString()}")
      runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        pw.println("versionName=${pkg.versionName}")
        pw.println("versionCode=${pkg.longVersionCode}")
      }
      pw.println()

      pw.println("--- Last logcat (tail 200 lines) ---")
      try {
        Runtime.getRuntime().exec(
          arrayOf("/system/bin/logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
        ).inputStream.use { isb ->
          InputStreamReader(isb).readLines().takeLast(200).forEach { pw.println(it) }
        }
      } catch (_: Throwable) {
        pw.println("(failed to dump live logcat, reading saved file tail)")
        try {
          val saved = logcatLog(context)
          if (saved.exists()) saved.readLines().takeLast(200).forEach { pw.println(it) }
        } catch (_: Throwable) {
        }
      }
      pw.println()

      pw.println("--- Last user actions (tail 100 lines) ---")
      try {
        val ua = userActionsLog(context)
        if (ua.exists()) ua.readLines().takeLast(100).forEach { pw.println(it) }
      } catch (_: Throwable) {
      }
      pw.println()

      pw.println("--- Full Stacktrace ---")
      throwable.printStackTrace(pw)
      pw.flush()
      pw.close()

      context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_CRASH_MARKER, true).apply()
      Logs.logE(context, "LogFox", "Crash snapshot written to: ${snap.absolutePath}")
    } catch (_: Throwable) {
      // 写快照失败不吞崩溃
    }
  }
}
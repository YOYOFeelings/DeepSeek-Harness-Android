package com.yoyo.dshmobile.shell.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志目录（filesDir/logs/，应用私有、免权限、各版本稳定，root 可直接读取）。
 * 所有日志集中于此；崩溃分析用：
 * - user-actions.log  用户行为轨迹
 * - exceptions.log    异常摘要
 * - logcat.log        本进程系统日志（抓取）
 * - crash-snapshot.txt 崩溃快照
 */
object Logs {

  fun dir(context: Context): File = File(context.filesDir, "logs")

  fun exceptionsLog(context: Context): File = File(dir(context), "exceptions.log")

  /** 结构化应用事件日志（命令/引擎/权限/插件/导航等，便于回放排查）。 */
  fun appEventsLog(context: Context): File = File(dir(context), "app-events.log")

  /** 线程安全追加一行到指定日志文件；失败静默（不影响主流程）。 */
  @Synchronized
  fun append(file: File, line: String) {
    try {
      file.parentFile?.mkdirs()
      file.appendText(line + "\n")
    } catch (_: Throwable) {
    }
  }

  /**
   * 追加一条结构化事件到 app-events.log。
   * 格式：`[时间] [tag] detail`；可附带异常类/消息，可选附带堆栈全文。
   */
  @Synchronized
  fun logEvent(context: Context, tag: String, detail: String, t: Throwable? = null) {
    val sb = StringBuilder()
    sb.append('[')
      .append(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
      .append("] [").append(tag).append("] ").append(detail)
    if (t != null) {
      sb.append(" -> ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
      sb.append('\n')
      val sw = java.io.StringWriter()
      t.printStackTrace(java.io.PrintWriter(sw))
      sb.append(sw.toString())
    }
    append(appEventsLog(context), sb.toString())
  }

  /** 追加带时间戳的异常摘要到 exceptions.log（异常日志补充）。 */
  fun logE(context: Context, tag: String, msg: String, t: Throwable? = null) {
    val sb = StringBuilder()
    sb.append('[')
      .append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
      .append("] [").append(tag).append("] ").append(msg)
    if (t != null) {
      sb.append(" -> ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
    }
    append(exceptionsLog(context), sb.toString())
  }

  /** 读取指定日志文件末尾若干行；缺失/失败返回空串。 */
  fun tail(file: File, maxLines: Int = 60): String {
    if (!file.exists()) return ""
    return try {
      file.readLines().takeLast(maxLines).joinToString("\n")
    } catch (_: Throwable) {
      ""
    }
  }
}
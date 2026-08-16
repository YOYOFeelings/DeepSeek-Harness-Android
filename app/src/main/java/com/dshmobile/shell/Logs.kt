package com.dshmobile.shell

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统一日志目录（filesDir/logs/，应用私有、免权限、Android 各版本稳定）。
 *  engine.log / terminal.log / terminal-detail.log / bootstrap.log / exceptions.log
 *  全部集中于此；导出时才写公共 Documents/dshdata/exports/。
 *  - [append] 线程安全落盘；
 *  - [logE] 统一把异常摘要追加到 exceptions.log（异常日志补充）；
 *  - [tail] 读取末尾若干行（引擎失败报告用）。
 */
object Logs {

  fun dir(context: Context): File = File(context.filesDir, "logs")

  fun engineLog(context: Context): File = File(dir(context), "engine.log")

  fun terminalLog(context: Context): File = File(dir(context), "terminal.log")

  fun terminalDetailLog(context: Context): File = File(dir(context), "terminal-detail.log")

  fun bootstrapLog(context: Context): File = File(dir(context), "bootstrap.log")

  fun exceptionsLog(context: Context): File = File(dir(context), "exceptions.log")

  /** 线程安全追加一行到指定日志文件；失败静默（不影响主流程）。 */
  @Synchronized
  fun append(file: File, line: String) {
    try {
      file.parentFile?.mkdirs()
      file.appendText(line + "\n")
    } catch (_: Throwable) {
    }
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

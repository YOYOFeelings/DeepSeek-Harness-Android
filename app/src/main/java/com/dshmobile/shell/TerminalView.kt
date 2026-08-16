package com.dshmobile.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量级“终端模拟”视图：一个 ScrollView 包着单个等宽 TextView，模拟命令行回显。
 * 全应用唯一的日志面板：引导/安装/更新/引擎输出统一输出到主页终端，不再在多个页面分散。
 *
 * 两个关键设计点：
 * 1. 所有 UI 变更都通过 [post] 投递到主线程——调用方可能来自下载/安装后台协程，
 *    直接改 TextView 会抛 CalledFromWrongThreadException；
 * 2. 可选地把每行文本镜像追加到 [logFile]，方便在升级失败后把完整日志交给用户排查，
 *    日志写入用 [logLock] 加锁保证并发安全（下载进度更新可能非常频繁）。
 */
class TerminalView(context: Context, private val logFile: File? = null) : ScrollView(context) {
  private val fileLog: File = logFile ?: Logs.terminalLog(context)

  /** 唯一子控件：等宽字体、深色底、浅色字，模拟终端外观。 */
  private val textView = TextView(context).apply {
    setTypeface(Typeface.MONOSPACE)
    setTextColor(Color.rgb(0xe6, 0xed, 0xf3))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setLineSpacing(dp(2).toFloat(), 1f)
    setPadding(dp(12), dp(12), dp(12), dp(12))
    setTextIsSelectable(true)
    // 长行自动换行（下载进度/路径等）：关闭横向滚动 + 高质量断行，确保
    // 超宽行按屏幕宽度软换行而不是被裁掉（"下载没有正常换行"修复）。
    setHorizontallyScrolling(false)
    setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
    setEllipsize(null)
  }

  /** 上一行所属的 stage；配合 [lastLive] 判断是否可以“原地更新”成进度行。 */
  private var lastStage: String? = null

  /** 上一行是否是实时进度行（total > 0）。只有连续的同 stage 进度行才能原地覆盖。 */
  private var lastLive: Boolean = false

  /** 日志文件写入锁：进度更新可能来自多个线程/协程，appendText 本身不是原子的。 */
  private val logLock = Any()

  /** 独立详细日志文件（appendDetail 专用），与 [fileLog] 同目录。 */
  private val detailLogFile: File =
    File(fileLog.parentFile, fileLog.nameWithoutExtension + "-detail.log")

  init {
    // 背景必须设在 ScrollView 上：TextView 高度是 WRAP_CONTENT，内容不满一屏时
    // 下方空白区域也会露出黑色底，否则会透出默认白底，破坏终端观感。
    // 圆角终端：GradientDrawable 圆角 + 深色底（用户要求的终端圆角优化）。
    background = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      setColor(Color.parseColor("#0f1720"))
      cornerRadius = dp(12).toFloat()
    }
    clipToOutline = true
    addView(
      textView,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    )
    isVerticalScrollBarEnabled = true
  }

  /**
   * 追加一行带时间戳的详细日志（比 [appendLine] 更详细，便于排查时序问题）。
   * 格式：`[MM-dd HH:mm:ss] text`。镜像写入 [logFile] 与独立详细日志文件
   * `terminal-detail.log`。可从任意线程调用（内部 post 到主线程执行）。
   */
  fun appendDetail(text: String) {
    val line = "[" + SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + "] " + text
    post {
      textView.append(line + "\n")
      fullScroll(View.FOCUS_DOWN)
      mirrorToLog(line)
      mirrorToDetailLog(line)
    }
  }

  /**
   * 追加一行普通文本并自动滚到底部。
   * 可从任意线程调用（内部 post 到主线程执行）。
   */
  fun appendLine(text: String) {
    post {
      textView.append(text + "\n")
      // 追加后必须重新滚到底部，否则新行会被顶出可视区，用户看不到最新输出。
      fullScroll(View.FOCUS_DOWN)
      mirrorToLog(text)
    }
  }

  /**
   * 追加/原地更新一条带进度的行。
   * - total > 0 且 done >= 0 时输出固定宽度的 ASCII 进度条
   *   `[stage] message… [██████░░░░░] 12.3/70.0 MB 18%`：宽度固定（20 格），
   *   不会随数字位数变化而抖动/换行异常；若上一行也是同一 stage 的进度行，
   *   则覆盖上一行而不是无限堆叠新行——这才是终端里实时进度条的观感。
   * - 否则（total <= 0）退化为普通文本行 `[stage] message`。
   */
  fun appendProgress(stage: String, message: String, done: Long, total: Long) {
    if (total > 0 && done >= 0) {
      val clamped = done.coerceAtMost(total)
      val pct = (clamped * 100 / total).toInt().coerceIn(0, 100)
      val barLen = 20
      val filled = (clamped * barLen / total).toInt().coerceIn(0, barLen)
      val bar = "\u2588".repeat(filled) + "\u2591".repeat(barLen - filled)
      val line = String.format(
        Locale.US,
        "[%s] %s… [%s] %.1f/%.1f MB %d%%",
        stage, message, bar, done / 1048576f, total / 1048576f, pct
      )
      post {
        val replace = lastStage == stage && lastLive
        if (replace) {
          // 移除上一行：截断到最后一个换行符之前，再追加新进度行，实现“原地刷新”。
          val current = textView.text.toString()
          val lastNl = current.lastIndexOf('\n')
          textView.setText(if (lastNl >= 0) current.substring(0, lastNl) else "")
        }
        textView.append(line + "\n")
        lastStage = stage
        lastLive = true
        fullScroll(View.FOCUS_DOWN)
        mirrorToLog(line)
      }
    } else {
      val line = "[$stage] $message"
      post {
        textView.append(line + "\n")
        // 普通行会打断“连续进度行”的判定，后续进度需要另起一行。
        lastStage = null
        lastLive = false
        fullScroll(View.FOCUS_DOWN)
        mirrorToLog(line)
      }
    }
  }

  /** 清空终端内容和日志文件，并重置进度行跟踪状态。 */
  fun clear() {
    post {
      textView.setText("")
      lastStage = null
      lastLive = false
      truncateLog()
      truncateDetailLog()
    }
  }

  /** 返回当前完整文本内容。 */
  fun getText(): String = textView.text.toString()

  /**
   * 复制当前完整终端文本到系统剪贴板。
   * 即使文本为空也会复制（空串），是否提示由调用方通过 [success] 回调决定；
   * 本方法不做任何 Toast，保持副作用最小。
   */
  fun copyAll(success: (() -> Unit)? = null) {
    val text = getText()
    val clipboard =
      context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("dsh terminal", text))
    success?.invoke()
  }

  /** dp 转 px 的私有辅助；minSdk 26 可直接读 density。 */
  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  /** 把一行文本镜像写入日志文件（若配置了 logFile）。失败静默，不影响终端展示。 */
  private fun mirrorToLog(line: String) {
    val file = fileLog
    synchronized(logLock) {
      try {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
      } catch (_: Throwable) {
        // 日志写入失败（磁盘满、权限问题等）不应拖垮安装/更新流程。
      }
    }
  }

  /** 把一行文本镜像写入独立详细日志文件（appendDetail 专用）。 */
  private fun mirrorToDetailLog(line: String) {
    val file = detailLogFile
    synchronized(logLock) {
      try {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
      } catch (_: Throwable) {
      }
    }
  }

  /** 把日志文件截断为空。与 [mirrorToLog] 共用同一把锁，避免交错写入。 */
  private fun truncateLog() {
    val file = fileLog
    synchronized(logLock) {
      try {
        file.parentFile?.mkdirs()
        file.writeText("")
      } catch (_: Throwable) {
      }
    }
  }

  /** 清空独立详细日志文件。 */
  private fun truncateDetailLog() {
    val file = detailLogFile
    synchronized(logLock) {
      try {
        file.parentFile?.mkdirs()
        file.writeText("")
      } catch (_: Throwable) {
      }
    }
  }
}

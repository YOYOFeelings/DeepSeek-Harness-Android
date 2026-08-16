package com.dshmobile.shell

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 终端页（底部导航 Tab 2）：Flat Minimalist 风格。
 *
 * 本页是「唯一」承载日志列表的页面：全应用（引导/安装/更新/引擎输出）都路由到
 * 下方的统一终端 [TerminalView]。
 *
 * 布局：
 * 1. 页面标题「终端」；
 * 2. [TerminalView] 用 layout_weight=1 独占剩余高度并独立滚动
 *    （解决横屏下终端下滑被裁剪的比例问题）。
 *
 * 公告/引擎状态/操作按钮已迁移至 [HomeScreen]。
 */
class TerminalScreen(
  context: Context,
  private val callbacks: Callbacks,
) : LinearLayout(context) {

  /** 宿主动作回调（精简版，仅保留 minimal 接口）。 */
  interface Callbacks {
    fun onRestartEngine()
    fun onOpenDirectory()
    fun onOpenWeb()
    fun onCheckUpdate()
    fun onInstallEnv()
    fun onCheckApkUpdate()
    fun onClearCache()
  }

  private val terminalView = TerminalView(context, logFile = Logs.terminalLog(context))

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // 页面标题
    addView(
      TextView(context).apply {
        text = I18n.t(context, "终端", "Terminal")
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) },
    )

    // 统一终端（独立滚动，独占剩余高度）
    addView(
      terminalView,
      LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
    )
  }

  /** 引擎状态：仅写终端日志，不再更新 UI 状态卡（状态卡在 HomeScreen）。 */
  fun setEngineStatus(running: Boolean, detail: String) {
    post {
      if (detail.isNotEmpty()) terminalView.appendLine(detail)
    }
  }

  /** 终端视图（宿主向其中写入引导/引擎/更新输出）。 */
  fun terminal(): TerminalView = terminalView

  /** 刷新（空实现，终端页无需刷新其他组件）。 */
  fun refresh() {
    // 终端页无需要刷新的 UI 元素
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
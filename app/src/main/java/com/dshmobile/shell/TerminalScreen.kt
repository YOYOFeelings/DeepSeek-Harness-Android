package com.dshmobile.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 终端页（主页，App 启动后直接展示）：Flat Minimalist 风格。
 *
 * 本页是「唯一」承载日志列表的页面：全应用（引导/安装/更新/引擎输出）都路由到
 * 页面下方的统一终端 [TerminalView]。
 *
 * 布局（重设计 v0.10.9）：
 * 1. 顶部固定区（ScrollView，可上下滚动）：标题 → 公告卡（从 GitHub 拉取，点击重载）
 *    → 状态卡（引擎圆点 + 状态 + 进入 Web/工作目录 + 存储统计）→ 更新动作按钮行
 *    （检查并应用更新 / 安装环境 / 检查应用更新）→ 重启引擎按钮；
 * 2. 下方 [TerminalView] 用 layout_weight=1 独占剩余高度并**独立滚动**
 *    （解决"主页控制台无法查看完整内容"——整页 ScrollView 嵌套滚动的问题）；
 * 3. 更新源列表 / 自动测速 / 自定义源 / 下载记录已整体迁往「设置 → 更新」子页。
 *
 * 更新源选择、测速、下载记录等更新配置逻辑一律在设置页完成，本页只保留动作按钮。
 */
class TerminalScreen(
  context: Context,
  private val callbacks: Callbacks,
) : LinearLayout(context) {

  /** 宿主动作回调。 */
  interface Callbacks {
    fun onRestartEngine()
    fun onOpenDirectory()
    /** 打开引擎 Web 界面（127.0.0.1:3080）。 */
    fun onOpenWeb()
    /** 宿主运行更新管线（检查并应用更新，先测速选最快源）。 */
    fun onCheckUpdate()
    /** 宿主运行环境安装管线（Node.js + Python）。 */
    fun onInstallEnv()
    /** 宿主检查应用自身（APK）更新（先测速选最快源）。 */
    fun onCheckApkUpdate()
    /** 切换到插件页。 */
    fun onOpenPlugins()
    /** 清理应用缓存（cacheDir + WebView 缓存）。 */
    fun onClearCache()
    /** 重新拉取公告（点击公告卡触发）。 */
    fun onReloadAnnouncement()
  }

  private val envManager = EnvManager(context)

  private val statusDot = View(context)
  private val statusText = TextView(context)
  private val statusDetail = TextView(context)
  private val storageText = TextView(context)
  private val envNodeLabel = TextView(context)
  private val envPythonLabel = TextView(context)
  private val terminalView = TerminalView(context, logFile = Logs.terminalLog(context))
  private val announcementBody = TextView(context)

  /** 公告卡容器（无公告时 GONE）。 */
  private val announcementCard: LinearLayout

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // ============ 顶部固定区（可滚动） ============
    val scroll = ScrollView(context).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
    }
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    // 页面标题
    content.addView(
      TextView(context).apply {
        text = I18n.t(context, "主页", "Home")
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
      },
    )

    // ============ 0. 公告卡 ============
    announcementCard = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), dp(12), dp(14), dp(12))
      background = resources.getDrawable(R.drawable.bg_card, null)
      isClickable = true
      isFocusable = true
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }.apply {
      val head = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      head.addView(
        TextView(context).apply {
          text = I18n.t(context, "公告", "Announcement")
          textSize = 13f
          typeface = Typeface.DEFAULT_BOLD
          setTextColor(resources.getColor(R.color.accent, null))
          layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        },
      )
      head.addView(
        TextView(context).apply {
          text = I18n.t(context, "点击重载", "Tap to reload")
          textSize = 11f
          setTextColor(resources.getColor(R.color.text_tertiary, null))
        },
      )
      addView(head)
      announcementBody.apply {
        textSize = 12f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
      }
      addView(announcementBody)
      setOnClickListener { callbacks.onReloadAnnouncement() }
    }
    content.addView(announcementCard)
    announcementCard.visibility = View.GONE

    // ============ 1. 状态卡 ============
    val statusContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val statusRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    statusDot.apply {
      background = dotDrawable(resources.getColor(R.color.text_tertiary, null))
      layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
    }
    statusText.apply {
      text = I18n.t(context, "引擎未启动", "Engine stopped")
      textSize = 14f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    statusRow.addView(statusDot)
    statusRow.addView(statusText)
    statusContent.addView(statusRow)

    statusDetail.apply {
      text = I18n.t(context, "引擎未启动", "Engine stopped")
      textSize = 11f
      setTextColor(resources.getColor(R.color.text_secondary, null))
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }
    statusContent.addView(statusDetail)

    val statusBtnRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }
    statusBtnRow.addView(
      flatButton(I18n.t(context, "进入 Web", "Open Web"), R.drawable.ic_web, accent = true) { callbacks.onOpenWeb() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
    )
    statusBtnRow.addView(
      flatButton(I18n.t(context, "工作目录", "Work dir"), R.drawable.ic_open, accent = false) { callbacks.onOpenDirectory() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    statusContent.addView(statusBtnRow)

    // 存储占用 + 一键清理缓存
    val storageRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    storageText.apply {
      text = storagePlaceholder()
      textSize = 11f
      setTextColor(resources.getColor(R.color.text_tertiary, null))
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    storageRow.addView(storageText)
    storageRow.addView(
      TextView(context).apply {
        text = I18n.t(context, "清理缓存", "Clear cache")
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.accent, null))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        isClickable = true
        isFocusable = true
        setOnClickListener { callbacks.onClearCache() }
      },
    )
    statusContent.addView(storageRow)
    content.addView(cardOf(statusContent))

    // ============ 2. 更新动作卡 ============
    val updateContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    updateContent.addView(sectionLabel(I18n.t(context, "更新", "Updates")))

    val updateBtnRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }
    updateBtnRow.addView(
      flatButton(I18n.t(context, "检查并应用更新", "Check & apply"), R.drawable.ic_update, accent = true) { callbacks.onCheckUpdate() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
    )
    updateBtnRow.addView(
      flatButton(I18n.t(context, "安装环境", "Install env"), R.drawable.ic_import, accent = false) { callbacks.onInstallEnv() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    updateContent.addView(updateBtnRow)

    envNodeLabel.apply {
      text = envPlaceholderNode()
      textSize = 12f
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    envPythonLabel.apply {
      text = envPlaceholderPython()
      textSize = 12f
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }
    updateContent.addView(envNodeLabel)
    updateContent.addView(envPythonLabel)

    // 应用（APK）自更新：独立按钮，点击先测速选最快源再下载。
    updateContent.addView(
      flatButton(I18n.t(context, "检查应用更新", "Check app update"), R.drawable.ic_speed, accent = false) { callbacks.onCheckApkUpdate() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
    )
    content.addView(cardOf(updateContent))

    // ============ 3. 统一终端（独立滚动，独占剩余高度） ============
    addView(
      terminalView,
      LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) },
    )
  }

  /** 引擎状态：切换圆点颜色与状态文本；detail 非空时写入统一终端。 */
  fun setEngineStatus(running: Boolean, detail: String) {
    post {
      statusDot.background = dotDrawable(
        if (running) resources.getColor(R.color.success, null) else resources.getColor(R.color.text_tertiary, null)
      )
      statusText.text = if (running) I18n.t(context, "引擎运行中", "Engine running") else I18n.t(context, "引擎未启动", "Engine stopped")
      statusDetail.text = if (running) I18n.t(context, "引擎运行中", "Engine running") else I18n.t(context, "引擎未启动", "Engine stopped")
      if (detail.isNotEmpty()) terminalView.appendLine(detail)
    }
  }

  /** 终端视图（宿主向其中写入引导/引擎/更新输出）。 */
  fun terminal(): TerminalView = terminalView

  /** 设置公告内容；null/空白 = 隐藏公告卡。 */
  fun setAnnouncement(text: String?) {
    post {
      val t = text?.trim()
      if (t.isNullOrEmpty()) {
        announcementCard.visibility = View.GONE
      } else {
        announcementBody.text = t
        announcementCard.visibility = View.VISIBLE
      }
    }
  }

  /** 显示公告加载占位（拉取期间调用）。 */
  fun setAnnouncementLoading(loading: Boolean) {
    post {
      if (loading) {
        announcementBody.text = I18n.t(context, "拉取公告中…", "Loading announcement…")
        if (announcementCard.visibility != View.VISIBLE) announcementCard.visibility = View.VISIBLE
      }
    }
  }

  /** 刷新环境标签 + 引擎状态卡（延迟/时长/存储）。 */
  fun refresh() {
    post {
      refreshEnv()
    }
    // 状态卡：后台探测引擎延迟/运行时长 + 统计存储，结果 post 回主线程更新。
    Thread {
      val probe = EngineProbe.check(timeoutMs = 2000)
      val running = probe.optBoolean("running", false)
      val latency = probe.optInt("latencyMs", 0)
      val uptime = if (running && EngineManager.lastStartedAt > 0) {
        val diff = System.currentTimeMillis() - EngineManager.lastStartedAt
        I18n.t(context, "已运行 ${diff / 3600_000}小时${(diff % 3600_000) / 60_000}分",
          "up ${diff / 3600_000}h${(diff % 3600_000) / 60_000}m")
      } else ""
      val dataMb = StorageStats.appDataUsage(context)
      val cacheMb = StorageStats.cacheSize(context)
      post {
        statusDetail.text = if (running) {
          val runningLabel = I18n.t(context, "引擎运行中", "Engine running")
          val latencyLabel = I18n.t(context, "延迟", "latency")
          val andSep = if (uptime.isNotEmpty()) " · " else ""
          "$runningLabel · $latencyLabel $latency ms$andSep$uptime"
        } else {
          I18n.t(context, "引擎未启动", "Engine stopped")
        }
        storageText.text = storageValue(dataMb, cacheMb)
      }
    }.start()
  }

  // ============ 环境检测 ============

  /** 后台探测 node / python 版本并刷新标签。 */
  private fun refreshEnv() {
    envNodeLabel.text = envPlaceholderNode()
    envPythonLabel.text = envPlaceholderPython()
    Thread {
      val info = envManager.installed()
      post {
        envNodeLabel.text = "Node.js  " + info.nodeLabel
        envPythonLabel.text = "Python  " + info.pythonLabel
      }
    }.start()
  }

  // ============ 组件辅助 ============

  /** 卡片容器：竖向、bg_card 背景、16/12/16/12 内边距，包裹单个子视图。 */
  private fun cardOf(child: View): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = resources.getDrawable(R.drawable.bg_card, null)
    addView(child)
  }

  /** 扁平按钮：图标 + 文字，accent=实心主按钮 / ghost=次要按钮。
   *  fgOverride 可覆盖前景色（如 ghost 底 + 品牌蓝文字）。 */
  private fun flatButton(
    label: String,
    iconRes: Int,
    accent: Boolean,
    fgOverride: Int? = null,
    onClick: () -> Unit,
  ): LinearLayout {
    val fg = fgOverride
      ?: if (accent) resources.getColor(R.color.surface, null) else resources.getColor(R.color.text, null)
    return LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(dp(16), dp(10), dp(16), dp(10))
      background = resources.getDrawable(if (accent) R.drawable.bg_button_accent else R.drawable.bg_button_ghost, null)
      isClickable = true
      isFocusable = true
      setOnClickListener { onClick() }
      val icon = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(fg)
      }
      val tv = TextView(context).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
      }
      addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
      addView(tv)
    }
  }

  /** 小标题（卡片内 section）：13sp 加粗，底部留白 4dp。 */
  private fun sectionLabel(text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = 13f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(resources.getColor(R.color.text, null))
    setPadding(0, 0, 0, dp(4))
  }

  private fun dotDrawable(color: Int): GradientDrawable =
    GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(color)
    }

  private fun storagePlaceholder(): String =
    I18n.t(context, "数据占用 - MB · 缓存 - MB", "Data - MB · Cache - MB")

  private fun storageValue(dataMb: Long, cacheMb: Long): String {
    val data = I18n.t(context, "数据占用", "Data")
    val cache = I18n.t(context, "缓存", "Cache")
    return "$data $dataMb MB · $cache $cacheMb MB"
  }

  private fun envPlaceholderNode(): String =
    I18n.t(context, "Node.js  检测中…", "Node.js  checking…")

  private fun envPlaceholderPython(): String =
    I18n.t(context, "Python  检测中…", "Python  checking…")

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

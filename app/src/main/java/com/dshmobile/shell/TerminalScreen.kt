package com.dshmobile.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 终端页（主页，App 启动后直接展示）：Flat Minimalist 风格。
 * 本页是「唯一」承载日志列表的页面：全应用（引导/安装/更新/引擎输出）都路由到
 * 页面下方的统一终端 [TerminalView]，同时把「检查更新 / 安装环境 / 更新源选择 /
 * 下载记录」等更新相关功能合并进本页卡片。
 *
 * 布局（整页在 ScrollView 内滚动）：
 * 1. 状态卡：引擎圆点 + 状态 +（进入 Web / 工作目录）；
 * 2. 更新卡：检查并应用更新 / 安装环境 / 运行时环境 / 更新源列表（自动测速 +
 *    自定义源）/ 下载记录；
 * 3. 统一终端（terminal.log）；
 * 4. 重启引擎按钮。
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
    /** 宿主运行更新管线（检查并应用更新）。 */
    fun onCheckUpdate()
    /** 宿主运行环境安装管线（Node.js + Python）。 */
    fun onInstallEnv()
    /** 切换到插件页。 */
    fun onOpenPlugins()
    /** 清理应用缓存（cacheDir + WebView 缓存）。 */
    fun onClearCache()
  }

  private val prefs = context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
  private val envManager = EnvManager(context)
  private val updateManager = UpdateManager.forPrefs(context)

  private val statusDot = View(context)
  private val statusText = TextView(context)
  private val statusDetail = TextView(context)
  private val storageText = TextView(context)
  private val envNodeLabel = TextView(context)
  private val envPythonLabel = TextView(context)
  private val mirrorRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val mirrorRowViews = HashMap<String, Pair<View, TextView>>()
  private val customInput = EditText(context)
  private val downloadHistoryText = TextView(context)
  private val terminalView = TerminalView(context, logFile = File(context.filesDir, "terminal.log"))
  private lateinit var speedButton: LinearLayout

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // 整页纵向滚动容器
    val scroll = ScrollView(context).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
    }
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    // 页面标题
    content.addView(
      TextView(context).apply {
        text = "主页"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
      },
    )

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
      text = "引擎未启动"
      textSize = 14f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    statusRow.addView(statusDot)
    statusRow.addView(statusText)
    statusContent.addView(statusRow)

    statusDetail.apply {
      text = "引擎未启动"
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
      flatButton("进入 Web", R.drawable.ic_web, accent = true) { callbacks.onOpenWeb() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
    )
    statusBtnRow.addView(
      flatButton("工作目录", R.drawable.ic_open, accent = false) { callbacks.onOpenDirectory() },
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
      text = "数据占用 - MB · 缓存 - MB"
      textSize = 11f
      setTextColor(resources.getColor(R.color.text_tertiary, null))
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    storageRow.addView(storageText)
    storageRow.addView(
      TextView(context).apply {
        text = "清理缓存"
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

    // ============ 2. 更新卡 ============
    val updateContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    updateContent.addView(sectionLabel("更新"))

    val updateBtnRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }
    updateBtnRow.addView(
      flatButton("检查并应用更新", R.drawable.ic_update, accent = true) { callbacks.onCheckUpdate() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
    )
    updateBtnRow.addView(
      flatButton("安装环境", R.drawable.ic_import, accent = false) { callbacks.onInstallEnv() },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    updateContent.addView(updateBtnRow)

    envNodeLabel.apply {
      text = "Node.js  检测中…"
      textSize = 12f
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    envPythonLabel.apply {
      text = "Python  检测中…"
      textSize = 12f
      setTextColor(resources.getColor(R.color.text, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }
    updateContent.addView(envNodeLabel)
    updateContent.addView(envPythonLabel)

    updateContent.addView(
      sectionLabel("更新源"),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
    )
    updateContent.addView(mirrorRows)

    speedButton = flatButton("自动测速选择最快源", R.drawable.ic_speed, accent = false) { runAutoSpeed() }
    updateContent.addView(
      speedButton,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
    )

    customInput.apply {
      hint = "自定义加速前缀，如 https://cdn.akaere.online/"
      textSize = 13f
      isSingleLine = true
      setTextColor(resources.getColor(R.color.text, null))
      setHintTextColor(resources.getColor(R.color.text_tertiary, null))
      background = resources.getDrawable(R.drawable.bg_button_ghost, null)
      setPadding(dp(14), dp(10), dp(14), dp(10))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    updateContent.addView(customInput)
    val accent = resources.getColor(R.color.accent, null)
    updateContent.addView(
      flatButton("添加自定义源", R.drawable.ic_import, accent = false, onClick = { runAddCustom() }, fgOverride = accent),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )

    updateContent.addView(
      sectionLabel("下载记录"),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
    )
    downloadHistoryText.apply {
      textSize = 12f
      typeface = Typeface.MONOSPACE
      setLineSpacing(dp(2).toFloat(), 1f)
      setTextColor(resources.getColor(R.color.text_secondary, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }
    updateContent.addView(downloadHistoryText)
    content.addView(cardOf(updateContent))

    // ============ 3. 统一终端（全应用唯一日志列表） ============
    content.addView(
      terminalView,
      LayoutParams(LayoutParams.MATCH_PARENT, dp(280)).apply { topMargin = dp(12) },
    )
    terminalView.minimumHeight = dp(200)

    // ============ 4. 重启引擎 ============
    content.addView(
      flatButton("重启引擎", R.drawable.ic_power, accent = true) { callbacks.onRestartEngine() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
    )
  }

  /** 引擎状态：切换圆点颜色与状态文本；detail 非空时写入统一终端。 */
  fun setEngineStatus(running: Boolean, detail: String) {
    post {
      statusDot.background = dotDrawable(
        if (running) resources.getColor(R.color.success, null) else resources.getColor(R.color.text_tertiary, null)
      )
      statusText.text = if (running) "引擎运行中" else "引擎未启动"
      statusDetail.text = if (running) "引擎运行中" else "引擎未启动"
      if (detail.isNotEmpty()) terminalView.appendLine(detail)
    }
  }

  /** 终端视图（宿主向其中写入引导/引擎/更新输出）。 */
  fun terminal(): TerminalView = terminalView

  /** 刷新环境标签 + 更新源列表 + 下载记录 + 引擎状态卡（延迟/时长/存储）。 */
  fun refresh() {
    post {
      refreshEnv()
      rebuildMirrorList()
      refreshDownloadHistory()
    }
    // 状态卡：后台探测引擎延迟/运行时长 + 统计存储，结果 post 回主线程更新。
    Thread {
      val probe = EngineProbe.check(timeoutMs = 2000)
      val running = probe.optBoolean("running", false)
      val latency = probe.optInt("latencyMs", 0)
      val uptime = if (running && EngineManager.lastStartedAt > 0) {
        val diff = System.currentTimeMillis() - EngineManager.lastStartedAt
        "已运行 ${diff / 3600_000}小时${(diff % 3600_000) / 60_000}分"
      } else ""
      val dataMb = StorageStats.appDataUsage(context)
      val cacheMb = StorageStats.cacheSize(context)
      post {
        statusDetail.text = if (running)
          "引擎运行中 · 延迟 $latency ms${if (uptime.isNotEmpty()) " · $uptime" else ""}"
        else
          "引擎未启动"
        storageText.text = "数据占用 $dataMb MB · 缓存 $cacheMb MB"
      }
    }.start()
  }

  // ============ 环境检测 ============

  /** 后台探测 node / python 版本并刷新标签。 */
  private fun refreshEnv() {
    envNodeLabel.text = "Node.js  检测中…"
    envPythonLabel.text = "Python  检测中…"
    Thread {
      val info = envManager.installed()
      post {
        envNodeLabel.text = "Node.js  " + info.nodeLabel
        envPythonLabel.text = "Python  " + info.pythonLabel
      }
    }.start()
  }

  // ============ 更新源 ============

  /** 重建更新源列表：每源一行（圆点 + 名称），点击切换激活源。 */
  private fun rebuildMirrorList() {
    mirrorRows.removeAllViews()
    mirrorRowViews.clear()
    val activeId =
      prefs.getString("active_mirror_id", UpdateManager.DEFAULT_MIRROR_ID) ?: UpdateManager.DEFAULT_MIRROR_ID
    for (m in updateManager.allMirrors()) {
      val dot = View(context)
      dot.layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
      val label = TextView(context).apply {
        text = m.name
        textSize = 13f
        setTextColor(resources.getColor(R.color.text, null))
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { setActiveMirror(m) }
      }
      row.addView(dot)
      row.addView(label)
      mirrorRows.addView(row)
      mirrorRowViews[m.id] = Pair(dot, label)
    }
    highlightMirror(activeId)
  }

  /** 切换激活源：持久化 + 高亮 + 写入终端。 */
  private fun setActiveMirror(m: Mirror) {
    prefs.edit().putString("active_mirror_id", m.id).apply()
    updateManager.activeMirror = m
    highlightMirror(m.id)
    terminalView.appendLine("更新源已切换：" + m.name)
  }

  /** 高亮当前激活源：激活圆点/文字=accent，其余=灰点/正文色。 */
  private fun highlightMirror(activeId: String) {
    val accent = resources.getColor(R.color.accent, null)
    val tertiary = resources.getColor(R.color.text_tertiary, null)
    val text = resources.getColor(R.color.text, null)
    for ((id, v) in mirrorRowViews) {
      val active = id == activeId
      v.first.background = dotDrawable(if (active) accent else tertiary)
      v.second.setTextColor(if (active) accent else text)
    }
  }

  /** 自动测速：后台逐源实测，结果刷新各行延迟并自动切换最快源。 */
  private fun runAutoSpeed() {
    speedButton.isEnabled = false
    speedButton.alpha = 0.5f
    terminalView.appendLine("正在测速更新源…")
    Thread {
      val fastest = updateManager.selectFastestMirror { m, ms ->
        post {
          mirrorRowViews[m.id]?.second?.text =
            if (ms != null) m.name + "（" + ms + "ms）" else m.name + "（不可用）"
        }
      }
      post {
        speedButton.isEnabled = true
        speedButton.alpha = 1f
        if (fastest != null) {
          setActiveMirror(fastest)
        } else {
          terminalView.appendLine("自动测速失败：所有更新源均不可用")
        }
      }
    }.start()
  }

  /** 添加自定义源：校验 http(s):// 前缀 → 持久化 → 重建列表 → 激活新源。 */
  private fun runAddCustom() {
    val input = customInput.text.toString().trim()
    if (input.isEmpty() || !(input.startsWith("https://") || input.startsWith("http://"))) {
      terminalView.appendLine("自定义更新源必须以 http:// 或 https:// 开头")
      return
    }
    val prefix = if (input.endsWith("/")) input else input + "/"
    prefs.edit().putString("custom_source", prefix).apply()
    updateManager.customPrefix = prefix
    customInput.setText("")
    rebuildMirrorList()
    setActiveMirror(Mirror("custom", "自定义 " + prefix, prefix))
    terminalView.appendLine("已添加自定义更新源：" + prefix)
  }

  // ============ 下载记录 ============

  /** 刷新下载记录：取最近 10 条，格式 时间 + 名称 + 大小 + [来源] + 状态 + 详情。 */
  private fun refreshDownloadHistory() {
    val records = DownloadHistory.list(context).take(10)
    if (records.isEmpty()) {
      downloadHistoryText.text = "（暂无下载记录）"
      return
    }
    val sb = StringBuilder()
    for (r in records) {
      sb.append(r.timeLabel()).append("  ").append(r.name)
      if (r.size.isNotEmpty()) sb.append("  ").append(r.size)
      if (r.source.isNotEmpty()) sb.append("  [").append(r.source).append("]")
      sb.append("  ").append(r.status)
      if (r.detail.isNotEmpty()) sb.append("  ").append(r.detail)
      sb.append('\n')
    }
    downloadHistoryText.text = sb.toString().trimEnd()
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

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

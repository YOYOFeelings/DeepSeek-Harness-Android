package com.dshmobile.shell

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 设置页（底部导航 Tab 4）：列表条目 → 点击跳转独立子页。
 *   - 无展开/收起交互；条目点击后进入对应子页（顶部返回栏 + 可滚动内容），系统返回键逐级返回；
 *   - 本页不包含任何日志列表 / TerminalView——所有日志统一在「主页」终端展示，
 *     设置内产生日志的动作（引擎检查等）通过 Callbacks.onAppendLog 转发到主页终端；
 *   - 关于子页为 KernelSU 风格：居中图标/名称/版本 + 信息行 + 占位链接行（QQ群/打赏支持等）；
 *   - 开关状态持久化到 dsh_shell prefs（settings_ 前缀）。纯原生 View 实现，无新依赖。 */
class SettingsScreen(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {

  interface Callbacks {
    fun onOpenWeb()
    fun onRestartEngine()
    fun onOpenDirectory()
    fun onExportDebugLogs()
    fun onSetKeepScreenOn(enable: Boolean)
    fun onOpenPlugins()
    fun onOpenTerminal()          // 切换到主页终端
    fun onCheckUpdate()           // 运行更新管线（日志输出到主页终端）
    fun onCheckApkUpdate()        // 检查应用自身（APK）更新
    fun onInstallEnv()            // 运行环境安装（日志输出到主页终端）
    fun onAppendLog(line: String) // 向主页终端写一行日志
    fun onOpenUrl(url: String)      // 用系统浏览器打开外链
    fun onRequestNotificationPermission()   // 请求/跳转通知权限
    fun onOpenAllFilesAccessSettings()      // 打开所有文件访问授权页
    fun onClearCache()            // 清理应用缓存（cacheDir + WebView 缓存）
    fun onViewEngineLog()         // 查看引擎日志（输出到主页终端）
    fun onLanguageChanged()       // 语言切换后重建页面
  }

  private val prefs = context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)

  /** 顶层条目列表（默认可见）。 */
  private val listContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  /** 子页容器（默认 GONE，显示当前子页）。 */
  private val subContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    visibility = View.GONE
  }

  private val aboutVersionText = TextView(context)
  private val aboutInfoText = TextView(context)

  /** 更新源列表容器（更新子页，refreshMirrorList 重建）。 */
  private lateinit var mirrorList: LinearLayout
  /** 更新源延迟行标签（测速结果实时刷新）。 */
  private lateinit var mirrorLatencyLabels: MutableList<TextView>
  /** 自定义源输入框。 */
  private lateinit var customInput: EditText
  /** 下载记录文本（更新子页）。 */
  private lateinit var downloadHistoryText: TextView

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // 顶层条目列表
    listEntry(I18n.t(context, "通用", "General"), R.drawable.ic_settings) { buildGeneral(it) }
    listEntry(I18n.t(context, "更新", "Updates"), R.drawable.ic_update) { buildUpdate(it) }
    listEntry(I18n.t(context, "存储", "Storage"), R.drawable.ic_open) { buildStorage(it) }
    listEntry(I18n.t(context, "权限", "Permissions"), R.drawable.ic_shield) { buildPermissions(it) }
    listEntry(I18n.t(context, "关于", "About"), R.drawable.ic_info) { buildAbout(it) }
    listEntry(I18n.t(context, "终端", "Terminal"), R.drawable.ic_terminal) { buildTerminal(it) }
    listEntry(I18n.t(context, "插件", "Plugins"), R.drawable.ic_plugin) { buildPlugins(it) }
    addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    addView(subContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  /** 刷新关于子页信息（版本号 / 引擎状态等）。 */
  fun refresh() {
    post { refreshAboutInfo(); refreshDownloadHistory(); refreshMirrorList() }
  }

  /** 是否正停留在某个子页。 */
  fun canPop(): Boolean = subContainer.visibility == View.VISIBLE

  /** 若正在子页则返回条目列表；已处于列表时返回 false。 */
  fun popPage(): Boolean {
    if (!canPop()) return false
    showList()
    return true
  }

  // ============ 顶层导航 ============

  /** 回到条目列表。 */
  private fun showList() {
    subContainer.visibility = View.GONE
    listContainer.visibility = View.VISIBLE
  }

  /** 展示子页：清空 subContainer，加入顶部返回栏（‹ 返回 + 标题）与已构建内容，隐藏条目列表。 */
  private fun showPage(title: String, build: (LinearLayout) -> Unit) {
    subContainer.removeAllViews()
    val page = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }

    // 顶部返回栏
    val backBar = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(2), 0, dp(8))
    }
    backBar.addView(
      TextView(context).apply {
        text = "‹ 返回"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.accent, null))
        setPadding(dp(2), dp(6), dp(14), dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener { popPage() }
      },
    )
    backBar.addView(
      TextView(context).apply {
        text = title
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
      },
    )
    page.addView(backBar)

    // 内容区（可滚动）
    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    build(body)
    val scroll = ScrollView(context).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
    }
    scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    page.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

    subContainer.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    subContainer.visibility = View.VISIBLE
    listContainer.visibility = View.GONE
  }

  /** 顶层条目：卡片行（图标 + 标题 + "›"），点击跳转子页。 */
  private fun listEntry(title: String, iconRes: Int, onClick: (LinearLayout) -> Unit) {
    val accent = resources.getColor(R.color.accent, null)
    val entry = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), dp(14), dp(16), dp(14))
      background = resources.getDrawable(R.drawable.bg_card, null)
      isClickable = true
      isFocusable = true
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    entry.addView(
      ImageView(context).apply {
        setImageResource(iconRes)
        colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        layoutParams = LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
      },
    )
    entry.addView(
      TextView(context).apply {
        text = title
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
      },
    )
    entry.addView(
      TextView(context).apply {
        text = "›"
        textSize = 18f
        setTextColor(resources.getColor(R.color.text_tertiary, null))
        gravity = Gravity.CENTER_VERTICAL
      },
    )
    entry.setOnClickListener { showPage(title) { body -> onClick(body) } }
    listContainer.addView(entry)
  }

  // ============ 子页内容 ============

  /** 通用：语言切换 + 三个原生开关 + 操作按钮。 */
  private fun buildGeneral(body: LinearLayout) {
    // 语言 / Language：点击切换中英文，持久化并重建页面。
    body.addView(languageRow())
    body.addView(divider())
    body.addView(switchRow(I18n.t(context, "保持屏幕常亮", "Keep screen on"),
      I18n.t(context, "引擎运行期间保持屏幕不熄屏", "Keep screen on while the engine runs"),
      "settings_keep_screen_on", false) { checked ->
      callbacks.onSetKeepScreenOn(checked)
    })
    body.addView(divider())
    body.addView(switchRow(I18n.t(context, "启动时自动启动引擎", "Auto-start engine"),
      I18n.t(context, "打开应用后自动拉起引擎服务", "Start the engine service when the app opens"),
      "settings_auto_start_engine", true) { /* 仅持久化 */ })
    body.addView(divider())
    body.addView(switchRow(I18n.t(context, "显示通知", "Show notifications"),
      I18n.t(context, "引擎/桥触发时显示系统通知", "Show system notifications for engine/bridge events"),
      "settings_show_notifications", true) { /* 仅持久化 */ })

    body.addView(sectionLabel(I18n.t(context, "操作", "Actions")), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(8)
    })
    val row1 = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    row1.addView(
      flatButton(I18n.t(context, "检查引擎", "Check engine"), accent = false) { runEngineCheck() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    row1.addView(
      flatButton(I18n.t(context, "打开 Web 界面", "Open Web"), accent = false) { callbacks.onOpenWeb() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(row1)
    val row2 = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    row2.addView(
      flatButton(I18n.t(context, "重启引擎", "Restart engine"), accent = true) { callbacks.onRestartEngine() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    row2.addView(
      flatButton(I18n.t(context, "选择工作目录", "Work dir"), accent = false) { callbacks.onOpenDirectory() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(row2)
    body.addView(
      flatButton(I18n.t(context, "导出调试日志", "Export debug logs"), accent = false) { callbacks.onExportDebugLogs() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
    body.addView(
      flatButton(I18n.t(context, "查看引擎日志", "View engine log"), accent = false) { callbacks.onViewEngineLog() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
  }

  /** 语言 / Language 行：点击切换中/英文，持久化 app_lang 并重建页面。 */
  private fun languageRow(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(8), 0, dp(8))
    isClickable = true
    isFocusable = true

    val textCol = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    textCol.addView(
      TextView(context).apply {
        text = I18n.t(context, "语言 / Language", "Language")
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    textCol.addView(
      TextView(context).apply {
        text = if (I18n.isZh(context)) "点击切换为 English" else "Tap to switch to 中文"
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    addView(textCol)

    addView(
      TextView(context).apply {
        text = if (I18n.isZh(context)) "中文" else "English"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.accent, null))
      },
    )

    setOnClickListener {
      I18n.set(context, if (I18n.isZh(context)) I18n.LANG_EN else I18n.LANG_ZH)
      callbacks.onLanguageChanged()
    }
  }

  /** 更新：检查开关 + 更新动作 + 更新源管理（列表/测速/自定义）+ 下载记录。
   *  更新源列表、自动测速、自定义源与下载记录从主页迁移至此（v0.10.9）。 */
  private fun buildUpdate(body: LinearLayout) {
    body.addView(switchRow(I18n.t(context, "检查更新", "Auto check"),
      I18n.t(context, "启动时自动检查新版本", "Check for new versions on startup"),
      "settings_auto_check_updates", true) { /* 仅持久化 */ })
    body.addView(divider())

    body.addView(
      flatButton(I18n.t(context, "检查并应用更新", "Check & apply update"), accent = true) { callbacks.onCheckUpdate() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
    )
    body.addView(
      flatButton(I18n.t(context, "安装/升级最新 Node.js + Python", "Install/upgrade Node.js + Python"), accent = false) { callbacks.onInstallEnv() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )

    body.addView(sectionLabel(I18n.t(context, "应用更新（APK）", "App update (APK)")), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(14)
    })
    body.addView(
      flatButton(I18n.t(context, "检查应用更新", "Check app update"), accent = true) { callbacks.onCheckApkUpdate() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )

    // ============ 更新源管理（从主页迁移） ============
    body.addView(sectionLabel(I18n.t(context, "更新源", "Update sources")), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(16)
    })
    body.addView(
      flatButton(I18n.t(context, "自动测速选择最快源", "Auto-select fastest source"), accent = false) { speedTestSources() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
    )

    // 更新源列表（每行圆点 + 名称 + 延迟 + 激活标记；点击切换激活源）
    mirrorList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    mirrorLatencyLabels = mutableListOf()
    body.addView(mirrorList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

    // 自定义源输入
    body.addView(sectionLabel(I18n.t(context, "自定义源", "Custom source")), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(12)
    })
    val customRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    customInput = EditText(context).apply {
      hint = "https://example.com/"
      textSize = 12f
      setSingleLine(true)
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    customInput.setText(prefs.getString("custom_source", null))
    customRow.addView(customInput)
    customRow.addView(
      flatButton(I18n.t(context, "添加", "Add"), accent = true) { addCustomSource() },
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) },
    )
    body.addView(customRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

    // 下载记录
    body.addView(sectionLabel(I18n.t(context, "下载记录", "Download history")), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(14)
    })
    downloadHistoryText = TextView(context).apply {
      textSize = 11f
      setLineSpacing(dp(2).toFloat(), 1f)
      setTextColor(resources.getColor(R.color.text_secondary, null))
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    body.addView(downloadHistoryText)
    refreshDownloadHistory()

    refreshMirrorList()
  }

  /** 刷新更新源列表：每行圆点 + 名称 + 延迟 + 激活标记；点击切换激活源并持久化。 */
  private fun refreshMirrorList() {
    if (!::mirrorList.isInitialized) return
    mirrorList.removeAllViews()
    mirrorLatencyLabels.clear()
    val um = UpdateManager.forPrefs(context)
    for (m in um.allMirrors()) {
      val active = um.activeMirror?.id == m.id
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener {
          prefs.edit().putString("active_mirror_id", m.id).apply()
          callbacks.onAppendLog(I18n.t(context, "已选择更新源：", "Source selected: ") + m.name)
          refreshMirrorList()
        }
      }
      val dot = View(context).apply {
        background = GradientDrawable().apply {
          shape = GradientDrawable.OVAL
          setColor(if (active) resources.getColor(R.color.success, null) else resources.getColor(R.color.text_tertiary, null))
        }
        layoutParams = LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
      }
      row.addView(dot)
      row.addView(
        TextView(context).apply {
          text = m.name
          textSize = 13f
          typeface = Typeface.DEFAULT_BOLD
          setTextColor(if (active) resources.getColor(R.color.accent, null) else resources.getColor(R.color.text, null))
          layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        },
      )
      val latencyLabel = TextView(context).apply {
        text = if (active) I18n.t(context, "已激活", "Active") else ""
        textSize = 11f
        setTextColor(if (active) resources.getColor(R.color.success, null) else resources.getColor(R.color.text_tertiary, null))
      }
      mirrorLatencyLabels.add(latencyLabel)
      row.addView(latencyLabel)
      mirrorList.addView(row)
    }
  }

  /** 自动测速：逐源实测延迟并实时刷新行标签，选最快源写入 prefs。 */
  private fun speedTestSources() {
    val um = UpdateManager.forPrefs(context)
    for (i in mirrorLatencyLabels.indices) {
      mirrorLatencyLabels[i].text = I18n.t(context, "测速中…", "testing…")
    }
    Thread {
      val fastest = um.speedTestAll { m, ms ->
        val idx = um.allMirrors().indexOfFirst { it.id == m.id }
        val text = if (ms != null) ms.toString() + " ms" else I18n.t(context, "不可用", "unavailable")
        post {
          if (idx in mirrorLatencyLabels.indices) mirrorLatencyLabels[idx].text = text
        }
      }
      post {
        if (fastest != null) {
          prefs.edit().putString("active_mirror_id", fastest.id).apply()
          callbacks.onAppendLog(
            I18n.t(context, "已选择最快更新源：", "Fastest source selected: ") + fastest.name,
          )
          refreshMirrorList()
        } else {
          callbacks.onAppendLog(I18n.t(context, "测速失败：所有更新源均不可用", "Speed test failed: no source available"))
        }
      }
    }.start()
  }

  /** 添加/更新自定义源：校验 URL 前缀并持久化。 */
  private fun addCustomSource() {
    val raw = customInput.text?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) {
      showToast(I18n.t(context, "请输入更新源地址", "Please enter a source URL"))
      return
    }
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
      showToast(I18n.t(context, "更新源需以 http(s):// 开头", "Source must start with http(s)://"))
      return
    }
    prefs.edit().putString("custom_source", raw).apply()
    callbacks.onAppendLog(I18n.t(context, "已添加自定义源：", "Custom source added: ") + raw)
    refreshMirrorList()
  }

  /** 刷新下载记录文本（最新在前，取前若干条）。 */
  private fun refreshDownloadHistory() {
    if (!::downloadHistoryText.isInitialized) return
    val records = DownloadHistory.list(context)
    if (records.isEmpty()) {
      downloadHistoryText.text = I18n.t(context, "暂无下载记录", "No download history yet")
      return
    }
    val sb = StringBuilder()
    for (r in records.take(8)) {
      sb.append(r.timeLabel()).append("  ").append(r.name).append("  ")
        .append(r.status).append('\n')
      if (r.detail.isNotEmpty()) sb.append("    ").append(r.detail).append('\n')
    }
    downloadHistoryText.text = sb.toString()
  }

  /** 存储：统计应用数据/缓存/公共导出仓库占用，提供一键清理缓存。后台计算避免阻塞 UI。 */
  private fun buildStorage(body: LinearLayout) {
    val dataMb = TextView(context)
    val cacheMb = TextView(context)
    val repoMb = TextView(context)
    body.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(4) })
    body.addView(storageRow(
      dataMb, "应用数据", "运行时 + 用户数据（filesDir）",
    ))
    body.addView(divider())
    body.addView(storageRow(
      cacheMb, "应用缓存", "可安全清理，不影响引擎与配置",
    ))
    body.addView(divider())
    body.addView(storageRow(
      repoMb, "公共导出仓库", "Documents/dshdata（会话导出）",
    ))
    body.addView(
      TextView(context).apply {
        text = "占用统计在进入本页时实时计算，数据量较大时可能需要几秒。"
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_tertiary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
    body.addView(
      flatButton("清理缓存", accent = true) { callbacks.onClearCache() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
    )
    // 后台统计占用，结果 post 回主线程。
    Thread {
      try {
        val data = StorageStats.appDataUsage(context)
        val cache = StorageStats.cacheSize(context)
        val repo = StorageStats.publicRepoSize(context)
        post {
          dataMb.text = "$data MB"
          cacheMb.text = "$cache MB"
          repoMb.text = "$repo MB"
        }
      } catch (_: Throwable) {
      }
    }.start()
  }

  /** 存储行：标题/描述 + 右侧占用数值（value 传入用于后台统计后刷新）。 */
  private fun storageRow(
    value: TextView, title: String, desc: String,
  ): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(10), 0, dp(10))
    val col = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(
      TextView(context).apply {
        text = title
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    col.addView(
      TextView(context).apply {
        text = desc
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    addView(col)
    value.apply {
      text = "- MB"
      textSize = 13f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(resources.getColor(R.color.accent, null))
    }
    addView(value)
  }

  /** 关于（KernelSU 风格）：居中图标/名称/版本 + 信息行 + 占位链接行 + 版权。 */
  private fun buildAbout(body: LinearLayout) {
    // 顶部：图标 + 名称 + 版本（居中）
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(0, dp(2), 0, dp(10))
    }
    header.addView(
      ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        layoutParams = LayoutParams(dp(56), dp(56))
      },
    )
    header.addView(
      TextView(context).apply {
        text = "deepseek HARNESS"
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    // 先把成员 aboutVersionText 从旧父容器解绑，再挂到新 header
    (aboutVersionText.parent as? ViewGroup)?.removeView(aboutVersionText)
    aboutVersionText.apply {
      textSize = 12f
      setTextColor(resources.getColor(R.color.text_secondary, null))
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(0, dp(4), 0, 0)
    }
    header.addView(aboutVersionText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    body.addView(header)

    body.addView(divider())

    // 先把成员 aboutInfoText 从旧父容器解绑，再挂到新 body
    (aboutInfoText.parent as? ViewGroup)?.removeView(aboutInfoText)
    aboutInfoText.textSize = 12f
    aboutInfoText.setLineSpacing(dp(3).toFloat(), 1f)
    aboutInfoText.setTextColor(resources.getColor(R.color.text, null))
    body.addView(aboutInfoText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    body.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    // 社区 / 项目入口：全宽单列纵向按钮（图标+文字+箭头），避免小屏下按钮被裁剪/遮挡
    body.addView(
      fullWidthTile(R.drawable.ic_web, I18n.t(context, "开源地址", "Open source")) { callbacks.onOpenUrl("https://github.com/YOYOFeelings/DeepSeek-Harness-Android") },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
    )
    body.addView(
      fullWidthTile(R.drawable.ic_info, I18n.t(context, "开源许可", "License")) { callbacks.onOpenUrl("https://github.com/YOYOFeelings/DeepSeek-Harness-Android/blob/main/LICENSE") },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
    body.addView(
      fullWidthTile(R.drawable.ic_terminal, I18n.t(context, "QQ群", "QQ group")) { showQQGroupDialog() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
    body.addView(
      fullWidthTile(R.drawable.ic_import, I18n.t(context, "打赏支持", "Donate")) { showToast(I18n.t(context, "打赏功能暂未开放，感谢支持", "Donation is not yet available. Thank you for your support!")) },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )

    // 底部版权
    body.addView(
      TextView(context).apply {
        text = "Powered by DeepSeek Harness"
        textSize = 11f
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(resources.getColor(R.color.text_tertiary, null))
        setPadding(0, dp(14), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
  }

  /** 终端：说明日志统一在主页终端 + 快捷入口。 */
  private fun buildTerminal(body: LinearLayout) {
    body.addView(
      TextView(context).apply {
        text = I18n.t(context, "所有安装/更新/运行日志统一在「主页」终端中展示。", "All logs are displayed in the Home terminal.")
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    body.addView(
      flatButton(I18n.t(context, "打开主页终端", "Open Home terminal"), accent = true) { callbacks.onOpenTerminal() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
  }

  /** 插件：快捷入口（插件管理在「插件」页）。 */
  private fun buildPlugins(body: LinearLayout) {
    body.addView(
      TextView(context).apply {
        text = I18n.t(context, "插件的导入、停用/启用与卸载请前往「插件」页。", "Go to the Plugins page to import, enable/disable, or uninstall plugins.")
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    body.addView(
      flatButton(I18n.t(context, "打开插件页", "Open Plugins"), accent = true) { callbacks.onOpenPlugins() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
  }

  // ============ 刷新逻辑 ============

  /** 刷新关于区：版本号 + Android / DSH_HOME / 引擎状态 / 运行时快照。 */
  private fun refreshAboutInfo() {
    try {
      val pkg = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
      } catch (_: Throwable) {
        null
      }
      // versionCode：API 28+ 用 longVersionCode，更早平台退回 int 的 versionCode（防 NoSuchMethodError）。
      val versionCode: Long = if (pkg == null) {
        0L
      } else if (Build.VERSION.SDK_INT >= 28) {
        pkg.longVersionCode
      } else {
        pkg.versionCode.toLong()
      }
      aboutVersionText.text = I18n.t(context, "版本 ", "Version ") + (pkg?.versionName ?: "?") + " (" + versionCode + ")"
      // 引擎探测放后台线程，结果 post 回主线程，避免 NetworkOnMainThreadException/ANR
      Thread {
        try {
          val running = EngineProbe.check().optBoolean("running", false)
          val dshDir = File(context.filesDir, "home/.dsh")
          val sb = StringBuilder()
          sb.append("Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
          sb.append("DSH_HOME: ").append(dshDir.absolutePath).append('\n')
          sb.append(I18n.t(context, "引擎状态: ", "Engine: "))
            .append(if (running) I18n.t(context, "运行中", "running") else I18n.t(context, "未运行", "stopped")).append('\n')
          sb.append(I18n.t(context, "运行时快照: ", "Runtime snapshot: "))
            .append(if (File(context.filesDir, "usr/bin/node").exists()) I18n.t(context, "已解压", "extracted") else I18n.t(context, "缺失", "missing"))
          post { aboutInfoText.text = sb.toString() }
        } catch (_: Throwable) {
        }
      }.start()
    } catch (_: Throwable) {
    }
  }

  // ============ 操作逻辑 ============

  /** [检查引擎]：结果通过回调转发到主页终端日志。 */
  private fun runEngineCheck() {
    callbacks.onAppendLog("===== 引擎检查 =====")
    // 引擎探测放后台线程（TerminalView.appendLine 内部 post {}，线程安全）
    Thread {
      try {
        callbacks.onAppendLog(EngineProbe.check().toString())
      } catch (_: Throwable) {
        callbacks.onAppendLog("引擎检查异常")
      }
    }.start()
  }

  // ============ 组件辅助 ============

  /** 一行开关（扁平行，无卡片背景）：左侧标题+描述，右侧 Switch；状态持久化并回调 onChange。 */
  private fun switchRow(
    label: String,
    desc: String,
    key: String,
    default: Boolean,
    onChange: (Boolean) -> Unit,
  ): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(8), 0, dp(8))

    val textCol = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    textCol.addView(
      TextView(context).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    textCol.addView(
      TextView(context).apply {
        text = desc
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    addView(textCol)

    val accent = resources.getColor(R.color.accent, null)
    val tertiary = resources.getColor(R.color.text_tertiary, null)
    val sw = Switch(context).apply {
      isChecked = prefs.getBoolean(key, default)
      buttonTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(accent, tertiary),
      )
      setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(key, checked).apply()
        onChange(checked)
      }
    }
    addView(sw)
  }

  /** 分隔线。 */
  private fun divider(): View = View(context).apply {
    setBackgroundColor(resources.getColor(R.color.border, null))
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
  }

  /** 小标题（子页内 section）。 */
  private fun sectionLabel(text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = 13f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(resources.getColor(R.color.text, null))
    setPadding(0, 0, 0, dp(4))
  }

  /** 全宽操作按钮：图标 + 文字 + 右箭头，ghost 卡背景（与列表条目风格一致）。
   *  调用方负责传全宽 LayoutParams。 */
  private fun fullWidthTile(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(14), dp(14), dp(14), dp(14))
      background = resources.getDrawable(R.drawable.bg_button_ghost, null)
      isClickable = true
      isFocusable = true
      addView(
        ImageView(context).apply {
          setImageResource(iconRes)
          colorFilter = PorterDuffColorFilter(resources.getColor(R.color.accent, null), PorterDuff.Mode.SRC_IN)
          layoutParams = LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
        },
      )
      addView(
        TextView(context).apply {
          text = label
          textSize = 14f
          typeface = Typeface.DEFAULT_BOLD
          setTextColor(resources.getColor(R.color.text, null))
          layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        },
      )
      addView(
        TextView(context).apply {
          text = "›"
          textSize = 18f
          setTextColor(resources.getColor(R.color.text_tertiary, null))
          gravity = Gravity.CENTER_VERTICAL
        },
      )
      setOnClickListener { onClick() }
    }

  /** 权限卡片：图标 + 标题/说明 + 状态 + 动作按钮。 */
  private fun permCard(
    iconRes: Int, title: String, desc: String, status: String, granted: Boolean,
    actionLabel: String, onClick: () -> Unit,
  ): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(12), dp(12), dp(12), dp(12))
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    addView(
      ImageView(context).apply {
        setImageResource(iconRes)
        colorFilter = PorterDuffColorFilter(resources.getColor(R.color.accent, null), PorterDuff.Mode.SRC_IN)
        layoutParams = LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
      },
    )
    val col = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(
      TextView(context).apply {
        text = title
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    col.addView(
      TextView(context).apply {
        text = desc
        textSize = 11f
        setLineSpacing(dp(1).toFloat(), 1f)
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    addView(col)
    if (actionLabel.isNotEmpty()) {
      addView(
        TextView(context).apply {
          text = if (granted) "已授权" else actionLabel
          textSize = 12f
          typeface = Typeface.DEFAULT_BOLD
          gravity = Gravity.CENTER
          setPadding(dp(10), dp(6), dp(10), dp(6))
          background = resources.getDrawable(if (granted) R.drawable.bg_button_ghost else R.drawable.bg_button_accent, null)
          setTextColor(if (granted) resources.getColor(R.color.text_tertiary, null) else resources.getColor(R.color.surface, null))
          alpha = if (granted) 0.6f else 1f
          setOnClickListener { if (!granted) onClick() }
        },
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
      )
    } else {
      addView(
        TextView(context).apply {
          text = status
          textSize = 11f
          setTextColor(resources.getColor(R.color.text_secondary, null))
        },
      )
    }
  }

  /** 权限子页：通知 / 所有文件访问 / 网络 三张权限卡片 + 说明。 */
  private fun buildPermissions(body: LinearLayout) {
    // 1) 通知权限（Android 13+ 需要运行时授权）
    body.addView(permCard(
      R.drawable.ic_info, "通知权限",
      "引擎事件 / 桥触发时显示系统通知。Android 13 及以上需要授权。",
      notifStatus(), notifGranted(), "去授权",
    ) { callbacks.onRequestNotificationPermission() })
    body.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6) })
    // 2) 所有文件访问（Android 11+ 外部工作区必需）
    body.addView(permCard(
      R.drawable.ic_open, "所有文件访问",
      "外部工作区需要该权限，引擎（bash）才能读写你选择的文件夹。",
      filesStatus(), filesGranted(), "去授权",
    ) { callbacks.onOpenAllFilesAccessSettings() })
    body.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6) })
    // 3) 网络（无需授权，仅说明）
    body.addView(permCard(
      R.drawable.ic_web, "网络",
      "用于在线更新与多镜像源加速下载，安装时自动使用。",
      "无需授权", true, "",
    ) {})
    body.addView(
      TextView(context).apply {
        text = "权限状态在进入本页时实时读取；授权结果返回后返回本页即可刷新。"
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_tertiary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
    )
  }

  private fun notifGranted(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
      context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun notifStatus(): String = when {
    Build.VERSION.SDK_INT < 33 -> "不适用（Android 13+）"
    notifGranted() -> "已授权"
    else -> "未授权"
  }

  private fun filesGranted(): Boolean =
    Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

  private fun filesStatus(): String = when {
    Build.VERSION.SDK_INT < 30 -> "不适用（Android 11+）"
    filesGranted() -> "已授予"
    else -> "未授予"
  }

  /** 扁平按钮（accent=实心主按钮 / ghost=次要按钮）。 */
  private fun flatButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
    TextView(context).apply {
      text = label
      textSize = 13f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(10), dp(8), dp(10), dp(8))
      background = resources.getDrawable(if (accent) R.drawable.bg_button_accent else R.drawable.bg_button_ghost, null)
      setTextColor(
        if (accent) resources.getColor(R.color.surface, null) else resources.getColor(R.color.text, null)
      )
      setOnClickListener { onClick() }
    }

  /** QQ 群弹窗：展示官方 QQ 群号。 */
  private fun showQQGroupDialog() {
    AlertDialog.Builder(context)
      .setTitle(I18n.t(context, "加入 QQ 群", "Join QQ Group"))
      .setMessage(I18n.t(context, "QQ 群 1：200317338\nQQ 群 2：932593560", "QQ Group 1: 200317338\nQQ Group 2: 932593560"))
      .setPositiveButton(I18n.t(context, "确定", "OK"), null)
      .show()
  }

  private fun showToast(text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

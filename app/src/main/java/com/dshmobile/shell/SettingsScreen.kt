package com.dshmobile.shell

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    fun onInstallEnv()            // 运行环境安装（日志输出到主页终端）
    fun onAppendLog(line: String) // 向主页终端写一行日志
    fun onOpenUrl(url: String)      // 用系统浏览器打开外链
    fun onRequestNotificationPermission()   // 请求/跳转通知权限
    fun onOpenAllFilesAccessSettings()      // 打开所有文件访问授权页
    fun onClearCache()            // 清理应用缓存（cacheDir + WebView 缓存）
    fun onViewEngineLog()         // 查看引擎日志（输出到主页终端）
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

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // 顶层条目列表
    listEntry("通用", R.drawable.ic_settings) { buildGeneral(it) }
    listEntry("更新", R.drawable.ic_update) { buildUpdate(it) }
    listEntry("存储", R.drawable.ic_open) { buildStorage(it) }
    listEntry("权限", R.drawable.ic_shield) { buildPermissions(it) }
    listEntry("关于", R.drawable.ic_info) { buildAbout(it) }
    listEntry("终端", R.drawable.ic_terminal) { buildTerminal(it) }
    listEntry("插件", R.drawable.ic_plugin) { buildPlugins(it) }
    addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    addView(subContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  /** 刷新关于子页信息（版本号 / 引擎状态等）。 */
  fun refresh() {
    post { refreshAboutInfo() }
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

  /** 通用：三个原生开关 + 操作按钮。 */
  private fun buildGeneral(body: LinearLayout) {
    body.addView(switchRow("保持屏幕常亮", "引擎运行期间保持屏幕不熄屏", "settings_keep_screen_on", false) { checked ->
      callbacks.onSetKeepScreenOn(checked)
    })
    body.addView(divider())
    body.addView(switchRow("启动时自动启动引擎", "打开应用后自动拉起引擎服务", "settings_auto_start_engine", true) { /* 仅持久化 */ })
    body.addView(divider())
    body.addView(switchRow("显示通知", "引擎/桥触发时显示系统通知", "settings_show_notifications", true) { /* 仅持久化 */ })

    body.addView(sectionLabel("操作"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(8)
    })
    val row1 = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    row1.addView(
      flatButton("检查引擎", accent = false) { runEngineCheck() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    row1.addView(
      flatButton("打开 Web 界面", accent = false) { callbacks.onOpenWeb() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(row1)
    val row2 = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    row2.addView(
      flatButton("重启引擎", accent = true) { callbacks.onRestartEngine() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    row2.addView(
      flatButton("选择工作目录", accent = false) { callbacks.onOpenDirectory() },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(row2)
    body.addView(
      flatButton("导出调试日志", accent = false) { callbacks.onExportDebugLogs() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
    body.addView(
      flatButton("查看引擎日志", accent = false) { callbacks.onViewEngineLog() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
  }

  /** 更新：检查更新开关 + 更新动作 + GitHub 更新源占位。 */
  private fun buildUpdate(body: LinearLayout) {
    body.addView(switchRow("检查更新", "启动时自动检查新版本", "settings_auto_check_updates", true) { /* 仅持久化，待接入 */ })
    body.addView(divider())

    body.addView(
      flatButton("检查并应用更新", accent = true) { callbacks.onCheckUpdate() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
    )
    body.addView(
      flatButton("安装/升级最新 Node.js + Python", accent = false) { callbacks.onInstallEnv() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )

    body.addView(sectionLabel("GitHub 更新源（待接入）"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(14)
    })
    body.addView(
      TextView(context).apply {
        text = "后续将对比 GitHub 仓库配置文件与本地产物判断是否更新，" +
          "并按最新配置中的下载链接进行下载。\n（配置中…）"
        textSize = 12f
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
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

    // 社区 / 项目入口：2×2 网格按钮（等宽、图标+文字）
    val gridRow1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    gridRow1.addView(
      gridTile(R.drawable.ic_web, "项目主页") { callbacks.onOpenUrl("https://github.com/YOYOFeelings/DeepSeek-Harness-Android") },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    gridRow1.addView(
      gridTile(R.drawable.ic_info, "开源许可") { callbacks.onOpenUrl("https://github.com/YOYOFeelings/DeepSeek-Harness-Android/blob/main/LICENSE") },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(gridRow1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    val gridRow2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    gridRow2.addView(
      gridTile(R.drawable.ic_terminal, "QQ群") { showToast("QQ 群暂未开放，敬请期待") },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
    )
    gridRow2.addView(
      gridTile(R.drawable.ic_import, "打赏支持") { showToast("打赏功能暂未开放，感谢支持") },
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
    )
    body.addView(gridRow2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

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
        text = "所有安装/更新/运行日志统一在「主页」终端中展示。"
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    body.addView(
      flatButton("打开主页终端", accent = true) { callbacks.onOpenTerminal() },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
    )
  }

  /** 插件：快捷入口（插件管理在「插件」页）。 */
  private fun buildPlugins(body: LinearLayout) {
    body.addView(
      TextView(context).apply {
        text = "插件的导入、停用/启用与卸载请前往「插件」页。"
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )
    body.addView(
      flatButton("打开插件页", accent = true) { callbacks.onOpenPlugins() },
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
      aboutVersionText.text = "版本 " + (pkg?.versionName ?: "?") + " (" + versionCode + ")"
      // 引擎探测放后台线程，结果 post 回主线程，避免 NetworkOnMainThreadException/ANR
      Thread {
        try {
          val running = EngineProbe.check().optBoolean("running", false)
          val dshDir = File(context.filesDir, "home/.dsh")
          val sb = StringBuilder()
          sb.append("Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
          sb.append("DSH_HOME: ").append(dshDir.absolutePath).append('\n')
          sb.append("引擎状态: ").append(if (running) "运行中" else "未运行").append('\n')
          sb.append("运行时快照: ")
            .append(if (File(context.filesDir, "usr/bin/node").exists()) "已解压" else "缺失")
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

  /** 2×2 网格单元：图标上、文字下，ghost 卡背景。调用方负责传等宽 LayoutParams。 */
  private fun gridTile(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(4), dp(14), dp(4), dp(14))
      background = resources.getDrawable(R.drawable.bg_button_ghost, null)
      isClickable = true
      isFocusable = true
      addView(
        ImageView(context).apply {
          setImageResource(iconRes)
          colorFilter = PorterDuffColorFilter(resources.getColor(R.color.accent, null), PorterDuff.Mode.SRC_IN)
          layoutParams = LayoutParams(dp(24), dp(24))
        },
      )
      addView(
        TextView(context).apply {
          text = label
          textSize = 12f
          setTextColor(resources.getColor(R.color.text, null))
          gravity = Gravity.CENTER_HORIZONTAL
          setPadding(0, dp(6), 0, 0)
        },
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
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

  private fun showToast(text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

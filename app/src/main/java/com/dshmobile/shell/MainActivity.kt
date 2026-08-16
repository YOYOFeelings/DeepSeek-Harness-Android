package com.dshmobile.shell

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 壳层 Activity（Flat Minimalist 设计）：启动后直接进入「终端」主页。
 * 首次启动：权限引导（通知 + 所有文件访问，非阻断）→ 终端内实时跑安装/更新管线
 * （解压运行时 → 写配置 → 在线更新[多镜像源自动测速/回退] → 启动引擎）。
 * 导航：竖屏/窄屏底部导航栏；横屏/大屏（平板大比例）自动切换为左侧导航栏。
 * 页签：终端 / 插件 / 设置（更新功能已并入终端主页：检查更新 / 安装运行时环境）。
 * 统一日志：所有日志（引导、引擎流程、插件导入、更新、环境安装、引擎检查）统一输出到主页终端。
 * 引擎启动：启动期间把 engine.log 实时流式输出到控制台，点击「重启引擎」必有输出反馈。
 */
class MainActivity : ComponentActivity() {

  private val prefs by lazy { getSharedPreferences("dsh_shell", Context.MODE_PRIVATE) }
  private val engineManager by lazy { EngineManager(this, pickToken) }
  private val engineFlowRunning = AtomicBoolean(false)

  /** 更新管理器（多镜像源自动测速/回退）。 */
  private val updateManager by lazy { UpdateManager.forPrefs(this) }

  /** 运行时环境（Node.js / Python）在线安装/升级管理器。 */
  private val envManager by lazy { EnvManager(this) }

  /** 目录选择桥鉴权 token（引擎侧 pick 端点校验；DSH_PICK_TOKEN）。 */
  private val pickToken: String by lazy { java.util.UUID.randomUUID().toString() }
  /** 待回传 JS 的目录选择回调 id（webView 桥 pickDirectory）。 */
  @Volatile private var pendingPickCallback: String? = null

  private lateinit var contentFrame: FrameLayout
  private lateinit var terminalScreen: TerminalScreen
  private lateinit var pluginsScreen: PluginsScreen
  private lateinit var settingsScreen: SettingsScreen
  private lateinit var bottomNavBar: LinearLayout
  private lateinit var leftNavRail: LinearLayout
  private lateinit var webOverlay: FrameLayout
  private lateinit var webView: android.webkit.WebView
  /** Web 覆盖层顶栏标题（兼作加载状态提示文本）。 */
  private lateinit var webTitleText: TextView

  /** 引导是否进行中：安装/更新管线在跑时不触发 onResume 的引擎流程（防双启动竞态）。 */
  @Volatile private var onboardingActive = false

  /** 引导页是否正在展示（期间不触发 onResume 引擎流程）。 */
  @Volatile private var guideActive = false

  /** 首次引导页视图（GONE 默认，首次启动时 VISIBLE）。 */
  private lateinit var onboardingView: View

  /** engine.log 已流式输出到终端的字节数（跨流程复用，只输出新增行）。 */
  @Volatile private var engineLogRead = 0L

  private enum class Tab { TERMINAL, PLUGINS, SETTINGS }

  /** 当前所在页签（系统返回键在设置页内页时先回退内页）。 */
  private var currentTab = Tab.TERMINAL

  /** 导航视图（item/icon/label），供底部导航与左侧导航共用高亮。 */
  private data class NavViews(val item: LinearLayout, val icon: ImageView, val label: TextView)

  private val navTabs = HashMap<Tab, MutableList<NavViews>>()

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {
      // 授权结果返回后刷新设置页「权限」子页状态
      runOnUiThread { if (::settingsScreen.isInitialized) settingsScreen.refresh() }
    }

  /** 外部工作区目录选择（SAF）。 */
  private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    val term = terminalScreen.terminal()
    if (uri != null) {
      val path = AndroidBridge.resolvePickedPath(uri)
      prefs.edit().putString("workspace_dir", path).apply()
      term.appendLine("工作目录已选择: $path")
      term.appendLine("外部工作区由引擎（bash）直接读写该文件夹")
      pendingPickCallback?.let { cb ->
        pendingPickCallback = null
        webView.evaluateJavascript(
          "window.__dshBridge && window.__dshBridge.onDirectoryPicked(" + jsString(cb) + ", " + jsString(path) + ")",
          null,
        )
      }
    } else {
      term.appendLine("已取消选择工作目录")
      pendingPickCallback?.let { cb ->
        pendingPickCallback = null
        webView.evaluateJavascript(
          "window.__dshBridge && window.__dshBridge.onDirectoryPicked(" + jsString(cb) + ", " + jsString("") + ")",
          null,
        )
      }
    }
  }

  /** 插件包（.tgz）文件选择（SAF）：复制到缓存后交给插件页解压导入。 */
  private val pluginPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val term = terminalScreen.terminal()
    if (uri == null) return@registerForActivityResult
    try {
      val tmp = File(cacheDir, "import-plugin.tgz")
      contentResolver.openInputStream(uri)?.use { input ->
        tmp.outputStream().use { out -> input.copyTo(out) }
      }
      val msg = pluginsScreen.importFrom(tmp)
      tmp.delete()
      term.appendLine("插件导入：" + msg)
      pluginsScreen.refresh()
    } catch (t: Throwable) {
      term.appendLine("插件导入失败：" + (t.message ?: t.javaClass.simpleName))
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    contentFrame = FrameLayout(this)
    terminalScreen = TerminalScreen(this, object : TerminalScreen.Callbacks {
      override fun onRestartEngine() = restartEngine()
      override fun onOpenDirectory() = pickDirectory()
      override fun onOpenWeb() = openWeb()
      override fun onCheckUpdate() = runCheckUpdate()
      override fun onInstallEnv() = runInstallEnv()
      override fun onOpenPlugins() { showTab(Tab.PLUGINS) }
      override fun onClearCache() = clearCache()
    })
    contentFrame.addView(constrained(terminalScreen), centeredParams())
    pluginsScreen = PluginsScreen(this, object : PluginsScreen.Callbacks {
      override fun onImportPlugin() = pluginPicker.launch(arrayOf("*/*"))
    })
    contentFrame.addView(constrained(pluginsScreen), centeredParams())
    pluginsScreen.visibility = View.GONE
    settingsScreen = SettingsScreen(this, object : SettingsScreen.Callbacks {
      override fun onOpenWeb() = openWeb()
      override fun onRestartEngine() = restartEngine()
      override fun onOpenDirectory() = pickDirectory()
      override fun onExportDebugLogs() = exportDebugLogs()
      override fun onSetKeepScreenOn(enable: Boolean) {
        contentFrame.keepScreenOn = enable
      }
      override fun onOpenPlugins() { showTab(Tab.PLUGINS) }
      override fun onOpenTerminal() { showTab(Tab.TERMINAL) }
      override fun onCheckUpdate() = runCheckUpdate()
      override fun onInstallEnv() = runInstallEnv()
      override fun onAppendLog(line: String) { terminalScreen.terminal().appendLine(line) }
      override fun onOpenUrl(url: String) {
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
          terminalScreen.terminal().appendLine("无法打开链接：" + url)
        }
      }
      override fun onRequestNotificationPermission() = requestNotificationPermission()
      override fun onOpenAllFilesAccessSettings() = openAllFilesAccessSettings()
      override fun onClearCache() = clearCache()
      override fun onViewEngineLog() = viewEngineLog()
    })
    contentFrame.addView(constrained(settingsScreen), centeredParams())
    settingsScreen.visibility = View.GONE
    webOverlay = buildWebOverlay()
    contentFrame.addView(webOverlay, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    // 首次引导页：盖在最上层（webOverlay 之后加入），默认 GONE。
    onboardingView = buildOnboardingView()
    contentFrame.addView(onboardingView, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    onboardingView.visibility = View.GONE
    root.addView(contentFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    bottomNavBar = buildBottomNavBar()
    root.addView(bottomNavBar, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
    ))
    leftNavRail = buildLeftNavRail()
    root.addView(leftNavRail, FrameLayout.LayoutParams(
      dp(78), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT,
    ))
    setContentView(root)
    refreshNavHighlight(Tab.TERMINAL)
    applyNavLayout()
    // 系统返回键：Web 覆盖层先关闭（并复位 webActive）；设置页内页先回退内页；否则走默认返回。
    onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webOverlay.visibility == View.VISIBLE) {
          webView.stopLoading()
          webOverlay.visibility = View.GONE
          EngineManager.webActive = false
        } else if (currentTab == Tab.SETTINGS && settingsScreen.canPop()) {
          settingsScreen.popPage()
        } else {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
        }
      }
    })
    startFlow()
  }

  override fun onResume() {
    super.onResume()
    // 引导页展示期间不启动引擎流程（等待「开始使用」）。
    if (guideActive) return
    // 引导期间不启动引擎流程（安装/更新管线在跑，避免双启动竞态）。
    if (onboardingActive) return
    // 设置页「启动时自动启动引擎」关闭时，回到前台不再自动拉起引擎。
    if (!autoStartEngineEnabled()) return
    Thread {
      if (!EngineProbe.check().optBoolean("running", false)) runOnUiThread { startEngineFlow() }
    }.start()
  }

  /** 横竖屏/尺寸变化（configChanges 接管）：重新计算导航布局。 */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applyNavLayout()
  }

  override fun onDestroy() {
    super.onDestroy()
    webView.destroy()
    // 引擎由 EngineService 前台服务保活（后台继续运行），此处不再杀引擎。
  }

  /** 内容容器：普通 FrameLayout，让页面内容铺满整个可用宽度（不做限宽居中，
   *  避免宽屏/平板下出现“中间一小块”的留白）。 */
  private fun constrained(v: View): FrameLayout {
    val wrap = FrameLayout(this)
    wrap.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return wrap
  }

  private fun centeredParams(): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
      Gravity.CENTER_HORIZONTAL,
    )

  /** 横屏/大屏（平板大比例）→ 左侧导航；竖屏窄屏 → 底部导航。 */
  private fun useLeftNav(): Boolean {
    val cfg = resources.configuration
    return cfg.screenWidthDp > cfg.screenHeightDp || cfg.screenWidthDp >= 600
  }

  /** 按方向/尺寸应用导航布局：切换底部/左侧导航，并给内容区留出对应边距。 */
  private fun applyNavLayout() {
    val left = useLeftNav()
    bottomNavBar.visibility = if (left) View.GONE else View.VISIBLE
    leftNavRail.visibility = if (left) View.VISIBLE else View.GONE
    val lp = contentFrame.layoutParams as FrameLayout.LayoutParams
    val targetLeft = if (left) dp(78) else 0
    val targetBottom = if (left) 0 else dp(62)
    if (lp.leftMargin != targetLeft || lp.bottomMargin != targetBottom) {
      lp.leftMargin = targetLeft
      lp.bottomMargin = targetBottom
      contentFrame.layoutParams = lp
    }
  }

  /** 入口：未看过引导页 → 先展示全屏引导页；否则 → 进安装（未装完）或主页（已装完）。 */
  private fun startFlow() {
    if (!prefs.getBoolean("guide_seen", false)) {
      guideActive = true
      onboardingView.visibility = View.VISIBLE
    } else {
      startPostGuide()
    }
  }

  /** 引导页「开始使用」：标记已看 → 进安装（未装完）或主页（已装完）。 */
  private fun startPostGuide() {
    if (prefs.getBoolean("onboarded", false)) {
      onboardingActive = false
      enterTerminal()
    } else {
      onboardingActive = true
      runOnboarding()
    }
  }

  /** 首次安装：直接在主页终端内跑安装/更新管线（权限说明已并入引导页，不再重复弹窗）。 */
  private fun runOnboarding() {
    val term = terminalScreen.terminal()
    term.appendLine("===== dsh 首次安装 =====")
    term.appendLine("安装 dsh 配置/插件并更新到最新版…")
    Thread { runBootstrapPipeline(term) }.start()
  }

  /** 所有文件访问权限的说明对话框（非阻断）："去授权"跳系统设置，"稍后"关闭。 */
  private fun showAllFilesAccessExplainDialog() {
    AlertDialog.Builder(this)
      .setTitle("需要文件访问权限")
      .setMessage("外部工作区需要「所有文件访问权限」，以便引擎（bash）能读写你选择的文件夹。")
      .setPositiveButton("去授权") { _: android.content.DialogInterface?, _: Int ->
        openAllFilesAccessSettings()
      }
      .setNegativeButton("稍后") { dialog: android.content.DialogInterface, _: Int ->
        dialog.dismiss()
      }
      .show()
  }

  /** 进入终端主页并确保引擎运行。 */
  private fun enterTerminal() {
    terminalScreen.terminal().appendLine("===== dsh 终端 =====")
    if (prefs.getBoolean("settings_auto_check_updates", true)) {
      terminalScreen.terminal().appendLine("[检查更新] 已开启（GitHub 更新源配置中…）")
    }
    // 设置页「启动时自动启动引擎」关闭时，不自动拉起引擎（可在终端页手动重启）。
    if (!autoStartEngineEnabled()) {
      terminalScreen.setEngineStatus(false, "")
      return
    }
    Thread {
      val running = EngineProbe.check().optBoolean("running", false)
      runOnUiThread {
        if (running) {
          terminalScreen.setEngineStatus(true, "引擎已在运行")
        } else {
          terminalScreen.setEngineStatus(false, "")
          startEngineFlow()
        }
      }
    }.start()
  }

  /** 设备 ABI + 内嵌快照架构诊断文本（供架构不匹配失败提示使用）。 */
  private fun archDiagnostic(): String =
    "设备 ABI=" + Build.SUPPORTED_ABIS.joinToString() +
      "，内嵌快照架构=" + engineManager.embeddedNodeArchLabel()

  /** 把 engine.log 中「上次已读位置之后」的新行追加到终端（后台线程调用）。 */
  private fun drainEngineLog(term: TerminalView) {
    val log = File(filesDir, "engine.log")
    if (!log.exists()) return
    val len = log.length()
    if (len <= engineLogRead) return
    try {
      val text = log.readText()
      val start = engineLogRead.coerceAtMost(text.length.toLong()).toInt()
      val newPart = text.substring(start)
      engineLogRead = len
      for (line in newPart.lineSequence()) {
        if (line.isNotBlank()) term.appendLine(line)
      }
    } catch (_: Throwable) {
    }
  }

  /** 启动引擎并轮询就绪；期间把 engine.log 实时流式输出到控制台。
   *  @return true=就绪；false=超时/失败（日志已追加到终端）。 */
  private fun startEngineWithStreaming(term: TerminalView): Boolean {
    if (!engineManager.startEngine(force = true)) {
      term.appendLine("引擎启动失败")
      appendEngineDiagnostics(term)
      terminalScreen.setEngineStatus(false, "")
      return false
    }
    var started = false
    for (i in 0..180) {
      drainEngineLog(term)
      if (EngineProbe.check().optBoolean("running", false)) { started = true; break }
      term.appendProgress("启动引擎", "等待引擎就绪", i.toLong(), 180L)
      Thread.sleep(500)
    }
    drainEngineLog(term)
    return started
  }

  /** 安装/更新 bootstrap 管线（后台线程执行）：
   *  解压运行时 → 写 dsh 配置 → 在线更新（架构不符时为必须，否则失败不阻断）
   *  → 启动引擎 → 完成进终端主页。 */
  private fun runBootstrapPipeline(term: TerminalView) {
    // 运行时就绪判定：node 已存在但架构错误时，跳过内嵌重解压（内嵌同样是错的）。
    if (!engineManager.nodeArchMatchesDevice()) {
      term.appendLine("检测到运行时架构与设备不匹配，将在线下载匹配架构…")
    } else if (engineManager.snapshotFresh()) {
      term.appendLine("内嵌运行时已就绪")
    } else {
      term.appendProgress("解压运行时", "解压内嵌运行时", 0, 0)
      val ok = engineManager.refreshSnapshot { done, total ->
        term.appendProgress("解压运行时", "解压内嵌运行时", done, total)
      }
      if (!ok) {
        term.appendLine("解压失败")
        onBootstrapFatal("解压内嵌运行时失败，请重试")
        return
      }
      term.appendLine("内嵌运行时已就绪")
    }
    // 再次判定架构：内嵌快照可能本身就是错误架构（解压后才知道），此时必须在线修复。
    val archMismatch = !engineManager.nodeArchMatchesDevice()
    if (archMismatch) {
      term.appendLine("内嵌运行时架构与设备不匹配，必须在线下载匹配架构…")
    }
    term.appendProgress("安装配置", "写入 dsh 配置/插件集合", 0, 0)
    val dsh = engineManager.installFactoryConfig()
    term.appendLine("dsh 配置已写入 " + dsh.absolutePath)

    // 在线更新（多镜像源自动测速/回退）：架构不匹配时为必须，否则失败不阻断。
    term.appendLine(if (archMismatch) "必须在线更新匹配架构运行时…" else "检查在线更新…")
    val updateDone = AtomicBoolean(false)
    val updateOk = AtomicBoolean(false)
    val um = UpdateManager.forPrefs(this)
    um.checkAndApply(
      onStage = { stage, msg ->
        term.appendProgress(stage, msg, 0, 0)
        if (stage == "完成") { updateOk.set(true); updateDone.set(true) }
        else if (stage == "失败") updateDone.set(true)
      },
      onProgress = { done, total -> term.appendProgress("更新", "进度", done, total) },
    )
    while (!updateDone.get()) Thread.sleep(200)
    if (!updateOk.get()) {
      if (archMismatch) {
        term.appendLine("在线更新失败（架构不匹配，必须修复）")
        appendEngineDiagnostics(term)
        onBootstrapFatal("无法下载匹配设备架构的运行时，请检查网络或更换更新源（" + archDiagnostic() + "）")
        return
      }
      term.appendLine("在线更新不可用，继续使用内嵌运行时")
    } else if (archMismatch) {
      term.appendLine("匹配架构运行时已就绪")
    }

    // 启动引擎并轮询就绪（期间流式输出 engine.log）。
    term.appendProgress("启动引擎", "启动 dsh 引擎", 0, 0)
    File(filesDir, "engine.log").delete()
    engineLogRead = 0
    val started = startEngineWithStreaming(term)
    if (!started) {
      term.appendLine("引擎启动超时")
      appendEngineDiagnostics(term)
      onBootstrapFatal("引擎启动超时，请重试")
      return
    }
    term.appendLine("引擎就绪")
    startEngineService()
    applyShizukuKeepAlive()
    runOnUiThread {
      prefs.edit().putBoolean("onboarded", true).apply()
      onboardingActive = false
      terminalScreen.setEngineStatus(true, "")
      term.appendLine("安装完成，欢迎使用 dsh")
    }
  }

  /** 检查更新（合并到主页终端）：后台线程跑在线更新管线，详细日志流式输出到主页终端。 */
  private fun runCheckUpdate() {
    val term = terminalScreen.terminal()
    term.appendDetail("开始检查更新")
    val done = AtomicBoolean(false)
    val ok = AtomicBoolean(false)
    updateManager.checkAndApply(
      onStage = { stage, msg ->
        term.appendProgress(stage, msg, 0, 0)
        if (stage == "完成") { ok.set(true); done.set(true) }
        else if (stage == "失败") done.set(true)
      },
      onProgress = { d, t -> term.appendProgress("更新", "进度", d, t) },
    )
    Thread {
      while (!done.get()) Thread.sleep(200)
      if (ok.get()) term.appendDetail("更新完成") else term.appendDetail("更新失败")
      runOnUiThread { terminalScreen.refresh() }
    }.start()
  }

  /** 安装/升级运行时环境（合并到主页终端）：后台线程跑环境安装管线，实时日志输出到主页终端。 */
  private fun runInstallEnv() {
    val term = terminalScreen.terminal()
    term.appendDetail("开始安装/升级运行时环境")
    envManager.installLatest(
      onLine = { line -> term.appendLine(line) },
      onDone = { ok, msg ->
        term.appendDetail(if (ok) "环境安装成功：" + msg else "环境安装失败：" + msg)
        runOnUiThread { terminalScreen.refresh() }
      },
    )
  }

  /** 引擎启动失败/超时后：把 engine.log 末尾追加到终端，便于排查。 */
  private fun appendEngineDiagnostics(term: TerminalView) {
    val tail = engineManager.engineLogTail(40)
    if (tail.isBlank()) {
      term.appendLine("（无 engine.log 输出）")
    } else {
      term.appendLine("----- engine.log 末尾 -----")
      for (line in tail.lineSequence()) term.appendLine(line)
      term.appendLine("----- 日志结束 -----")
    }
  }

  /** 致命失败处理（主线程）：弹重试/跳过对话框。 */
  private fun onBootstrapFatal(msg: String) {
    runOnUiThread {
      AlertDialog.Builder(this)
        .setTitle("安装未完成")
        .setMessage(msg)
        .setCancelable(false)
        .setPositiveButton("重试") { _: android.content.DialogInterface?, _: Int ->
          Thread { runBootstrapPipeline(terminalScreen.terminal()) }.start()
        }
        .setNegativeButton("稍后进入主页") { _: android.content.DialogInterface?, _: Int ->
          prefs.edit().putBoolean("onboarded", true).apply()
          onboardingActive = false
          terminalScreen.setEngineStatus(false, "")
        }
        .show()
    }
  }

  /** 用户主动重启引擎：清空旧日志 → 停旧进程 → 强制启动 → 流式输出日志到控制台。
   *  若上一次流程仍在进行，也会明确提示，不再静默无输出。 */
  private fun restartEngine() {
    val term = terminalScreen.terminal()
    if (engineFlowRunning.get()) {
      term.appendLine("上一次引擎流程仍在进行，请稍候再试")
      return
    }
    term.appendLine("===== 重启引擎 =====")
    Thread {
      engineManager.stopEngine()
      File(filesDir, "engine.log").delete()
      engineLogRead = 0
      val started = startEngineWithStreaming(term)
      if (started) {
        term.appendLine("引擎就绪")
        terminalScreen.setEngineStatus(true, "")
        startEngineService(); applyShizukuKeepAlive()
      } else {
        term.appendLine("引擎启动超时")
        appendEngineDiagnostics(term)
        terminalScreen.setEngineStatus(false, "")
      }
    }.start()
  }

  /** Engine-first flow: 复用已在跑的引擎，否则解压快照/修复架构并启动内嵌引擎，轮询就绪。 */
  private fun startEngineFlow() {
    if (!engineFlowRunning.compareAndSet(false, true)) return
    Thread {
      try {
        if (EngineProbe.check().optBoolean("running", false)) return@Thread
        // 运行时就绪判定：node 已存在但架构错误时，跳过内嵌重解压（内嵌同样是错的）。
        val needArchFix = !engineManager.nodeArchMatchesDevice()
        if (!needArchFix && !engineManager.snapshotFresh()) {
          terminalScreen.setEngineStatus(false, "正在更新运行时（约 70MB）…")
          val ok = engineManager.refreshSnapshot { done, _ ->
            terminalScreen.terminal().appendProgress("解压运行时", "更新运行时", done, 0)
          }
          if (!ok) {
            terminalScreen.terminal().appendLine("运行时更新失败，请重试")
            terminalScreen.setEngineStatus(false, "")
            return@Thread
          }
        }
        // 再次判定架构：内嵌快照可能本身就是错误架构（解压后才知道），此时必须在线修复。
        val archMismatch = needArchFix || !engineManager.nodeArchMatchesDevice()
        if (archMismatch) {
          // node 架构不符：内嵌快照架构错误，在线下载匹配架构（必须成功）。
          terminalScreen.terminal().appendLine("检测到运行时架构不匹配，在线下载匹配架构…")
          val done = AtomicBoolean(false)
          val ok = AtomicBoolean(false)
          val um = UpdateManager.forPrefs(this)
          um.checkAndApply(
            onStage = { stage, msg ->
              terminalScreen.terminal().appendProgress(stage, msg, 0, 0)
              if (stage == "完成") { ok.set(true); done.set(true) }
              else if (stage == "失败") done.set(true)
            },
            onProgress = { d, t -> terminalScreen.terminal().appendProgress("更新", "进度", d, t) },
          )
          while (!done.get()) Thread.sleep(200)
          if (!ok.get()) {
            terminalScreen.terminal().appendLine("在线更新失败，引擎无法启动（请检查网络/更新源）")
            terminalScreen.terminal().appendLine(archDiagnostic())
            appendEngineDiagnostics(terminalScreen.terminal())
            terminalScreen.setEngineStatus(false, "")
            return@Thread
          }
          terminalScreen.terminal().appendLine("匹配架构运行时已就绪")
        }
        terminalScreen.terminal().appendLine("启动引擎…")
        val started = startEngineWithStreaming(terminalScreen.terminal())
        if (started) {
          startEngineService()
          applyShizukuKeepAlive()
          terminalScreen.setEngineStatus(true, "引擎就绪")
        } else {
          terminalScreen.terminal().appendLine("引擎启动超时，请重试")
          appendEngineDiagnostics(terminalScreen.terminal())
          terminalScreen.setEngineStatus(false, "")
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** 工作目录选择：未授 All Files Access 先引导，否则直接 SAF 选择。 */
  private fun pickDirectory() {
    if (Build.VERSION.SDK_INT < 30) {
      terminalScreen.terminal().appendLine("Android 10 及以下不支持选择外部目录")
      return
    }
    if (!android.os.Environment.isExternalStorageManager()) {
      showAllFilesAccessExplainDialog()
      return
    }
    directoryPicker.launch(null)
  }

  /** 打开引擎 Web 界面（WebView 覆盖层淡入；顶部含刷新/外部浏览器/关闭）。 */
  private fun openWeb() {
    Thread {
      val probe = EngineProbe.check()
      if (!probe.optBoolean("running", false)) {
        // reason=refused：连接拒绝=引擎确未运行；timeout 等=引擎繁忙/无响应。
        val hint = if (probe.optString("reason", "") == "refused")
          "引擎未运行，请先「重启引擎」再进入 Web"
        else
          "引擎繁忙或无响应，请稍候重试"
        runOnUiThread { terminalScreen.terminal().appendLine(hint) }
        return@Thread
      }
      runOnUiThread {
        // 标记 Web 覆盖层打开：看门狗在用户主动使用引擎期间不自动重启。
        EngineManager.webActive = true
        webOverlay.alpha = 0f
        webOverlay.visibility = View.VISIBLE
        webOverlay.animate().alpha(1f).setDuration(200).start()
        webView.loadUrl(EngineProbe.ENGINE_URL)
      }
    }.start()
  }

  /** Web 端 localStorage 宿主化代理脚本：在引擎页面脚本运行前注入 `</head>` 前。 */
  private val storageShimScript: String = """<script>
(function () {
  if (window.__dshStorageBridgeInstalled) return;
  var B = window.androidBridge;
  if (!B || typeof B.saveSlotConfig !== 'function' || typeof B.readSlotConfig !== 'function') return;
  var nativeLS = null;
  try { nativeLS = window.localStorage; } catch (e) {}
  var mem = Object.create(null);
  var CHUNK = 256 * 1024;
  function m(k) { return '__dshc__m:' + k; }
  function p(k, i) { return '__dshc__p:' + k + ':' + i; }
  function readRaw(k) {
    try { var v = B.readSlotConfig(k); return (v === null || v === undefined) ? null : v; } catch (e) { return null; }
  }
  function writeRaw(k, v) { try { return !!B.saveSlotConfig(k, String(v)); } catch (e) { return false; } }
  function removeRaw(k) { try { B.removeSlotConfig(k); } catch (e) {} }
  function getItem(k) {
    if (k === null || k === undefined) k = 'null';
    k = String(k);
    var meta = readRaw(m(k));
    if (meta) {
      try {
        var o = JSON.parse(meta);
        var out = '';
        for (var i = 0; i < o.count; i++) {
          var part = readRaw(p(k, i));
          if (part === null) return null;
          out += part;
        }
        mem[k] = out;
        return out;
      } catch (e) {}
    }
    if (Object.prototype.hasOwnProperty.call(mem, k)) return mem[k];
    var v = readRaw(k);
    if (v === null) {
      try { return nativeLS ? nativeLS.getItem(k) : null; } catch (e) { return null; }
    }
    mem[k] = v;
    return v;
  }
  function setItem(k, v) {
    if (k === null || k === undefined) k = 'null';
    k = String(k);
    v = String(v === null || v === undefined ? '' : v);
    removeItem(k);
    mem[k] = v;
    if (v.length > CHUNK) {
      var count = Math.ceil(v.length / CHUNK), ok = true;
      for (var i = 0; i < count; i++) {
        if (!writeRaw(p(k, i), v.substr(i * CHUNK, CHUNK))) { ok = false; break; }
      }
      if (ok) writeRaw(m(k), JSON.stringify({ count: count, len: v.length }));
    } else {
      writeRaw(k, v);
    }
    try { if (nativeLS) nativeLS.setItem(k, v); } catch (e) {}
  }
  function removeItem(k) {
    if (k === null || k === undefined) k = 'null';
    k = String(k);
    var meta = readRaw(m(k));
    if (meta) {
      try {
        var o = JSON.parse(meta);
        for (var i = 0; i < o.count; i++) removeRaw(p(k, i));
      } catch (e) {}
      removeRaw(m(k));
    }
    removeRaw(k);
    delete mem[k];
    try { if (nativeLS) nativeLS.removeItem(k); } catch (e) {}
  }
  function clear() {
    try { B.clearSlotConfigs(); } catch (e) {}
    mem = Object.create(null);
    try { if (nativeLS) nativeLS.clear(); } catch (e) {}
  }
  var proxy = {
    getItem: getItem,
    setItem: setItem,
    removeItem: removeItem,
    clear: clear,
    key: function (i) { var ks = Object.keys(mem); return (i >= 0 && i < ks.length) ? ks[i] : null; },
    get length() { return Object.keys(mem).length; }
  };
  try {
    Object.defineProperty(window, 'localStorage', {
      configurable: true, enumerable: true, writable: true, value: proxy
    });
    window.__dshStorageBridgeInstalled = true;
  } catch (e) {}
})();
</script>"""

  /**
   * 拉取主页 index HTML 并注入 localStorage 宿主化代理（见 storageShimScript），
   * 返回改写后的响应；任何失败返回 null（WebView 走默认加载，优雅降级）。
   * 运行在 shouldInterceptRequest 的后台线程，可做网络请求。
   */
  private fun injectIndexShim(url: Uri): android.webkit.WebResourceResponse? {
    // 引擎繁忙/无响应时快速回退：不再叠加第二个 GET 请求拖慢引擎，让 WebView 原生加载。
    if (EngineManager.webActive) {
      val busy = try { !EngineProbe.check(timeoutMs = 1500).optBoolean("running", false) } catch (_: Throwable) { true }
      if (busy) return null
    }
    var conn: java.net.HttpURLConnection? = null
    return try {
      conn = (java.net.URL(url.toString()).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 3000
        readTimeout = 5000
        setRequestProperty("Accept", "text/html, */*;q=0.8")
      }
      if (conn.responseCode != 200) return null
      val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      if (html.isEmpty() || html.length > 4_000_000) return null
      val injected = if (html.contains("</head>", ignoreCase = true))
        html.replaceFirst("</head>", storageShimScript + "</head>", ignoreCase = true)
      else storageShimScript + html
      android.webkit.WebResourceResponse(
        "text/html",
        "utf-8",
        java.io.ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
      )
    } catch (_: Throwable) {
      null
    } finally {
      conn?.disconnect()
    }
  }

  /** 首次引导页：全屏覆盖层，说明权限作用与注意事项，点「开始使用」进入安装/主页。 */
  private fun buildOnboardingView(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(resources.getColor(R.color.bg, null))
      setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    // 顶部居中：图标 + 应用名 + 副标题
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(0, dp(10), 0, dp(20))
    }
    header.addView(
      ImageView(this).apply {
        setImageResource(R.mipmap.ic_launcher)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
      },
    )
    header.addView(
      TextView(this).apply {
        text = "deepseek HARNESS"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        setPadding(0, dp(10), 0, 0)
      },
    )
    header.addView(
      TextView(this).apply {
        text = "本地 AI 终端壳层"
        textSize = 14f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
    )
    root.addView(header)

    // 可滚动内容（横竖屏自适应）
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(sectionLabel("权限说明"))
    content.addView(permissionGuideRow(
      R.drawable.ic_web, "网络",
      "用于在线更新与多镜像源加速下载。",
      "已授予（应用声明）", null, null,
    ))
    content.addView(permissionGuideRow(
      R.drawable.ic_info, "通知权限 (Android 13+)",
      "引擎事件 / 桥触发时显示系统通知。",
      if (Build.VERSION.SDK_INT < 33) "不适用" else if (guideNotifGranted()) "已授权" else "未授权",
      if (Build.VERSION.SDK_INT >= 33 && !guideNotifGranted()) "去授权" else null,
    ) { requestNotificationPermission() })
    content.addView(permissionGuideRow(
      R.drawable.ic_open, "所有文件访问 (Android 11+)",
      "外部工作区需要该权限，引擎（bash）才能读写你选择的文件夹。",
      if (Build.VERSION.SDK_INT < 30) "不适用" else if (guideFilesGranted()) "已授予" else "未授予",
      if (Build.VERSION.SDK_INT >= 30 && !guideFilesGranted()) "去授权" else null,
    ) { openAllFilesAccessSettings() })
    content.addView(
      sectionLabel("注意事项").apply { setPadding(0, dp(18), 0, dp(4)) },
    )
    content.addView(
      TextView(this).apply {
        text = "• 首次安装会自动解压运行时并在线更新，可能需要几分钟\n• 期间请保持网络畅通\n• 安装完成后自动进入主页"
        textSize = 13f
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextColor(resources.getColor(R.color.text, null))
      },
    )
    scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    // 底部主按钮「开始使用」
    root.addView(
      TextView(this).apply {
        text = "开始使用"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        background = resources.getDrawable(R.drawable.bg_button_accent, null)
        setTextColor(resources.getColor(R.color.surface, null))
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) }
        setOnClickListener {
          prefs.edit().putBoolean("guide_seen", true).apply()
          onboardingView.visibility = View.GONE
          guideActive = false
          startPostGuide()
        }
      },
    )
    return root
  }

  /** 引导页权限说明行：图标 + 标题/说明（weight=1）+ 右侧状态文字或「去授权」按钮。 */
  private fun permissionGuideRow(
    iconRes: Int, title: String, desc: String, status: String,
    actionLabel: String?, onClick: (() -> Unit)?,
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(12), dp(12), dp(12), dp(12))
    background = resources.getDrawable(R.drawable.bg_card, null)
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) }
    addView(
      ImageView(this@MainActivity).apply {
        setImageResource(iconRes)
        setTint(resources.getColor(R.color.accent, null))
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
      },
    )
    val col = LinearLayout(this@MainActivity).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(
      TextView(this@MainActivity).apply {
        text = title
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    col.addView(
      TextView(this@MainActivity).apply {
        text = desc
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    addView(col)
    if (actionLabel != null && onClick != null) {
      addView(flatTopButton(actionLabel) { onClick() })
    } else {
      addView(
        TextView(this@MainActivity).apply {
          text = status
          textSize = 11f
          setTextColor(resources.getColor(R.color.text_secondary, null))
        },
      )
    }
  }

  /** 引导页小节标题（与设置页同款视觉）。 */
  private fun sectionLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(resources.getColor(R.color.text, null))
    setPadding(0, 0, 0, dp(4))
  }

  private fun guideNotifGranted(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun guideFilesGranted(): Boolean =
    Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

  /** 请求通知权限（Android 13+）；低版本直接提示。 */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    else terminalScreen.terminal().appendLine("通知权限仅 Android 13+ 需要")
  }

  /** Web 覆盖层：顶栏（标题 + 刷新 + 外部浏览器 + 关闭）+ WebView。 */
  private fun buildWebOverlay(): FrameLayout {
    val overlay = FrameLayout(this).apply { visibility = View.GONE }
    val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val topBar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(resources.getColor(R.color.surface, null))
      setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    topBar.addView(
      TextView(this).apply {
        text = "dsh Web 界面"
        textSize = 14f
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }.also { webTitleText = it },
    )
    topBar.addView(
      flatTopButton("刷新") { webView.reload() },
    )
    topBar.addView(
      flatTopButton("浏览器") {
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EngineProbe.ENGINE_URL)))
        } catch (_: Throwable) {
        }
      },
    )
    topBar.addView(
      flatTopButton("关闭") {
        webView.stopLoading()
        webOverlay.animate().alpha(0f).setDuration(150).withEndAction {
          webOverlay.visibility = View.GONE
          webOverlay.alpha = 1f
          // 覆盖层隐藏：恢复看门狗自动重启能力。
          EngineManager.webActive = false
        }.start()
      },
    )
    container.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    webView = android.webkit.WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.setSupportZoom(true)
      settings.builtInZoomControls = true
      settings.displayZoomControls = false
      webViewClient = object : android.webkit.WebViewClient() {
        // 拦截主页 index HTML，注入 localStorage→宿主文件代理（见 injectIndexShim）。
        override fun shouldInterceptRequest(
          view: android.webkit.WebView,
          request: android.webkit.WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
          val url = request.url
          val isIndex = request.isForMainFrame &&
            (url.host == "127.0.0.1" || url.host == "localhost") &&
            (url.path.isNullOrEmpty() || url.path == "/" || url.path == "/index.html")
          return if (isIndex) injectIndexShim(url) else null
        }

        override fun onPageStarted(view: android.webkit.WebView, url: String?, favicon: android.graphics.Bitmap?) {
          super.onPageStarted(view, url, favicon)
          webTitleText.text = "加载中…"
        }

        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
          super.onPageFinished(view, url)
          webTitleText.text = "dsh Web 界面"
        }

        override fun onReceivedError(
          view: android.webkit.WebView,
          request: android.webkit.WebResourceRequest,
          error: android.webkit.WebResourceError,
        ) {
          super.onReceivedError(view, request, error)
          // -1 = ERR_ABORTED（用户取消/快速导航），忽略；仅主帧加载失败提示。
          if (request.isForMainFrame && error.errorCode != -1) {
            webTitleText.text = "加载失败，请确认引擎运行，点「刷新」重试"
          }
        }

        override fun onReceivedHttpError(
          view: android.webkit.WebView,
          request: android.webkit.WebResourceRequest,
          errorResponse: android.webkit.WebResourceResponse,
        ) {
          super.onReceivedHttpError(view, request, errorResponse)
          if (request.isForMainFrame) {
            webTitleText.text = "加载失败（HTTP " + errorResponse.statusCode + "），点「刷新」重试"
          }
        }
      }
      webChromeClient = android.webkit.WebChromeClient()
      addJavascriptInterface(
        AndroidBridge(
          context = this@MainActivity,
          onPickRequest = { callbackId ->
            runOnUiThread { pendingPickCallback = callbackId; directoryPicker.launch(null) }
          },
          onKeepScreen = { enable -> runOnUiThread { contentFrame.keepScreenOn = enable } },
          onNotify = { title, text -> showBridgeNotification(title, text) },
          onAllFilesAccessRequest = { runOnUiThread { openAllFilesAccessSettings() } },
          onDebugLogsRequest = { exportDebugLogs() },
          pickToken = pickToken,
        ),
        "androidBridge",
      )
    }
    container.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    overlay.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return overlay
  }

  private fun flatTopButton(label: String, onClick: () -> Unit): TextView =
    TextView(this).apply {
      text = label
      textSize = 12f
      setTextColor(resources.getColor(R.color.accent, null))
      setPadding(dp(8), dp(6), dp(8), dp(6))
      isClickable = true
      isFocusable = true
      setOnClickListener { onClick() }
    }

  /** 导航项（图标 + 文字竖排居中，选中高亮品牌蓝）；点击带按压缩放动画。
   *  BUGFIX v0.10.9：图标一律用代码 setTint 上色（去掉 XML android:tint，避免
   *  部分 OEM 设备上 theme-attr tint 导致矢量图标不显示）。 */
  private fun buildNavItem(label: String, iconRes: Int, tab: Tab): LinearLayout {
    val item = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = android.view.Gravity.CENTER
      setPadding(0, dp(8), 0, dp(8))
      isClickable = true
      isFocusable = true
      setOnClickListener {
        animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
          animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
        showTab(tab)
      }
    }
    val icon = ImageView(this).apply {
      setImageResource(iconRes)
      setTint(resources.getColor(R.color.text_tertiary, null))
      layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }
    val labelTv = TextView(this).apply {
      text = label
      textSize = 11f
      gravity = android.view.Gravity.CENTER
      setPadding(0, dp(3), 0, 0)
    }
    item.addView(icon)
    item.addView(labelTv)
    navTabs.getOrPut(tab) { mutableListOf() }.add(NavViews(item, icon, labelTv))
    return item
  }

  /** 兼容 minSdk 26：统一用 setImageTintList 给 ImageView 上色。 */
  private fun ImageView.setTint(color: Int) {
    imageTintList = ColorStateList.valueOf(color)
  }

  /** 底部导航条（Flat Minimalist）：图标 + 文字竖排居中，选中高亮品牌蓝。 */
  private fun buildBottomNavBar(): LinearLayout {
    val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    wrapper.addView(View(this).apply {
      setBackgroundColor(resources.getColor(R.color.border, null))
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    })
    val inner = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      setBackgroundColor(resources.getColor(R.color.surface, null))
      setPadding(0, dp(4), 0, dp(4))
    }
    fun addTab(label: String, iconRes: Int, tab: Tab) {
      inner.addView(buildNavItem(label, iconRes, tab), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    addTab("终端", R.drawable.ic_terminal, Tab.TERMINAL)
    addTab("插件", R.drawable.ic_plugin, Tab.PLUGINS)
    addTab("设置", R.drawable.ic_settings, Tab.SETTINGS)
    wrapper.addView(inner)
    return wrapper
  }

  /** 左侧导航栏（横屏/大屏）：竖排导航项，均分高度。
   *  BUGFIX v0.10.9：items 必须 MATCH_PARENT 宽度，否则 width=0 → 图标不可见。 */
  private fun buildLeftNavRail(): LinearLayout {
    val rail = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(resources.getColor(R.color.surface, null))
      setPadding(dp(4), dp(16), dp(4), dp(16))
    }
    // 分割线让导航项视觉上更清晰
    fun addTab(label: String, iconRes: Int, tab: Tab) {
      rail.addView(buildNavItem(label, iconRes, tab),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    addTab("终端", R.drawable.ic_terminal, Tab.TERMINAL)
    addTab("插件", R.drawable.ic_plugin, Tab.PLUGINS)
    addTab("设置", R.drawable.ic_settings, Tab.SETTINGS)
    return rail
  }

  /** 高亮当前 tab（图标 + 文字品牌蓝），其余灰。 */
  private fun refreshNavHighlight(active: Tab) {
    val accent = resources.getColor(R.color.accent, null)
    val inactive = resources.getColor(R.color.text_tertiary, null)
    for ((tab, views) in navTabs) {
      val selected = tab == active
      for (v in views) {
        v.icon.imageTintList = ColorStateList.valueOf(if (selected) accent else inactive)
        v.label.setTextColor(if (selected) accent else inactive)
      }
    }
  }

  /** Tab 切换：内容层三屏互斥显隐，目标屏淡入 + 上移过渡。 */
  private fun showTab(tab: Tab) {
    currentTab = tab
    refreshNavHighlight(tab)
    val screens = mapOf(
      Tab.TERMINAL to terminalScreen,
      Tab.PLUGINS to pluginsScreen,
      Tab.SETTINGS to settingsScreen,
    )
    for ((t, s) in screens) {
      if (t == tab) { if (s.visibility != View.VISIBLE) { s.visibility = View.VISIBLE; s.alpha = 0f; s.translationY = dp(14).toFloat(); s.animate().alpha(1f).translationY(0f).setDuration(220).start() } }
      else { s.animate().cancel(); s.visibility = View.GONE }
    }
    when (tab) {
      Tab.TERMINAL -> { if (autoStartEngineEnabled()) Thread { if (!EngineProbe.check().optBoolean("running", false)) runOnUiThread { startEngineFlow() } }.start() }
      Tab.PLUGINS -> pluginsScreen.refresh()
      Tab.SETTINGS -> settingsScreen.refresh()
    }
  }

  /** Start the foreground service (engine keep-alive + watchdog). */
  private fun startEngineService() {
    try {
      startForegroundService(Intent(this, EngineService::class.java))
    } catch (_: Exception) {
      // Foreground-service start limits: service will start on next launch.
    }
  }

  /** Best-effort Shizuku keep-alive boost; outcome logged only. */
  private fun applyShizukuKeepAlive() {
    try {
      Thread {
        android.util.Log.i("dsh-shizuku", ShizukuSupport.status(this))
      }.start()
    } catch (_: Throwable) {
    }
  }

  /** Open the system All Files Access screen for this app. */
  private fun openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < 30) return
    try {
      startActivity(
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
          .setData(Uri.parse("package:$packageName")),
      )
    } catch (_: Exception) {
      try {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      } catch (_: Exception) {
        // 无任何可用入口：静默忽略。
      }
    }
  }

  /** 设置页「启动时自动启动引擎」开关（默认开启）。 */
  private fun autoStartEngineEnabled(): Boolean =
    prefs.getBoolean("settings_auto_start_engine", true)

  /** 桥协议 showNotification：测试通知（API 33+ 未授权则静默跳过）。
   *  设置页「显示通知」关闭时不弹出。 */
  private fun showBridgeNotification(title: String, text: String) {
    if (!prefs.getBoolean("settings_show_notifications", true)) return
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    try {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
          android.app.NotificationChannel("bridge", "dsh 通知", android.app.NotificationManager.IMPORTANCE_LOW),
        )
      }
      val pending = android.app.PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
      )
      manager.notify(
        1001,
        androidx.core.app.NotificationCompat.Builder(this, "bridge")
          .setSmallIcon(android.R.drawable.stat_notify_chat)
          .setContentTitle(title)
          .setContentText(text)
          .setContentIntent(pending)
          .setAutoCancel(true)
          .build(),
      )
    } catch (_: Throwable) {
    }
  }

  /** 桥协议 downloadDebugLogs：打包 engine.log/bootstrap.log/env 到公共导出仓库 exports/。 */
  private fun exportDebugLogs() {
    Thread {
      try {
        val dir = File(engineManager.dshDataDir, "exports").apply { mkdirs() }
        val zip = File(dir, "dsh-debug-" + System.currentTimeMillis() + ".zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
          for ((f, name) in listOf(
            File(filesDir, "engine.log") to "engine.log",
            File(filesDir, "bootstrap.log") to "bootstrap.log",
          )) {
            if (!f.exists()) continue
            zos.putNextEntry(java.util.zip.ZipEntry(name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
          }
          zos.putNextEntry(java.util.zip.ZipEntry("env.txt"))
          zos.write(
            ("Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + "\n" +
              "ABI " + Build.SUPPORTED_ABIS.joinToString() + "\n").toByteArray(),
          )
          zos.closeEntry()
        }
        runOnUiThread { terminalScreen.terminal().appendLine("调试日志已导出: " + zip.absolutePath) }
      } catch (t: Throwable) {
        runOnUiThread { terminalScreen.terminal().appendLine("导出调试日志失败: " + (t.message ?: t.javaClass.simpleName)) }
      }
    }.start()
  }

  /** 清理应用缓存（cacheDir 内容 + WebView 缓存），结果写主页终端并刷新状态卡数值。 */
  private fun clearCache() {
    Thread {
      try {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runOnUiThread { webView.clearCache(true) }
        runOnUiThread {
          terminalScreen.terminal().appendLine("缓存已清理")
          terminalScreen.refresh()
        }
      } catch (t: Throwable) {
        runOnUiThread {
          terminalScreen.terminal().appendLine("清理缓存失败: " + (t.message ?: t.javaClass.simpleName))
        }
      }
    }.start()
  }

  /** 查看引擎日志：把 engine.log 末尾 40 行输出到主页终端。 */
  private fun viewEngineLog() {
    val tail = engineManager.engineLogTail(40)
    val term = terminalScreen.terminal()
    term.appendLine("----- engine.log 末尾 -----")
    if (tail.isBlank()) term.appendLine("（无 engine.log 输出）")
    else for (line in tail.lineSequence()) term.appendLine(line)
    term.appendLine("----- 日志结束 -----")
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

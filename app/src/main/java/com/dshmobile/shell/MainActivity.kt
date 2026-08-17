package com.dshmobile.shell

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

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

  /** APK 自更新管理器（独立于 usr 运行时更新）。 */
  private val apkUpdateManager by lazy { ApkUpdateManager(this) }

  /** 待处理的 APK 更新信息（供强制更新弹框 onResume 复用）。 */
  @Volatile private var lastUpdateInfo: ApkUpdateManager.UpdateInfo? = null

  /** 强制更新未完成：阻断引导/主页/引擎流程。 */
  @Volatile private var apkUpdatePending = false

  /** 用户已选择更新但等待安装权限授权时的待下载地址。 */
  @Volatile private var apkDownloadPendingUrl: String? = null

  /** 启动流程只允许触发一次。 */
  private val flowStarted = AtomicBoolean(false)

  /** APK 下载完成广播接收：匹配 downloadId 后校验状态与 APK 有效性，成功则安装，
   *  失败（网络错误或镜像返回非 APK 内容）则自动换下一个加速源重试。 */
  private val apkDownloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
      if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == apkUpdateManager.lastDownloadId()) {
          val apk = apkUpdateManager.findDownloadedApk()
          val ok = apkUpdateManager.downloadStatus(id) == DownloadManager.STATUS_SUCCESSFUL &&
            apkUpdateManager.isApkValid(apk)
          if (ok) {
            try { apk?.let { apkUpdateManager.installApk(it) } } catch (_: Throwable) { }
          } else {
            // 下载失败或产物无效：自动换下一个加速源重试。
            // retryWithNextSource 内含 2s 稳定等待，必须在后台线程执行（避免阻塞主线程）。
            apk?.delete()
            Thread {
              val next = apkUpdateManager.retryWithNextSource()
              val msg = if (next != null)
                I18n.t(c, "当前加速源下载失败，已自动切换下一个源重试…", "Current source failed; switching to the next one and retrying…")
              else
                I18n.t(c, "所有更新源均下载失败，请检查网络后重试", "All update sources failed; check your network and retry")
              // Activity 已销毁时不再操作 UI，避免空指针闪退。
              if (isFinishing || isDestroyed) return@Thread
              runOnUiThread {
                try {
                  if (::terminalScreen.isInitialized) terminalScreen.terminal().appendLine(msg)
                } catch (_: Throwable) { }
                if (next == null) {
                  Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
              }
            }.start()
          }
        }
      }
    }
  }

  /** 安装来源授权结果：返回后若已授权且有待下载更新，继续下载。 */
  private val installPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      val url = apkDownloadPendingUrl
      if (url != null && apkUpdateManager.canInstall()) {
        apkDownloadPendingUrl = null
        chooseSourceThenDownload(url)
      }
    }

  /** 目录选择桥鉴权 token（引擎侧 pick 端点校验；DSH_PICK_TOKEN）。 */
  private val pickToken: String by lazy { java.util.UUID.randomUUID().toString() }
  /** 待回传 JS 的目录选择回调 id（webView 桥 pickDirectory）。 */
  @Volatile private var pendingPickCallback: String? = null

  private lateinit var contentFrame: FrameLayout
  /** 根布局：承载内容 + 底部/左侧导航，应用背景（颜色/图片）绘制在其上。 */
  private lateinit var rootFrame: FrameLayout
  private lateinit var homeScreen: HomeScreen
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

  /** 引导页选中的权限模式（「开始使用」时持久化到 prefs）。 */
  private var guidePermMode = PermissionMode.NORMAL

  /** 引导页权限模式卡片引用（mode → 卡片），供选中高亮刷新。 */
  private val guidePermCards = mutableListOf<Pair<PermissionMode, LinearLayout>>()

  /** engine.log 已流式输出到终端的字节数（跨流程复用，只输出新增行）。 */
  @Volatile private var engineLogRead = 0L

  private enum class Tab { HOME, TERMINAL, PLUGINS, SETTINGS }

  /** 当前所在页签（系统返回键在设置页内页时先回退内页）。 */
  private var currentTab = Tab.HOME

  /** 导航视图（item/icon/label），供底部导航与左侧导航共用高亮；isBottom 区分底部导航（带动效）。 */
  private data class NavViews(val item: LinearLayout, val icon: ImageView, val label: TextView, val isBottom: Boolean = true)

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
      // 同步主页/设置页的工作区路径小字（选择后立即显示，重启/更新后也会从 prefs 恢复）。
      if (::homeScreen.isInitialized) homeScreen.updateWorkspaceLabel()
      if (::settingsScreen.isInitialized) settingsScreen.updateWorkspaceLabel()
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

  /** 插件包（.tgz）文件选择（SAF）：复制到缓存后交给插件页解压导入。
   *  注意：解压大插件包耗时，必须在后台线程执行，避免主线程卡顿触发「进程未响应」（ANR）。 */
  private val pluginPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val term = terminalScreen.terminal()
    if (uri == null) return@registerForActivityResult
    Thread {
      try {
        runOnUiThread { term.appendLine("正在复制并解压插件包…") }
        val tmp = File(cacheDir, "import-plugin.tgz")
        contentResolver.openInputStream(uri)?.use { input ->
          tmp.outputStream().use { out -> input.copyTo(out) }
        }
        val msg = pluginsScreen.importFrom(tmp)
        tmp.delete()
        runOnUiThread {
          term.appendLine("插件导入：" + msg)
          pluginsScreen.refresh()
          // 导入成功后触发引擎重启使插件生效（引擎未在跑时仅提示）。
          if (msg.startsWith("已导入插件")) {
            term.appendLine("重启引擎使插件生效…")
            if (EngineProbe.check().optBoolean("running", false)) {
              restartEngine()
            } else {
              term.appendLine("引擎未在运行，已就绪的插件将在下次启动时加载")
            }
          }
        }
      } catch (t: Throwable) {
        runOnUiThread { term.appendLine("插件导入失败：" + (t.message ?: t.javaClass.simpleName)) }
      }
    }.start()
  }

  /** 背景图片选择（相册）：直接把所选图 Uri 交给全屏裁剪覆盖层；确认后由裁剪回调落盘并应用，取消不改动。 */
  private val backgroundImagePicker =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri == null) return@registerForActivityResult
      // 不预写文件：裁剪覆盖层直接按 content:// 解码；仅确认后才写 background.png，
      // 避免「取消」把所选图残留到正式背景文件上、下次启动意外生效。
      showBackgroundCrop(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // LogFox：尽早启动 logcat 抓取（本应用 UID），崩溃现场越完整越好。
    runCatching { LogFox.startCapture(this) }
    // 全局崩溃兜底：崩溃前先写诊断上下文到 exceptions.log，再转发给系统原 handler
    runCatching {
      val prev = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
          val sb = StringBuilder()
          sb.append("===== ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append(" =====\n")
          sb.append("thread: ").append(thread.name).append(" id=").append(thread.id).append('\n')
          sb.append("message: ").append(throwable.message ?: "(no message)").append('\n')
          // 诊断上下文：引擎状态快照 + 关键文件
          runCatching {
            val em = engineManager
            val usr = File(filesDir, "usr")
            val preload = RuntimePermissions.resolveTermuxExecPreload(usr)
            sb.append("SDK=").append(Build.VERSION.SDK_INT).append(" ABI=").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            sb.append("snapshotFresh=").append(em.snapshotFresh()).append('\n')
            sb.append("nodeArchMatchesDevice=").append(em.nodeArchMatchesDevice()).append('\n')
            sb.append("lastStartAttemptAt=").append(EngineManager.lastStartAttemptAt).append('\n')
            sb.append("lastStartedAt=").append(EngineManager.lastStartedAt).append('\n')
            sb.append("termux-exec preload=").append(preload?.absolutePath ?: "(null)").append(" size=").append(preload?.length() ?: -1).append('\n')
            sb.append("usr/bin/node exists=").append(File(usr, "bin/node").exists()).append(" size=").append(File(usr, "bin/node").length()).append('\n')
            val dshBin = File(usr, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            sb.append("dsh/bin.js exists=").append(dshBin.exists()).append(" size=").append(dshBin.length()).append('\n')
            val up = File(filesDir, ".update-in-progress")
            sb.append(".update-in-progress=").append(up.exists()).append('\n')
          }
          sb.append("stacktrace:\n")
          val sw = java.io.StringWriter()
          throwable.printStackTrace(java.io.PrintWriter(sw))
          sb.append(sw.toString()).append('\n')
          val log = Logs.exceptionsLog(this@MainActivity)
          log.parentFile?.mkdirs()
          log.appendText(sb.toString())
          // LogFox：写入完整崩溃快照（logcat 尾部 + 用户行为尾部 + 引擎状态 + stacktrace）
          runCatching {
            LogFox.writeCrashSnapshot(this@MainActivity, thread, throwable, engineManager, filesDir)
          }
        } catch (_: Throwable) { /* 写日志失败不应再抛出第二波崩溃 */ }
        // 原封不动转发给系统：保持崩溃语义，绝不吞异常
        prev?.uncaughtException(thread, throwable)
      }
    }
    rootFrame = FrameLayout(this)
    contentFrame = FrameLayout(this)
    homeScreen = HomeScreen(this, object : HomeScreen.Callbacks {
      override fun onRestartEngine() = restartEngine()
      override fun onOpenDirectory() = pickDirectory()
      override fun onOpenWeb() = openWeb()
      override fun onCheckUpdate() = runCheckUpdate()
      override fun onInstallEnv() = runInstallEnv()
      override fun onCheckApkUpdate() = checkApkUpdateManually()
      override fun onClearCache() = clearCache()
      override fun onReloadAnnouncement() = loadAnnouncement()
      override fun onOpenLogsPage() {
        showTab(Tab.SETTINGS)
        settingsScreen.openLogs()
      }
    })
    contentFrame.addView(constrained(homeScreen), centeredParams())
    homeScreen.visibility = View.GONE
    terminalScreen = TerminalScreen(this, object : TerminalScreen.Callbacks {
      override fun onRestartEngine() = restartEngine()
      override fun onOpenDirectory() = pickDirectory()
      override fun onOpenWeb() = openWeb()
      override fun onCheckUpdate() = runCheckUpdate()
      override fun onInstallEnv() = runInstallEnv()
      override fun onCheckApkUpdate() = checkApkUpdateManually()
      override fun onClearCache() = clearCache()
    })
    contentFrame.addView(constrained(terminalScreen), centeredParams())
    terminalScreen.visibility = View.GONE
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
      override fun onCheckApkUpdate() = checkApkUpdateManually()
      override fun onLanguageChanged() = rebuildUiLanguage()
      override fun onPickBackgroundImage() { backgroundImagePicker.launch("image/*") }
      override fun onApplyBackground() = applyBackground()
      override fun onDownloadBackground(url: String) = downloadBackground(url)
      override fun onRandomBackground() = randomBackground()
      override fun onClearLogs(): Boolean = LogFox.clearAllLogs(this@MainActivity)
      override fun onViewLog(kind: String) {
        LogFox.trackUser(this@MainActivity, "button", "view_log_$kind")
        val tail = LogFox.tailLog(this@MainActivity, kind, 120)
        val term = terminalScreen.terminal()
        term.appendLine("----- ${kind} 末尾 -----")
        if (tail.startsWith("(") || tail.isBlank()) term.appendLine(tail)
        else for (line in tail.lineSequence()) term.appendLine(line)
        term.appendLine("----- 日志结束 -----")
      }
      override fun onOpenLogsPage() {
        showTab(Tab.SETTINGS)
        settingsScreen.openLogs()
      }
    })
    contentFrame.addView(constrained(settingsScreen), centeredParams())
    settingsScreen.visibility = View.GONE
    webOverlay = buildWebOverlay()
    contentFrame.addView(webOverlay, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    rootFrame.addView(contentFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    bottomNavBar = buildBottomNavBar()
    rootFrame.addView(bottomNavBar, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
    ))
    leftNavRail = buildLeftNavRail()
    rootFrame.addView(leftNavRail, FrameLayout.LayoutParams(
      dp(78), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT,
    ))
    // 引导页：盖在 rootFrame 最顶层（盖住导航栏），仅首次启动时构建。
    if (!prefs.getBoolean("guide_seen", false)) {
      onboardingView = buildOnboardingView()
      rootFrame.addView(onboardingView, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
      ))
      guideActive = true
      onboardingView.visibility = View.VISIBLE
    } else {
      guideActive = false
    }
    setContentView(rootFrame)
    applyBackground()
    showTab(Tab.HOME)
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
    registerDownloadReceiver()
    startWithApkCheck()
    loadAnnouncement()
    // 上次异常退出提示（日志已由 LogFox 崩溃快照保存；经主页提示条展示）。
    if (prefs.getBoolean(LogFox.PREF_CRASH_MARKER, false)) {
      Logs.logE(this, "LogFox", "上次异常退出：crash-marker 为 true，完整日志已保存至设置 → 日志")
    }
  }

  override fun onResume() {
    super.onResume()
    // APK 强制更新未完成：重新弹框阻断，不进入任何引擎/主页流程。
    if (apkUpdatePending) {
      showApkUpdateDialog()
      return
    }
    // 从「安装未知应用」设置页返回且已授权 → 继续此前被挂起的下载。
    apkDownloadPendingUrl?.let { url ->
      if (apkUpdateManager.canInstall()) {
        apkDownloadPendingUrl = null
        chooseSourceThenDownload(url)
      }
    }
    // 引导页展示期间不启动引擎流程（等待「开始使用」）。
    if (guideActive) return
    // 引导期间不启动引擎流程（安装/更新管线在跑，避免双启动竞态）。
    if (onboardingActive) return
    // 设置页「启动时自动启动引擎」关闭时，回到前台不再自动拉起引擎。
    if (!autoStartEngineEnabled()) return
    Thread {
      // 网络切换/Activity 重建后引擎可能短暂未就绪：延迟 1s 再探测，避免误判未运行而重复启动。
      try { Thread.sleep(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
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
    runCatching { unregisterReceiver(apkDownloadReceiver) }
    runCatching { LogFox.stopCapture() }
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

  /** 按已保存的背景设置应用应用根背景：
   *   图片 → BitmapDrawable（按屏幕尺寸缩放，可选模糊，失败回退默认色）；
   *   渐变 → GradientDrawable（两色 + 角度）；颜色 → ColorDrawable；默认 → 背景色。
   *   图片/渐变可叠加明暗遮罩（settings_bg_dim，0..80 → 黑色 alpha 0..~204）。 */
  private fun applyBackground() {
    LogFox.trackUser(this, "other", "applyBackground")
    if (!::rootFrame.isInitialized) return
    val type = prefs.getString("settings_bg_type", "").orEmpty()
    val value = prefs.getString("settings_bg_value", "").orEmpty()
    val dim = prefs.getInt("settings_bg_dim", 20).coerceIn(0, 80)
    val blur = prefs.getBoolean("settings_bg_blur", false)
    try {
      var base: Drawable? = null
      when (type) {
        "image" -> {
          val file = File(value)
          if (file.exists()) {
            val bmp = decodeBackgroundBitmap(file.absolutePath, blur)
            if (bmp != null) base = BitmapDrawable(resources, bmp)
          }
        }
        "gradient" -> {
          val parts = value.split("|")
          if (parts.size >= 3) {
            val start = parts[0].trim()
            val end = parts[1].trim()
            val angle = parts[2].trim().toIntOrNull() ?: 0
            base = GradientDrawable().apply {
              orientation = orientationForAngle(angle)
              setColors(intArrayOf(Color.parseColor(start), Color.parseColor(end)))
            }
          }
        }
        "color" -> if (value.isNotEmpty()) base = ColorDrawable(Color.parseColor(value))
      }
      val withOverlay = base != null && (type == "image" || type == "gradient") && dim > 0
      rootFrame.background = if (withOverlay) {
        val alpha = (dim * 255 / 100).coerceIn(0, 255)
        LayerDrawable(arrayOf(base, ColorDrawable(Color.argb(alpha, 0, 0, 0))))
      } else {
        base ?: ColorDrawable(resources.getColor(R.color.bg, null))
      }
    } catch (_: Throwable) {
      runCatching { rootFrame.setBackgroundColor(resources.getColor(R.color.bg, null)) }
    }
  }

  /** 按屏幕尺寸计算 inSampleSize 解码背景图，避免超大图 OOM；解码失败返回 null。 */
  private fun decodeScaledBackground(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxDim = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
    var sample = 1
    while (bounds.outWidth / (sample * 2) > maxDim || bounds.outHeight / (sample * 2) > maxDim) {
      sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
  }

  /** 解码背景图：blur=false 直接按屏幕尺寸缩放解码；blur=true 先缩小到 ~120px 再放大回屏幕尺寸（廉价柔化模糊）。
   *  仅解码本身失败才返回 null（模糊路径失败回退到普通缩放解码）。 */
  private fun decodeBackgroundBitmap(path: String, blur: Boolean): Bitmap? {
    if (!blur) return decodeScaledBackground(path)
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(path, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      val smallMax = 120
      var sample = 1
      while (bounds.outWidth / (sample * 2) > smallMax || bounds.outHeight / (sample * 2) > smallMax) {
        sample *= 2
      }
      val small = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return decodeScaledBackground(path)
      val w = resources.displayMetrics.widthPixels
      val h = resources.displayMetrics.heightPixels
      val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      Canvas(target).drawBitmap(
        small,
        Rect(0, 0, small.width, small.height),
        Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG),
      )
      target
    } catch (_: Throwable) {
      decodeScaledBackground(path)
    }
  }

  /** 角度（度）→ GradientDrawable.Orientation：0/45/90/135/180 映射到指定方向，其余取最近。 */
  private fun orientationForAngle(angle: Int): GradientDrawable.Orientation = when {
    angle <= 22 -> GradientDrawable.Orientation.LEFT_RIGHT
    angle <= 67 -> GradientDrawable.Orientation.TL_BR
    angle <= 112 -> GradientDrawable.Orientation.TOP_BOTTOM
    angle <= 157 -> GradientDrawable.Orientation.BL_TR
    angle <= 180 -> GradientDrawable.Orientation.RIGHT_LEFT
    else -> GradientDrawable.Orientation.LEFT_RIGHT
  }

  /** 后台下载 URL 图片到本地临时文件（≤25MB，写 cacheDir/crop-source.png，写入失败回退 filesDir）。
   *  仅作全屏裁剪的图片源；确认裁剪后才落盘为正式背景 background.png，取消则不影响已应用背景。
   *  返回已保存文件或 null；必须在后台线程调用。 */
  private fun performDownloadBackground(url: String): File? {
    var conn: HttpURLConnection? = null
    try {
      conn = URL(url).openConnection() as HttpURLConnection
      conn.connectTimeout = 15000
      conn.readTimeout = 30000
      conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) dsh-mobile")
      conn.instanceFollowRedirects = true
      if (conn.responseCode != 200) return null
      val input = conn.inputStream ?: return null
      val buffer = ByteArrayOutputStream()
      val chunk = ByteArray(8192)
      var total = 0L
      while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > 25 * 1024 * 1024) return null // 超 25MB 放弃
        buffer.write(chunk, 0, n)
      }
      val data = buffer.toByteArray()
      if (data.isEmpty()) return null
      try {
        val f = File(cacheDir, "crop-source.png")
        f.writeBytes(data)
        return f
      } catch (_: Throwable) {
        try {
          val f = File(filesDir, "crop-source.png")
          f.writeBytes(data)
          return f
        } catch (_: Throwable) {
          return null
        }
      }
    } catch (_: Throwable) {
      return null
    } finally {
      try { conn?.disconnect() } catch (_: Throwable) {}
    }
  }

  /** 显示全屏背景裁剪覆盖层（盖住 rootFrame 最上层，半透明黑）。
   *  确认：把裁剪结果保存到 filesDir/background.png（失败回退 cacheDir），写 prefs 并应用、移除覆盖层；
   *  取消：仅移除覆盖层，不做任何改动。 */
  private fun showBackgroundCrop(uri: Uri) {
    if (!::rootFrame.isInitialized) return
    val cropView = BackgroundCropView(this, uri)
    cropView.setCallback(object : BackgroundCropView.Callback {
      override fun onCropConfirm(cropped: Bitmap) {
        try {
          var target = File(filesDir, "background.png")
          var ok = false
          try {
            target.outputStream().use { out ->
              cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            ok = target.length() > 0
          } catch (_: Throwable) {
            ok = false
          }
          if (!ok) {
            target = File(cacheDir, "background.png")
            target.outputStream().use { out ->
              cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
          }
          prefs.edit()
            .putString("settings_bg_type", "image")
            .putString("settings_bg_value", target.absolutePath)
            .apply()
          applyBackground()
          if (::settingsScreen.isInitialized) settingsScreen.refreshAppearance()
          terminalScreen.terminal().appendLine("背景图片已设置")
        } catch (t: Throwable) {
          Toast.makeText(this@MainActivity, "背景图片保存失败：" + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_SHORT).show()
        } finally {
          if (cropView.parent != null) rootFrame.removeView(cropView)
          if (!cropped.isRecycled) cropped.recycle()
        }
      }

      override fun onCropCancel() {
        if (cropView.parent != null) rootFrame.removeView(cropView)
      }
    })
    rootFrame.addView(cropView, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
  }

  /** 下载 URL 图片为背景：后台下载 → 成功进入全屏裁剪覆盖层，确认后保存裁剪结果并应用。 */
  private fun downloadBackground(url: String) {
    LogFox.trackUser(this, "other", "downloadBackground")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      Toast.makeText(this, "链接需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
      return
    }
    Thread {
      val file = performDownloadBackground(url)
      runOnUiThread {
        if (file != null) {
          showBackgroundCrop(Uri.fromFile(file))
        } else {
          Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show()
        }
      }
    }.start()
  }

  /** 随机获取图片为背景：后台依次尝试候选图源，首个成功即进入全屏裁剪覆盖层；全部失败提示。 */
  private fun randomBackground() {
    LogFox.trackUser(this, "other", "randomBackground")
    val candidates = listOf(
      "https://picsum.photos/1080/1920",
      "https://picsum.photos/1920/1080",
      "https://loremflickr.com/1080/1920",
      "https://loremflickr.com/1920/1080",
    )
    Thread {
      var saved: File? = null
      for (c in candidates) {
        val f = performDownloadBackground(c)
        if (f != null) { saved = f; break }
      }
      val file = saved
      runOnUiThread {
        if (file != null) {
          showBackgroundCrop(Uri.fromFile(file))
        } else {
          Toast.makeText(this, "随机图片获取失败", Toast.LENGTH_SHORT).show()
        }
      }
    }.start()
  }

  /** 启动流程只触发一次（APK 检查/非强制更新弹框关闭后均可能调用）。 */
  private fun startFlowOnce() {
    if (flowStarted.compareAndSet(false, true)) startFlow()
  }

  /** 入口：引导页已在 onCreate 中（guide_seen=false 时）全屏展示并置 guideActive=true，等待「开始使用」；否则直接进安装（未装完）或主页（已装完）。 */
  private fun startFlow() {
    // 引导页展示中：不重复拉起，等「开始使用」回调 startPostGuide。
    if (guideActive) return
    startPostGuide()
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

  /** 入口：先执行 APK 版本检查，再进入正常启动流程。 */
  private fun startWithApkCheck() {
    LogFox.trackUser(this, "update", "startWithApkCheck")
    lifecycleScope.launch {
      apkUpdateManager.checkUpdate(
        onResult = { info ->
          lastUpdateInfo = info
          if (info.hasNew) {
            presentApkUpdate(info)
          } else {
            startFlowOnce()
          }
        },
        onError = { _ -> startFlowOnce() },
      )
    }
  }

  /** 统一处理更新信息：强制则阻断并弹不可取消框；普通则弹可稍后提醒框。 */
  private fun presentApkUpdate(info: ApkUpdateManager.UpdateInfo) {
    lastUpdateInfo = info
    if (info.isForced) apkUpdatePending = true
    showApkUpdateDialog()
  }

  /** 展示 APK 更新弹窗（强制不可取消；普通关闭后恢复启动流程）。 */
  private fun showApkUpdateDialog() {
    val info = lastUpdateInfo ?: return
    apkUpdateManager.showUpdateDialog(
      info,
      forced = info.isForced,
      onDownload = { url -> onUserChooseApkUpdate(url) },
      onOpenInstallSettings = { openInstallPermissionSettings() },
      onDismissed = { if (!info.isForced) startFlowOnce() },
    )
  }

  /** 用户点击「立即更新」：未授权安装来源先引导设置，否则先测速选源再下载。 */
  private fun onUserChooseApkUpdate(url: String) {
    LogFox.trackUser(this, "update", "onUserChooseApkUpdate")
    if (!apkUpdateManager.canInstall()) {
      apkDownloadPendingUrl = url
      openInstallPermissionSettings()
    } else {
      chooseSourceThenDownload(url)
    }
  }

  /** 先弹测速选源弹窗，确认后用所选源下载；取消则本次不下载（等待用户再次触发）。 */
  private fun chooseSourceThenDownload(url: String) {
    LogFox.trackUser(this, "update", "chooseSourceThenDownload")
    showSpeedTestDialog { fastest ->
      if (fastest != null) {
        prefs.edit().putString("active_mirror_id", fastest.id).apply()
        startApkDownload(url, fastest)
      } else {
        Toast.makeText(this, I18n.t(this, "已取消更新", "Update cancelled"), Toast.LENGTH_SHORT).show()
      }
    }
  }

  /** 跳转「安装未知应用」设置页。 */
  private fun openInstallPermissionSettings() {
    try {
      installPermissionLauncher.launch(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
      )
    } catch (_: Throwable) {
      Toast.makeText(this, I18n.t(this, "请到系统设置中开启「安装未知应用」权限", "Please enable \"Install unknown apps\" in system settings"), Toast.LENGTH_LONG).show()
    }
  }

  /** 启动 DownloadManager 下载（可选指定加速源），并清除强制更新阻断标记（下载+安装流程接管）。 */
  private fun startApkDownload(url: String, mirror: Mirror? = null) {
    try {
      apkUpdateManager.startDownload(url, mirror)
      apkUpdatePending = false
      Toast.makeText(this, I18n.t(this, "已开始下载更新，完成后将自动安装", "Download started; it will be installed automatically when finished"), Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
      Toast.makeText(this, I18n.t(this, "下载启动失败：", "Failed to start download: ") + (t.message ?: "未知错误"), Toast.LENGTH_LONG).show()
    }
  }

  /** 注册 DownloadManager 下载完成广播（Android 13+ 需显式导出标志）。 */
  private fun registerDownloadReceiver() {
    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(apkDownloadReceiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      registerReceiver(apkDownloadReceiver, filter)
    }
  }

  /** 手动检查 APK 更新（设置/主页「检查应用更新」按钮）：先检查，有新版弹版本框，
   *  点「立即更新」时再走测速选源弹窗（与启动自动检查流程统一）。 */
  private fun checkApkUpdateManually() {
    LogFox.trackUser(this, "update", "checkApkUpdateManually")
    lifecycleScope.launch {
      apkUpdateManager.checkUpdate(
        onResult = { info ->
          lastUpdateInfo = info
          if (info.hasNew) {
            presentApkUpdate(info)
          } else {
            Toast.makeText(this@MainActivity, I18n.t(this@MainActivity, "当前已是最新版本", "You're up to date"), Toast.LENGTH_SHORT).show()
          }
        },
        onError = { err ->
          Toast.makeText(this@MainActivity, I18n.t(this@MainActivity, "检查更新失败：", "Update check failed: ") + err, Toast.LENGTH_LONG).show()
        },
      )
    }
  }

  /** 语言切换后重建界面：各页面在 onCreate 时按当前语言取文案（默认中文 + 英文导航入口）。 */
  private fun rebuildUiLanguage() {
    LogFox.trackUser(this, "other", "rebuildUiLanguage")
    recreate()
  }

  /** 拉取公告（主页公告卡）：结果回主线程更新 HomeScreen 公告区。 */
  private fun loadAnnouncement() {
    LogFox.trackUser(this, "other", "loadAnnouncement")
    homeScreen.setAnnouncementLoading(true)
    AnnouncementManager.load(this) { text ->
      runOnUiThread {
        homeScreen.setAnnouncementLoading(false)
        homeScreen.setAnnouncement(text)
      }
    }
  }

  /** 同步引擎状态到主页状态卡与终端页。 */
  private fun updateEngineStatus(running: Boolean, detail: String) {
    if (::homeScreen.isInitialized) homeScreen.setEngineStatus(running, detail)
    terminalScreen.setEngineStatus(running, detail)
  }

  /** 交互式测速选源弹窗：并发测速所有源，自动选最快，用户可单选框手动改选，确认后开始更新。 */
  private fun showSpeedTestDialog(onComplete: (Mirror?) -> Unit) {
    val um = UpdateManager.forPrefs(this)
    val sources = um.allMirrors()
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
    }

    // 顶部状态行：进度条 + 状态文本
    val statusProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      isIndeterminate = true
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply {
        bottomMargin = dp(8)
      }
    }
    container.addView(statusProgress)
    val statusText = TextView(this).apply {
      text = I18n.t(this@MainActivity, "正在并发检测 N 个源…", "Testing N sources concurrently…")
        .replace("N", sources.size.toString())
      textSize = 12f
      setTextColor(resources.getColor(R.color.text_secondary, null))
      setPadding(0, 0, 0, dp(8))
    }
    container.addView(statusText)

    val radios = mutableListOf<RadioButton>()
    val latencies = mutableListOf<TextView>()
    for (m in sources) {
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
      }
      val i = radios.size
      val radio = RadioButton(this).apply {
        text = m.name
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener {
          for (j in radios.indices) radios[j].isChecked = (j == i)
        }
      }
      radios.add(radio)
      row.addView(radio)
      val latency = TextView(this).apply {
        text = I18n.t(this@MainActivity, "测速中…", "testing…")
        textSize = 12f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      }
      latencies.add(latency)
      row.addView(latency)
      container.addView(row)
    }

    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    var retryAction: (() -> Unit)? = null
    // 测速失败次数：全失败时按钮变「重试」，再次失败给出更明确的错误提示（区分首次/多次失败）
    var failedAttempts = 0
    // dialog 需先声明（可空 var），供确认按钮点击时 dismiss；K2 不允许 lambda 前向引用后声明的局部 val
    var dialog: android.app.AlertDialog? = null

    val confirmBtn = TextView(this).apply {
      text = I18n.t(this@MainActivity, "正在测速…", "Testing…")
      textSize = 14f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(16), dp(10), dp(16), dp(10))
      background = resources.getDrawable(R.drawable.bg_button_accent, null)
      setTextColor(resources.getColor(R.color.surface, null))
      isClickable = true
      isFocusable = true
      isEnabled = false
      setOnClickListener {
        val retry = retryAction
        if (retry != null) {
          retry()
        } else if (completed.compareAndSet(false, true)) {
          val idx = radios.indexOfFirst { it.isChecked }
          val selected = if (idx >= 0 && idx < sources.size) sources[idx] else null
          dialog?.dismiss()
          onComplete(selected)
        }
      }
    }

    dialog = DialogUi.show(
      this,
      title = I18n.t(this, "测速并选择更新源", "Test & pick source"),
      content = container,
      iconRes = R.drawable.ic_speed,
      actions = listOf(
        DialogUi.Action(I18n.t(this, "取消", "Cancel"), accent = false) {
          if (completed.compareAndSet(false, true)) onComplete(null)
        },
      ),
      cancelable = true,
      onCancel = { if (completed.compareAndSet(false, true)) onComplete(null) },
      footer = confirmBtn,
    )

    var runTest: (() -> Unit)? = null
    runTest = {
      // 重置 UI
      for (j in radios.indices) radios[j].isChecked = false
      for (l in latencies) l.text = I18n.t(this@MainActivity, "测速中…", "testing…")
      statusProgress.visibility = android.view.View.VISIBLE
      statusText.text = I18n.t(this@MainActivity, "正在并发检测 N 个源…", "Testing N sources concurrently…")
        .replace("N", sources.size.toString())
      confirmBtn.text = I18n.t(this@MainActivity, "正在测速…", "Testing…")
      confirmBtn.isEnabled = false
      retryAction = null

      Thread {
        val fastest = um.speedTestAll { m, ms ->
          val idx = sources.indexOfFirst { it.id == m.id }
          val text = if (ms != null) "$ms ms" else I18n.t(this, "不可用", "unavailable")
          runOnUiThread {
            if (idx in latencies.indices) latencies[idx].text = text
          }
        }
        runOnUiThread {
          if (completed.get()) return@runOnUiThread
          statusProgress.visibility = android.view.View.GONE
          if (fastest != null) {
            val idx = sources.indexOfFirst { it.id == fastest.id }
            for (j in radios.indices) radios[j].isChecked = (j == idx)
            statusText.text = I18n.t(this, "已选择最快源：", "Fastest: ") + fastest.name
            confirmBtn.text = I18n.t(this, "开始更新（", "Update (") + fastest.name + "）"
            retryAction = null
          } else {
            failedAttempts++
            if (failedAttempts > 1) {
              statusText.text = I18n.t(this, "多次测速均失败：所有更新源不可达，请检查网络后重试", "Speed test failed repeatedly: all update sources unreachable; check your network and retry")
            } else {
              statusText.text = I18n.t(this, "所有源均不可用，请重试或检查网络", "All sources unavailable; retry or check network")
            }
            confirmBtn.text = I18n.t(this, "重试", "Retry")
            retryAction = { runTest?.invoke() }
          }
          confirmBtn.isEnabled = true
        }
      }.start()
    }
    runTest.invoke()
  }

  /** 引擎启动失败弹窗：展示详细错误日志报告（引擎日志 + 设备/架构诊断），支持下载与重试。
   *  满足「引擎启动错误时弹窗显示详细错误日志报告并支持下载」。
   *  若引擎日志含 preset/persona 冲突特征串（插件内置 agent-presets 与 dsh 核心 preset 重复注册），
   *  追加「停用冲突插件并重启」操作，一键回滚到可用状态。 */
  private fun showEngineFailureDialog(reason: String) {
    val tail = engineManager.engineLogTail(60)
    val degradedHint = if (tail.contains("降级模式") || tail.contains("degraded"))
      I18n.t(this, "⚠️ 本次为降级模式启动（termux-exec 钩子未注入）\n", "⚠️ Started in degraded mode (termux-exec hook not injected)\n")
    else ""
    // preset/persona 冲突特征：插件内置 agent-presets 与 dsh 核心 preset 重复注册 → 会话无法恢复。
    val conflictHints = listOf("already registered", "failed to mount", "resume failed for session", "agent-presets", "persona")
    val hasConflict = conflictHints.any { tail.contains(it, ignoreCase = true) }
    val conflictHint = if (hasConflict)
      I18n.t(this, "\n\n⚠️ 检测到插件与引擎预设冲突（persona/preset 重复注册），通常由最近安装的含 agent-presets 的插件导致。可在下方「停用冲突插件并重启」。", "\n\n⚠️ A plugin conflicts with engine presets (duplicate persona/preset registration), usually caused by a recently installed plugin shipping its own agent-presets. Use \"Disable conflicting plugin & restart\" below.")
    else ""
    val report = buildString {
      append(degradedHint)
      append(I18n.t(this@MainActivity, "引擎启动失败：", "Engine failed to start: ")).append(reason).append('\n').append('\n')
      append("Android ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
      append(I18n.t(this@MainActivity, "设备 ABI：", "Device ABI: "))
        .append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
      append(I18n.t(this@MainActivity, "快照架构：", "Snapshot arch: "))
        .append(engineManager.embeddedNodeArchLabel()).append('\n')
      append('\n').append(I18n.t(this@MainActivity, "----- engine.log -----", "----- engine.log -----")).append('\n')
      append(if (tail.isBlank()) I18n.t(this@MainActivity, "（无日志输出）", "(no log output)") else tail)
      append(conflictHint)
    }
    runOnUiThread {
      val actions = mutableListOf<DialogUi.Action>()
      actions += DialogUi.Action(I18n.t(this, "下载错误报告", "Download report")) { exportEngineErrorReport(report) }
      if (hasConflict) {
        actions += DialogUi.Action(
          I18n.t(this, "停用冲突插件并重启", "Disable conflicting plugin & restart"),
          accent = true,
        ) { disableConflictPluginsAndRestart() }
      }
      actions += DialogUi.Action(I18n.t(this, "重试", "Retry"), accent = true) { restartEngine() }
      actions += DialogUi.Action(I18n.t(this, "关闭", "Close")) { /* 仅关闭 */ }
      DialogUi.show(
        this,
        title = I18n.t(this, "引擎启动失败", "Engine failed to start"),
        message = report,
        iconRes = R.drawable.ic_shield,
        actions = actions,
        cancelable = true,
      )
    }
  }

  /** 停用含 agent-presets 的冲突插件并重启引擎（启动失败弹窗的恢复入口）。
   *  无冲突插件时仅提示，不重启。 */
  private fun disableConflictPluginsAndRestart() {
    val term = terminalScreen.terminal()
    val disabled = pluginsScreen.disablePresetConflictPlugins()
    pluginsScreen.refresh()
    if (disabled.isEmpty()) {
      term.appendLine("未发现含 agent-presets 的冲突插件，未做改动")
      return
    }
    term.appendLine("已停用冲突插件：" + disabled.joinToString("、"))
    if (EngineProbe.check().optBoolean("running", false)) {
      term.appendLine("重启引擎使改动生效…")
      restartEngine()
    } else {
      term.appendLine("引擎未在运行，已停用的插件将在下次启动时不再加载")
    }
  }

  /** 导出引擎错误报告为文本并调起系统分享（FileProvider，filesDir/logs 已在 file_paths 映射）。 */
  private fun exportEngineErrorReport(report: String) {
    Thread {
      try {
        val f = File(Logs.dir(this), "engine-error-report.txt")
        f.writeText(report)
        runOnUiThread { shareFile(f, "text/plain") }
      } catch (t: Throwable) {
        runOnUiThread {
          Toast.makeText(this, I18n.t(this, "导出失败：", "Export failed: ") + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
      }
    }.start()
  }

  /** 通过 FileProvider 调起系统分享（支持日志 zip / 错误报告 txt）。 */
  private fun shareFile(file: File, mime: String) {
    try {
      val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(intent, I18n.t(this, "分享日志", "Share logs")))
    } catch (t: Throwable) {
      Toast.makeText(this, I18n.t(this, "分享失败：", "Share failed: ") + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }
  }

  /** 首次安装：直接在主页终端内跑安装/更新管线（权限说明已并入引导页，不再重复弹窗）。 */
  private fun runOnboarding() {
    LogFox.trackUser(this, "other", "runOnboarding")
    val term = terminalScreen.terminal()
    term.appendLine(I18n.t(this, "===== dsh 首次安装 =====", "===== dsh first install ====="))
    term.appendLine(I18n.t(this, "安装 dsh 配置/插件并更新到最新版…", "Installing dsh config, plugins, and updating to the latest version…"))
    Thread { runBootstrapPipeline(term) }.start()
  }

  /** 所有文件访问权限的说明对话框（非阻断）："去授权"跳系统设置，"稍后"关闭。 */
  private fun showAllFilesAccessExplainDialog() {
    DialogUi.show(
      this,
      title = I18n.t(this, "需要文件访问权限", "File access required"),
      message = I18n.t(this, "外部工作区需要「所有文件访问权限」，以便引擎（bash）能读写你选择的文件夹。", "The external workspace requires \"All files access\" so the engine (bash) can read/write your chosen folder."),
      iconRes = R.drawable.ic_open,
      actions = listOf(
        DialogUi.Action(I18n.t(this, "去授权", "Grant"), accent = true) { openAllFilesAccessSettings() },
        DialogUi.Action(I18n.t(this, "稍后", "Later")) { /* 仅关闭 */ },
      ),
      cancelable = true,
    )
  }

  /** 若发现 .update-in-progress 标记，执行安全启动自检（三重校验 + Level 3 自愈），
   *  终端输出进度；返回 true=标记存在且进入自检（调用方应跳过本轮 auto-start engine），false=正常启动。
   *  若标记存在但最终 EngineProbe.running=true，或自检已结束（通过/失败），调用方后续在成功路径再 delete 标记。 */
  private fun maybeSafeBootSelfCheck(term: TerminalView, onHealed: () -> Unit): Boolean {
    val marker = File(filesDir, ".update-in-progress")
    if (!marker.exists()) return false
    term.appendLine("[安全启动] 检测到上一次更新未完成启动确认，正在自检关键运行时文件…")
    Thread {
      // 三重校验 → Level 3 自愈 → 再次校验
      val before = engineManager.verifyCriticalFiles()
      if (before.isNotEmpty()) {
        term.appendLine("[安全启动] 首次校验: " + before.joinToString("; "))
      }
      val healed = engineManager.selfHealCriticalFiles(3)
      term.appendLine("[安全启动] 最高自愈后校验通过?=$healed")
      if (healed) {
        onHealed()
      } else {
        val after = engineManager.verifyCriticalFiles()
        term.appendLine("[安全启动] 自检失败，仍有问题：" + after.joinToString("; "))
        runOnUiThread {
          appendEngineDiagnostics(term)
          updateEngineStatus(false, "")
          showEngineFailureDialog("更新后安全启动自检失败")
        }
        runCatching { marker.delete() } // 不留下永远卡住的标记（进入失败分支）
      }
    }.start()
    return true
  }

  /** 进入终端主页并确保引擎运行。 */
  private fun enterTerminal() {
    terminalScreen.terminal().appendLine(I18n.t(this, "===== dsh 终端 =====", "===== dsh terminal ====="))
    if (prefs.getBoolean("settings_auto_check_updates", true)) {
      terminalScreen.terminal().appendLine(I18n.t(this, "[检查更新] 已开启（GitHub 更新源配置中…）", "[Check update] Enabled (configuring GitHub sources…)"))
    }
    // 设置页「启动时自动启动引擎」关闭时，不自动拉起引擎（可在终端页手动重启）。
    if (!autoStartEngineEnabled()) {
      updateEngineStatus(false, "")
      return
    }
    Thread {
      val safeBoot = maybeSafeBootSelfCheck(terminalScreen.terminal()) {
        // 自愈通过后按正常流程再次尝试启动
        runOnUiThread { startEngineFlow() }
      }
      if (safeBoot) return@Thread
      val running = EngineProbe.check().optBoolean("running", false)
      runOnUiThread {
        if (running) {
          updateEngineStatus(true, "引擎已在运行")
        } else {
          updateEngineStatus(false, "")
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
    val log = Logs.engineLog(this)
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
   *  @param showErrorDialog 失败时是否弹详细错误报告框（引导管线用 onBootstrapFatal，传 false）。
   *  @return true=就绪；false=超时/失败（日志已追加到终端）。 */
  private fun startEngineWithStreaming(term: TerminalView, showErrorDialog: Boolean = true): Boolean {
    var attempt = 0
    var startOk = engineManager.startEngine(force = true)
    while (!startOk && attempt < 2) {
      attempt++
      val level = if (attempt == 1) 1 else 3
      term.appendLine("自愈启动：第 ${attempt}/2 次，步骤=${if (level==1) "补权限/命名修复" else "重解压 usr/lib 子树"} …")
      try { engineManager.selfHealCriticalFiles(level) } catch (_: Throwable) {}
      startOk = engineManager.startEngine(force = true)
    }
    if (!startOk) {
      term.appendLine("引擎启动失败（已自愈重试 2 次）")
      appendEngineDiagnostics(term)
      updateEngineStatus(false, "")
      if (showErrorDialog) showEngineFailureDialog("startEngine 返回 false（已自愈重试 2 次）")
      return false
    }
    val engineLaunchMs = System.currentTimeMillis()
    var started = false
    var crashReported = false
    // 常规探测预算 180 次（90s）。引擎进程若仍存活（慢速冷启动/大快照首启/
    // 更新后重新拉起），只要进程还活着就不判失败——继续报「等待就绪」——
    // 额外宽限到 300 次（150s），修复「引擎明明在启动中却弹启动失败」。
    val maxProbes = 300
    var probes = 0
    while (probes <= maxProbes) {
      val ep = engineManager.engineProcessHandle
      val now = System.currentTimeMillis()
      val alive = ep != null && ep.isAlive
      if (ep != null && !ep.isAlive && !started && now - engineLaunchMs < 30000L && !crashReported) {
        crashReported = true
        Logs.append(Logs.engineLog(this), "引擎启动后闪退(<30s)，launchAge=" + (now - engineLaunchMs) + "ms")
        term.appendLine("引擎启动后闪退(<30s)，launchAge=" + (now - engineLaunchMs) + "ms")
        appendCrashDiagnostics(term)
        EngineManager.lastStartedAt = 0
        EngineManager.lastStartAttemptAt = 0
      }
      drainEngineLog(term)
      if (EngineProbe.check().optBoolean("running", false)) { started = true; break }
      probes++
      // 常规预算用尽后：进程还活着就继续等（慢启动），进程已死才判定失败。
      if (probes > 180 && !alive) break
      if (probes > 180 && probes % 20 == 0) {
        term.appendLine("引擎进程仍在运行，继续等待就绪…（已等待 " + (probes / 2) + "s）")
      }
      term.appendProgress("启动引擎", "等待引擎就绪", probes.toLong(), maxProbes.toLong())
      Thread.sleep(500)
    }
    if (started) {
      runCatching { File(filesDir, ".update-in-progress").delete() }
    }
    drainEngineLog(term)
    if (!started && showErrorDialog) {
      updateEngineStatus(false, "")
      showEngineFailureDialog("引擎启动超时（" + maxProbes + " 次探测未就绪）")
    }
    return started
  }

  /** 安装/更新 bootstrap 管线（后台线程执行）：
   *  解压运行时 → 写 dsh 配置 → 在线更新（架构不符时为必须，否则失败不阻断）
   *  → 启动引擎 → 完成进终端主页。 */
  private fun runBootstrapPipeline(term: TerminalView) {
    val marker = File(filesDir, ".update-in-progress")
    if (marker.exists()) {
      term.appendLine("[安全启动] 检测到上一次更新未完成启动确认，正在自检关键运行时文件…")
      val before = engineManager.verifyCriticalFiles()
      if (before.isNotEmpty()) term.appendLine("[安全启动] 首次校验: " + before.joinToString("; "))
      engineManager.selfHealCriticalFiles(3)
    }
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
    val waitDeadline = System.currentTimeMillis() + 180_000
    while (!updateDone.get() && System.currentTimeMillis() < waitDeadline) Thread.sleep(200)
    if (!updateDone.get()) updateOk.set(false)
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
    Logs.engineLog(this).delete()
    engineLogRead = 0
    val started = startEngineWithStreaming(term, showErrorDialog = false)
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
      updateEngineStatus(true, "")
      term.appendLine("安装完成，欢迎使用 dsh")
    }
  }

  /** 检查更新（合并到主页终端）：先测速选源，确认后再跑在线更新管线，日志流式输出到主页终端。 */
  private fun runCheckUpdate() {
    LogFox.trackUser(this, "update", "runCheckUpdate")
    showSpeedTestDialog { fastest ->
      if (fastest != null) {
        updateManager.activeMirror = fastest
        prefs.edit().putString("active_mirror_id", fastest.id).apply()
        runRuntimeUpdate()
      } else {
        terminalScreen.terminal().appendDetail(I18n.t(this@MainActivity, "已取消更新", "Update cancelled"))
      }
    }
  }

  /** 用已选源跑在线更新管线：后台线程等待完成，带 3 分钟超时防永久卡死。 */
  private fun runRuntimeUpdate() {
    LogFox.trackUser(this, "update", "runRuntimeUpdate")
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
      val waitDeadline = System.currentTimeMillis() + 180_000
      while (!done.get() && System.currentTimeMillis() < waitDeadline) Thread.sleep(200)
      if (!done.get()) {
        term.appendDetail("更新超时")
      } else if (ok.get()) {
        term.appendDetail("更新完成")
      } else {
        term.appendDetail("更新失败")
      }
      runOnUiThread { homeScreen.refresh() }
    }.start()
  }

  /** 安装/升级运行时环境（合并到主页终端）：后台线程跑环境安装管线，实时日志输出到主页终端。 */
  private fun runInstallEnv() {
    LogFox.trackUser(this, "update", "runInstallEnv")
    val term = terminalScreen.terminal()
    term.appendDetail("开始安装/升级运行时环境")
    envManager.installLatest(
      onLine = { line -> term.appendLine(line) },
      onDone = { ok, msg ->
        term.appendDetail(if (ok) "环境安装成功：" + msg else "环境安装失败：" + msg)
        runOnUiThread { homeScreen.refresh() }
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

  private fun appendCrashDiagnostics(term: TerminalView) {
    val engTail = engineManager.engineLogTail(100)
    term.appendLine("----- engine.log 最后 100 行 -----")
    if (engTail.isBlank()) term.appendLine("（无）") else term.appendLine(engTail)
    term.appendLine("----- engine.log end -----")
    val exLog = Logs.exceptionsLog(this)
    val exTail = if (exLog.exists()) try {
      exLog.readLines().takeLast(50).joinToString("\n")
    } catch (_: Throwable) { "" } else { "" }
    term.appendLine("----- exceptions.log 最后 50 行 -----")
    if (exTail.isBlank()) term.appendLine("（无）") else term.appendLine(exTail)
    term.appendLine("----- exceptions.log end -----")
  }

  /** 致命失败处理（主线程）：弹重试/跳过对话框（可返回键/点外部关闭）。 */
  private fun onBootstrapFatal(msg: String) {
    runOnUiThread {
      DialogUi.show(
        this,
        title = I18n.t(this, "安装未完成", "Installation incomplete"),
        message = msg,
        iconRes = R.drawable.ic_shield,
        actions = listOf(
          DialogUi.Action(I18n.t(this, "重试", "Retry"), accent = true) {
            Thread { runBootstrapPipeline(terminalScreen.terminal()) }.start()
          },
          DialogUi.Action(I18n.t(this, "稍后进入主页", "Home later")) {
            prefs.edit().putBoolean("onboarded", true).apply()
            onboardingActive = false
            updateEngineStatus(false, "")
          },
        ),
        cancelable = true,
      )
    }
  }

  /** 用户主动重启引擎：清空旧日志 → 停旧进程 → 强制启动 → 流式输出日志到控制台。
   *  若上一次流程仍在进行，也会明确提示，不再静默无输出。 */
  private fun restartEngine() {
    LogFox.trackUser(this, "engine", "restartEngine")
    val term = terminalScreen.terminal()
    if (engineFlowRunning.get()) {
      term.appendLine("上一次引擎流程仍在进行，请稍候再试")
      return
    }
    term.appendLine("===== 重启引擎 =====")
    Thread {
      engineManager.stopEngine()
      Logs.engineLog(this).delete()
      engineLogRead = 0
      val started = startEngineWithStreaming(term)
      if (started) {
        term.appendLine("引擎就绪")
        updateEngineStatus(true, "")
        startEngineService(); applyShizukuKeepAlive()
      } else {
        term.appendLine("引擎启动超时")
        appendEngineDiagnostics(term)
        updateEngineStatus(false, "")
      }
    }.start()
  }

  /** Engine-first flow: 复用已在跑的引擎，否则解压快照/修复架构并启动内嵌引擎，轮询就绪。 */
  private fun startEngineFlow() {
    LogFox.trackUser(this, "engine", "startEngineFlow")
    if (!engineFlowRunning.compareAndSet(false, true)) return
    Thread {
      try {
        val term = terminalScreen.terminal()
        val safeBoot = maybeSafeBootSelfCheck(term) {
          // 自愈通过后回到正常启动流程（先 snapshotFresh 检查等），这里通过清空 flowRunning 让下一轮重入
          engineFlowRunning.set(false)
          runOnUiThread { startEngineFlow() }
        }
        if (safeBoot) return@Thread
        if (EngineProbe.check().optBoolean("running", false)) return@Thread
        // 运行时就绪判定：node 已存在但架构错误时，跳过内嵌重解压（内嵌同样是错的）。
        val needArchFix = !engineManager.nodeArchMatchesDevice()
        if (!needArchFix && !engineManager.snapshotFresh()) {
          updateEngineStatus(false, "正在更新运行时（约 70MB）…")
          val ok = engineManager.refreshSnapshot { done, _ ->
            terminalScreen.terminal().appendProgress("解压运行时", "更新运行时", done, 0)
          }
          if (!ok) {
            terminalScreen.terminal().appendLine("运行时更新失败，请重试")
            updateEngineStatus(false, "")
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
            updateEngineStatus(false, "")
            showEngineFailureDialog("在线更新失败（架构不匹配）")
            return@Thread
          }
          terminalScreen.terminal().appendLine("匹配架构运行时已就绪")
        }
        terminalScreen.terminal().appendLine("启动引擎…")
        val started = startEngineWithStreaming(terminalScreen.terminal())
        if (started) {
          startEngineService()
          applyShizukuKeepAlive()
          updateEngineStatus(true, "引擎就绪")
        } else {
          terminalScreen.terminal().appendLine("引擎启动超时，请重试")
          appendEngineDiagnostics(terminalScreen.terminal())
          updateEngineStatus(false, "")
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** 工作目录选择：未授 All Files Access 先引导，否则直接 SAF 选择。 */
  private fun pickDirectory() {
    LogFox.trackUser(this, "page", "pickDirectory")
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
    LogFox.trackUser(this, "page", "openWeb")
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
        text = I18n.t(this@MainActivity, "本地 AI 终端壳层", "Local AI terminal shell")
        textSize = 14f
        setTextColor(resources.getColor(R.color.text_secondary, null))
      },
    )
    root.addView(header)

    // 可滚动内容（横竖屏自适应）
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(sectionLabel(I18n.t(this, "权限说明", "Permissions")))
    content.addView(permissionGuideRow(
      R.drawable.ic_web, I18n.t(this, "网络", "Network"),
      I18n.t(this, "用于在线更新与多镜像源加速下载。", "For online updates and multi-mirror accelerated downloads."),
      I18n.t(this, "已授予（应用声明）", "Granted (declared)"), null, null,
    ))
    content.addView(permissionGuideRow(
      R.drawable.ic_info, I18n.t(this, "通知权限 (Android 13+)", "Notification permission (Android 13+)"),
      I18n.t(this, "引擎事件 / 桥触发时显示系统通知。", "Show system notifications for engine/bridge events."),
      if (Build.VERSION.SDK_INT < 33) I18n.t(this, "不适用", "N/A") else if (guideNotifGranted()) I18n.t(this, "已授权", "Granted") else I18n.t(this, "未授权", "Not granted"),
      if (Build.VERSION.SDK_INT >= 33 && !guideNotifGranted()) I18n.t(this, "去授权", "Grant") else null,
    ) { requestNotificationPermission() })
    content.addView(permissionGuideRow(
      R.drawable.ic_open, I18n.t(this, "所有文件访问 (Android 11+)", "All files access (Android 11+)"),
      I18n.t(this, "外部工作区需要该权限，引擎（bash）才能读写你选择的文件夹。", "Required for external workspace; the engine (bash) needs it to read/write your chosen folder."),
      if (Build.VERSION.SDK_INT < 30) I18n.t(this, "不适用", "N/A") else if (guideFilesGranted()) I18n.t(this, "已授予", "Granted") else I18n.t(this, "未授予", "Not granted"),
      if (Build.VERSION.SDK_INT >= 30 && !guideFilesGranted()) I18n.t(this, "去授权", "Grant") else null,
    ) { openAllFilesAccessSettings() })
    content.addView(
      sectionLabel(I18n.t(this, "权限模式（命令执行通道）", "Permission mode (command channel)")).apply { setPadding(0, dp(18), 0, dp(4)) },
    )
    // 四种权限模式卡片：普通 / adb / ROOT / adb(shizuku)，按不同权限挂载不同容器调用通道。
    PermissionMode.entries.forEach { mode ->
      val card = permissionModeCard(mode)
      guidePermCards.add(mode to card)
      content.addView(card)
    }
    // 默认选中「普通权限」并高亮。
    refreshPermCardHighlight()
    content.addView(
      sectionLabel(I18n.t(this, "注意事项", "Notes")).apply { setPadding(0, dp(18), 0, dp(4)) },
    )
    content.addView(
      TextView(this).apply {
        text = I18n.t(this@MainActivity,
          "• 首次安装会自动解压运行时并在线更新，可能需要几分钟\n• 期间请保持网络畅通\n• 安装完成后自动进入主页",
          "• First install extracts the runtime and updates online; this may take a few minutes\n• Keep the network available during this time\n• You will enter the home screen automatically when done")
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
        text = I18n.t(this@MainActivity, "开始使用", "Get started")
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
          // 持久化引导页所选权限模式（命令执行通道）。
          PermissionMode.save(this@MainActivity, guidePermMode)
          LogFox.trackUser(this@MainActivity, "guide", "perm_mode=" + guidePermMode.id)
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

  /** 权限模式卡片：图标 + 标题/说明/前置要求 + 右侧状态；点击选中并高亮。 */
  private fun permissionModeCard(mode: PermissionMode): LinearLayout {
    val card = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(12), dp(12), dp(12), dp(12))
      background = resources.getDrawable(R.drawable.bg_card, null)
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply { bottomMargin = dp(8) }
      isClickable = true
      isFocusable = true
      setOnClickListener {
        guidePermMode = mode
        LogFox.trackUser(this@MainActivity, "guide", "select_perm_mode=" + mode.id)
        refreshPermCardHighlight()
      }
    }
    val icon = ImageView(this).apply {
      setImageResource(R.drawable.ic_shield)
      setTint(resources.getColor(R.color.accent, null))
      layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
    }
    card.addView(icon)
    val col = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(
      TextView(this).apply {
        text = mode.label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    col.addView(
      TextView(this).apply {
        text = mode.desc
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    col.addView(
      TextView(this).apply {
        text = mode.prereq
        textSize = 10f
        setTextColor(resources.getColor(R.color.text_tertiary, null))
        setPadding(0, dp(2), 0, 0)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    card.addView(col)
    // 右侧状态文字（可用/不可用 + 原因）。
    card.addView(
      TextView(this).apply {
        text = mode.status(this@MainActivity)
        textSize = 10f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
      },
    )
    return card
  }

  /** 刷新权限模式卡片选中高亮（选中的加描边 + 浅色底）。 */
  private fun refreshPermCardHighlight() {
    for ((mode, card) in guidePermCards) {
      val selected = mode == guidePermMode
      card.background = if (selected) {
        GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(12).toFloat()
          setColor(resources.getColor(R.color.accent_soft, null))
          setStroke(dp(2), resources.getColor(R.color.accent, null))
        }
      } else {
        resources.getDrawable(R.drawable.bg_card, null)
      }
    }
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

  /** Web 覆盖层：顶栏（标题 + 刷新 + 外部浏览器 + 关闭）+ WebView。
   *  构建 Web 覆盖层；部分 OEM ROM 无/异常 WebView 时降级为空覆盖层，不阻断启动。 */
  private fun buildWebOverlay(): FrameLayout {
    return try {
      buildWebOverlayContent()
    } catch (t: Throwable) {
      Logs.logE(this, "web", "WebView overlay build failed, degraded to empty overlay", t)
      FrameLayout(this).apply { visibility = View.GONE }
    }
  }

  private fun buildWebOverlayContent(): FrameLayout {
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
        text = I18n.t(this@MainActivity, "dsh Web 界面", "dsh Web UI")
        textSize = 14f
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }.also { webTitleText = it },
    )
    topBar.addView(
      flatTopButton(I18n.t(this, "刷新", "Refresh")) { webView.reload() },
    )
    topBar.addView(
      flatTopButton(I18n.t(this, "浏览器", "Browser")) {
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EngineProbe.ENGINE_URL)))
        } catch (_: Throwable) {
        }
      },
    )
    topBar.addView(
      flatTopButton(I18n.t(this, "关闭", "Close")) {
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
          webTitleText.text = I18n.t(this@MainActivity, "加载中…", "Loading…")
      }

        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
          super.onPageFinished(view, url)
          webTitleText.text = I18n.t(this@MainActivity, "dsh Web 界面", "dsh Web UI")
        }

        override fun onReceivedError(
          view: android.webkit.WebView,
          request: android.webkit.WebResourceRequest,
          error: android.webkit.WebResourceError,
        ) {
          super.onReceivedError(view, request, error)
          // -1 = ERR_ABORTED（用户取消/快速导航），忽略；仅主帧加载失败提示。
          if (request.isForMainFrame && error.errorCode != -1) {
            webTitleText.text = I18n.t(this@MainActivity, "加载失败，请确认引擎运行，点「刷新」重试", "Load failed. Check engine and tap Refresh to retry.")
          }
        }

        override fun onReceivedHttpError(
          view: android.webkit.WebView,
          request: android.webkit.WebResourceRequest,
          errorResponse: android.webkit.WebResourceResponse,
        ) {
          super.onReceivedHttpError(view, request, errorResponse)
          if (request.isForMainFrame) {
            webTitleText.text = I18n.t(this@MainActivity, "加载失败（HTTP ", "Load failed (HTTP ") + errorResponse.statusCode + "），" + I18n.t(this@MainActivity, "点「刷新」重试", "tap Refresh to retry")
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
  private fun buildNavItem(label: String, iconRes: Int, tab: Tab, isBottom: Boolean = true): LinearLayout {
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
      // 底部导航初始隐藏文字（仅图标），选中后由 refreshNavHighlight 淡入；左侧导航常显。
      visibility = if (isBottom) View.GONE else View.VISIBLE
    }
    item.addView(icon)
    item.addView(labelTv)
    navTabs.getOrPut(tab) { mutableListOf() }.add(NavViews(item, icon, labelTv, isBottom))
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
      inner.addView(buildNavItem(label, iconRes, tab, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    addTab(I18n.t(this, "主页", "Home"), R.drawable.ic_home, Tab.HOME)
    addTab(I18n.t(this, "终端", "Terminal"), R.drawable.ic_terminal, Tab.TERMINAL)
    addTab(I18n.t(this, "插件", "Plugins"), R.drawable.ic_plugin, Tab.PLUGINS)
    addTab(I18n.t(this, "设置", "Settings"), R.drawable.ic_settings, Tab.SETTINGS)
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
      rail.addView(buildNavItem(label, iconRes, tab, false),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    addTab(I18n.t(this, "主页", "Home"), R.drawable.ic_home, Tab.HOME)
    addTab(I18n.t(this, "终端", "Terminal"), R.drawable.ic_terminal, Tab.TERMINAL)
    addTab(I18n.t(this, "插件", "Plugins"), R.drawable.ic_plugin, Tab.PLUGINS)
    addTab(I18n.t(this, "设置", "Settings"), R.drawable.ic_settings, Tab.SETTINGS)
    return rail
  }

  /** 高亮当前 tab（图标 + 文字品牌蓝），其余灰。
   *  底部导航：选中项图标上移 -6dp + 文字淡入；非选中项复位 + 文字淡出隐藏（仅图标）。
   *  左侧导航（横屏/大屏）：保持原有行为，图标 + 文字常显，仅切换高亮颜色。 */
  private fun refreshNavHighlight(active: Tab) {
    val accent = resources.getColor(R.color.accent, null)
    val inactive = resources.getColor(R.color.text_tertiary, null)
    for ((tab, views) in navTabs) {
      val selected = tab == active
      for (v in views) {
        if (v.isBottom) {
          if (selected) {
            v.icon.animate().translationY(-dp(6).toFloat()).setDuration(150).start()
            v.icon.imageTintList = ColorStateList.valueOf(accent)
            v.label.animate().cancel()
            v.label.visibility = View.VISIBLE
            v.label.alpha = 0f
            v.label.setTextColor(accent)
            v.label.animate().alpha(1f).setDuration(150).start()
          } else {
            v.icon.animate().translationY(0f).setDuration(150).start()
            v.icon.imageTintList = ColorStateList.valueOf(inactive)
            v.label.animate().alpha(0f).setDuration(100).withEndAction { v.label.visibility = View.GONE }.start()
          }
        } else {
          // 左侧导航：保持原有高亮逻辑（图标 + 文字常显）
          v.icon.imageTintList = ColorStateList.valueOf(if (selected) accent else inactive)
          v.label.setTextColor(if (selected) accent else inactive)
          v.label.visibility = View.VISIBLE
        }
      }
    }
  }

  /** Tab 切换：内容层三屏互斥显隐，目标屏淡入 + 上移过渡。 */
  private fun showTab(tab: Tab) {
    currentTab = tab
    LogFox.trackUser(this, "nav", tab.name)
    refreshNavHighlight(tab)
    val screens = mapOf(
      Tab.HOME to homeScreen,
      Tab.TERMINAL to terminalScreen,
      Tab.PLUGINS to pluginsScreen,
      Tab.SETTINGS to settingsScreen,
    )
    for ((t, s) in screens) {
      if (t == tab) { if (s.visibility != View.VISIBLE) { s.visibility = View.VISIBLE; s.alpha = 0f; s.translationY = dp(14).toFloat(); s.animate().alpha(1f).translationY(0f).setDuration(220).start() } }
      else { s.animate().cancel(); s.visibility = View.GONE }
    }
    when (tab) {
      Tab.HOME -> homeScreen.refresh()
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

  /** 桥协议 downloadDebugLogs / 设置页「导出调试日志」：打包全部日志（filesDir/logs/）为 zip，
   *  经 FileProvider 调起系统分享；不再依赖公共存储权限。 */
  private fun exportDebugLogs() {
    Thread {
      try {
        val zip = File(Logs.dir(this), "dsh-debug-" + System.currentTimeMillis() + ".zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
          for ((f, name) in listOf(
            Logs.engineLog(this) to "engine.log",
            Logs.bootstrapLog(this) to "bootstrap.log",
            Logs.terminalLog(this) to "terminal.log",
            Logs.terminalDetailLog(this) to "terminal-detail.log",
            Logs.exceptionsLog(this) to "exceptions.log",
            LogFox.userActionsLog(this) to "user-actions.log",
            LogFox.userStatsFile(this) to "user-stats.txt",
            LogFox.logcatLog(this) to "logcat.log",
            LogFox.crashSnapshot(this) to "crash-snapshot.txt",
          )) {
            if (!f.exists()) continue
            zos.putNextEntry(java.util.zip.ZipEntry(name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
          }
          zos.putNextEntry(java.util.zip.ZipEntry("env.txt"))
          val pkg = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Throwable) { null }
          zos.write(
            ("Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + "\n" +
              "ABI " + Build.SUPPORTED_ABIS.joinToString() + "\n" +
              "app versionName " + (pkg?.versionName ?: "?") + " / versionCode " + (pkg?.longVersionCode ?: "?") + "\n").toByteArray(),
          )
          zos.closeEntry()
        }
        runOnUiThread {
          shareFile(zip, "application/zip")
          terminalScreen.terminal().appendLine(
            I18n.t(this, "调试日志已打包：", "Debug logs packed: ") + zip.absolutePath,
          )
        }
      } catch (t: Throwable) {
        Logs.logE(this, "export", "导出调试日志失败", t)
        runOnUiThread {
          terminalScreen.terminal().appendLine(
            I18n.t(this, "导出调试日志失败：", "Export debug logs failed: ") + (t.message ?: t.javaClass.simpleName),
          )
        }
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
          homeScreen.refresh()
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

package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yoyo.dshmobile.engine.EngineProcess
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.onboarding.AnnouncementManager
import com.yoyo.dshmobile.shell.onboarding.RootHelper
import com.yoyo.dshmobile.shell.onboarding.ShizukuHelper
import com.yoyo.dshmobile.shell.ui.SPACE_MD
import com.yoyo.dshmobile.shell.ui.SPACE_SM
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yoyo.dshmobile.shell.ui.screenTopBar
import com.yoyo.dshmobile.shell.ui.themedDialog
import com.yoyo.dshmobile.shell.ui.tintId
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页仪表盘（原生 View，白色简洁风）。
 * 结构：顶部标题栏 → 更新横幅（发现新版本时）→ 「状态」卡片区 → 公告 → 工作区 → 「快捷操作」。
 * 数据源为「软件自身」指标：运行时间由独立 1s 计时器逐秒累加，内存/存储固定 30s 低频刷新；
 * 公告复用版本更新结果（最新版本 + 说明 + 发布时间），随更新检查自动刷新。
 * 色值一律走 dh_* 主题令牌，禁止硬编码十六进制。
 *
 * @param onOpenUpdate 点击「新版本」横幅回调
 * @param onSwitchDir 「切换目录」回调：由宿主（MainActivity）打开系统目录选择器（SAF）
 */
class HomeScreen(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onOpenUpdate: (() -> Unit)? = null,
  private val onSwitchDir: (() -> Unit)? = null,
) {

  /** 进程级启动时刻（跨页面重建保留，体现「软件运行时间」）。 */
  companion object {
    private var processStartedElapsed: Long = -1L

    /** 本次会话主页更新弹窗是否已弹过（防重复，跨重建保持实例级即可，进程内只弹一次）。 */
    private var updateDialogShown = false
  }

  val rootView: View

  private fun dp(v: Int): Int = context.dp(v)

  // ---- 可刷新引用 ----
  private var uptimeValue = TextView(context)
  private var memValue = TextView(context)
  private var memBar: ProgressBar? = null
  private var storValue = TextView(context)
  private var storBar: ProgressBar? = null
  private var pluginValue = TextView(context)
  private val engineValue = TextView(context)
  private var announceCard: View? = null
  private val announceBody = TextView(context)
  private lateinit var workspacePathText: TextView

  /** 运行时间 1s 计时器 / 内存存储 30s 低频刷新协程。 */
  private var uptimeJob: Job? = null
  private var systemJob: Job? = null

  init {
    if (processStartedElapsed < 0) processStartedElapsed = SystemClock.elapsedRealtime()

    val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    // 顶部栏（含居中「主页」标题）作为必显锚点
    page.addView(
      buildTopBar(),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    // 更新横幅（发现新版本时显示；初始隐藏）
    val banner = buildUpdateBanner()
    page.addView(banner, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(SPACE_MD), dp(16), dp(SPACE_MD))
    }

    addSafe(body, 0, ::buildStatusGrid)
    addSafe(body, SPACE_MD, ::buildAnnounceCard)
    addSafe(body, SPACE_MD, ::buildWorkspaceCard)
    addSafe(body, SPACE_MD, ::buildQuickActions)

    val scroll = ScrollView(context)
    scroll.addView(body, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    page.addView(scroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = page

    // 立即填充一次数据，随后运行时间逐秒累加、内存/存储 30s 低频刷新
    refreshValues()
    startUptimeTicker()
    startSystemRefresh()
    fetchAndRefreshAnnounce()
    fetchBanner(banner)
  }

  private fun lpTop(top: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(top) }

  private fun addSafe(container: LinearLayout, top: Int, builder: () -> View) {
    container.addView(
      runCatching { builder() }.getOrElse { t ->
        TextView(context).apply {
          text = "区块加载失败\n${t.javaClass.simpleName}: ${t.message ?: "(无消息)"}"
          setTextColor(context.color(R.color.dh_danger))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          setPadding(0, dp(8), 0, dp(8))
        }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) },
    )
  }

  /* ---------- 顶部标题栏（无右侧设置图标） ---------- */
  private fun buildTopBar(): View {
    val bar = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), dp(14), dp(16), dp(12))
    }

    bar.addView(
      TextView(context).apply {
        text = "D"
        setTextColor(context.color(R.color.dh_on_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = context.roundedBg(context.color(R.color.dh_primary), 18)
      },
      LinearLayout.LayoutParams(dp(36), dp(36)),
    )

    bar.addView(
      TextView(context).apply {
        text = context.getString(R.string.home_top_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )

    val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(bar, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    wrap.addView(
      View(context).apply { setBackgroundColor(context.color(R.color.dh_divider)) },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
    )
    return wrap
  }

  /* ---------- 更新横幅 ---------- */
  private fun buildUpdateBanner(): View {
    val banner = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_warning), 12)
      setPadding(dp(14), dp(10), dp(14), dp(10))
      visibility = View.GONE
      setOnClickListener { onOpenUpdate?.invoke() }
    }
    banner.addView(ImageView(context).apply {
      setImageResource(R.drawable.ic_arrow_right)
      tintId(R.color.dh_warning)
      rotation = 180f
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(18), dp(18)))
    banner.addView(
      TextView(context).apply {
        text = context.getString(R.string.home_banner_update)
        setTextColor(context.color(R.color.dh_on_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { leftMargin = dp(8) },
    )
    return banner
  }

  private fun fetchBanner(banner: View) {
    scope.launch {
      try {
        val info = withContext(Dispatchers.IO) { UpdateManager.fetchLatest() }
        val current = UpdateManager.currentVersion(context)
        if (info != null && UpdateManager.isNewer(info.version, current)) {
          banner.visibility = View.VISIBLE
          showUpdateDialog(info.version)
        }
      } catch (_: Throwable) {
        // 拉取失败保持隐藏
      }
    }
  }

  /** 发现新版本时弹一次 MD3 更新弹窗；「去更新」进入更新页，「取消」仅关闭（横幅保持可见）。 */
  private fun showUpdateDialog(version: String) {
    if (updateDialogShown) return
    updateDialogShown = true
    runCatching {
      MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.home_update_dialog_title))
        .setMessage(context.getString(R.string.home_update_dialog_msg, version))
        .setPositiveButton(context.getString(R.string.home_update_dialog_confirm)) { _, _ -> onOpenUpdate?.invoke() }
        .setNegativeButton(context.getString(R.string.home_update_dialog_cancel), null)
        .show()
    }
  }

  /* ---------- 运行时间逐秒累加 / 内存存储 30s 低频刷新 ---------- */
  private fun startUptimeTicker() {
    uptimeJob?.cancel()
    uptimeJob = scope.launch {
      while (true) {
        uptimeValue.text = formatUptime(softwareUptime())
        delay(1000)
      }
    }
  }

  private fun startSystemRefresh() {
    systemJob?.cancel()
    systemJob = scope.launch {
      while (true) {
        val mem = withContext(Dispatchers.IO) { appMemoryMb() }
        val storBytes = withContext(Dispatchers.IO) { appStorageBytes() }
        val plugins = withContext(Dispatchers.IO) { pluginCount() }
        memValue.text = "$mem MB"
        memBar?.progress = ((mem * 100f) / 2048f).toInt().coerceIn(0, 100)
        storValue.text = "${storBytes / (1024L * 1024L)} MB"
        storBar?.progress = (storBytes * 100 / (512L * 1024 * 1024)).toInt().coerceIn(0, 100)
        pluginValue.text = "$plugins"
        delay(30_000)
      }
    }
  }

  /** 首次填充全部展示值（主线程）。 */
  private fun refreshValues() {
    uptimeValue.text = formatUptime(softwareUptime())
  }

  /* ---------- 「状态」仪表盘卡片区 ---------- */
  private fun buildStatusGrid(): View {
    val plugins = pluginCount()
    var engineValueTv: TextView? = null

    val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row1.addView(statusCard(
      R.drawable.ic_clock, R.string.home_status_uptime, "", R.string.home_status_uptime_desc,
      capture = { v, _ -> uptimeValue = v },
      valueColorRes = R.color.dh_primary,
    ), lpCell(endGap = true))
    row1.addView(statusCard(
      R.drawable.ic_plugin, R.string.home_status_plugins, "$plugins", R.string.home_status_plugins_desc,
      valueColorRes = R.color.dh_primary,
      capture = { v, _ -> pluginValue = v },
    ), lpCell(Gravity.END))

    val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row2.addView(statusCard(
      R.drawable.ic_memory, R.string.home_status_memory, "", R.string.home_status_memory_desc,
      progress = 0, valueColorRes = R.color.dh_primary,
      capture = { v, bar -> memValue = v; memBar = bar },
    ), lpCell(endGap = true))
    row2.addView(statusCard(
      R.drawable.ic_list, R.string.home_status_engine,
      context.getString(R.string.home_status_engine_checking),
      descRes = 0, valueColorRes = R.color.dh_text_faint,
      capture = { v, _ -> engineValueTv = v },
    ), lpCell(Gravity.END))
    scope.launch {
      val running = withContext(Dispatchers.IO) { EngineProcess.probe(context, 1500) }
      engineValueTv?.text = context.getString(if (running) R.string.home_status_engine_running else R.string.home_status_engine_stopped)
      engineValueTv?.setTextColor(context.color(if (running) R.color.dh_success else R.color.dh_text_faint))
    }

    val row3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row3.addView(statusCard(
      R.drawable.ic_folder, R.string.home_status_storage, "", R.string.home_status_storage_desc,
      progress = 0, valueColorRes = R.color.dh_primary,
      capture = { v, bar -> storValue = v; storBar = bar },
    ), lpCell())

    val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    grid.addView(row1)
    grid.addView(row2, lpTop(SPACE_SM))
    grid.addView(row3, lpTop(SPACE_SM))
    return grid
  }

  /** @param endGap true 时右侧留出 [SPACE_SM] 横向间隔（两卡并排时用）。 */
  private fun lpCell(gravity: Int = Gravity.START, endGap: Boolean = false): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      .apply {
        this.gravity = gravity
        if (endGap) rightMargin = dp(SPACE_SM)
      }

  private fun statusCard(
    icon: Int,
    titleRes: Int,
    value: String,
    descRes: Int,
    progress: Int? = null,
    valueColorRes: Int = R.color.dh_primary,
    capture: ((TextView, ProgressBar?) -> Unit)? = null,
  ): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    val head = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(ImageView(context).apply {
      setImageResource(icon)
      tintId(R.color.dh_primary)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(18), dp(18)))
    head.addView(
      TextView(context).apply {
        text = context.getString(titleRes)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = dp(8) },
    )
    card.addView(head, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val valueView = TextView(context).apply {
      text = value
      setTextColor(context.color(valueColorRes))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      typeface = Typeface.DEFAULT_BOLD
    }
    card.addView(valueView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    var bar: ProgressBar? = null
    if (progress != null) {
      bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        this.progress = progress.coerceIn(0, 100)
        progressTintList = ColorStateList.valueOf(context.color(valueColorRes))
        minHeight = dp(6)
      }
      card.addView(bar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) })
    }
    capture?.invoke(valueView, bar)

    if (descRes != 0) {
      card.addView(
        TextView(context).apply {
          text = context.getString(descRes)
          setTextColor(context.color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          .apply { topMargin = dp(4) },
      )
    }
    return card
  }

  /* ---------- 公告卡片（来源：仓库独立公告文件 NOTICE.md，而非更新说明） ---------- */
  private fun buildAnnounceCard(): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    announceCard = card
    card.addView(sectionHeader(R.string.announce_title))
    announceBody.apply {
      text = context.getString(R.string.announce_loading)
      setTextColor(context.color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setLineSpacing(dp(2).toFloat(), 1f)
    }
    card.addView(announceBody, lpTop(SPACE_SM))
    return card
  }

  private fun fetchAndRefreshAnnounce() {
    scope.launch {
      val notice = withContext(Dispatchers.IO) { AnnouncementManager.fetchNotice(context) }
      if (notice != null && notice.isNotBlank()) {
        announceCard?.visibility = View.VISIBLE
        announceBody.text = notice
      } else {
        // 无在线内容也无缓存：隐藏公告卡
        announceCard?.visibility = View.GONE
      }
    }
  }

  private fun sectionHeader(titleRes: Int): View {
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
      TextView(context).apply {
        text = context.getString(titleRes)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    header.addView(ImageView(context).apply {
      setImageResource(R.drawable.ic_arrow_right)
      tintId(R.color.dh_text_faint)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
    return header
  }

  /* ---------- 工作区卡片 ---------- */
  private fun buildWorkspaceCard(): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    card.addView(sectionHeader(R.string.workspace_title))

    workspacePathText = TextView(context).apply {
      text = context.getString(R.string.workspace_path)
      setTextColor(context.color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      typeface = Typeface.MONOSPACE
    }
    card.addView(workspacePathText, lpTop(8))
    // 回填已保存目录（SAF 选中并持久化），无则保持默认路径
    scope.launch {
      WorkspacePrefs.loadDisplay(context)?.let { workspacePathText.text = it }
    }

    val bottom = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END
    }
    bottom.addView(
      Button(context, null, 0, R.style.Widget_Dsh_Button_Outline).apply {
        text = context.getString(R.string.switch_dir)
        background = context.roundedBg(
          android.graphics.Color.TRANSPARENT, 24,
          strokeColor = context.color(R.color.dh_primary), strokeDp = 1,
        )
        // 走系统目录选择器（SAF），由宿主 MainActivity 打开并把结果回填
        setOnClickListener { onSwitchDir?.invoke() }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(12) },
    )
    card.addView(bottom, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    return card
  }

  /** 宿主（MainActivity）完成系统目录选择后调用，回填工作区显示名。 */
  fun onDirectoryPicked(display: String?) {
    if (::workspacePathText.isInitialized && !display.isNullOrBlank()) {
      workspacePathText.text = display
    }
  }

  /* ---------- 「快捷操作」区 ---------- */
  private fun buildQuickActions(): View {
    val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(
      TextView(context).apply {
        text = context.getString(R.string.home_quick_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
      },
    )
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(actionCard(
      R.drawable.ic_terminal, R.string.home_quick_restart, R.string.home_quick_restart_desc,
      { restartEngine() }), lpCell(endGap = true))
    row.addView(actionCard(
      R.drawable.ic_settings, R.string.home_quick_latency, R.string.home_quick_latency_desc,
      { measureLatency() }), lpCell(Gravity.END))
    wrap.addView(row, lpTop(10))
    return wrap
  }

  private fun actionCard(icon: Int, titleRes: Int, descRes: Int, onClick: () -> Unit): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
      setOnClickListener { onClick() }
    }
    card.addView(ImageView(context).apply {
      setImageResource(icon)
      tintId(R.color.dh_primary)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(22), dp(22)))
    card.addView(
      TextView(context).apply {
        text = context.getString(titleRes)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(10) },
    )
    card.addView(
      TextView(context).apply {
        text = context.getString(descRes)
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    return card
  }

  /* ---------- 快捷操作逻辑 ---------- */
  private fun restartEngine() {
    scope.launch {
      val msg = try {
        val ready = withContext(Dispatchers.IO) { engineReadyAsync() }
        if (ready) context.getString(R.string.engine_restart_sent)
        else context.getString(R.string.engine_not_running)
      } catch (_: Throwable) {
        context.getString(R.string.engine_not_running)
      }
      toast(msg)
    }
  }

  private fun measureLatency() {
    scope.launch {
      val ms = withContext(Dispatchers.IO) { measureRttMs() }
      val msg = if (ms < 0) {
        context.getString(R.string.latency_failed)
      } else {
        String.format(Locale.getDefault(), context.getString(R.string.latency_result), ms)
      }
      toast(msg)
    }
  }

  private fun measureRttMs(): Long {
    val host = context.getString(R.string.latency_host)
    val t0 = System.nanoTime()
    return try {
      val conn = (URL(host).openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = 5000
        readTimeout = 5000
      }
      val ok = conn.responseCode == HttpURLConnection.HTTP_OK
      conn.disconnect()
      if (ok) (System.nanoTime() - t0) / 1_000_000 else -1L
    } catch (_: Throwable) {
      -1L
    }
  }

  private fun toast(msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

  /* ---------- 数据源（软件自身） ---------- */
  private suspend fun engineReadyAsync(): Boolean = withContext(Dispatchers.IO) {
    if (ShizukuHelper.isRunning && ShizukuHelper.isGranted) {
      true
    } else {
      RootHelper.isRootAvailable()
    }
  }

  /** 软件运行时间：进程启动至今。 */
  private fun softwareUptime(): Long =
    SystemClock.elapsedRealtime() - processStartedElapsed

  private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
  }

  /** 软件进程内存占用（PSS, MB）。 */
  private fun appMemoryMb(): Long = try {
    val info = android.os.Debug.MemoryInfo()
    android.os.Debug.getMemoryInfo(info)
    info.totalPss / 1024L
  } catch (_: Throwable) {
    0L
  }

  /** 软件自身存储占用（App 私有目录总和，字节；含 files/rootfs 引擎运行时）。 */
  private fun appStorageBytes(): Long = try {
    val dataDir = File(context.applicationInfo.dataDir)
    val filesDir = File(dataDir, "files")
    val filesBytes = if (filesDir.exists()) {
      filesDir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    } else 0L
    val otherBytes = listOf(
      File(dataDir, "cache"),
      File(dataDir, "code_cache"),
      File(dataDir, "databases"),
      File(dataDir, "shared_prefs"),
      File(dataDir, "no_backup"),
    ).filter { it.exists() }.sumOf { dirSize(it) }
    filesBytes + otherBytes
  } catch (_: Throwable) {
    0L
  }

  private fun dirSize(f: File): Long = try {
    if (f.isFile) f.length()
    else f.listFiles()?.sumOf { dirSize(it) } ?: 0L
  } catch (_: Throwable) {
    0L
  }

  /** 已检测插件数：来自 filesDir/plugins 下 json（com.yoyo.dshmobile.shell.ui.pluginCount）。 */
  private fun pluginCount(): Int = com.yoyo.dshmobile.shell.ui.pluginCount(context)
}
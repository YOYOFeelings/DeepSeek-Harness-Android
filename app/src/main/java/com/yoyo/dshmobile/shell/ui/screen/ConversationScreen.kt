package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.yoyo.dshmobile.engine.EngineMirrors
import com.yoyo.dshmobile.engine.EngineProcess
import com.yoyo.dshmobile.engine.EngineRootfs
import com.yoyo.dshmobile.engine.EngineService
import com.yoyo.dshmobile.engine.Manifest
import com.yoyo.dshmobile.engine.Mirror
import com.yoyo.dshmobile.engine.RuntimeUpdater
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.engine.SessionActivity
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.ui.LoadingButton
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import com.yoyo.dshmobile.shell.ui.themedDialog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「会话」导航页：展示引擎状态 + 启动/停止/打开会话/检查更新。
 * 颜色/圆角/尺寸一律走 dh_ 主题令牌与 dp / roundedBg 工具；未就绪时按钮置灰。
 */
class ConversationScreen(
  private val context: Context,
  private val scope: CoroutineScope,
) {

  private val dp: (Int) -> Int = { context.dp(it) }

  val rootView: View

  private val statusText = TextView(context)
  private val detailText = TextView(context)

  private val startButton = actionButton(context.getString(R.string.engine_start), primary = true)
  private val stopButton = actionButton(context.getString(R.string.engine_stop), primary = false)
  private val openButton = actionButton(context.getString(R.string.engine_open_session), primary = false)
  private val updateButton = LoadingButton(context)

  // 下载进度 + 引擎日志（骨架阶段用于“看清楚引擎在干嘛/失败原因”）
  private val progressRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val progressBar = ProgressBar(context).apply {
    max = 100
    progress = 0
  }
  private val progressLabel = TextView(context)
  private val logBody = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(context.color(R.color.dh_text_secondary))
    typeface = android.graphics.Typeface.MONOSPACE
    setLineSpacing(0f, 1.15f)
  }
  private var pollToken = 0

  private var downloaded: Manifest? = null

  /** 镜像更新源持久化 key（仅存「上次选中哪个镜像」，镜像表本身内置不可增删）。 */
  private val enginePrefs = context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.nav_conversation)),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val scroll = ScrollView(context).apply {
      isFillViewport = true
      setPadding(dp(16), dp(16), dp(16), dp(16))
      setBackgroundColor(context.color(R.color.dh_background))
    }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    // 引擎状态卡片
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(
        context.color(R.color.dh_surface), 16,
        strokeColor = context.color(R.color.dh_divider), strokeDp = 1,
      )
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    card.addView(
      TextView(context).apply {
        text = context.getString(R.string.conversation_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    statusText.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTextColor(context.color(R.color.dh_text_secondary))
    }
    detailText.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      setTextColor(context.color(R.color.dh_text_faint))
    }
    card.addView(statusText, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(10) })
    card.addView(detailText, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(4) })

    content.addView(card, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // 第 1 排：启动 / 停止
    val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row1.addView(startButton, lpBtnCell(endGap = true))
    row1.addView(stopButton, lpBtnCell())
    content.addView(row1, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
      .apply { topMargin = dp(12) })

    // 第 2 排：打开会话 / 检查并更新引擎
    updateButton.setLabel(context.getString(R.string.engine_check_update))
    updateButton.setOnClick { doUpdate() }
    val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    row2.addView(openButton, lpBtnCell(endGap = true))
    row2.addView(updateButton, lpBtnCell())
    content.addView(row2, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
      .apply { topMargin = dp(12) })

    // 下载进度（横向进度条 + 字节文案），默认隐藏
    progressLabel.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setTextColor(context.color(R.color.dh_text_secondary))
    }
    progressRow.addView(progressBar, LinearLayout.LayoutParams(0, dp(6), 1f))
    progressRow.addView(progressLabel, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { leftMargin = dp(12); gravity = Gravity.CENTER_VERTICAL })
    progressRow.visibility = View.GONE
    content.addView(progressRow, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(8) })

    // 引擎日志区（滚动只读，轮询刷新 app-events.log 尾部，含下载进度/失败原因/运行输出）
    val logScroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = true
      isFillViewport = false
      setPadding(dp(8), dp(8), dp(8), dp(8))
      setBackgroundColor(context.color(R.color.dh_surface))
      addView(logBody, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    content.addView(logScroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
      .apply { topMargin = dp(12) })

    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    rootView = root

    startButton.setOnClickListener { doStart() }
    stopButton.setOnClickListener { doStop() }
    openButton.setOnClickListener { doOpen() }

    refreshStatus()
    startLogPolling()
  }

  private fun doStart() {
    val dialog = showStartDialogSimple()
    scope.launch {
      val extracted = withContext(Dispatchers.IO) { EngineRootfs.isExtracted(context) }
      if (dialog.isShowing) dialog.dismiss()
      if (!extracted) {
        showUpdateRequiredDialog()
        return@launch
      }
      val seq = EngineService.lastStartSeq
      EngineService.start(context)
      val deadline = System.currentTimeMillis() + 20_000
      while (System.currentTimeMillis() < deadline) {
        delay(700)
        if (EngineService.lastStartSeq > seq && EngineService.lastStartFailed) {
          val reason = EngineService.lastStartError
            ?: context.getString(R.string.engine_start_failed_unknown)
          if (dialog.isShowing) dialog.dismiss()
          if (!showEnvMissingDialogIfNeeded()) showStartErrorDialog(reason)
          refreshStatus()
          return@launch
        }
        val running = withContext(Dispatchers.IO) { EngineProcess.probe(context, 1500) }
        if (running) {
          if (dialog.isShowing) dialog.dismiss()
          refreshStatus()
          return@launch
        }
      }
      // 20s 超时仍未真正启动
      if (dialog.isShowing) dialog.dismiss()
      showStartErrorDialog(context.getString(R.string.engine_start_dialog_stuck))
      refreshStatus()
    }
  }

  /** 简单的启动中弹窗：居中转圈 + 状态文本；不设按钮、不可取消。 */
  private fun showStartDialogSimple(): AlertDialog {
    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
    }
    body.addView(
      ProgressBar(context).apply { isIndeterminate = true },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    body.addView(
      TextView(context).apply {
        text = context.getString(R.string.engine_start_dialog_starting)
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(10) },
    )
    return context.themedDialog(
      title = context.getString(R.string.engine_start_dialog_title),
      contentView = body,
      negativeText = "",
      positiveText = "",
    ).apply {
      setCancelable(false)
      show()
    }
  }

  /** 引擎文件未就绪：提醒用户先去更新。 */
  private fun showUpdateRequiredDialog() {
    context.themedDialog(
      title = context.getString(R.string.engine_no_rootfs_update_required),
      negativeText = context.getString(R.string.engine_start_dialog_close),
      positiveText = context.getString(R.string.engine_start_dialog_go_update),
      onPositive = { doUpdate() },
    ).show()
  }

  /** 启动失败弹窗：错误信息 + 最近 30 行日志；「重试」再次启动。 */
  private fun showStartErrorDialog(errorMsg: String) {
    val logView = TextView(context).apply {
      text = Logs.tail(Logs.appEventsLog(context), 30)
        .ifBlank { context.getString(R.string.engine_logs_empty) }
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = android.graphics.Typeface.MONOSPACE
      setLineSpacing(0f, 1.15f)
    }
    val logScroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = true
      isFillViewport = false
      setPadding(dp(8), dp(8), dp(8), dp(8))
      setBackgroundColor(context.color(R.color.dh_surface))
      addView(logView, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    body.addView(
      TextView(context).apply {
        text = errorMsg.ifBlank { context.getString(R.string.engine_start_failed_unknown) }
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    body.addView(logScroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(8) })

    context.themedDialog(
      title = context.getString(R.string.engine_start_failed_title),
      contentView = body,
      negativeText = context.getString(R.string.engine_start_dialog_close),
      positiveText = context.getString(R.string.engine_retry),
      onPositive = { doStart() },
    ).show()
  }

  /** 引擎环境/工具缺失提示：启动失败时校验关键文件（node/bin.js/termux-exec preload），
   *  缺失则 Toast + MD3 弹窗列缺失项，并提供「去更新引擎」走统一更新入口补齐。返回是否已弹窗。 */
  private fun showEnvMissingDialogIfNeeded(): Boolean {
    val usrDir = File(EngineRootfs.rootfsDir(context), "usr")
    val issues = EngineProcess.verifyCriticalFiles(usrDir)
    if (issues.isEmpty()) return false
    Toast.makeText(context, context.getString(R.string.env_missing_title), Toast.LENGTH_SHORT).show()
    context.themedDialog(
      title = context.getString(R.string.env_missing_title),
      message = context.getString(R.string.env_missing_inner) + "\n" + issues.joinToString("\n"),
      negativeText = context.getString(R.string.engine_start_dialog_close),
      positiveText = context.getString(R.string.env_go_update),
      onPositive = { doUpdate() },
    ).show()
    return true
  }

  private fun doStop() {
    EngineService.stop(context)
    Logs.logEvent(context, "Engine", "service-stop-requested")
    refreshStatus()
  }

  private fun doOpen() {
    if (!EngineRootfs.isExtracted(context)) {
      statusText.text = context.getString(R.string.engine_no_rootfs)
      return
    }
    context.startActivity(Intent(context, SessionActivity::class.java))
  }

  private fun doUpdate() {
    // 已存在待装包或已装 rootfs 时，先弹覆盖确认，避免用户误以为可增量升级
    val pending = File(context.cacheDir, "rootfs-new.tar.xz").exists()
    val installed = EngineRootfs.isExtracted(context)
    if (pending || installed) {
      context.themedDialog(
        title = context.getString(R.string.engine_update_overwrite_title),
        message = context.getString(R.string.engine_update_overwrite_msg),
        negativeText = context.getString(R.string.engine_progress_cancel), // 取消
        positiveText = context.getString(R.string.dh_ok), // 确定
        onPositive = { proceedUpdate() },
      ).show()
    } else {
      proceedUpdate()
    }
  }

  private fun proceedUpdate() {
    updateButton.setLoading(true)
    showMirrorPicker { mirror ->
      // 记忆上次选择
      enginePrefs.edit().putString("engine_mirror_id", mirror.id).apply()
      runUpdate(mirror)
    }
  }

  /** MD3 镜像选择弹窗：并发测延迟、逐行刷新，点某行立即用它更新（不强制等全部测完）。 */
  private fun showMirrorPicker(onChosen: (Mirror) -> Unit) {
    val mirrors = EngineMirrors.all()
    val lastId = enginePrefs.getString("engine_mirror_id", null)
    // 上次选中的置顶
    val ordered = mirrors.sortedByDescending { it.id == lastId }

    val rows = HashMap<String, TextView>()
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = false
      addView(list, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val picker = context.themedDialog(
      title = context.getString(R.string.engine_mirror_dialog_title),
      contentView = scroll,
      negativeText = "",
      positiveText = context.getString(R.string.engine_mirror_close),
    )
    picker.show()

    for (m in ordered) {
      val latTv = TextView(context).apply {
        text = context.getString(R.string.engine_mirror_testing)
        setTextColor(context.color(R.color.dh_text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      }
      rows[m.id] = latTv
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.roundedBg(
          context.color(R.color.dh_surface), 12,
          strokeColor = context.color(R.color.dh_divider), strokeDp = 1,
        )
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener {
          if (picker.isShowing) picker.dismiss()
          onChosen(m)
        }
      }
      val nameTv = TextView(context).apply {
        text = m.name + if (m.id == lastId) "  " + context.getString(R.string.engine_mirror_last) else ""
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setSingleLine(true)
      }
      row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      row.addView(latTv, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = dp(8) })
      list.addView(row, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6) })
    }

    // 并发测速；每源完成即刷新对应行（用户无需等全部完成）
    for (m in mirrors) {
      val latTv = rows[m.id] ?: continue
      scope.launch {
        val ms = EngineMirrors.speedTest(RuntimeUpdater.DEFAULT_MANIFEST_URL, m, 4000)
        postUi {
          latTv.text = if (ms != null) "${ms} ms"
            else context.getString(R.string.engine_mirror_fail)
        }
      }
    }
    updateButton.setLoading(false)
  }

  /** 使用选定镜像执行完整更新：MD3 醒目进度弹窗覆盖 下载/校验/解压/切换/重启。 */
  private fun runUpdate(mirror: Mirror) {
    scope.launch {
      val manifest = withContext(Dispatchers.IO) { RuntimeUpdater.checkForUpdate(context, mirror) }
      if (manifest == null) {
        updateButton.setLoading(false)
        statusText.text = context.getString(R.string.engine_update_fail)
        Logs.logEvent(context, "Engine", "update-check-null")
        refreshLogs()
        return@launch
      }
      val current = EngineRootfs.engineVersion(context)
      // manifest.version 恒为空串（真实源无 JSON version），此时一律放行下载；
      // 仅当双方版本都非空且 current >= manifest.version 才判「已是最新」。
      if (manifest.version.isNotEmpty() && current.isNotEmpty() && current >= manifest.version) {
        updateButton.setLoading(false)
        statusText.text = context.getString(R.string.engine_update_none)
        return@launch
      }

      val ui = showUpdateDialog()
      ui.dialog.setCancelable(false)
      ui.phase.text = context.getString(R.string.engine_update_phase_download)
      ui.bar.isIndeterminate = true

      val file = withContext(Dispatchers.IO) {
        RuntimeUpdater.download(context, manifest, { done, total ->
          postUi {
            ui.bar.isIndeterminate = false
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            ui.bar.progress = pct.coerceIn(0, 100)
            ui.pct.text = context.getString(
              R.string.engine_progress_share, formatBytes(done), formatBytes(total), pct)
          }
        }, mirror)
      }

      if (file != null) {
        withContext(Dispatchers.IO) {
          RuntimeUpdater.apply(context, manifest) { phase, pct ->
            postUi {
              ui.phase.text = phase
              if (pct != null) {
                ui.bar.isIndeterminate = false
                ui.bar.progress = pct.coerceIn(0, 100)
                ui.pct.text = "$pct%"
              } else {
                ui.bar.isIndeterminate = true
                ui.pct.text = ""
              }
            }
          }
        }
        updateButton.setLoading(false)
        ui.phase.text = context.getString(R.string.engine_update_done)
        ui.bar.isIndeterminate = false
        ui.bar.progress = 100
        ui.pct.text = "100%"
        ui.dialog.setCancelable(true)
        postUi { ui.dialog.dismiss() }
        Logs.logEvent(context, "Engine", "update-applied ${manifest.version}")
        refreshStatus()
      } else {
        updateButton.setLoading(false)
        if (ui.dialog.isShowing) ui.dialog.dismiss()
        statusText.text = context.getString(R.string.engine_update_fail)
      }
      refreshLogs()
    }
  }

  /** 构造 MD3 更新进度弹窗（醒目横向进度条，主色提示）。 */
  private fun showUpdateDialog(): UpdateUi {
    val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      progress = 0
      isIndeterminate = true
      progressTintList = ColorStateList.valueOf(context.color(R.color.dh_primary))
      backgroundTintList = ColorStateList.valueOf(context.color(R.color.dh_divider))
      minimumHeight = dp(10)
    }
    val phase = TextView(context).apply {
      setTextColor(context.color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    val pct = TextView(context).apply {
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    body.addView(phase, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    body.addView(bar, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(8) })
    body.addView(pct, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(4) })

    val dialog = context.themedDialog(
      title = context.getString(R.string.engine_update_dialog_title),
      contentView = body,
      negativeText = "",
      positiveText = context.getString(R.string.engine_progress_cancel),
      onPositive = { uiCancelToken++ },
    )
    dialog.setCancelable(false)
    dialog.show()
    return UpdateUi(dialog, bar, phase, pct)
  }

  /** 递增即表示用户从进度弹窗点「取消」（后台协程仍会跑完，仅关窗，避免打断解压）。 */
  private var uiCancelToken = 0

  private class UpdateUi(
    val dialog: AlertDialog,
    val bar: ProgressBar,
    val phase: TextView,
    val pct: TextView,
  )

  private fun startLogPolling() {
    val token = ++pollToken
    scope.launch {
      while (token == pollToken) {
        delay(1200)
        refreshLogs()
      }
    }
  }

  /** 读 app-events.log 尾部（含下载进度/失败原因/引擎 proc 输出）填充日志区。 */
  private fun refreshLogs() {
    val tail = Logs.tail(Logs.appEventsLog(context), 60)
    logBody.text = tail.ifBlank { context.getString(R.string.engine_logs_empty) }
  }

  /** 下载进度：字节 -> 横向进度条百分比 + 文案。在主线程调用。 */
  private fun showProgress(done: Long, total: Long) {
    progressRow.visibility = View.VISIBLE
    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
    progressBar.progress = pct.coerceIn(0, 100)
    val txt = if (total > 0) context.getString(R.string.engine_progress, formatBytes(done), formatBytes(total))
      else context.getString(R.string.engine_update_downloading)
    progressLabel.text = txt
  }

  private fun hideProgressOnUi() {
    progressRow.visibility = View.GONE
    progressBar.progress = 0
  }

  private fun formatBytes(n: Long): String {
    if (n >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", n / (1024f * 1024f))
    if (n >= 1024) return String.format(java.util.Locale.US, "%.0f KB", n / 1024f)
    return "$n B"
  }

  private fun postUi(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
  }

  private fun refreshStatus() {
    val extracted = EngineRootfs.isExtracted(context)
    if (extracted) {
      statusText.text = context.getString(R.string.engine_ready_version, EngineRootfs.engineVersion(context))
    } else {
      statusText.text = context.getString(R.string.engine_no_rootfs)
    }
    scope.launch {
      val running = withContext(Dispatchers.IO) { EngineProcess.probe(context, 1000) }
      val runningText =
        if (running) context.getString(R.string.engine_running)
        else context.getString(R.string.engine_stopped)
      detailText.text = runningText
      updateButtons(extracted, running)
    }
  }

  private fun updateButtons(extracted: Boolean, running: Boolean) {
    startButton.isEnabled = extracted
    startButton.alpha = if (extracted) 1f else 0.4f
    openButton.isEnabled = extracted
    openButton.alpha = if (extracted) 1f else 0.4f
    stopButton.isEnabled = extracted
    stopButton.alpha = if (extracted) 1.0f else 0.4f
  }

  /** 两列按钮格子的等宽参数（endGap=true 时右侧留 8dp 间隙）。 */
  private fun lpBtnCell(endGap: Boolean = false): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
      if (endGap) rightMargin = dp(8)
    }

  /** 描边/实心动作按钮（颜色、圆角、尺寸走主题令牌）。 */
  private fun actionButton(label: String, primary: Boolean): TextView =
    TextView(context).apply {
      text = label
      gravity = Gravity.CENTER
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      if (primary) {
        setTextColor(context.color(R.color.dh_on_primary))
        background = context.roundedBg(context.color(R.color.dh_primary), 20)
      } else {
        setTextColor(context.color(R.color.dh_primary))
        background = context.roundedBg(
          android.graphics.Color.TRANSPARENT, 20,
          strokeColor = context.color(R.color.dh_primary), strokeDp = 1,
        )
      }
    }
}
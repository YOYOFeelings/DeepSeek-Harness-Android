package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.ui.SPACE_LG
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 「更新」独立页：当前/最新版本 + 更新说明 + 带进度条的下载并安装。
 * 作为主页「发现新版本」横幅与设置里「版本更新」的共同目标页。
 */
class UpdateScreen(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onBack: () -> Unit,
) {

  val rootView: View

  private fun dp(v: Int): Int = context.dp(v)

  private val currentVersionView = TextView(context)
  private val latestVersionView = TextView(context)
  private val releaseBodyView = TextView(context)
  private val statusView = TextView(context)
  private val downloadSizeView = TextView(context)
  private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    minimumHeight = dp(8)
    val track = context.roundedBg(context.color(R.color.dh_divider), 4)
    val fill = context.roundedBg(context.color(R.color.dh_primary), 4)
    val layer = LayerDrawable(arrayOf(track, fill))
    layer.setId(0, android.R.id.background)
    layer.setId(1, android.R.id.progress)
    progressDrawable = layer
  }
  private val downloadButton = TextView(context)

  private var latest: UpdateManager.ReleaseInfo? = null
  private var history: List<UpdateManager.ReleaseInfo> = emptyList()
  private var historyExpanded = false
  private var historyDownloading: UpdateManager.ReleaseInfo? = null
  private val historyArrow = TextView(context)
  private val historyCount = TextView(context)
  private val historyContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val expandedBodyVersions = mutableSetOf<String>()
  private var downloading = false

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.settings_update), R.drawable.ic_arrow_left, onBack),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val scroll = ScrollView(context)
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(SPACE_LG), dp(SPACE_LG), dp(SPACE_LG), dp(SPACE_LG))
    }

    // 版本卡片
    val versionCard = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    currentVersionView.apply {
      text = context.getString(R.string.update_current_version, UpdateManager.currentVersion(context))
      setTextColor(context.color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    latestVersionView.apply {
      text = context.getString(R.string.update_checking)
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    // 卡片头部：左版本信息 + 右侧「刷新」
    val versionHeader = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    versionHeader.addView(currentVersionView, LinearLayout.LayoutParams(
      0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    versionHeader.addView(
      TextView(context).apply {
        text = context.getString(R.string.update_refresh)
        setTextColor(context.color(R.color.dh_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { startFetch() }
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    versionCard.addView(versionHeader)
    versionCard.addView(latestVersionView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    content.addView(versionCard)

    // 更新说明卡片
    val bodyCard = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    bodyCard.addView(
      TextView(context).apply {
        text = context.getString(R.string.update_content_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
      },
    )
    releaseBodyView.apply {
      text = context.getString(R.string.update_checking)
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      setLineSpacing(dp(3).toFloat(), 1f)
    }
    bodyCard.addView(releaseBodyView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    content.addView(bodyCard, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

    // 往期版本（卡片式可折叠 header，更显眼）
    val historyHeader = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(13), dp(16), dp(13))
      isClickable = true
      setOnClickListener { toggleHistory() }
    }
    historyArrow.apply {
      text = "▸"
      setTextColor(context.color(R.color.dh_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }
    historyHeader.addView(
      TextView(context).apply {
        text = context.getString(R.string.update_history_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    historyCount.apply {
      text = ""
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    historyHeader.addView(historyCount, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { leftMargin = dp(6) })
    historyHeader.addView(
      historyArrow,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { gravity = Gravity.END },
    )
    content.addView(historyHeader, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    historyContainer.visibility = View.GONE
    content.addView(historyContainer, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

    // 下载按钮
    downloadButton.apply {
      text = context.getString(R.string.update_available)
      setTextColor(context.color(R.color.dh_on_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = context.roundedBg(context.color(R.color.dh_primary), 22)
      setPadding(0, dp(12), 0, dp(12))
      isEnabled = false
      alpha = 0.5f
      setOnClickListener { startDownload() }
    }
    content.addView(downloadButton, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

    // 进度 + 状态
    content.addView(progressBar, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(16) })
    statusView.apply {
      text = " "
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      gravity = Gravity.CENTER
    }
    content.addView(statusView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    downloadSizeView.apply {
      text = ""
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      gravity = Gravity.CENTER
    }
    content.addView(downloadSizeView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = root

    startFetch()
  }

  private fun startFetch() {
    scope.launch {
      try {
        val current = UpdateManager.currentVersion(context)
        val info = UpdateManager.fetchLatest()
        latest = info
        if (info == null) {
          latestVersionView.text = context.getString(R.string.update_error)
          statusView.text = context.getString(R.string.update_error)
          return@launch
        }
        latestVersionView.text = context.getString(R.string.update_new_version, info.version)
        val updatable = devForceUpdate() || UpdateManager.isNewer(info.version, current)
        val body = UpdateManager.fetchReleaseBody()
        releaseBodyView.text = body?.takeIf { it.isNotBlank() }
          ?: context.getString(R.string.update_error)
        val fetchedHistory = UpdateManager.fetchHistoryReleases()
        history = fetchedHistory
        renderHistory()
        historyExpanded = false
        expandedBodyVersions.clear()
        historyArrow.text = "▸"
        historyContainer.visibility = View.GONE
        if (!updatable) {
          statusView.text = context.getString(R.string.update_latest)
          downloadButton.text = context.getString(R.string.update_latest)
        } else {
          statusView.text = context.getString(R.string.update_available)
          downloadButton.text = context.getString(R.string.settings_update) + " · 下载"
          downloadButton.isEnabled = true
          downloadButton.alpha = 1f
        }
      } catch (_: CancellationException) {
        throw CancellationException()
      } catch (_: Throwable) {
        latestVersionView.text = context.getString(R.string.update_error)
        statusView.text = context.getString(R.string.update_error)
      }
    }
  }

  private fun startDownload() {
    val info = latest ?: return
    if (downloading) return
    downloading = true
    downloadButton.isEnabled = false
    downloadButton.text = context.getString(R.string.update_downloading)
    downloadButton.alpha = 0.5f
    downloadSizeView.text = ""
    statusView.text = context.getString(R.string.update_downloading)
    scope.launch {
      try {
        val file = withContext(Dispatchers.IO) {
          UpdateManager.downloadWithProgress(context, info) { done, total ->
            progressBar.post {
              if (total > 0) {
                progressBar.progress = ((done * 100) / total).toInt().coerceIn(0, 100)
                downloadSizeView.text = "${formatBytes(done)}/${formatBytes(total)}"
              } else {
                downloadSizeView.text = formatBytes(done)
              }
              statusView.text = String.format(
                Locale.getDefault(), "%d%% · %s", progressBar.progress, context.getString(R.string.update_downloading))
            }
          }
        }
        downloading = false
        if (file == null) {
          statusView.text = context.getString(R.string.update_no_file)
          downloadButton.text = context.getString(R.string.settings_update)
          resetButton()
          return@launch
        }
        progressBar.post { progressBar.progress = 100 }
        statusView.text = context.getString(R.string.update_downloaded)
        downloadSizeView.text = ""
        downloadButton.text = context.getString(R.string.update_downloaded)
        downloadButton.isEnabled = true
        downloadButton.alpha = 1f
        if (!UpdateManager.install(context, file)) {
          statusView.text = context.getString(R.string.update_error)
        }
      } catch (_: CancellationException) {
        throw CancellationException()
      } catch (_: Throwable) {
        downloading = false
        statusView.text = context.getString(R.string.update_error)
        downloadButton.text = context.getString(R.string.settings_update)
        resetButton()
      }
    }
  }

  private fun toggleHistory() {
    historyExpanded = !historyExpanded
    historyArrow.text = if (historyExpanded) "▾" else "▸"
    historyContainer.visibility = if (historyExpanded) View.VISIBLE else View.GONE
    if (!historyExpanded) expandedBodyVersions.clear()
    renderHistory()
  }

  /** 单条历史版本展开/收起其更新日志。 */
  private fun toggleHistoryBody(version: String) {
    if (!expandedBodyVersions.add(version)) expandedBodyVersions.remove(version)
    renderHistory()
  }

  /** 渲染「往期版本」列表，每条为独立卡片，可点击展开更新日志；空时显示占位文案。 */
  private fun renderHistory() {
    historyContainer.removeAllViews()
    historyCount.text = if (history.isEmpty()) "" else context.getString(R.string.update_history_count, history.size)
    if (history.isEmpty()) {
      historyContainer.addView(
        TextView(context).apply {
          text = context.getString(R.string.update_history_empty)
          setTextColor(context.color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
      return
    }
    for (info in history) {
      val expanded = info.version in expandedBodyVersions

      val head = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
      left.addView(
        TextView(context).apply {
          text = info.version
          setTextColor(context.color(R.color.dh_text_primary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        },
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
      if (info.publishedAt.isNotBlank()) {
        left.addView(
          TextView(context).apply {
            text = info.publishedAt
            setTextColor(context.color(R.color.dh_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) },
        )
      }
      head.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

      head.addView(
        TextView(context).apply {
          text = if (expanded) "▾" else "▸"
          setTextColor(context.color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        },
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          .apply { leftMargin = dp(8) },
      )

      val downloadTv = TextView(context).apply {
        text = context.getString(R.string.update_history_download)
        setTextColor(context.color(R.color.dh_on_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        background = context.roundedBg(context.color(R.color.dh_primary), 14)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener {
          if (historyDownloading != null) return@setOnClickListener
          startHistoryDownload(info)
        }
      }
      head.addView(downloadTv, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = dp(10) })

      // 更新日志区（点击行展开/收起）
      val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
      if (info.body.isNotBlank()) {
        body.addView(
          TextView(context).apply {
            text = context.getString(R.string.update_history_log_title)
            setTextColor(context.color(R.color.dh_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) },
        )
        body.addView(
          TextView(context).apply {
            text = info.body
            setTextColor(context.color(R.color.dh_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1f)
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) },
        )
      } else {
        body.addView(
          TextView(context).apply {
            text = context.getString(R.string.update_history_log_empty)
            setTextColor(context.color(R.color.dh_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) },
        )
      }
      body.visibility = if (expanded) View.VISIBLE else View.GONE

      val row = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.roundedBg(context.color(R.color.dh_surface), 12)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        setOnClickListener { toggleHistoryBody(info.version) }
      }
      row.addView(head)
      row.addView(body, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      historyContainer.addView(row, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6) })
    }
  }

  private fun startHistoryDownload(info: UpdateManager.ReleaseInfo) {
    if (historyDownloading != null) return
    historyDownloading = info
    downloadButton.isEnabled = false
    downloadButton.alpha = 0.5f
    progressBar.progress = 0
    downloadSizeView.text = ""
    statusView.text = context.getString(R.string.update_history_downloading) + " " + info.version
    scope.launch {
      try {
        val file = withContext(Dispatchers.IO) {
          UpdateManager.downloadWithProgress(context, info) { done, total ->
            progressBar.post {
              if (total > 0) {
                progressBar.progress = ((done * 100) / total).toInt().coerceIn(0, 100)
                downloadSizeView.text = "${formatBytes(done)}/${formatBytes(total)}"
              } else {
                downloadSizeView.text = formatBytes(done)
              }
              statusView.text =
                context.getString(R.string.update_history_downloading) + " " + info.version
            }
          }
        }
        historyDownloading = null
        if (file == null) {
          statusView.text = context.getString(R.string.update_no_file)
          resetButton()
          return@launch
        }
        progressBar.post { progressBar.progress = 100 }
        statusView.text = context.getString(R.string.update_downloaded)
        downloadSizeView.text = ""
        if (!UpdateManager.install(context, file)) {
          statusView.text = context.getString(R.string.update_error)
        }
      } catch (_: CancellationException) {
        throw CancellationException()
      } catch (_: Throwable) {
        historyDownloading = null
        statusView.text = context.getString(R.string.update_error)
        resetButton()
      }
    }
  }

  private fun formatBytes(n: Long): String {
    if (n >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", n / (1024f * 1024f))
    if (n >= 1024) return String.format(java.util.Locale.US, "%.0f KB", n / 1024f)
    return "$n B"
  }

  private fun resetButton() {
    val enabled = latest?.let {
      devForceUpdate() || UpdateManager.isNewer(it.version, UpdateManager.currentVersion(context))
    } == true
    downloadButton.isEnabled = enabled
    downloadButton.alpha = if (enabled) 1f else 0.5f
  }

  /** 开发者模式「更新直接提示」开关已开启时，忽略版本比较，直接视为有新版（用于测试更新链路）。 */
  private fun devForceUpdate(): Boolean =
    context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
      .getBoolean("dev_force_update", false)

  private fun toast(msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
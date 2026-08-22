package com.yoyo.dshmobile.shell.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import java.io.File
import java.util.Locale

/**
 * 日志页：列出 `filesDir/logs/` 下的日志文件。点击行内联展开内容（可复制），再次点击收起；
 * 同一时刻只展开一个文件。通过 [onBack] 返回设置页。
 */
class LogsScreen(
  private val context: Context,
  private val onBack: () -> Unit,
) {

  val rootView: View

  /** 尺寸单位换算（转发到主题密度工具；在 apply 地块内可直接引用）。 */
  private fun dp(v: Int): Int = context.dp(v)

  private val list = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }
  private var expandedIndex = -1
  private var expandedView: View? = null

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(
        context.getString(R.string.logs_title),
        leadingRes = R.drawable.ic_arrow_left,
        onLeadingClick = onBack,
      ),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val files = listLogFiles()
    if (files.isEmpty()) {
      val empty = TextView(context).apply {
        text = context.getString(R.string.logs_empty)
        setTextColor(context.color(R.color.dh_text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(0), dp(40), dp(0), dp(0))
      }
      root.addView(empty, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    } else {
      files.forEachIndexed { index, f ->
        list.addView(logRow(f, index), if (index == 0) {
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        })
      }
      val scroll = ScrollView(context)
      scroll.addView(list, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    rootView = root
  }

  private fun listLogFiles(): List<File> = try {
    Logs.dir(context).listFiles()
      ?.filter { it.isFile }
      ?.sortedByDescending { it.lastModified() }
      ?.toList() ?: emptyList()
  } catch (_: Throwable) {
    emptyList()
  }

  private fun logRow(file: File, index: Int): View {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(12), dp(16), dp(12))
      setOnClickListener { toggleExpand(file, index, this) }
    }
    row.addView(
      TextView(context).apply {
        text = file.name
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.MONOSPACE
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    row.addView(
      TextView(context).apply {
        text = String.format(
          Locale.getDefault(), "%s  ·  %s",
          humanSize(file.length()),
          DateFormat.getDateFormat(context).format(file.lastModified()),
        )
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(4) },
    )
    return row
  }

  /** 行内展开/收起：同一时刻只展开一个文件。 */
  private fun toggleExpand(file: File, index: Int, row: LinearLayout) {
    if (expandedIndex == index) {
      // 收起当前
      expandedView?.let { row.removeView(it) }
      expandedIndex = -1
      expandedView = null
    } else {
      // 收起上一个
      if (expandedView != null && expandedIndex in 0 until list.childCount) {
        (list.getChildAt(expandedIndex) as ViewGroup).removeView(expandedView)
      }
      val content = buildLogContent(file)
      row.addView(content, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) })
      expandedIndex = index
      expandedView = content
    }
  }

  /** 展开块：右上「复制」+ 固定高度可滚动等宽内容。 */
  private fun buildLogContent(file: File): View {
    val content = try {
      file.readText()
    } catch (_: Throwable) {
      context.getString(R.string.update_error)
    }
    val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(
      TextView(context).apply {
        text = context.getString(R.string.terminal_copy)
        setTextColor(context.color(R.color.dh_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.END
        setPadding(0, 0, dp(4), dp(4))
        setOnClickListener {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText(file.name, content))
          Toast.makeText(context, context.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
        }
      },
    )
    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = true
      setPadding(dp(12), dp(12), dp(12), dp(12))
      background = context.roundedBg(context.color(R.color.dh_background), 12)
    }
    scroll.addView(TextView(context).apply {
      text = content
      setTextColor(context.color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.MONOSPACE
      setLineSpacing(dp(2).toFloat(), 1f)
    }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    wrap.addView(scroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
    return wrap
  }

  private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.1f MB", mb)
  }
}
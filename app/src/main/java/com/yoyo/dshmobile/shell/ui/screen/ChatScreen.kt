package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 对话页：会话式界面（历史气泡 + 底部输入/发送）。
 * 发送后经共享执行器 [DeviceExecutor]（Shizuku 优先 → Root 回退）把输入作为设备命令执行，
 * 输出追加为 assistant 气泡。外部 LLM 接入不在本次范围。
 */
class ChatScreen(
  private val context: Context,
  private val scope: CoroutineScope,
) {

  val rootView: View

  /** 尺寸单位换算（转发到主题密度工具；在 apply 地块内可直接引用）。 */
  private fun dp(v: Int): Int = context.dp(v)

  private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val scroll = ScrollView(context)
  private val input = EditText(context)
  private val send = TextView(context)

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.chat_title)),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    scroll.addView(list, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    root.addView(buildInputBar(), LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    rootView = root

    // 默认提示气泡
    appendBubble(context.getString(R.string.chat_hint), isUser = false)
  }

  private fun buildInputBar(): View {
    val bar = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    input.apply {
      hint = context.getString(R.string.chat_hint)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      gravity = Gravity.START or Gravity.CENTER_VERTICAL
      background = context.roundedBg(
        context.color(R.color.dh_surface),
        20,
        strokeColor = context.color(R.color.dh_divider),
        strokeDp = 1,
      )
      setPadding(dp(14), dp(10), dp(14), dp(10))
      setTextColor(context.color(R.color.dh_text_primary))
      setHintTextColor(context.color(R.color.dh_text_faint))
    }
    bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    send.apply {
      text = context.getString(R.string.chat_send)
      setTextColor(context.color(R.color.dh_on_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      gravity = Gravity.CENTER
      background = context.roundedBg(context.color(R.color.dh_primary), 20)
      setOnClickListener { sendText() }
    }
    bar.addView(send, LinearLayout.LayoutParams(dp(72), dp(46))
      .apply { leftMargin = dp(12) })
    return bar
  }

  private fun sendText() {
    val text = input.text.toString().trim()
    if (text.isEmpty()) return
    input.setText("")
    appendBubble(text, isUser = true)
    scope.launch {
      try {
        val result = DeviceExecutor.run(text, context)
        appendBubble(formatOutput(result), isUser = false)
      } catch (_: CancellationException) {
        // 页面销毁取消时不追加
      } catch (_: Throwable) {
        appendBubble(context.getString(R.string.update_error), isUser = false)
      }
    }
  }

  private fun formatOutput(result: ExecResult): String {
    if (!result.ok) return result.output.ifBlank {
      context.getString(R.string.update_error)
    }
    val out = result.output.trim()
    return out.ifBlank { "(无输出)" }
  }

  private fun appendBubble(text: String, isUser: Boolean) {
    val bubble = TextView(context).apply {
      this.text = text
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTextColor(if (isUser) context.color(R.color.dh_on_primary) else context.color(R.color.dh_text_primary))
      setPadding(dp(14), dp(10), dp(14), dp(10))
      if (isUser) {
        background = context.roundedBg(context.color(R.color.dh_primary), 16)
      } else {
        background = context.roundedBg(
          context.color(R.color.dh_surface),
          16,
          strokeColor = context.color(R.color.dh_divider),
          strokeDp = 1,
        )
      }
    }
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = if (isUser) Gravity.END else Gravity.START
    }
    row.addView(
      bubble,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    list.addView(row, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(10) })
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
  }
}
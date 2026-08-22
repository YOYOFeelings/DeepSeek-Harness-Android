package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.onboarding.MODE_ADVANCED
import com.yoyo.dshmobile.shell.onboarding.MODE_NORMAL
import com.yoyo.dshmobile.shell.onboarding.MODE_SHIZUKU
import com.yoyo.dshmobile.shell.onboarding.currentMode
import com.yoyo.dshmobile.shell.onboarding.markPermissionGranted
import com.yoyo.dshmobile.shell.onboarding.savePermissionMode
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import com.yoyo.dshmobile.shell.ui.tintId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 权限模式独立页：三档（普通 / 高级 / Shizuku）并列选择，点选即持久化并高亮当前档。
 * 与 HomeScreen 的权限展示、DeviceExecutor 的命令沙箱共用同一持久化 key（OnboardingDataStore）。
 */
class PermissionModeScreen(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onBack: () -> Unit,
) {

  val rootView: View

  /** 尺寸单位换算（转发到主题密度工具）。 */
  private fun dp(v: Int): Int = context.dp(v)

  /** 三档选项（key 即持久化值与企业文案，副标题/图标来自资源）。 */
  private data class ModeOption(val key: String, val icon: Int, val descRes: Int)

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(
        context.getString(R.string.permission_mode_title),
        leadingRes = R.drawable.ic_arrow_left,
        onLeadingClick = onBack,
      ),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val options = listOf(
      ModeOption(MODE_NORMAL, R.drawable.ic_perm_notification, R.string.permission_mode_normal_desc),
      ModeOption(MODE_ADVANCED, R.drawable.ic_settings, R.string.permission_mode_advanced_desc),
      ModeOption(MODE_SHIZUKU, R.drawable.ic_terminal, R.string.permission_mode_shizuku_desc),
    )
    val current = currentMode(context)

    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    content.addView(
      TextView(context).apply {
        text = context.getString(R.string.permission_mode_page_intro)
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(2).toFloat(), 1f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(14) },
    )

    val cards = mutableListOf<Pair<LinearLayout, () -> Unit>>()
    options.forEachIndexed { index, opt ->
      val check = ImageView(context).apply {
        contentDescription = null
        setImageResource(R.drawable.ic_radio_unchecked)
        tintId(R.color.dh_text_faint)
      }
      val name = TextView(context).apply {
        text = opt.key
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.color(R.color.dh_text_primary))
      }

      lateinit var card: LinearLayout

      fun refresh() {
        val isSel = currentMode(context) == opt.key
        check.setImageResource(if (isSel) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
        check.tintId(if (isSel) R.color.dh_primary else R.color.dh_text_faint)
        name.setTextColor(context.color(if (isSel) R.color.dh_primary else R.color.dh_text_primary))
        card.background = context.roundedBg(
          context.color(R.color.dh_surface), 16,
          strokeColor = if (isSel) context.color(R.color.dh_primary) else null,
          strokeDp = 1,
        )
      }

      card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener {
          scope.launch {
            savePermissionMode(context, opt.key)
            markPermissionGranted(context, false)
            Logs.logEvent(context, "PermissionMode", "saved=" + opt.key)
          }
          cards.forEach { it.second() }
        }
        addView(
          ImageView(context).apply {
            setImageResource(opt.icon)
            tintId(R.color.dh_primary)
            contentDescription = null
          },
          LinearLayout.LayoutParams(dp(22), dp(22)),
        )
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(name, LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labels.addView(
          TextView(context).apply {
            text = context.getString(opt.descRes)
            setTextColor(context.color(R.color.dh_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          },
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(3) },
        )
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
          .apply { leftMargin = dp(14) })
        addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
      }
      content.addView(card, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = if (index == options.lastIndex) 0 else dp(12) })
      cards.add(card to ::refresh)
      refresh()
    }

    val scroll = ScrollView(context)
    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = root
  }
}
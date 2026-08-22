package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.animation.ValueAnimator
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar

/**
 * 「开发者设置」独立页：仅供作者自测。
 * 进入需在设置页通过密码 + 协议校验；页面顶部有警示横幅，内置可展开的设置卡片（更新直接提示开关）。
 * 所有色值/尺寸走主题令牌，MD3 圆角卡片 + 高度展开动画。
 */
class DeveloperSettingsScreen(
  private val context: Context,
  private val onBack: () -> Unit,
) {

  val rootView: View

  private fun dp(v: Int): Int = context.dp(v)

  /** dev 开关持久化（与 UpdateScreen 读取同一 key）。 */
  private val devPrefs = context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
  private val forceUpdateSwitch = SwitchCompat(context)

  init {
    forceUpdateSwitch.apply {
      isChecked = devPrefs.getBoolean("dev_force_update", false)
      buttonTintList = ColorStateList.valueOf(context.color(R.color.dh_primary))
      setOnCheckedChangeListener { _, checked ->
        devPrefs.edit().putBoolean("dev_force_update", checked).apply()
        if (checked) context.toast(context.getString(R.string.dev_password_ok))
      }
    }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.dev_title), R.drawable.ic_arrow_left, onBack),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    // 警示横幅：开发者模式开启提示
    content.addView(
      TextView(context).apply {
        text = context.getString(R.string.dev_warning_banner)
        setTextColor(context.color(R.color.dh_danger))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = context.roundedBg(context.color(R.color.dh_surface), 16)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    // 更新直接提示（不检查版本）展开卡
    content.addView(
      buildCollapsibleCard(
        title = context.getString(R.string.dev_update_force),
        desc = context.getString(R.string.dev_update_force_desc),
        chevron = null,
        trailing = forceUpdateSwitch,
        detail = { scroll ->
          scroll.addView(
            TextView(context).apply {
              text = context.getString(R.string.dev_danger_hint)
              setTextColor(context.color(R.color.dh_text_secondary))
              setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
              setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
          )
          scroll.addView(
            TextView(context).apply {
              text = context.getString(R.string.dev_switch_off)
              setTextColor(context.color(R.color.dh_primary))
              setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
              setPadding(0, dp(2), 0, dp(2))
              setOnClickListener {
                forceUpdateSwitch.isChecked = false
                devPrefs.edit().putBoolean("dev_force_update", false).apply()
                context.toast(context.getString(R.string.engine_start_dialog_close))
              }
            },
            LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
              .apply { topMargin = dp(10) },
          )
        },
      ),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(14) },
    )

    val scroll = ScrollView(context)
    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = root
  }

  /**
   * 可展开的 MD3 圆角设置卡：标题 + 描述 + 右侧开关/箭头；点击卡片以高度动画展开 `detail` 内容区。
   */
  private fun buildCollapsibleCard(
    title: String,
    desc: String,
    chevron: TextView?,
    trailing: View,
    detail: (LinearLayout) -> Unit,
  ): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(13), dp(16), dp(13))
    }

    val detailContent = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      detail(this)
      visibility = View.GONE
    }
    var expanded = false

    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      isClickable = true
      setOnClickListener {
        expanded = !expanded
        animateHeight(detailContent, expanded)
        card.requestLayout()
      }
    }
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(
      TextView(context).apply {
        text = title
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    labels.addView(
      TextView(context).apply {
        text = desc
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(dp(1).toFloat(), 1f)
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(trailing, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { leftMargin = dp(8) })

    card.addView(header)
    card.addView(detailContent, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return card
  }

  /** 高度展开/收起动画（0 ↔ measuredHeight）。 */
  private fun animateHeight(view: View, expand: Boolean) {
    if (expand) {
      view.visibility = View.VISIBLE
      view.measure(
        View.MeasureSpec.makeMeasureSpec(
          (view.parent as? View)?.width ?: 0, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
      )
    }
    val start = if (expand) 0 else view.height
    val end = if (expand) view.measuredHeight else 0
    if (start == end) {
      if (!expand) view.visibility = View.GONE
      return
    }
    ValueAnimator.ofInt(start, end).apply {
      duration = 220
      addUpdateListener { a ->
        val h = a.animatedValue as Int
        val lp = view.layoutParams
        lp.height = h
        view.layoutParams = lp
      }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          if (!expand) view.visibility = View.GONE
          view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
          view.requestLayout()
        }
      })
      start()
    }
  }

  private fun Context.toast(msg: String) =
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
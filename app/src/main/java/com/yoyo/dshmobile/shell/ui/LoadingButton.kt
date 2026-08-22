package com.yoyo.dshmobile.shell.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.yoyo.dshmobile.shell.R

/**
 * 带 Loading 态的按钮（描边样式，与「Outline Button」视觉一致，单一来源配色）。
 *
 * 结构：外层 [FrameLayout] 内放原生 [Button]（满占）+ 居中 [ProgressBar]（默认 GONE）。
 * [setLoading] 切换加载态：
 * - true：显示 spinner、禁用按钮、隐藏文字（防止重复点击）；
 * - false：恢复文字、启用按钮、隐藏 spinner。
 *
 * 颜色一律读取 [R.color]（dh_primary / android 透明背景），不硬编码色值。
 */
class LoadingButton @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

  private val density = resources.displayMetrics.density
  private fun dp(v: Int): Int = (v * density).toInt()

  private val primary = ContextCompat.getColor(context, R.color.dh_primary)

  private val button = Button(context, null, 0, R.style.Widget_Dsh_Button_Outline).apply {
    background = roundedBg(
      ContextCompat.getColor(context, android.R.color.transparent),
      dp(20),
      strokeColor = primary,
      strokeWidthDp = dp(1),
    )
    setTextColor(primary)
    gravity = Gravity.CENTER
  }

  private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
    visibility = GONE
    isIndeterminate = true
    indeterminateTintList = ColorStateList.valueOf(primary)
  }

  private var label: CharSequence = ""

  init {
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(
      progress,
      LayoutParams(dp(22), dp(22), Gravity.CENTER),
    )
  }

  /** 设置按钮文案。 */
  fun setLabel(text: CharSequence) {
    label = text
    button.text = text
  }

  /** 设置点击回调（内部转发给原生 Button）。 */
  fun setOnClick(listener: () -> Unit) {
    button.setOnClickListener { listener() }
  }

  /** 切换为主色实心 CTA 样式（蓝渐变底 + 白色文字），保留内置 loading spinner 动画。 */
  fun setPrimaryStyle() {
    button.background = ContextCompat.getDrawable(context, R.drawable.btn_primary_gradient)
    button.setTextColor(ContextCompat.getColor(context, R.color.dh_on_primary))
  }

  /** 切换加载态。 */
  fun setLoading(loading: Boolean) {
    if (loading) {
      button.isEnabled = false
      button.text = ""
      progress.visibility = VISIBLE
    } else {
      button.isEnabled = true
      button.text = label
      progress.visibility = GONE
    }
  }

  /** 圆角矩形背景 drawable（颜色来自 R.color）。 */
  private fun roundedBg(
    color: Int,
    radiusPx: Int,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
  ): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
      shape = android.graphics.drawable.GradientDrawable.RECTANGLE
      cornerRadius = radiusPx.toFloat()
      setColor(color)
      if (strokeColor != null) setStroke(strokeWidthDp, strokeColor)
    }
}
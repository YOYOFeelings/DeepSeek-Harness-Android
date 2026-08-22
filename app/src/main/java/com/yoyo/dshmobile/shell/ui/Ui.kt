package com.yoyo.dshmobile.shell.ui

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yoyo.dshmobile.shell.R

/**
 * 原生 View 通用样式工具（单一来源，页面不硬编码色值/尺寸）。
 * 色值一律读取 R.color.dh_*，尺寸经 density 换算。
 * 供主页/对话/终端/设置/日志等主界面复用。
 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

/**
 * 页面顶部标题栏（左侧可选手徽标/返回箭头 + 标题 + 底部极细分隔线）。
 * 供对话/终端/设置/日志等页复用；色值与尺寸一律走主题资源，不硬编码。
 *
 * @param leadingRes 左端图标资源 id；null 时不显示左端元素（标题靠左）。
 * @param onLeadingClick 左端图标/标题点击回调（如返回）；null 时不可点。
 */
fun Context.screenTopBar(
  title: CharSequence?,
  leadingRes: Int? = null,
  onLeadingClick: (() -> Unit)? = null,
): View {
  val bar = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = android.view.Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(12))
  }

  if (leadingRes != null) {
    bar.addView(
      ImageView(this).apply {
        setImageResource(leadingRes)
        tintId(R.color.dh_text_secondary)
        contentDescription = title
        setOnClickListener { onLeadingClick?.invoke() }
      },
      LinearLayout.LayoutParams(dp(24), dp(24))
        .apply { marginEnd = dp(12) },
    )
  }

  bar.addView(
    TextView(this).apply {
      text = title
      setTextColor(color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = android.view.Gravity.START
    },
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
  )

  val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  wrap.addView(bar, LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  wrap.addView(
    View(this).apply { setBackgroundColor(color(R.color.dh_divider)) },
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
  )
  return wrap
}

/** 圆角矩形背景 drawable。 */
fun Context.roundedBg(
  fill: Int,
  radiusDp: Int,
  strokeColor: Int? = null,
  strokeDp: Int = 1,
): GradientDrawable =
  GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(fill)
    if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
  }

/** MD3 描边输入框（OutlinedBox）：圆角方框 + 浮动标签（点击后标签上浮到左上角）。 */
fun Context.outlinedEditText(
  hint: CharSequence,
  password: Boolean = true,
): TextInputLayout =
  TextInputLayout(this).apply {
    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
    boxStrokeColor = color(R.color.dh_primary)
    setHintTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat)
    hintTextColor = ColorStateList.valueOf(color(R.color.dh_primary))
    setHint(hint)
    addView(
      TextInputEditText(this@outlinedEditText).apply {
        if (password) {
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
          inputType = InputType.TYPE_CLASS_TEXT
        }
        maxLines = 1
        setTextColor(color(R.color.dh_text_primary))
        setHintTextColor(color(R.color.dh_text_faint))
        textSize = 15f
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
  }

/** 给图标按主题色着色。 */
fun ImageView.tintId(id: Int) {
  imageTintList = ColorStateList.valueOf(context.color(id))
}

/**
 * 供输入弹窗使用的 MD3 圆角描边输入框（布局 `dialog_rounded_input.xml`，四角统一圆角 35dp、
 * singleLine、hint 粗体、浮动标签）。
 * @return 根视图（供 `.setView()`）与内部编辑框（可自定义 hint / inputType）。
 */
fun Context.roundedInputView(
  hint: CharSequence,
  password: Boolean = false,
): Pair<View, TextInputEditText> {
  // 传一个临时父布局做参数解析（attachToRoot=false），避免 InflateParams 警告且根布局得到 MATCH_PARENT 参数
  val root = LayoutInflater.from(this)
    .inflate(R.layout.dialog_rounded_input, LinearLayout(this), false)
  val field = root.findViewById<TextInputEditText>(R.id.ti)
  field.setHint(hint)
  field.inputType = if (password) {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
  } else {
    InputType.TYPE_CLASS_TEXT
  }
  return root to field
}

/** 统一间距令牌（dp 值）。页面引用以保持 UI 一致。 */
const val SPACE_SM = 12
const val SPACE_MD = 14
const val SPACE_LG = 16

/**
 * 统一主题化对话弹窗：MD3 圆角表面承载 标题 + 可选内容/自定义视图 + 底部圆角按钮。
 * 色值与尺寸一律走主题令牌；返回已创建的 [AlertDialog]。
 */
fun Context.themedDialog(
  title: String,
  message: String? = null,
  contentView: View? = null,
  negativeText: String = "取消",
  positiveText: String = "确定",
  onNegative: (() -> Unit)? = null,
  onPositive: (() -> Unit)? = null,
): AlertDialog {
  val pad = dp(20)
  val root = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    // 不包白底圆角卡片：圆角由 MD3 表层负责，避免「外方内直角」。
    setPadding(pad, pad, pad, pad)
  }
  root.addView(
    TextView(this).apply {
      text = title
      setTextColor(color(R.color.dh_text_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = android.view.Gravity.START
    },
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
  )
  if (message != null) {
    root.addView(
      TextView(this).apply {
        text = message
        setTextColor(color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(2).toFloat(), 1f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(6) },
    )
  }
  if (contentView != null) {
    root.addView(contentView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(6) })
  }

  lateinit var dialog: AlertDialog
  val buttons = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = android.view.Gravity.END
  }
  if (negativeText.isNotBlank()) {
    buttons.addView(
      TextView(this).apply {
        text = negativeText
        setTextColor(color(R.color.dh_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = android.view.Gravity.CENTER
        background = roundedBg(android.graphics.Color.TRANSPARENT, 18, color(R.color.dh_primary), 1)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener {
          onNegative?.invoke()
          dialog.dismiss()
        }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
  }
  if (positiveText.isNotBlank()) {
    buttons.addView(
      TextView(this).apply {
        text = positiveText
        setTextColor(color(R.color.dh_on_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        background = roundedBg(color(R.color.dh_primary), 18)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener {
          onPositive?.invoke()
          dialog.dismiss()
        }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = dp(12) },
    )
  }
  root.addView(buttons, LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    .apply { topMargin = dp(16) })

  dialog = MaterialAlertDialogBuilder(this).setView(root).create()
  return dialog
}
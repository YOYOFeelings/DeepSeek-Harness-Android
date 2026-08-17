package com.dshmobile.shell

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 统一弹窗（Flat Minimalist）：圆角白卡 + 图标标题行 + 可滚动正文 + accent/ghost 按钮。
 *
 * UX 设计：
 * - 全部弹窗默认可关闭（返回键 / 点外部）；如需阻断可传 cancelable=false；
 * - 至少提供一个显式出口按钮（关闭/取消/稍后），避免「初始化错误一直无法关闭」；
 * - 正文放入 ScrollView 并限制最大高度（横竖屏均不会超出屏幕、不被裁剪）；
 * - 按钮沿用 accent/ghost 扁平样式，点击后自动关闭并回调；
 * - [onCancel] 仅在「非按钮关闭」（返回键 / 点外部 / 外部 dismiss）时触发，避免与按钮回调重复。
 */
object DialogUi {

  /** 底部按钮动作。 */
  data class Action(
    val label: String,
    val accent: Boolean = false,
    val onClick: (() -> Unit)? = null,
  )

  /** 展示文本型弹窗并返回 dialog（供外部主动 dismiss）。 */
  fun show(
    context: Context,
    title: String,
    message: String,
    iconRes: Int = 0,
    actions: List<Action> = emptyList(),
    cancelable: Boolean = true,
    onCancel: (() -> Unit)? = null,
  ): AlertDialog = build(context, title, iconRes, actions, cancelable, onCancel) { root ->
    if (message.isNotBlank()) {
      val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      }
      val bodyText = TextView(context).apply {
        text = message
        textSize = 13f
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setTextColor(color(context, R.color.text_secondary))
        setPadding(0, dp(context, 12), 0, dp(context, 4))
        maxHeight = dp(context, (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density * 0.5f).toInt())
      }
      scroll.addView(bodyText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      root.addView(scroll)
    }
  }

  /** 展示自定义内容型弹窗并返回 dialog（供外部主动 dismiss，如测速动态列表）。
   *  内容包进 ScrollView 并限制最大高度（约屏可用高度 60%），源列表等长内容可上下滑动、
   *  横竖屏均不超出屏幕（修复「测速弹窗无法滑动、看不到全部源」）。
   *  [footer] 为固定在内容区下方、不随内容滚动的视图（如测速弹窗的「开始更新/重试」按钮），
   *  保证长内容滚到底时操作按钮始终可见、可点（修复「按钮被内容顶出屏幕」）。 */
  fun show(
    context: Context,
    title: String,
    content: View,
    iconRes: Int = 0,
    actions: List<Action> = emptyList(),
    cancelable: Boolean = true,
    onCancel: (() -> Unit)? = null,
    footer: View? = null,
  ): AlertDialog = build(context, title, iconRes, actions, cancelable, onCancel) { root ->
    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = true
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    content.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // 最大高度：约屏幕可用高度的 60%（与 message 重载一致）。ScrollView 没有 maxHeight，
    // 用限高容器在 onMeasure 阶段约束，长内容可滚动、不超出屏幕。
    val maxHeightPx = dp(
      context,
      (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density * 0.60f).toInt(),
    )
    root.addView(
      MaxHeightLayout(context, maxHeightPx).apply {
        addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    if (footer != null) {
      root.addView(
        footer,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          topMargin = dp(context, 10)
        },
      )
    }
  }

  private fun build(
    context: Context,
    title: String,
    iconRes: Int,
    actions: List<Action>,
    cancelable: Boolean,
    onCancel: (() -> Unit)?,
    contentBuilder: (LinearLayout) -> Unit,
  ): AlertDialog {
    val dialog = AlertDialog.Builder(context).create()
    dialog.setCancelable(cancelable)
    dialog.setCanceledOnTouchOutside(cancelable)

    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 14))
      background = context.resources.getDrawable(R.drawable.bg_dialog, null)
    }

    // 标题行：图标 + 标题
    val head = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    if (iconRes != 0) {
      head.addView(ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color(context, R.color.accent))
        layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply { marginEnd = dp(context, 8) }
      })
    }
    head.addView(TextView(context).apply {
      text = title
      textSize = 16f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(color(context, R.color.text))
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    root.addView(head)

    contentBuilder(root)

    // 按钮行
    if (actions.isNotEmpty()) {
      val btnRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 14) }
      }
      var dismissedByAction = false
      for (a in actions) {
        val btn = TextView(context).apply {
          text = a.label
          textSize = 13f
          typeface = Typeface.DEFAULT_BOLD
          gravity = Gravity.CENTER
          setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
          setTextColor(if (a.accent) Color.WHITE else color(context, R.color.accent))
          background = context.resources.getDrawable(if (a.accent) R.drawable.bg_button_accent else R.drawable.bg_button_ghost, null)
          isClickable = true
          isFocusable = true
          setOnClickListener {
            dismissedByAction = true
            dialog.dismiss()
            a.onClick?.invoke()
          }
        }
        btnRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          if (btnRow.childCount > 0) marginStart = dp(context, 8)
        })
      }
      root.addView(btnRow)
      dialog.setOnDismissListener {
        if (!dismissedByAction) onCancel?.invoke()
      }
    } else {
      dialog.setOnDismissListener { onCancel?.invoke() }
    }

    dialog.setView(root)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    return dialog
  }

  private fun color(context: Context, res: Int): Int = context.resources.getColor(res, null)

  private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/**
 * 限高容器：在 onMeasure 阶段把高度约束在 [maxHeightPx] 以内，
 * 配合 ScrollView 实现「内容超出可滚动、不超出屏幕」的效果。
 * ScrollView 本身没有 maxHeight 属性，必须用此容器约束。
 */
private class MaxHeightLayout(context: Context, private val maxHeightPx: Int) : FrameLayout(context) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val limited = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
    super.onMeasure(widthMeasureSpec, limited)
  }
}
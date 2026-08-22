package com.yoyo.dshmobile.shell.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yoyo.dshmobile.shell.R

/**
 * 骨架屏 shimmer 加载动画视图（一次性）。
 *
 * 绘制一组占位块（标题条 / 副标题 / 正文行 / 卡片），并在其上叠加一条从左向右扫过的高亮光带，
 * 形成主流的骨架 loading 效果。颜色全部来自 [R.color]（单一来源）：
 * - 骨架底色：`dh_divider`
 * - 扫光高亮：`dh_surface`（白），取半透明后叠加在底色上
 *
 * 用法：
 * ```
 * val shimmer = ShimmerView(context).apply { setOnFinished { ... } }
 * container.addView(shimmer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
 * shimmer.start()
 * ```
 * 动画结束后自身 `visibility = GONE` 并触发 [OnFinished] 回调；视图 detach 时自动取消动画，避免泄漏。
 */
class ShimmerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {

  /** 动画结束回调（完成后可借此切回真实内容）。 */
  fun interface OnFinished {
    fun onFinished()
  }

  private val density = resources.displayMetrics.density
  private fun dp(v: Int): Float = v * density

  // 骨架底色（dh_divider）与扫光高亮（dh_surface 白，半透明）
  private val baseColor = ContextCompat.getColor(context, R.color.dh_divider)
  private val surfaceColor = ContextCompat.getColor(context, R.color.dh_surface)
  private val highlightColor = Color.argb(0x77, Color.red(surfaceColor), Color.green(surfaceColor), Color.blue(surfaceColor))

  private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    color = baseColor
  }
  private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val skeleton = mutableListOf<RectF>()

  private var animator: ValueAnimator? = null
  private var onFinished: OnFinished? = null
  private var startRequested = false

  init {
    addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) = Unit
      override fun onViewDetachedFromWindow(v: View) {
        animator?.cancel()
        animator = null
      }
    })
  }

  /** 注册动画结束回调。 */
  fun setOnFinished(listener: OnFinished): ShimmerView {
    onFinished = listener
    return this
  }

  /** 开始扫光动画（布局完成后调用；若视图尚未完成测量会等首次布局后自动开始）。 */
  fun start() {
    startRequested = true
    if (width > 0 && height > 0) {
      beginAnimation()
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    buildSkeleton(w, h)
    if (startRequested) beginAnimation()
  }

  /** 按视图尺寸构建骨架占位块（标题 / 副标题 / 正文行 / 卡片）。 */
  private fun buildSkeleton(w: Int, h: Int) {
    skeleton.clear()
    if (w <= 0 || h <= 0) return

    val pad = dp(16)
    val left = pad
    val right = w - pad
    val contentW = right - left

    val titleH = dp(26)
    val titleW = contentW * 0.42f
    var y = pad

    // 标题条
    skeleton += RectF(left, y, left + titleW, y + titleH)
    y += titleH + dp(10)

    // 副标题
    val subH = dp(14)
    val subW = contentW * 0.30f
    skeleton += RectF(left, y, left + subW, y + subH)
    y += subH + dp(24)

    // 正文行 x3
    val lineH = dp(14)
    val lineGap = dp(12)
    repeat(3) {
      skeleton += RectF(left, y, right, y + lineH)
      y += lineH + lineGap
    }
    y += dp(18)

    // 卡片 x2
    val cardH = dp(72)
    val cardGap = dp(14)
    repeat(2) {
      if (y + cardH > h - pad) return
      skeleton += RectF(left, y, right, y + cardH)
      y += cardH + cardGap
    }
  }

  private fun beginAnimation() {
    animator?.cancel()
    val w = width.toFloat()
    val span = w * 0.9f
    val gradient = LinearGradient(
      0f, 0f, span, 0f,
      intArrayOf(Color.TRANSPARENT, highlightColor, Color.TRANSPARENT),
      floatArrayOf(0f, 0.5f, 1f),
      Shader.TileMode.CLAMP,
    )
    val matrix = Matrix()
    val vAnim = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 900L
      repeatCount = 1 // 首播 + 1 次重复 = 共 2 次扫光
      addUpdateListener { progress ->
        val offset = (w + span * 2f) * (progress as Float) - span * 2f
        matrix.setTranslate(offset, 0f)
        gradient.setLocalMatrix(matrix)
        highlightPaint.shader = gradient
        invalidate()
      }
      addListener(object : android.animation.Animator.AnimatorListener {
        override fun onAnimationStart(animation: android.animation.Animator) = Unit
        override fun onAnimationEnd(animation: android.animation.Animator) {
          visibility = GONE
          onFinished?.onFinished()
        }
        override fun onAnimationCancel(animation: android.animation.Animator) = Unit
        override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
      })
    }
    animator = vAnim
    vAnim.start()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (skeleton.isEmpty()) return
    // 底色占位块
    for (r in skeleton) canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, basePaint)
    // 扫光高亮（同一组块，用带 shader 的 paint 再画一遍）
    if (highlightPaint.shader != null) {
      for (r in skeleton) canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, highlightPaint)
    }
  }
}
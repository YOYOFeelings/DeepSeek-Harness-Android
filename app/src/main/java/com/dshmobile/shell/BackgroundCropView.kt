package com.dshmobile.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏背景图片裁剪视图：用于「选择/下载/随机图片作为应用背景」时进行裁剪。
 * - 图片 fit 到屏幕（保持宽高比、居中显示），单指平移 + 双指缩放（min=fit，max≈4x）。
 * - 顶栏「取消」「确认」；确认时按屏幕比例从当前变换矩阵中裁出 Bitmap 并通过回调返回。
 * - 构造时传入图片 Uri（content:// 或 file://），在首次布局后用 contentResolver 采样解码加载。
 */
class BackgroundCropView(context: Context, private val imageUri: Uri) : FrameLayout(context) {

  /** 回调接口 */
  interface Callback {
    fun onCropConfirm(cropped: Bitmap)
    fun onCropCancel()
  }

  private var callback: Callback? = null
  fun setCallback(cb: Callback) { callback = cb }

  private val imageView = ImageView(context)
  private val matrix = Matrix()

  private var srcBitmap: Bitmap? = null
  private var fitScale = 1f
  private var loadStarted = false

  private var lastFocusX = 0f
  private var lastFocusY = 0f

  /** 双指缩放上限：fit 屏幕的 4 倍。 */
  private val maxZoom = 4f

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
      if (srcBitmap == null) return false
      var factor = detector.scaleFactor
      val cur = matrix.mapRadius(1f)
      val minS = fitScale
      val maxS = fitScale * maxZoom
      val next = cur * factor
      if (next < minS) factor = minS / cur
      else if (next > maxS) factor = maxS / cur
      matrix.postScale(factor, factor, detector.focusX, detector.focusY)
      imageView.imageMatrix = matrix
      clampMatrix()
      return true
    }
  })

  init {
    setBackgroundColor(Color.argb(204, 0, 0, 0)) // #CC000000 半透明黑，盖住导航等一切 UI
    isClickable = true
    isFocusable = true

    imageView.apply {
      scaleType = ImageView.ScaleType.MATRIX
      setBackgroundColor(Color.BLACK) // fit 之外的留黑区
    }
    addView(imageView, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))

    // 顶栏：半透明黑 + 水平「取消」「确认」
    val topBar = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.argb(128, 0, 0, 0)) // #80000000
      addView(TextView(context).apply {
        text = "取消"
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { callback?.onCropCancel() }
      })
      addView(TextView(context).apply {
        text = "确认"
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { confirmCrop() }
      })
    }
    addView(topBar, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
    ))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    if (!loadStarted && imageView.width > 0 && imageView.height > 0) {
      loadStarted = true
      loadBitmapAsync()
    }
  }

  /** 后台采样解码加载图片（最大尺寸不超过屏幕宽高 2x，防 OOM），成功后回主线程布局。 */
  private fun loadBitmapAsync() {
    Thread {
      val bmp = decodeSampledBitmap()
      post {
        if (bmp == null) {
          // 解码失败：无图可裁，直接取消
          callback?.onCropCancel()
          return@post
        }
        srcBitmap = bmp
        imageView.setImageBitmap(bmp)
        setupInitialMatrix(bmp)
      }
    }.start()
  }

  /** 用 BitmapFactory.Options 采样解码；先读边界再按屏幕尺寸 2x 计算 inSampleSize。 */
  private fun decodeSampledBitmap(): Bitmap? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(imageUri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      val maxDim = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
      var sample = 1
      while (bounds.outWidth / (sample * 2) > maxDim || bounds.outHeight / (sample * 2) > maxDim) {
        sample *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      context.contentResolver.openInputStream(imageUri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
      }
    } catch (_: Throwable) {
      null
    }
  }

  /** 初始矩阵：fit 到 ImageView（保持宽高比），居中显示。 */
  private fun setupInitialMatrix(bmp: Bitmap) {
    val viewW = imageView.width.toFloat()
    val viewH = imageView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return
    fitScale = minOf(viewW / bmp.width, viewH / bmp.height)
    val scaledW = bmp.width * fitScale
    val scaledH = bmp.height * fitScale
    val tx = (viewW - scaledW) / 2f
    val ty = (viewH - scaledH) / 2f
    matrix.reset()
    matrix.postScale(fitScale, fitScale)
    matrix.postTranslate(tx, ty)
    imageView.imageMatrix = matrix
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastFocusX = event.x
        lastFocusY = event.y
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
      }
      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount >= 2) {
          // 双指：缩放由 scaleDetector 处理，同时跟随后指做轻微平移
          lastFocusX = (event.getX(0) + event.getX(1)) / 2f
          lastFocusY = (event.getY(0) + event.getY(1)) / 2f
        } else if (event.pointerCount == 1) {
          val dx = event.x - lastFocusX
          val dy = event.y - lastFocusY
          lastFocusX = event.x
          lastFocusY = event.y
          if (srcBitmap != null && (dx != 0f || dy != 0f)) {
            matrix.postTranslate(dx, dy)
            clampMatrix()
            imageView.imageMatrix = matrix
          }
        }
      }
      MotionEvent.ACTION_POINTER_UP -> {
        lastFocusX = event.getX(0)
        lastFocusY = event.getY(0)
      }
    }
    return true
  }

  /** 平移/缩放后把可见区域限制在图片范围内，避免图片被移出屏幕后无图可裁。 */
  private fun clampMatrix() {
    val src = srcBitmap ?: return
    if (imageView.width <= 0 || imageView.height <= 0) return
    val inv = Matrix()
    if (!matrix.invert(inv)) return
    val rect = RectF(0f, 0f, imageView.width.toFloat(), imageView.height.toFloat())
    inv.mapRect(rect)
    val imgW = src.width.toFloat()
    val imgH = src.height.toFloat()
    val visW = rect.width()
    val visH = rect.height()
    var dx = 0f
    var dy = 0f
    if (visW >= imgW) {
      // 图片宽度小于可视宽度 → 水平居中
      dx = imgW / 2f - (rect.left + rect.right) / 2f
    } else {
      if (rect.left > 0f) dx = -rect.left
      else if (rect.right < imgW) dx = imgW - rect.right
    }
    if (visH >= imgH) {
      // 图片高度小于可视高度 → 垂直居中
      dy = imgH / 2f - (rect.top + rect.bottom) / 2f
    } else {
      if (rect.top > 0f) dy = -rect.top
      else if (rect.bottom < imgH) dy = imgH - rect.bottom
    }
    if (dx != 0f || dy != 0f) {
      val scale = matrix.mapRadius(1f)
      matrix.postTranslate(dx * scale, dy * scale)
    }
  }

  /** 确认：按屏幕比例从当前变换矩阵中裁出 Bitmap 并回调；失败回退全图缩放到屏幕尺寸。 */
  private fun confirmCrop() {
    val src = srcBitmap ?: run {
      callback?.onCropCancel()
      return
    }
    val outW = imageView.width
    val outH = imageView.height
    if (outW <= 0 || outH <= 0) {
      callback?.onCropCancel()
      return
    }
    val inv = Matrix()
    if (!matrix.invert(inv)) {
      // 矩阵奇异 → 回退全图
      callback?.onCropConfirm(fitToScreen(src, outW, outH))
      return
    }
    val screenRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
    val imgRect = RectF()
    inv.mapRect(imgRect, screenRect)

    val left = maxOf(0f, imgRect.left)
    val top = maxOf(0f, imgRect.top)
    val right = minOf(src.width.toFloat(), imgRect.right)
    val bottom = minOf(src.height.toFloat(), imgRect.bottom)
    if (right - left <= 0f || bottom - top <= 0f) {
      callback?.onCropConfirm(fitToScreen(src, outW, outH))
      return
    }
    val availW = right - left
    val availH = bottom - top
    val aspect = outW.toFloat() / outH
    var cropW: Float
    var cropH: Float
    if (availW / availH > aspect) {
      cropH = availH
      cropW = cropH * aspect
    } else {
      cropW = availW
      cropH = cropW / aspect
    }
    if (cropW > availW) { cropW = availW; cropH = cropW / aspect }
    if (cropH > availH) { cropH = availH; cropW = cropH * aspect }

    val rLeft = (left + right - cropW) / 2f
    val rTop = (top + bottom - cropH) / 2f
    val iLeft = rLeft.toInt().coerceIn(0, src.width)
    val iTop = rTop.toInt().coerceIn(0, src.height)
    val iRight = (rLeft + cropW).toInt().coerceIn(0, src.width)
    val iBottom = (rTop + cropH).toInt().coerceIn(0, src.height)
    if (iRight <= iLeft || iBottom <= iTop) {
      callback?.onCropConfirm(fitToScreen(src, outW, outH))
      return
    }

    val cropped = try {
      Bitmap.createBitmap(src, iLeft, iTop, iRight - iLeft, iBottom - iTop, null, false)
    } catch (_: Throwable) {
      null
    }
    if (cropped == null) {
      callback?.onCropConfirm(fitToScreen(src, outW, outH))
      return
    }
    // 统一缩放到屏幕尺寸（保证文件体积与内存可控，且宽高比与屏幕一致）
    val result = if (cropped.width == outW && cropped.height == outH) cropped else {
      val s = try {
        Bitmap.createScaledBitmap(cropped, outW, outH, true)
      } catch (_: Throwable) {
        cropped
      }
      if (s != cropped) cropped.recycle()
      s
    }
    callback?.onCropConfirm(result)
  }

  /** 回退：全图缩放到屏幕尺寸。 */
  private fun fitToScreen(src: Bitmap, outW: Int, outH: Int): Bitmap {
    return try {
      val s = Bitmap.createScaledBitmap(src, outW, outH, true)
      s
    } catch (_: Throwable) {
      src
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    imageView.setImageDrawable(null)
    srcBitmap?.recycle()
    srcBitmap = null
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

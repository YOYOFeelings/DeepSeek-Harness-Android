package com.yoyo.dshmobile.shell.engine

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yoyo.dshmobile.engine.EngineProcess
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp

/**
 * 引擎交互全屏页：WebView 加载 http://127.0.0.1:3080。
 * 外链（非 127.0.0.1）交系统浏览器；加载失败显示重试提示；返回键可后退。
 */
class SessionActivity : AppCompatActivity() {

  private lateinit var webView: WebView
  private lateinit var retryView: TextView

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    webView = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
          val host = request.url.host
          if (host != null && host != EngineProcess.ENGINE_HOST) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
          }
          return false
        }

        override fun onReceivedError(
          view: WebView,
          request: WebResourceRequest,
          error: WebResourceError,
        ) {
          Logs.logEvent(this@SessionActivity, "Engine", "session-load-error")
          retryView.visibility = View.VISIBLE
        }
      }
    }
    root.addView(webView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    retryView = TextView(this).apply {
      visibility = View.GONE
      gravity = Gravity.CENTER
      setTextColor(color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setPadding(dp(24), dp(16), dp(24), dp(16))
      text = getString(R.string.session_load_failed)
      setOnClickListener {
        visibility = View.GONE
        webView.reload()
      }
    }
    root.addView(retryView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    setContentView(root)
    webView.loadUrl("http://${EngineProcess.ENGINE_HOST}:${EngineProcess.ENGINE_PORT}")
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (::webView.isInitialized && webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
  }
}
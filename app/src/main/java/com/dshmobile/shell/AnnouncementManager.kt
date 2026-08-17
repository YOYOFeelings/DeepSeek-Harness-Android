package com.dshmobile.shell

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公告拉取：从 GitHub 仓库固定 raw 地址读取 NOTICE.md（独立公告配置，
 * 与更新说明 ANNOUNCEMENT.md 相互独立），优先官方直连、镜像回退，
 * 拒绝 HTML 垃圾页（加速源可能返回 Cloudflare/404/挑战页 HTML），
 * 缓存最近一次内容。失败时回退缓存；无缓存则返回 null
 * （界面隐藏公告卡，不阻断任何流程）。
 */
object AnnouncementManager {

  const val URL =
    "https://raw.githubusercontent.com/YOYOFeelings/DeepSeek-Harness-Android/main/NOTICE.md"

  private const val PREFS = "dsh_shell"
  private const val KEY_CACHE = "announcement_cache"
  private const val KEY_TS = "announcement_cache_ts"

  /**
   * 后台拉取公告（线程安全，回调在后台线程）。
   * @param onResult (text: String?)：非空=展示内容；null=拉取失败且无缓存。
   */
  fun load(context: Context, onResult: (String?) -> Unit) {
    Thread {
      val cached = cache(context)
      var result: String? = cached
      try {
        // 1) 优先官方直连（raw.githubusercontent.com），避免镜像返回 HTML 挑战页。
        var body = fetch(URL)
        if (body.isBlank()) {
          Logs.logE(context, "announcement", "官方直连无有效公告（失败或返回 HTML），回退镜像源")
          // 2) 镜像回退（加速代理可能返回 Cloudflare/404/挑战页 HTML）。
          // BUG-3 修复：activeMirror=null（自动模式）时 resolved==URL，等同于重试同一地址。
          //   改为优先使用第一个内置加速源（akaere）作为回退。
          val um = UpdateManager.forPrefs(context)
          val fallbackMirror = um.builtinMirrors().firstOrNull { it.id != "official" }
          val resolved = fallbackMirror?.resolve(URL) ?: URL
          body = fetch(resolved)
        }
        if (body.isNotBlank()) {
          // 仅缓存校验通过（非 HTML）的内容。
          result = body
          saveCache(context, body)
        } else {
          Logs.logE(context, "announcement", "官方与镜像均无有效公告，回退缓存")
        }
      } catch (t: Throwable) {
        Logs.logE(context, "announcement", "拉取公告失败，使用缓存", t)
      }
      onResult(result?.takeIf { it.isNotBlank() })
    }.start()
  }

  private fun cache(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, null)

  private fun saveCache(context: Context, text: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_CACHE, text)
      .putLong(KEY_TS, System.currentTimeMillis())
      .apply()
  }

  /** 手动跟随重定向的 GET（镜像源可能跳转），返回响应文本；
   *  失败/非 200/返回内容为 HTML 垃圾页时返回空串。 */
  private fun fetch(url: String): String {
    var current = url
    var conn: HttpURLConnection? = null
    try {
      var redirects = 0
      while (true) {
        conn = URL(current).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", UpdateManager.UA)
        conn.setRequestProperty("Accept", "*/*")
        val code = conn.responseCode
        if (code in 300..399) {
          if (redirects >= 5) return ""
          val loc = conn.getHeaderField("Location") ?: return ""
          conn.disconnect(); conn = null
          current = URL(URL(current), loc).toString()
          redirects++
        } else {
          if (code != 200) return ""
          val text = conn.inputStream.bufferedReader().use { it.readText() }
          return if (looksLikeHtml(text)) "" else text
        }
      }
    } catch (_: Throwable) {
      return ""
    } finally {
      conn?.disconnect()
    }
  }

  /** 判断响应体是否为 HTML/垃圾页（加速源可能返回 Cloudflare/404/挑战页）。 */
  private fun looksLikeHtml(body: String): Boolean {
    val t = body.trim()
    val lower = t.lowercase()
    return lower.startsWith("<!doctype") ||
      lower.startsWith("<html") ||
      lower.startsWith("<!--") ||
      lower.startsWith("<?xml") ||
      (t.startsWith("<") &&
        (lower.contains("<head") || lower.contains("<body") || lower.contains("<title") || lower.contains("</html>")))
  }
}

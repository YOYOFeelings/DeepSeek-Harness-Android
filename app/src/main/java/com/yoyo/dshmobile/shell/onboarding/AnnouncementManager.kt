package com.yoyo.dshmobile.shell.onboarding

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主页公告管理：从仓库独立公告文件 NOTICE.md 读取正文并本地缓存。
 *
 * 设计原则：
 * - 公告独立于「版本更新说明」，二者互不关联（NOTICE.md 与 ANNOUNCEMENT.md 分离）；
 * - 拉取成功 → 写入缓存供离线回退；拉取失败 → 优先返回缓存，无缓存返回 null（由调用方隐藏公告卡）；
 * - 网络只在 IO 线程执行，对外方法绝不抛异常。
 */
object AnnouncementManager {

  /** 仓库独立公告文件地址（集中配置）。 */
  const val NOTICE_URL =
    "https://raw.githubusercontent.com/YOYOFeelings/DeepSeek-Harness-Android/main/NOTICE.md"

  private const val PREFS = "announcement_prefs"
  private const val KEY_CONTENT = "content"

  /** 网络连接/读取超时（ms）：首次进入 1 分钟内拉取不到则回退缓存。 */
  private const val TIMEOUT_MS = 60_000L

  /**
   * 在 IO 线程拉取公告正文。
   * - 成功（HTTP 200 且非空正文）：写入缓存并返回该正文；
   * - 超时/失败：返回缓存内容；无缓存返回 null。
   */
  suspend fun fetchNotice(context: Context): String? = withContext(Dispatchers.IO) {
    val fresh = try {
      val connection = (URL(NOTICE_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS.toInt()
        readTimeout = TIMEOUT_MS.toInt()
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        requestMethod = "GET"
      }
      try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) null
        else connection.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
      } finally {
        connection.disconnect()
      }
    } catch (_: Exception) {
      null
    }
    if (fresh != null) {
      saveCache(context, fresh)
      return@withContext fresh
    }
    loadCache(context)
  }

  private fun saveCache(context: Context, content: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_CONTENT, content)
      .apply()
  }

  private fun loadCache(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONTENT, null)
}
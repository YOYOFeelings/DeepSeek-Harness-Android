package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yoyo.dshmobile.engine.DownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 版本更新管理器：从 GitHub Releases 读取最新版本、下载 APK、经 FileProvider 调系统安装器。
 * 所有对外方法不抛异常；失败返回空值，由调用方（设置页）展示友好文案。
 */
object UpdateManager {

  /** GitHub Releases 最新发行信息地址（集中配置）。 */
  const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/YOYOFeelings/DeepSeek-Harness-Android/releases/latest"

  /** GitHub Releases 列表接口（用于「往期版本」）。 */
  const val RELEASES_URL =
    "https://api.github.com/repos/YOYOFeelings/DeepSeek-Harness-Android/releases"

  /** GitHub 仓库首页（供「关于」页打开）。 */
  const val REPO_HOME_URL = "https://github.com/YOYOFeelings/DeepSeek-Harness-Android"

  /** 远程发行信息。 */
  data class ReleaseInfo(
    val version: String,
    val apkUrl: String?,
    val apkName: String?,
    val publishedAt: String = "",
    /** release body（更新日志），往期版本展开时展示。 */
    val body: String = "",
  )

  /** 网络超时（ms）。 */
  private const val TIMEOUT_MS = 20_000

  /** 在 IO 线程拉取最新发行信息；失败返回 null。 */
  suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
    try {
      val raw = httpGet(LATEST_RELEASE_URL) ?: return@withContext null
      val obj = JSONObject(raw)
      val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: return@withContext null
      var apkUrl: String? = null
      var apkName: String? = null
      val assets = obj.optJSONArray("assets") ?: JSONArray()
      for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        if (name.endsWith(".apk", ignoreCase = true)) {
          apkName = name
          apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
          break
        }
      }
      ReleaseInfo(tag, apkUrl, apkName)
    } catch (_: Throwable) {
      null
    }
  }

  /** 在 IO 线程拉取「往期版本」列表；过滤掉与最新版本相同 tag 的项；失败返回空列表，不抛异常。 */
  suspend fun fetchHistoryReleases(): List<ReleaseInfo> = withContext(Dispatchers.IO) {
    try {
      val latestTag = fetchLatest()?.version
      val raw = httpGet(RELEASES_URL) ?: return@withContext emptyList()
      val arr = JSONArray(raw)
      val result = mutableListOf<ReleaseInfo>()
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
        if (latestTag != null && tag == latestTag) continue
        var apkUrl: String? = null
        var apkName: String? = null
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        for (j in 0 until assets.length()) {
          val asset = assets.optJSONObject(j) ?: continue
          val name = asset.optString("name")
          if (name.endsWith(".apk", ignoreCase = true)) {
            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            if (apkUrl != null) apkName = name
            break
          }
        }
        if (apkUrl != null) {
          result += ReleaseInfo(
            version = tag,
            apkUrl = apkUrl,
            apkName = apkName,
            publishedAt = obj.optString("published_at"),
            body = obj.optString("body").trim(),
          )
        }
      }
      result
    } catch (_: Throwable) {
      emptyList()
    }
  }

  /** 远程版本是否比当前版本更新（忽略 `v` 前缀与 `-beta` 后缀比较主版本号）。 */
  fun isNewer(remote: String?, current: String): Boolean {
    if (remote.isNullOrBlank()) return false
    val norm = { s: String ->
      s.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    }
    val r = norm(remote)
    val c = norm(current)
    val max = maxOf(r.size, c.size)
    for (i in 0 until max) {
      val a = if (i < r.size) r[i] else 0
      val b = if (i < c.size) c[i] else 0
      if (a != b) return a > b
    }
    return false
  }

  /** 在 IO 线程下载 APK 到 cacheDir/apk/，经统一下载源（含所选镜像）；失败返回 null。 */
  suspend fun download(context: Context, info: ReleaseInfo): File? =
    downloadWithProgress(context, info) { _, _ -> }

  /** 经 FileProvider + 系统安装器安装 APK。调用方需保证文件已存在且在 cacheDir/apk/。 */
  fun install(context: Context, file: File): Boolean = try {
    val uri: Uri =
      FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
  } catch (_: Throwable) {
    false
  }

  /** 当前版本名（读取应用信息；失败返回空串）。 */
  fun currentVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
  } catch (_: Throwable) {
    ""
  }

  /** 当前构建号（versionCode；失败返回空串）。 */
  fun currentBuild(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
  } catch (_: Throwable) {
    ""
  }

  /** 拉取最新发行说明（Releases body，作为公告/更新说明）；失败返回 null。 */
  suspend fun fetchReleaseBody(): String? = withContext(Dispatchers.IO) {
    try {
      val obj = JSONObject(httpGet(LATEST_RELEASE_URL) ?: return@withContext null)
      obj.optString("body").trim().takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * 带进度下载 APK 到 cacheDir/apk/，经统一下载源 + 所选镜像；失败返回 null。
   * @param onProgress done: 已下载字节, total: 总长（未知为 0）。
   */
  suspend fun downloadWithProgress(
    context: Context,
    info: ReleaseInfo,
    onProgress: (done: Long, total: Long) -> Unit,
  ): File? = withContext(Dispatchers.IO) {
    val url = info.apkUrl ?: return@withContext null
    val dir = File(context.cacheDir, "apk").apply { mkdirs() }
    val target = File(dir, info.apkName ?: "update.apk")
    val ok = DownloadSource.downloadBlocking(url, target, DownloadSource.mirror(context), onProgress)
    if (ok && target.length() > 0) target else {
      target.delete()
      null
    }
  }

  /** 简单 HTTP GET 文本；非 200/异常返回 null。 */
  private fun httpGet(url: String): String? = try {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
      setRequestProperty("Accept", "application/vnd.github.v3+json")
      requestMethod = "GET"
    }
    try {
      if (connection.responseCode != HttpURLConnection.HTTP_OK) null
      else connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  } catch (_: Throwable) {
    null
  }
}
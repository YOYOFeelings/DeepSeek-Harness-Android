package com.yoyo.dshmobile.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 统一下载源（单一真理源）：App 自身 APK 更新与引擎 runtime 更新，其下载、镜像解析、
 * 进度回调、镜像 id 记忆全部收敛到本对象；其它模块一律调用它而不是各自内联 HttpURLConnection。
 *
 * 镜像表/测速沿用 [EngineMirrors]，本对象统一下载实现与镜像偏好读写（SharedPreferences `engine_prefs`）。
 */
object DownloadSource {

  private const val PREFS = "engine_prefs"
  private const val KEY_MIRROR = "engine_mirror_id"

  // ---- 镜像表（委托 EngineMirrors 保持兼容） ----
  fun all(): List<Mirror> = EngineMirrors.all()

  fun byId(id: String): Mirror? = EngineMirrors.byId(id)

  fun waitAll(): List<Mirror> = all()

  /** 持久化的首选镜像 id（引擎/App 共用 engine_prefs/engine_mirror_id）。 */
  fun preferredMirrorId(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MIRROR, null)

  /** 按持久化 id 解析出的镜像；无则 null（=官方直连/不指定）。 */
  fun mirror(context: Context): Mirror? = preferredMirrorId(context)?.let { byId(it) }

  /** 保存首选镜像 id（引擎/App 共用）。 */
  fun saveMirrorId(context: Context, id: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_MIRROR, id).apply()
  }

  /** 解析 GitHub 家族 URL：命中则前置镜像加速前缀；无镜像或非 GitHub 命中返回原样。 */
  fun resolve(url: String, mirror: Mirror?): String = mirror?.resolve(url) ?: url

  suspend fun speedTest(url: String, mirror: Mirror, timeoutMs: Int = 4000): Long? =
    EngineMirrors.speedTest(url, mirror, timeoutMs)

  /** 带进度通用下载（同步/IO 线程调用）；成功写入 dest 并返回真，否则返回假（dest 可能残留，由调用方清理）。 */
  @JvmName("downloadBlocking")
  fun downloadBlocking(
    url: String,
    dest: File,
    mirror: Mirror? = null,
    onProgress: ((done: Long, total: Long) -> Unit)? = null,
    timeoutMs: Int = 8000,
  ): Boolean {
    val resolved = resolve(url, mirror)
    return runCatching {
      val conn = URL(resolved).openConnection() as HttpURLConnection
      try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        conn.requestMethod = "GET"
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
        val total = conn.contentLengthLong.coerceAtLeast(0L)
        var done = 0L
        conn.inputStream.use { input ->
          dest.outputStream().use { out ->
            val buf = ByteArray(8 * 1024)
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)
              done += n
              onProgress?.invoke(done, total)
            }
          }
        }
        dest.length() > 0
      } finally {
        runCatching { conn.disconnect() }
      }
    }.getOrDefault(false)
  }

  /** 协程版通用下载（自动切 IO 线程）。 */
  suspend fun download(
    url: String,
    dest: File,
    mirror: Mirror? = null,
    onProgress: ((done: Long, total: Long) -> Unit)? = null,
    timeoutMs: Int = 8000,
  ): Boolean = withContext(Dispatchers.IO) {
    downloadBlocking(url, dest, mirror, onProgress, timeoutMs)
  }
}
package com.dshmobile.shell

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 更新源：官方直连或某个国内加速前缀。prefix 为空 = 官方直连。 */
class Mirror(val id: String, val name: String, val prefix: String) {
  /** 解析 GitHub 家族 URL：命中时前置加速前缀（官方直连原样返回）。 */
  fun resolve(url: String): String {
    if (prefix.isEmpty()) return url
    val host = try {
      URL(url).host.lowercase()
    } catch (_: Exception) {
      return url
    }
    return if (host in UpdateManager.GH_HOSTS) prefix + url else url
  }

  override fun toString(): String = name
}

/**
 * Runtime snapshot online update（M2）：
 *  1) 拉取 GitHub Releases 的 MANIFEST.txt（sha256  path  size 每行一个）；
 *  2) 按设备 ABI 匹配 snapshot-{arm64|x86_64}.tar.xz；
 *  3) 经所选更新源（官方直连 / 多个国内加速源，自动测速最快或用户指定 / 自定义）下载；
 *  4) SHA256 校验 → 解压到 usr-new → 原子切换 usr → 写 .snapshot-online 标记
 *     （防止下次启动把在线更新覆盖回内嵌快照）。
 * 引擎重启由 EngineService 看门狗在下次轮询完成。
 */
class UpdateManager(private val context: Context) {

  /** 发布清单 URL（GitHub Releases 的 latest/download/MANIFEST.txt）。 */
  var manifestUrl: String = DEFAULT_MANIFEST_URL

  /** 当前激活更新源（null = 未指定；UI/引导流程按预置或自动测速赋值）。 */
  var activeMirror: Mirror? = null

  /** 用户自定义加速前缀（UI「添加更新源」写入）。 */
  var customPrefix: String? = null

  /** 并发防护：同一时刻只允许一个更新流程在跑，重复触发直接拒绝。 */
  @Volatile private var updateRunning = false

  fun builtinMirrors(): List<Mirror> = BUILTIN_MIRRORS

  fun customMirror(): Mirror? =
    customPrefix?.takeIf { it.isNotEmpty() }?.let { Mirror("custom", "自定义", it) }

  fun allMirrors(): List<Mirror> = builtinMirrors() + listOfNotNull(customMirror())

  fun mirrorById(id: String): Mirror? = allMirrors().firstOrNull { it.id == id }

  /** 设备 ABI → 快照名（snapshot-arm64.tar.xz / snapshot-x86_64.tar.xz）。 */
  fun abiName(): String = when {
    Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) } -> "arm64"
    Build.SUPPORTED_ABIS.any { it.contains("x86_64", ignoreCase = true) } -> "x86_64"
    else -> "unsupported"
  }

  /**
   * 自动测速：对每个可用更新源实测拉取 manifest 的延迟（毫秒），
   * 返回最快且可用（能拉到内容）的源；全部失败返回 null。
   * @param onLatency (mirror, latencyMs|null) 每源回调，可在主线程刷新 UI。
   */
  fun selectFastestMirror(onLatency: (Mirror, Long?) -> Unit = { _, _ -> }): Mirror? =
    speedTestAll(onLatency)

  /** 与 [selectFastestMirror] 等价，提供全量逐源延迟回调（测速弹窗展示用）。 */
  fun speedTestAll(onEach: (Mirror, Long?) -> Unit = { _, _ -> }): Mirror? {
    var fastest: Mirror? = null
    var best = Long.MAX_VALUE
    for (m in allMirrors()) {
      val ms = measureMirror(m)
      onEach(m, ms)
      if (ms != null && ms < best) {
        best = ms
        fastest = m
      }
    }
    if (fastest == null) {
      Logs.logE(context, "update", "自动测速失败：所有更新源均不可用")
    }
    return fastest
  }

  /** 用指定（或当前激活）镜像解析 URL（APK 下载、公告拉取等复用；无前缀源原样返回）。 */
  fun resolveForDownload(url: String, mirror: Mirror? = null): String =
    (mirror ?: activeMirror)?.resolve(url) ?: url

  /** 实测某源拉取 manifest 的延迟；不可用返回 null。 */
  private fun measureMirror(m: Mirror): Long? = try {
    val start = System.currentTimeMillis()
    val body = fetchText(m.resolve(manifestUrl), m, readTimeoutMs = 6000)
    if (body.isBlank()) null else System.currentTimeMillis() - start
  } catch (_: Throwable) {
    null
  }

  /**
   * 运行更新流程（后台线程）。
   * 更新源自动回退：当前激活源拉清单/下载失败时，自动依次尝试其余可用源
   * （解决"某加速源在你设备网络下不可达 → 更新地址下载失败"）。
   * @param onStage (stage, message)：检查/下载/校验/解压/切换/完成/失败。
   * @param onProgress (done, total) 字节级进度，回调在后台线程。
   */
  fun checkAndApply(
    onStage: (stage: String, message: String) -> Unit,
    onProgress: ((done: Long, total: Long) -> Unit)? = null,
  ) {
    if (updateRunning) {
      onStage("失败", "更新已在运行")
      return
    }
    updateRunning = true
    Thread {
      try {
      // 下载记录用变量（跨 try/catch 共享；仅真正发起下载后才写记录）。
      var downloadAttempted = false
      var downloadSizeLabel = ""
      var downloadMirrorName = ""
      try {
        // 候选源顺序：当前激活源优先，其余按内置+自定义顺序回退。
        val mirrors = candidateMirrors()

        onStage("检查", "检查更新…")
        var manifestBody: String? = null
        var bodyMirror: Mirror = mirrors.first()
        for (m in mirrors) {
          try {
            manifestBody = fetchText(m.resolve(manifestUrl), m, readTimeoutMs = 20_000)
            bodyMirror = m
            break
          } catch (_: Throwable) {
            onStage("检查", "更新源 " + m.name + " 不可达，尝试下一个…")
          }
        }
        if (manifestBody.isNullOrBlank()) {
          throw IllegalStateException("所有更新源均不可达，请检查网络或更换更新源")
        }
        onStage("检查", "解析发布清单…")
        val (sha, filename, size) = findSnapshot(manifestBody)
          ?: throw IllegalStateException("发布清单中无 ${abiName()} 架构快照（当前设备 " +
            Build.SUPPORTED_ABIS.joinToString() + "）")

        onStage("检查", "发现 " + sizeString(size) + " 快照" + mirrorTag())
        onStage("下载", "下载快照（" + sizeString(size) + "）…")
        val tmp = File(context.filesDir, "update.tar.xz")
        val downloadUrl = downloadBase(bodyMirror) + filename
        var downloaded = false
        // 下载记录信息（成功记录在「完成」阶段写入；失败在 catch 中写入）。
        downloadAttempted = true
        downloadSizeLabel = sizeString(size)
        for (m in mirrors) {
          try {
            download(m.resolve(downloadUrl), m, tmp, size) { done, total ->
              onProgress?.invoke(done, total) ?: Unit
            }
            downloaded = true
            downloadMirrorName = sourceLabel(m)
            break
          } catch (_: Throwable) {
            tmp.delete()
            onStage("下载", "更新源 " + m.name + " 下载失败，尝试下一个…")
          }
        }
        if (!downloaded) throw IllegalStateException("所有更新源下载均失败")

        onStage("校验", "SHA256 校验…")
        val actual = sha256(tmp)
        if (!actual.equals(sha, ignoreCase = true)) {
          tmp.delete()
          throw IllegalStateException("SHA256 不匹配: " + actual.take(12) + "…")
        }
        onStage("校验", "SHA256 通过")

        onStage("解压", "解压新快照…")
        val stage = File(context.filesDir, "update-stage")
        deleteRecursively(stage)
        SnapshotExtractor.extract(
          tmp.inputStream(), size, stage, { done, total -> onProgress?.invoke(done, total) ?: Unit },
        )
        tmp.delete()
        val newUsr = File(stage, "usr")
        if (!File(newUsr, "bin/node").exists()) throw IllegalStateException("新快照缺少 node")
        // 切换前补设新 usr 可执行权限 + Android exec 属性（幂等），
        // 防止不同设备/不同快照下新 usr/bin 无 exec 位 → 更新后无法运行。
        RuntimePermissions.ensureExecutable(newUsr)

        onStage("切换", "切换运行时…")
        val usr = File(context.filesDir, "usr")
        val old = File(context.filesDir, "usr-old")
        deleteRecursively(old)
        if (usr.exists()) usr.renameTo(old)
        if (!newUsr.renameTo(usr)) throw IllegalStateException("切换失败")
        deleteRecursively(stage)
        deleteRecursively(old)

        // Kill the old engine process: the EngineService watchdog restarts
        // it from the NEW usr within seconds.
        try {
          Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "bin.js")).waitFor()
        } catch (_: Throwable) {
        }
        // 在线更新标记：优先级高于内嵌指纹，防止下次启动误判"快照过期"而
        // 重解压 assets 快照，把在线更新覆盖回出厂。
        File(context.filesDir, ".snapshot-online").writeText(sha)
        DownloadHistory.add(
          context,
          DownloadHistory.Record(
            time = System.currentTimeMillis(),
            name = "运行时快照（" + abiName() + "）",
            size = downloadSizeLabel,
            source = downloadMirrorName,
            status = "成功",
            detail = "SHA256 校验通过并切换运行时",
          ),
        )
        onStage("完成", "更新完成，引擎将自动重启")
      } catch (t: Throwable) {
        Logs.logE(context, "update", "运行时更新失败", t)
        if (downloadAttempted) {
          DownloadHistory.add(
            context,
            DownloadHistory.Record(
              time = System.currentTimeMillis(),
              name = "运行时快照（" + abiName() + "）",
              size = downloadSizeLabel,
              source = downloadMirrorName,
              status = "失败",
              detail = t.message ?: t.javaClass.simpleName,
            ),
          )
        }
        onStage("失败", "更新失败：" + (t.message ?: t.javaClass.simpleName))
      }
      } finally {
        updateRunning = false
      }
    }.start()
  }

  /** 候选源顺序：当前激活源优先，其余按内置+自定义顺序（用于自动回退）。 */
  private fun candidateMirrors(): List<Mirror> {
    val all = allMirrors()
    if (all.isEmpty()) return listOf(Mirror("official", "官方直连", ""))
    return if (activeMirror == null) all
    else listOf(activeMirror!!) + all.filter { it.id != activeMirror!!.id }
  }

  /** MANIFEST.txt 每行 "sha256  path  size"：找与设备 ABI 匹配的 snapshot 条目。 */
  private fun findSnapshot(body: String): Triple<String, String, Long>? {
    val abi = abiName()
    for (line in body.lineSequence()) {
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size < 3) continue
      val sha = parts[0]
      val path = parts[1]
      val size = parts[2].toLongOrNull() ?: 0L
      val filename = path.substringAfterLast('/')
      if (filename == "snapshot-$abi.tar.xz") return Triple(sha, filename, size)
    }
    return null
  }

  /** manifest 同目录（release download 目录）作为快照下载基址，保留镜像前缀。 */
  private fun downloadBase(m: Mirror): String {
    val base = m.resolve(manifestUrl)
    return base.substringBeforeLast('/') + "/"
  }

  private fun mirrorTag(): String {
    val m = activeMirror
    val p = m?.prefix?.takeIf { it.isNotEmpty() }
    return if (p == null) "" else " [" + m!!.name + " 加速]"
  }

  /** 下载记录用源标签：官方直连显示名字，加速源标注「加速」。 */
  private fun sourceLabel(m: Mirror): String {
    val p = m.prefix.takeIf { it.isNotEmpty() }
    return if (p == null) m.name else m.name + " 加速"
  }

  private fun sizeString(size: Long): String =
    if (size > 0) "%.1f MB".format(size / 1024.0 / 1024.0) else "?"

  /** 用指定（或当前激活）镜像解析 URL；无前缀源原样返回。 */
  private fun resolve(url: String, mirror: Mirror? = null): String {
    val m = mirror ?: activeMirror
    return m?.resolve(url) ?: url
  }

  /** 手动跟随重定向的 HTTP 连接：重定向目标命中 GitHub 域名时重新加镜像前缀，
   *  保证大文件下载全程走加速源（严格网络下 release-assets 直连会被墙）。 */
  private fun open(url: String, mirror: Mirror?, readTimeoutMs: Int): HttpURLConnection {
    var current = url
    var conn = openRaw(current, readTimeoutMs)
    for (i in 0 until 6) {
      val code = conn.responseCode
      if (code in 300..399) {
        val loc = conn.getHeaderField("Location") ?: return conn
        conn.disconnect()
        current = resolve(URL(URL(current), loc).toString(), mirror)
        conn = openRaw(current, readTimeoutMs)
      } else {
        return conn
      }
    }
    return conn
  }

  private fun openRaw(url: String, readTimeoutMs: Int): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = false
    conn.connectTimeout = 10_000
    conn.readTimeout = readTimeoutMs
    conn.setRequestProperty("User-Agent", UA)
    conn.setRequestProperty("Accept", "*/*")
    return conn
  }

  private fun fetchText(url: String, mirror: Mirror?, readTimeoutMs: Int): String {
    val conn = open(url, mirror, readTimeoutMs)
    try {
      val code = conn.responseCode
      if (code != 200) throw IllegalStateException("HTTP $code")
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  private fun download(
    url: String,
    mirror: Mirror?,
    dest: File,
    expectedSize: Long,
    onProgress: (Long, Long) -> Unit,
  ) {
    val conn = open(url, mirror, 60_000)
    try {
      val code = conn.responseCode
      if (code != 200) throw IllegalStateException("下载 HTTP $code")
      val total = if (expectedSize > 0) expectedSize else conn.contentLengthLong
      conn.inputStream.use { input ->
        dest.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          var last = 0L
          var n = input.read(buf)
          while (n >= 0) {
            out.write(buf, 0, n)
            done += n
            if (done - last >= 256 * 1024) {
              onProgress(done, total)
              last = done
            }
            n = input.read(buf)
          }
          onProgress(done, total)
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      var n = input.read(buf)
      while (n >= 0) {
        digest.update(buf, 0, n)
        n = input.read(buf)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun deleteRecursively(file: File) {
    if (!file.exists()) return
    file.walkBottomUp().forEach { it.delete() }
  }

  companion object {
    /** 生产默认：GitHub Releases 的 MANIFEST.txt（官方发布清单）。 */
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/YOYOFeelings/DeepSeek-Harness-Android/releases/latest/download/MANIFEST.txt"

    /** 默认激活更新源 id（持久化配置缺省时使用 akaere）。 */
    const val DEFAULT_MIRROR_ID = "akaere"

    const val UA = "Mozilla/5.0 (Linux; Android) dsh-mobile"

    /** GitHub 家族域名：命中时加加速前缀。 */
    val GH_HOSTS = setOf(
      "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
      "codeload.github.com", "api.github.com", "release-assets.githubusercontent.com",
      "gist.githubusercontent.com", "user-images.githubusercontent.com",
    )

    /** 内置更新源（官方直连 + 国内加速）。akaere 作为默认源（实测更稳）；
     *  gh-proxy 等保留为可选项；选源用自动测速，不可用的源会被跳过。 */
    val BUILTIN_MIRRORS = listOf(
      Mirror("akaere", "cdn.akaere.online", "https://cdn.akaere.online/"),
      Mirror("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/"),
      Mirror("official", "官方直连", ""),
      Mirror("ghproxy.net", "ghproxy.net", "https://ghproxy.net/"),
      Mirror("ghproxy.cn", "ghproxy.cn", "https://ghproxy.cn/"),
      Mirror("ghfast", "ghfast.top", "https://ghfast.top/"),
    )

    /** 按持久化配置构造 UpdateManager：读取 custom_source 与 active_mirror_id（默认 akaere），
     *  并一次性迁移旧默认 gh-proxy → akaere（幂等，显式选择过其他源的用户不受影响）。 */
    fun forPrefs(context: Context): UpdateManager {
      val prefs = context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
      val customSource = prefs.getString("custom_source", null)
      var activeMirrorId = prefs.getString("active_mirror_id", DEFAULT_MIRROR_ID) ?: DEFAULT_MIRROR_ID
      if (activeMirrorId == "gh-proxy" && !prefs.getBoolean("migrated_old_default", false)) {
        activeMirrorId = DEFAULT_MIRROR_ID
        prefs.edit()
          .putString("active_mirror_id", activeMirrorId)
          .putBoolean("migrated_old_default", true)
          .commit()
      }
      return UpdateManager(context).apply {
        customPrefix = customSource
        activeMirror = mirrorById(activeMirrorId) ?: mirrorById(DEFAULT_MIRROR_ID)
      }
    }
  }
}

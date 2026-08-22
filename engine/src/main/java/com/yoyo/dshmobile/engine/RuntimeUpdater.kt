package com.yoyo.dshmobile.engine

import android.content.Context
import android.os.Build
import com.yoyo.dshmobile.shell.log.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * 引擎在线运行时更新：manifest 拉取 -> 下载 -> SHA-256 校验 -> 阶段切换 -> 看门狗重启。
 * 全程 runCatching 包裹，失败/无网络优雅回滚，不中断当前引擎。
 */
data class Manifest(val version: String, val url: String, val sha256: String)

object RuntimeUpdater {

  // manifest 服务地址（GitHub Release 的 MANIFEST.txt，每行文本：sha256  path  size）。
  // 官方源仅含 x86_64；arm64 设备匹配不到时自动落到回退源（双 ABI 全量）。
  const val DEFAULT_MANIFEST_URL = "https://github.com/YOYOFeelings/DeepSeek-Harness-Android/releases/latest/download/MANIFEST.txt"
  const val MANIFEST_FALLBACK_URL = "https://github.com/kelai141/dsh-mobile-apk/releases/download/v0.10.8/MANIFEST.txt"

  private const val DOWNLOAD_NAME = "rootfs-new.tar.xz"

  private const val MIN_SIZE = 1L shl 20  // 1 MiB

  private const val DONE_MARKER = ".extracted"

  /** 设备 ABI → 快照名（snapshot-arm64.tar.xz / snapshot-x86_64.tar.xz）。 */
  fun abiName(): String = when {
    Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) } -> "arm64"
    Build.SUPPORTED_ABIS.any { it.contains("x86_64", ignoreCase = true) } -> "x86_64"
    else -> "unsupported"
  }

  /** 从 MANIFEST.txt 正文中按当前 ABI 匹配 snapshot 行，返回 (sha256, path, size)。 */
  private fun findSnapshot(body: String): Triple<String, String, Long>? {
    val abi = abiName()
    for (line in body.lineSequence()) {
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size >= 3) {
        val sha = parts[0]
        val path = parts[1]
        val size = parts[2].toLongOrNull() ?: 0L
        if (path.substringAfterLast('/') == "snapshot-$abi.tar.xz") {
          return Triple(sha, path, size)
        }
      }
    }
    return null
  }

  /** 拉取并解析远端 manifest（主源 → 回退源），失败/格式错误返回 null。 */
  fun checkForUpdate(
    context: Context,
    mirror: Mirror = EngineMirrors.byId("official") ?: EngineMirrors.all().first(),
  ): Manifest? {
    val candidates = listOf(DEFAULT_MANIFEST_URL, MANIFEST_FALLBACK_URL)
    for (manifestUrl in candidates) {
      // 与 download 一致：manifest 拉取也走所选镜像，规避 github 被墙时直连超时/404
      val resolvedUrl = mirror.resolve(manifestUrl)
      val body = runCatching {
        val conn = URL(resolvedUrl).openConnection() as HttpURLConnection
        try {
          conn.connectTimeout = 5000
          conn.readTimeout = 5000
          conn.requestMethod = "GET"
          if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            Logs.logEvent(context, "Engine", "update-manifest-http=${conn.responseCode}")
            return@runCatching null
          }
          conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
          runCatching { conn.disconnect() }
        }
      }.getOrElse { t ->
        Logs.logEvent(context, "Engine", "check-update-fail", t)
        null
      }
      if (body == null) continue
      val snapshot = findSnapshot(body) ?: continue
      // 下载地址用 manifest path 的 basename（命中文件实际位于 release 根目录，
      // manifest 里的 path 可能带子目录前缀，直接拼接会 404）。
      val filename = snapshot.second.substringAfterLast('/')
      val downloadUrl = resolvedUrl.substringBeforeLast('/') + "/" + filename
      return Manifest(version = "", url = downloadUrl, sha256 = snapshot.first)
    }
    Logs.logEvent(context, "Engine", "update-manifest-unreachable")
    return null
  }

  /**
   * 下载新 rootfs 到 cacheDir，边下边回调进度，下载完做 SHA-256 校验；
   * 不匹配删除文件返回 null。可选镜像（mirror）会把 GitHub 直连地址改写为镜像前缀地址，
   * 规避国内直连 GitHub 超时。
   */
  fun download(
    context: Context,
    manifest: Manifest,
    onProgress: (done: Long, total: Long) -> Unit,
    mirror: Mirror = EngineMirrors.byId("official") ?: EngineMirrors.all().first(),
  ): File? {
    val target = File(context.cacheDir, DOWNLOAD_NAME)
    val temp = File(context.cacheDir, DOWNLOAD_NAME + ".tmp")
    return try {
      // 实际传输统一走 DownloadSource（镜像解析 + 进度 + 断连处理），下载后仍做 SHA-256 校验
      val ok = DownloadSource.downloadBlocking(manifest.url, temp, mirror, onProgress, 8000)
      if (!ok) {
        Logs.logEvent(context, "Engine", "update-download-fail-http")
        temp.delete()
        null
      } else if (sha256(temp) == manifest.sha256) {
        val size = temp.length()
        if (size < MIN_SIZE) {
          temp.delete()
          Logs.logEvent(context, "Engine", "update-download-too-small size=$size")
          null
        } else {
          if (target.exists()) target.delete()
          temp.renameTo(target)
          Logs.logEvent(context, "Engine", "update-download-ok size=$size")
          target
        }
      } else {
        temp.delete()
        Logs.logEvent(context, "Engine", "update-sha-mismatch")
        null
      }
    } catch (t: Throwable) {
      runCatching { temp.delete() }
      Logs.logEvent(context, "Engine", "update-download-fail", t)
      target.delete()
      null
    }
  }

  /**
   * 应用新 rootfs：把已下载校验的 rootfs-new.tar.xz 阶段解压到 rootfs-new ->
   * 停引擎 -> 原子 rename 切换 -> 写版本标记 -> 重启引擎。任一步失败回滚（保留旧 rootfs）。
   */
  suspend fun apply(
    context: Context,
    manifest: Manifest,
    onPhase: (phase: String, percent: Int?) -> Unit = { _, _ -> },
  ) {
    withContext(Dispatchers.IO) {
      val fsRoot = EngineRootfs.rootfsDir(context)
      val newDir = File(context.filesDir, "rootfs-new")
      val oldDir = File(context.filesDir, "rootfs-old")
      val archive = File(context.cacheDir, DOWNLOAD_NAME)
      val ok = runCatching {
        // 下载的更新包必须存在且 SHA-256 与之 manifest 一致，否则放弃
        onPhase("校验完整性", null)
        if (!archive.exists()) error("update archive missing")
        if (sha256(archive) != manifest.sha256) error("update sha mismatch")

        // 阶段解压到 rootfs-new（拒绝 .. / 符号链接逃逸）
        if (newDir.exists()) newDir.deleteRecursively()
        extractTarXz(archive, newDir) { pct -> onPhase("正在解压", pct) }
        File(newDir, DONE_MARKER).writeText(manifest.version)

        onPhase("停引擎", null)
        EngineService.stop(context)
        // 等待旧引擎释放句柄/端口
        try {
          Thread.sleep(600)
        } catch (_: InterruptedException) {
        }

        onPhase("切换内核", null)
        var preserved = false
        if (fsRoot.exists()) {
          if (!fsRoot.renameTo(oldDir)) error("rename rootfs->old failed")
          preserved = true
        }
        if (!newDir.renameTo(fsRoot)) {
          // 回滚：恢复旧 rootfs
          if (preserved && oldDir.exists()) oldDir.renameTo(fsRoot)
          error("rename new->rootfs failed")
        }
        runCatching { oldDir.deleteRecursively() }

        onPhase("重启引擎", null)
        EngineService.start(context)
        Logs.logEvent(context, "Engine", "update-apply-ok version=${manifest.version}")
      }.isSuccess
      if (!ok) {
        runCatching {
          if (newDir.exists()) newDir.deleteRecursively()
          File(newDir, DONE_MARKER).delete()
        }
        Logs.logEvent(context, "Engine", "update-fail")
      }
      runCatching { archive.delete() }
    }
  }

  /**
   * 解压 rootfs-new.tar.xz 到 destDir：XZ 解压流 -> Tar 顺序写出。
   * 路径安全：目标文件 canonicalPath 必须以 destDir canonicalPath 为前缀，拒绝 `..`/符号链接逃逸。
   * 修复点（v0.12 起）：
   *  - 保留归档内符号链接（先删后建，防悬空重解压冲突）——此前直接跳过导致 rootfs 缺链接；
   *  - 对 usr/bin 与 usr/lib 下文件补设 exec 位，并打 `security.android.exec` 属性（Android 15+ 必需）；
   *  - 解压后断言关键文件（node / bin.js / termux-exec preload）存在，缺失视为解压失败（走既有回滚）。
   */
  private fun extractTarXz(archive: File, destDir: File, onProgress: (Int) -> Unit = {}) {
    val base = destDir.canonicalPath + File.separator
    val totalBytes = archive.length().coerceAtLeast(1)
    val raw = archive.inputStream().buffered()
    // 包一层计数流：按「已读 XZ 压缩字节 / 包大小」给出解压进度
    val counting = object : java.io.FilterInputStream(raw) {
      var done = 0L
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) done += n
        return n
      }
    }
    try {
      XZCompressorInputStream(counting).use { xz ->
        TarArchiveInputStream(xz).use { tar ->
          var entry = tar.nextTarEntry
          while (entry != null) {
            val out = File(destDir, entry.name).canonicalFile
            if (!out.path.startsWith(base)) error("unsafe path in archive: ${entry.name}")
            when {
              entry.isSymbolicLink || entry.isLink -> {
                // 保留符号链接：先删后建，避免重解压时悬空冲突
                out.parentFile?.mkdirs()
                Files.deleteIfExists(out.toPath())
                Files.createSymbolicLink(out.toPath(), Paths.get(entry.linkName))
              }
              entry.isDirectory -> out.mkdirs()
              else -> {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { os ->
                  val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                  while (true) {
                    val read = tar.read(buf)
                    if (read < 0) break
                    os.write(buf, 0, read)
                  }
                }
                // 补设 exec 位：归档自带 exec 位，或位于 usr/bin / usr/lib（node 依赖链需可执行）
                val isExec = (entry.mode and 0x49) != 0 ||
                  entry.name.startsWith("usr/bin/") || entry.name.startsWith("usr/lib/")
                if (isExec) out.setExecutable(true, true)
              }
            }
            onProgress(((counting.done * 100) / totalBytes).toInt().coerceIn(0, 100))
            entry = tar.nextTarEntry
          }
        }
      }
      // 解压完成：补设 exec 位 + 打 Android exec 属性（幂等，失败 silent）
      RuntimePermissions.ensureExecutable(File(destDir, "usr"))
      // 符号链接实体化：规避 app data FUSE 对符号链接的读取限制，确保 linker 读到真实 .so（幂等，失败 silent）
      RuntimePermissions.materializeSymlinks(File(destDir, "usr"))
      // 真文件化 node 精确依赖库：兜底「链接不可读/文件缺失」，确保 linker 找到 libz.so.1 等（幂等，失败 silent）
      RuntimePermissions.ensureNodeLibsReal(File(destDir, "usr"))
      // 断言关键文件存在（缺失视为解压失败，抛异常走既有回滚）
      val node = File(destDir, "usr/bin/node")
      val binJs = File(destDir, "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
      val preload = RuntimePermissions.resolveTermuxExecPreload(File(destDir, "usr"))
      if (!node.exists() || node.length() == 0L) error("extract-assert: usr/bin/node missing")
      if (!binJs.exists() || binJs.length() == 0L) error("extract-assert: dsh bin.js missing")
      if (preload == null || preload.length() == 0L) error("extract-assert: termux-exec preload missing")
    } finally {
      runCatching { raw.close() }
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) sb.append(String.format("%02x", b))
    return sb.toString()
  }
}
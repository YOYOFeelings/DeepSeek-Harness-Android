package com.dshmobile.shell

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Owns the embedded Termux environment snapshot: first-launch extraction into
 * filesDir/usr and the dsh engine process lifecycle (PATH/LD_LIBRARY_PATH/HOME
 * injected explicitly — the snapshot is self-sufficient, no Termux app needed).
 */
class EngineManager(private val context: Context, private val pickToken: String? = null) {

  val usrDir = File(context.filesDir, "usr")
  val homeDir = File(context.filesDir, "home")

  /** 私有 DSH_HOME/.dsh 目录。 */
  val dshDir: File get() = File(homeDir, ".dsh")

  /**
   * 公共导出仓库：/storage/emulated/0/Documents/dshdata。
   * 仅存放用户主动导出的 session zip（exports/）与 .nomedia 防扫描标记；
   * 运行时用户数据全部回私有 app data（files/home/.dsh）。
   */
  val dshDataDir: File
    get() {
      val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        ?: File(context.filesDir, "dshdata-fallback")
      return File(publicDocs, "dshdata")
    }
  private val nodeBin = File(usrDir, "bin/node")
  private val dshBin = File(usrDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
  /** ensurePrivateDshData() 同步锁：防止 MainActivity 引导线程与 EngineService 看门狗线程并发写 DSH_HOME 标记文件。 */
  private val dshDataLock = Any()
  private var engineProcess: Process? = null

  val engineReady: Boolean get() = nodeBin.exists()

  /** 内嵌快照指纹（assets/snapshot.sha256，由 build-release.ps1 生成）。 */
  private fun bundledFingerprint(): String = try {
    context.assets.open("snapshot.sha256").bufferedReader().use { it.readText().trim() }
  } catch (_: Exception) {
    ""
  }

  private fun fingerprintFile(): File = File(context.filesDir, ".snapshot-fingerprint")

  /**
   * 快照是否已解压且与内嵌版本一致：node 存在 + 指纹匹配。
   * 升级安装（v0.10.5→v0.10.6 教训）：engineReady 只查 node 存在，
   * 升级后快照不重解压 → 旧插件继续跑（注入守卫 bug 等修复不生效）。
   */
  fun snapshotFresh(): Boolean {
    if (!nodeBin.exists()) return false
    // 在线更新标记：优先级高于内嵌指纹。在线更新后若回退到内嵌指纹比较，
    // 升级装包指纹不匹配 → 下次启动重解压 assets 快照，把在线更新覆盖回出厂
    // （引擎"更新完无法正常启动"的直接原因）。见 UpdateManager 写标记处。
    val online = File(context.filesDir, ".snapshot-online").takeIf { it.exists() }?.readText()
    if (online != null && online.isNotBlank()) return true
    val fp = bundledFingerprint()
    if (fp.isEmpty()) return true // 无指纹文件（旧构建）不强制重解压
    return fingerprintFile().exists() && fingerprintFile().readText().trim() == fp
  }

  /**
   * node 二进制的 ELF 架构是否与设备 ABI 匹配。
   * 内嵌快照可能打进错误架构（如 ARM64 设备装上 x86_64 快照 → 引擎 ELF
   * "is for EM_X86_64 (62) instead of EM_AARCH64 (183)" 直接无法启动）。
   * 返回 true = 匹配或无法判定（不阻断）；false = 架构不符，必须在线下载
   * 匹配架构快照（UpdateManager 按设备 ABI 选择 snapshot-{arm64|x86_64}）。
   */
  fun nodeArchMatchesDevice(): Boolean {
    if (!nodeBin.exists()) return true // 未解压时视为未知，不阻断
    val machine = readElfMachine(nodeBin) ?: return true
    val arm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }
    val x86_64 = Build.SUPPORTED_ABIS.any { it.contains("x86_64", ignoreCase = true) }
    return when (machine) {
      183 -> arm64 // EM_AARCH64
      62 -> x86_64 // EM_X86_64
      else -> true // 未知架构不阻断（不做误判）
    }
  }

  /** 当前 usr/bin/node 的 ELF 架构名（"arm64"/"x86_64"/"未知"）；无 node/非 ELF 返回 "未知"。 */
  fun embeddedNodeArchLabel(): String {
    val machine = readElfMachine(nodeBin) ?: return "未知"
    return when (machine) {
      183 -> "arm64"
      62 -> "x86_64"
      else -> "机器码$machine"
    }
  }

  /** 读取 ELF 文件头 e_machine 字段（偏移 0x12，2 字节小端）。非 ELF / 读取失败返回 null。 */
  private fun readElfMachine(file: File): Int? {
    try {
      file.inputStream().use { input ->
        val magic = ByteArray(4)
        if (input.read(magic) != 4) return null
        if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
          magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
        ) {
          return null
        }
        val skip = 0x12 - 4
        if (input.skip(skip.toLong()) < skip) return null
        val lo = input.read()
        val hi = input.read()
        if (lo < 0 || hi < 0) return null
        return (hi shl 8) or lo
      }
    } catch (_: Throwable) {
      return null
    }
  }

  /**
   * 升级/快照变化：备份用户数据 → 全量重解压内嵌快照 → 恢复用户数据 → 写指纹。
   * 快照剥离了 sessions/storages/attachments/凭据/settings（make-snapshot.sh），
   * 直接重解压会"丢失"这些私有数据，必须先备份后恢复；profiles 出厂配置
   * （cordis*.yml/node_modules）以快照为准（手动 patch 需在新版基础上重打）。
   * 任何失败：恢复备份数据，保留旧运行时（下次启动重试）。
   */
  fun refreshSnapshot(onProgress: (Long, Long) -> Unit): Boolean {
    val backup = File(context.filesDir, ".dsh-backup")
    val dsh = File(homeDir, ".dsh")
    try {
      if (dsh.exists()) {
        backup.deleteRecursively()
        dsh.copyRecursively(backup)
      }
      val ok = extractSnapshot(onProgress)
      if (!ok) {
        restoreUserData(backup, dsh)
        Log.e(TAG, "snapshot refresh: extract failed, kept old runtime")
        return false
      }
      restoreUserData(backup, dsh)
      backup.deleteRecursively()
      fingerprintFile().writeText(bundledFingerprint())
      Log.i(TAG, "snapshot refreshed (fingerprint " + bundledFingerprint().take(12) + ")")
      return true
    } catch (t: Throwable) {
      restoreUserData(backup, dsh)
      Log.e(TAG, "snapshot refresh failed; kept old runtime", t)
      return false
    }
  }

  /** 恢复快照剥离的用户数据目录/文件（从备份拷贝回私有 .dsh）。 */
  private fun restoreUserData(backup: File, dsh: File) {
    if (!backup.exists()) return
    for (name in listOf(
      "sessions", "storages", "attachments", "profiles",
      ".credentials.yaml", "settings.yaml", ".anonymous-user-id", ".private-layout",
    )) {
      val src = File(backup, name)
      if (!src.exists()) continue
      val dst = File(dsh, name)
      if (dst.exists()) dst.deleteRecursively()
      src.copyRecursively(dst)
    }
  }

  /** 进程级启动守卫（MainActivity 与 EngineService 各自 new EngineManager，
   *  实例字段互不可见——双启动竞态必须用 companion 级 CAS）。 */
  private val starting: Boolean
    get() = STARTING.get()

  /**
   * Extract the bundled snapshot archive into filesDir. Runs on any thread;
   * callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractSnapshot(onProgress: (Long, Long) -> Unit): Boolean {
    return try {
      val fd = context.assets.openFd("snapshot.tar.xz")
      SnapshotExtractor.extract(context.assets.open("snapshot.tar.xz"), fd.length, context.filesDir, onProgress)
      homeDir.mkdirs()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "snapshot extract failed", t)
      false
    }
  }

  /**
   * 确保私有 DSH_HOME 数据布局就绪（幂等，后台线程调用）。
   *
   * v0.10.5 起运行时数据全部回私有 app data；Documents/dshdata 只保留为
   * 用户主动导出仓库（exports/ + .nomedia）。本方法负责：
   *  - 首次/干净安装：直接建立私有 .dsh 布局与公共导出仓库；
   *  - 检测到 v0.10.4 及更早的公共迁移布局（.migrated-from 或私有 symlink）：
   *    执行反向迁移，把 sessions/storages/attachments/profiles/settings.yaml
   *    复制回私有实体，并清理公共旧数据；
   *  - 任何失败都保留公共数据，引擎仍以私有 DSH_HOME 启动，下次重试。
   *
   * DSH_HOME 永远保持私有域：profiles/node_modules 的 flat-fallback symlink
   * 机制依赖 app 私有域（公共 FUSE 禁 symlink），绝不能整体迁移 DSH_HOME。
   */
  fun ensurePrivateDshData(): File {
    synchronized(dshDataLock) {
      doEnsurePrivateDshData()
    }
    return File(homeDir, ".dsh")
  }

  /** 内部实现（须在 dshDataLock 保护下调用）。 */
  private fun doEnsurePrivateDshData(): File {
    val dshData = dshDataDir
    val privateDsh = File(homeDir, ".dsh")
    privateDsh.mkdirs()
    // Web 控制页保存的插件/配置在 profiles 下：保证目录存在且可写（写读测试），
    // 失败给出可操作诊断——否则 Web 保存不生效。
    val profiles = File(privateDsh, "profiles")
    try {
      profiles.mkdirs()
      val test = File(profiles, ".test-write")
      test.writeText("test")
      test.delete()
    } catch (t: Throwable) {
      Log.e(TAG, "profiles 目录不可写（Web 保存插件/配置会失败）: " + t.message)
      Logs.append(Logs.engineLog(context), "[警告] profiles 目录不可写，Web 控制页保存的插件/配置可能丢失: " + t.message)
      Logs.logE(context, TAG, "profiles 目录不可写", t)
    }
    val privateMarker = File(privateDsh, ".private-layout")
    if (privateMarker.exists()) {
      ensurePublicExportRepo(dshData)
      return privateDsh
    }
    if (isLegacyPublicLayout(dshData, privateDsh)) {
      try {
        reverseMigrate(dshData, privateDsh)
        privateMarker.writeText("private")
        ensurePublicExportRepo(dshData)
        Log.i(TAG, "dshdata reverse migration done -> " + privateDsh.absolutePath)
      } catch (t: Throwable) {
        // 迁移失败不阻断启动：DSH_HOME 仍私有，引擎可用，下次再试。
        Log.e(TAG, "dshdata reverse migration failed; keeping public data", t)
      }
    } else {
      // 干净安装或已经是私有布局：直接标记，无需迁移。
      try {
        privateMarker.writeText("private")
      } catch (t: Throwable) {
        Log.w(TAG, "private layout marker write failed", t)
      }
      ensurePublicExportRepo(dshData)
    }
    return privateDsh
  }

  /**
   * 写入/同步出厂 dsh 配置（安装/更新引导中的「dsh 配置/插件集合」步骤）：
   * 确保私有 .dsh 布局与 .private-layout 标记就位（复用 ensurePrivateDshData()），
   * 并补写缺省 settings.yaml 与 .factory-config 标记。幂等；失败仅告警不阻断引擎启动。
   * @return 私有 DSH_HOME/.dsh 目录。
   */
  fun installFactoryConfig(): File {
    val dsh = ensurePrivateDshData()
    try {
      val settings = File(dsh, "settings.yaml")
      if (!settings.exists()) settings.writeText(DEFAULT_SETTINGS_YAML)
      File(dsh, ".factory-config").writeText("1")
    } catch (t: Throwable) {
      Log.w(TAG, "factory config install failed", t)
    }
    return dsh
  }

  /** 识别 v0.10.4 及更早的公共迁移布局。 */
  private fun isLegacyPublicLayout(dshData: File, privateDsh: File): Boolean {
    if (File(dshData, ".migrated-from").exists()) return true
    for (name in listOf("sessions", "storages", "attachments")) {
      if (isSymlink(File(privateDsh, name))) return true
    }
    for (profile in listOf("web", "headless")) {
      for (name in listOf("cordis.yml", "cordis.patch.yml")) {
        if (isSymlink(File(privateDsh, "profiles/$profile/$name"))) return true
      }
    }
    return false
  }

  /** 反向迁移：公共数据复制回私有实体，公共旧目录/文件清理。 */
  private fun reverseMigrate(dshData: File, privateDsh: File) {
    for (name in listOf("sessions", "storages", "attachments")) {
      reverseMigrateDir(File(privateDsh, name), File(dshData, name))
    }
    for (profile in listOf("web", "headless")) {
      for (name in listOf("cordis.yml", "cordis.patch.yml")) {
        reverseMigrateFile(
          File(privateDsh, "profiles/$profile/$name"),
          File(dshData, "profiles/$profile/$name"),
        )
      }
    }
    reverseMigrateFile(File(privateDsh, "settings.yaml"), File(dshData, "settings.yaml"))

    // 清理旧公共数据（冲突时已改名 *.public-backup，不会被动到）。
    val removedPaths = mutableListOf<String>()
    for (name in listOf("sessions", "storages", "attachments", "profiles", "settings.yaml", ".migrated-from")) {
      val f = File(dshData, name)
      if (f.exists()) {
        removedPaths += f.absolutePath
        if (!f.deleteRecursively()) {
          throw java.io.IOException("failed to delete public path " + f.absolutePath)
        }
      }
    }
    // 通知 MediaScanner 旧公共子目录已删除，清掉相册已索引的假视频条目。
    if (removedPaths.isNotEmpty()) {
      try {
        MediaScannerConnection.scanFile(context, removedPaths.toTypedArray(), null, null)
      } catch (t: Throwable) {
        Log.w(TAG, "media scan cleanup failed", t)
      }
    }
  }

  /** 目录级反向迁移：删私有 symlink，公共实体复制回私有并校验后删公共源。 */
  private fun reverseMigrateDir(privateDir: File, publicDir: File) {
    if (isSymlink(privateDir)) {
      Files.delete(privateDir.toPath())
    }
    if (!publicDir.isDirectory) return
    if (privateDir.isDirectory) {
      if (privateDir.listFiles()?.isNotEmpty() == true) {
        // 冲突：私有实体优先，公共副本保留待核。
        val backup = uniqueBackup(publicDir)
        if (!publicDir.renameTo(backup)) {
          throw java.io.IOException("failed to backup public dir " + publicDir.absolutePath)
        }
        Log.w(TAG, "private " + privateDir.absolutePath + " exists; public kept as " + backup.absolutePath)
        return
      }
      privateDir.deleteRecursively()
    }
    privateDir.parentFile?.mkdirs()
    copyTreeVerified(publicDir, privateDir)
    if (!publicDir.deleteRecursively()) {
      throw java.io.IOException("failed to delete public source " + publicDir.absolutePath)
    }
  }

  /** 文件级反向迁移：删私有 symlink，公共文件复制回私有后删公共源。 */
  private fun reverseMigrateFile(privateFile: File, publicFile: File) {
    if (isSymlink(privateFile)) {
      Files.delete(privateFile.toPath())
    }
    if (!publicFile.isFile) return
    if (privateFile.exists()) {
      if (privateFile.length() > 0) {
        // 公共文件是迁移后的活动副本（settings/profiles），以公共为准；
        // 私有旧实体备份保留，不静默删除。
        val backup = uniquePrivateBackup(privateFile)
        if (!privateFile.renameTo(backup)) {
          throw java.io.IOException("failed to backup private file " + privateFile.absolutePath)
        }
        Log.w(TAG, "private file backed up as " + backup.absolutePath)
      } else {
        privateFile.delete()
      }
    }
    privateFile.parentFile?.mkdirs()
    publicFile.copyTo(privateFile, overwrite = true)
    if (privateFile.length() != publicFile.length()) {
      throw java.io.IOException("copy verification failed for " + publicFile.absolutePath)
    }
    if (!publicFile.delete()) {
      throw java.io.IOException("failed to delete public source " + publicFile.absolutePath)
    }
  }

  /** 确保公共导出仓库存在：根目录 + .nomedia + exports/。 */
  private fun ensurePublicExportRepo(dshData: File) {
    try {
      dshData.mkdirs()
      File(dshData, ".nomedia").writeText("")
      File(dshData, "exports").mkdirs()
    } catch (t: Throwable) {
      Log.w(TAG, "public export repo setup failed", t)
    }
  }

  private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

  private fun uniqueBackup(publicFile: File): File {
    var candidate = File(publicFile.parentFile, publicFile.name + ".public-backup")
    var i = 1
    while (candidate.exists()) {
      candidate = File(publicFile.parentFile, publicFile.name + ".public-backup-" + i)
      i++
    }
    return candidate
  }

  private fun uniquePrivateBackup(privateFile: File): File {
    var candidate = File(privateFile.parentFile, privateFile.name + ".private-backup")
    var i = 1
    while (candidate.exists()) {
      candidate = File(privateFile.parentFile, privateFile.name + ".private-backup-" + i)
      i++
    }
    return candidate
  }

  /** 递归拷贝目录树，并校验文件数与总大小。 */
  private fun copyTreeVerified(src: File, dst: File) {
    dst.mkdirs()
    src.listFiles()?.forEach { f ->
      val target = File(dst, f.name)
      if (f.isDirectory) {
        copyTreeVerified(f, target)
      } else {
        f.copyTo(target, overwrite = true)
      }
    }
    val srcFiles = src.walkBottomUp().filter { it.isFile }.toList()
    val dstFiles = dst.walkBottomUp().filter { it.isFile }.toList()
    val srcSize = srcFiles.sumOf { it.length() }
    val dstSize = dstFiles.sumOf { it.length() }
    if (srcFiles.size != dstFiles.size || srcSize != dstSize) {
      throw java.io.IOException("copy verification failed for " + src.absolutePath)
    }
  }

  /**
   * Start the dsh web engine from the embedded snapshot.
   * @param port 引擎监听端口。
   * @param force 为 true 时跳过冷却窗口直接尝试启动（用户主动/安装引导场景）；
   *              为 false 时遵循冷却窗口（EngineService 看门狗用，防慢启动竞态）。
   */
  fun startEngine(port: Int = 3080, force: Boolean = false): Boolean {
    // 跨设备自愈：启动前兜底补设 usr/bin 可执行权限与 Android exec 属性（幂等、失败不阻断）。
    try {
      RuntimePermissions.ensureExecutable(usrDir)
    } catch (_: Throwable) {
    }
    // LD_PRELOAD 依赖快照内的 termux-exec 库：缺失时所有子进程 exec 会失败，
    // 且叠加冷却窗口 = 引擎静默停摆 90s——启动前显式断言，缺失即 loud fail。
    val preload = File(usrDir, "lib/libtermux-exec-ld-preload.so")
    if (!preload.exists()) {
      Log.e(TAG, "engine start failed: termux-exec preload missing at " + preload.absolutePath)
      Logs.append(Logs.engineLog(context), "termux-exec preload 缺失: " + preload.absolutePath)
      Logs.logE(context, TAG, "termux-exec preload 缺失: " + preload.absolutePath)
      return false
    }
    val now = System.currentTimeMillis()
    // 进程级 CAS：并发调用只有一个能真正启动（设备实证 EADDRINUSE 双启动）。
    if (!STARTING.compareAndSet(false, true)) return true
    // 冷却窗口：非 force 时上次尝试后 90s 内不重复启动（冷启动 boot 需 20-45s）。
    // 命中冷却直接返回 false——调用方应视为"本次未真正启动"，可据实提示/重试，
    // 而不是误报成功（否则引擎进程已死时会在 90s 内反复假成功导致"无法启动"）。
    if (!force && now - EngineManager.lastStartAttemptAt < START_COOLDOWN_MS) {
      STARTING.set(false)
      return false
    }
    return try {
      val args = arrayOf(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath, "web", "--port", port.toString(),
      )
      val env = mapOf(
        "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
        "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
        "HOME" to homeDir.absolutePath,
        // DSH_HOME 始终保持在私有域（FUSE 禁 symlink，公共域无法维护
        // profiles/node_modules flat fallback）；运行时用户数据全部在私有
        // files/home/.dsh，公共 Documents/dshdata 仅作导出仓库。
        "DSH_HOME" to ensurePrivateDshData().absolutePath,
        // os.tmpdir() falls back to the baked-in Termux tmp on Android
        // (unwritable from the app domain); keep spill inside filesDir.
        "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
        // Android 16 forbids exec of app-data ELF regardless of targetSdk
        // (observed on Android 16/vivo: direct exec EACCES even at targetSdk
        // 34). Termux's execve hook re-routes denied execs through
        // /system/bin/linker64 (same mechanism as JNI libs); the snapshot
        // ships libtermux-exec-*-ld-preload.so. The hook only rewrites for
        // untrusted_app_25/27 SELinux domains, so force mode is required.
        "LD_PRELOAD" to preload.absolutePath,
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
        "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
        "TERMUX__ROOTFS" to context.filesDir.absolutePath,
        "TERMUX__PREFIX" to usrDir.absolutePath,
        "TERMUX_APP__DATA_DIR" to context.filesDir.parentFile!!.absolutePath,
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/com.dshmobile.shell",
        "TERMUX_VERSION" to "0.118.3",
        // 目录选择桥端点鉴权 token（web-compat 插件校验 x-dsh-pick-token）。
        "DSH_PICK_TOKEN" to (pickToken ?: ""),
      )
      engineProcess = startWithArgs(args, env)
      // 冷却只在真实启动后写入：失败路径不占用冷却窗口（可立即重试）。
      EngineManager.lastStartAttemptAt = now
      // 真实启动时刻：主页状态卡运行时长基准（手动停止/重启后自动归零）。
      EngineManager.lastStartedAt = now
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      Logs.logE(context, TAG, "引擎启动失败", t)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * Spawn the engine, falling back to the system linker when the direct exec
   * is denied: Android 15+ apps targeting SDK 35+ may not exec app-data ELF
   * binaries, but loading them through /system/bin/linker64 is the same
   * mechanism as native libraries (always permitted for app data).
   */
  private fun startWithArgs(args: Array<String>, env: Map<String, String>): Process {
    val log = Logs.engineLog(context)
    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.redirectOutput(log)
        // 引擎工作目录固定为私有 home：保证相对路径的配置/插件读写都落在
        // DSH_HOME 私有域（Web 保存配置文件、profiles 读写依赖此）。
        b.directory(homeDir.apply { mkdirs() })
      }
    return try {
      build(args.toList()).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** Stop the engine process (best-effort). */
  fun stopEngine() {
    engineProcess?.destroy()
    engineProcess = null
    // 手动停止后重置冷却：用户回前台应立即允许重新启动。
    EngineManager.lastStartAttemptAt = 0
    // 手动停止后运行时长归零（主页状态卡不再显示旧时长）。
    EngineManager.lastStartedAt = 0
  }

  /** 读取 engine.log 末尾若干行（引擎启动失败诊断用；无日志返回空串）。 */
  fun engineLogTail(maxLines: Int = 40): String {
    val log = Logs.engineLog(context)
    if (!log.exists()) return ""
    return try {
      log.readLines().takeLast(maxLines).joinToString("\n")
    } catch (_: Throwable) {
      ""
    }
  }

  companion object {
    private const val TAG = "dsh-engine"

    /** 出厂缺省 settings.yaml（引擎首次运行会按需覆盖/补充）。 */
    private const val DEFAULT_SETTINGS_YAML = "# dsh-mobile 出厂配置（由 APK 引导安装写入）\n"

    /** Watchdog/retry backoff: no new start within this window of the last
     *  attempt. Cold node boot on the phone takes 20-45s (plugin tree + first
     *  bind); a 5s watchdog poll would otherwise race a healthy boot and
     *  double-start the engine (device-observed EADDRINUSE). 90s covers the
     *  slowest observed boot with margin. */
    const val START_COOLDOWN_MS = 90_000L

    /** 进程级启动 CAS：跨 EngineManager 实例可见（双启动竞态防护）。 */
    val STARTING = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次真实启动时刻（epoch ms）；watchdog 冷却窗口基准。 */
    @Volatile
    var lastStartAttemptAt: Long = 0

    /** Web 覆盖层是否打开（用户在主动使用引擎）。打开期间看门狗不自动重启。 */
    @Volatile var webActive: Boolean = false

    /** 引擎最近一次真实启动时刻（epoch ms）；主页状态卡运行时长用。 */
    @Volatile var lastStartedAt: Long = 0
  }
}
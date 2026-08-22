package com.yoyo.dshmobile.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 跨设备运行时权限自愈辅助（移植自旧项目 dsh-mobile-apk/.../RuntimePermissions.kt）：
 *  - 幂等补设 `usr/bin` 及关键 `usr/lib` 的 exec 位（owner/group/other 任一已有 exec 位则保留，
 *    否则补设 owner-exec）；
 *  - stamp `security.android.exec` 属性（Android 15+ 强制 exec 属性时，缺失会导致
 *    `Cannot run program ".../usr/bin/node": error=13, Permission denied`）。
 * 所有操作失败 silent（不抛异常），重复调用无副作用。
 */
object RuntimePermissions {

  /** 统一解析 termux-exec preload 文件：优先硬编码目标；不存在时通配 usr/lib/libtermux-exec*ld-preload*.so；
   * 若通配命中但目标缺失，复制为目标路径。都无则返回 null。 */
  fun resolveTermuxExecPreload(usrDir: File): File? {
    val target = File(usrDir, "lib/libtermux-exec-ld-preload.so")
    if (target.exists() && target.length() > 0) return target
    val libDir = File(usrDir, "lib")
    if (!libDir.exists() || !libDir.isDirectory) return null
    val matched = libDir.listFiles()?.firstOrNull {
      val n = it.name
      it.isFile && it.length() > 0 &&
        n.startsWith("libtermux-exec") && n.contains("ld-preload") && n.endsWith(".so")
    } ?: return null
    try {
      matched.copyTo(target, overwrite = true)
      return target.takeIf { it.exists() && it.length() > 0 }
    } catch (_: Throwable) { return null }
  }

  /** 幂等补设 usr 目录下可执行文件权限与 Android exec 属性。 */
  fun ensureExecutable(usrDir: File) {
    if (!usrDir.exists()) return
    // 处理 bin 目录
    val binDir = File(usrDir, "bin")
    if (binDir.exists()) {
      binDir.listFiles()?.forEach { file ->
        if (file.isFile) {
          setExecutable(file)
          stampAndroidExecAttr(listOf(file))
        }
      }
    }
    // 处理关键 lib（termux-exec 等需要可执行的 so）：通配解析优先，兜底固定路径
    val resolvedPath = resolveTermuxExecPreload(usrDir)?.relativeTo(usrDir)?.path
    val criticalLibs = mutableListOf<String>()
    if (resolvedPath != null) criticalLibs.add(resolvedPath)
    if (resolvedPath == null) criticalLibs.add("lib/libtermux-exec-ld-preload.so")
    criticalLibs.forEach { path ->
      val file = File(usrDir, path)
      if (file.exists() && file.isFile) {
        setExecutable(file)
        stampAndroidExecAttr(listOf(file))
      }
    }
  }

  /** owner/group/other 任一 exec 位缺失时补设 owner-exec。 */
  private fun setExecutable(file: File) {
    if (!file.canExecute()) { // canExecute 反映当前用户可执行性（owner-exec 缺失时返回 false）
      file.setExecutable(true, true)
    }
  }

  /**
   * 把 usr/lib（及 usr/bin 下指向 usr/lib 的链接）中的符号链接实体化：
   * 解析链接目标为最终实体文件（canonical），若在 usr 目录内存在实体则
   * 复制实体内容覆盖链接本身，得到真实文件。幂等；失败静默。
   * 目的：规避 Android 11+ app data FUSE 对符号链接的读取限制，
   * 让 bionic linker 直接从 LD_LIBRARY_PATH 读到真实 .so。
   * 不追踪跨出 usrDir 的目标（如 /system 或 /data/data/com.termux，保持原链接不动）。
   */
  fun materializeSymlinks(usrDir: File) {
    if (!usrDir.exists()) return
    val usrCanonical = runCatching { usrDir.canonicalFile }.getOrNull() ?: return
    listOf(File(usrDir, "lib"), File(usrDir, "bin")).forEach { dir ->
      if (!dir.exists() || !dir.isDirectory) return@forEach
      dir.listFiles()?.forEach { materializeOne(usrCanonical, it) }
    }
  }

  /** 单个符号链接实体化：仅当目标是 usr 目录内的真实文件时，复制目标内容覆盖链接本身。 */
  private fun materializeOne(usrCanonical: File, link: File) {
    // 跟随链接 read：链接指向文件时 isFile==true；再确认为符号链接
    if (!link.isFile) return
    if (!java.nio.file.Files.isSymbolicLink(link.toPath())) return
    val target = runCatching { link.canonicalFile }.getOrNull() ?: return
    if (target == link || !target.isFile || target.length() <= 0L) return
    val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return
    val usrPath = runCatching { usrCanonical.canonicalPath }.getOrNull() ?: return
    if (!targetPath.startsWith(usrPath + File.separatorChar + "")) return
    runCatching {
      // 先写同目录临时文件再 rename 到 link（原子替换），避免先删后拷断档；失败则删 tmp 且保留原链接
      val tmp = File(link.parentFile, link.name + ".tmp" + System.nanoTime())
      try {
        target.copyTo(tmp, overwrite = true)
        tmp.setExecutable(true, true)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(link)) return@runCatching // rename 失败：保持原链接不动
      } finally {
        if (tmp.exists()) tmp.delete()
      }
    }
  }

  /**
   * node 主程序的非系统 DT_NEEDED 库（libc/libm/libdl 由 Android linker 提供，不在此列）。
   * 必须保证这些名字在 usr/lib 下都是「真实且可读」的文件，linker 才能通过 LD_LIBRARY_PATH 找到。
   */
  private val NODE_NEEDED_LIBS = listOf(
    "libz.so.1",
    "libcares.so",
    "libsqlite3.so",
    "libcrypto.so.3",
    "libssl.so.3",
    "libicui18n.so.78",
    "libicuuc.so.78",
    "libc++_shared.so",
  )

  /**
   * 强制把 node 精确依赖的库名合成/真文件化到 usr/lib，覆盖两况：
   *  - 既有符号链接不可读（FUSE）——先删后以实体内容覆盖；
   *  - 链接/文件直接缺失（符号链接创建失败 readback 不到）——从同前缀实体补出。
   * 逐文件 runCatching，失败静默；幂等。返回「库名 -> 是否到位」，供诊断日志。
   */
  fun ensureNodeLibsReal(usrDir: File): Map<String, Boolean> {
    val libDir = File(usrDir, "lib")
    val result = LinkedHashMap<String, Boolean>()
    if (!libDir.isDirectory) {
      NODE_NEEDED_LIBS.forEach { result[it] = false }
      return result
    }
    val byName = HashMap<String, File>()
    libDir.listFiles()?.forEach { f ->
      if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
        byName.putIfAbsent(f.name, f) // 同名前只留一个
      }
    }
    fun candidates(prefix: String): File? =
      byName[prefix] ?: byName.entries.firstOrNull { it.key.startsWith(prefix) && it.value.length() > 0L }?.value

    // 同 base 下取版本号最大者（真实且非空），如 libz.so.1.3.2<-libz.so、libicui18n.so.78.3<-libicui18n.so（libssl.so.3 不会退选 libssl.so.1）
    fun bestOf(base: String): File? {
      val regex = Regex("^${Regex.escape(base)}\\.(\\d+(\\.\\d+)*)$")
      // 版本比较用「每段补零拼接」的字符串作为可比较键（List<Int> 不实现 Comparable，且需按数字大小而非字典序）
      return byName.entries
        .mapNotNull { (n, f) ->
          if (java.nio.file.Files.isSymbolicLink(f.toPath()) || f.length() <= 0L) return@mapNotNull null
          val m = regex.matchEntire(n) ?: return@mapNotNull null
          f to m.groupValues[1].split(".").joinToString(".") { it.padStart(6, '0') }
        }
        .maxByOrNull { it.second }?.first
    }

    for (name in NODE_NEEDED_LIBS) {
      val ok = runCatching {
        val targetFile = File(libDir, name)
        // 已是真实文件且非空 → 视为到位
        if (!java.nio.file.Files.isSymbolicLink(targetFile.toPath()) && targetFile.isFile && targetFile.length() > 0L) {
          return@runCatching true
        }
        // 候选：精确名首次；次取同 base 版本号最大者；再兜底按 base 前缀
        val base = name.substringBeforeLast(".so") + ".so"
        val src = candidates(name) ?: bestOf(base) ?: candidates(base)
        if (src == null) return@runCatching false
        // 先写同目录临时文件再 rename 到目标（原子替换），避免 delete+copy 断档；失败删 tmp
        val tmp = File(libDir, name + ".tmp" + System.nanoTime())
        try {
          src.copyTo(tmp, overwrite = true)
          if (!tmp.renameTo(targetFile)) return@runCatching false
        } finally {
          if (tmp.exists()) tmp.delete()
        }
        if (!java.nio.file.Files.isSymbolicLink(targetFile.toPath()) && targetFile.isFile && targetFile.length() > 0L) {
          targetFile.setExecutable(true, true)
          targetFile.setReadable(true, true)
          targetFile.setWritable(true, true)
          byName[name] = targetFile
          true
        } else false
      }.getOrDefault(false)
      result[name] = ok
    }
    return result
  }

  /** stamp security.android.exec 属性（Android 15+ 强制 exec 属性；经 /system/bin/setfattr 批量打标，每批 ≤64）。 */
  fun stampAndroidExecAttr(files: List<File>) {
    if (files.isEmpty()) return
    files.filter { it.exists() && it.isFile }.chunked(64).forEach { batch ->
      try {
        val cmd = mutableListOf("/system/bin/setfattr", "-n", "security.android.exec", "-v", "1")
        batch.forEach { cmd.add(it.absolutePath) }
        ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
      } catch (_: Throwable) {
        // 内核不强制该属性时 setfattr 可能失败；忽略。
      }
    }
  }
}

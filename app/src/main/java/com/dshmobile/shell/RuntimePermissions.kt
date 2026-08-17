package com.dshmobile.shell

import java.io.File

/**
 * 跨设备运行时权限自愈辅助（共享给 EnvManager / EngineManager / UpdateManager）：
 *  - 幂等补设 `usr/bin` 及关键 `usr/lib` 的 exec 位（owner/group/other 任一已有 exec 位则保留，
 *    否则补设 owner-exec）；
 *  - stamp `security.android.exec` 属性（Android 15+ 强制 exec 属性时，缺失会导致
 *    `Cannot run program ".../usr/bin/bash": error=13, Permission denied`）。
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
          stampAndroidExecAttr(file)
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
        stampAndroidExecAttr(file)
      }
    }
  }

  /** owner/group/other 任一 exec 位缺失时补设 owner-exec。 */
  private fun setExecutable(file: File) {
    if (!file.canExecute()) { // canExecute 反映当前用户可执行性（owner-exec 缺失时返回 false）
      file.setExecutable(true, true)
    }
  }

  /** stamp security.android.exec 属性（复用 SnapshotExtractor 的 setfattr 方式）。 */
  private fun stampAndroidExecAttr(file: File) {
    try {
      ProcessBuilder(
        "/system/bin/setfattr", "-n", "security.android.exec", "-v", "1",
        file.absolutePath,
      ).redirectErrorStream(true).start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Throwable) {
      // 内核不强制该属性时 setfattr 可能失败；忽略。
    }
  }
}

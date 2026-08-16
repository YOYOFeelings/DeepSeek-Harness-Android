package com.dshmobile.shell

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 存储统计工具：纯本地、无 UI、无新权限，后台线程调用。
 * 返回 MB 单位的整数（不足 1MB 记为 0）。
 */
object StorageStats {

  /** 应用私有数据占用（filesDir，含 usr 运行时 / home 用户数据），单位 MB。 */
  fun appDataUsage(context: Context): Long = dirSizeMb(context.filesDir)

  /** 应用缓存占用（cacheDir），单位 MB。 */
  fun cacheSize(context: Context): Long = dirSizeMb(context.cacheDir)

  /** 公共导出仓库（Documents/dshdata）大小，单位 MB。 */
  fun publicRepoSize(context: Context): Long {
    val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) ?: return 0L
    return dirSizeMb(File(docs, "dshdata"))
  }

  private fun dirSizeMb(dir: File): Long =
    if (!dir.exists()) 0L else dirSizeBytes(dir) / (1024 * 1024)

  private fun dirSizeBytes(dir: File): Long {
    if (dir.isFile) return dir.length()
    if (!dir.isDirectory) return 0L
    var total = 0L
    dir.listFiles()?.forEach { total += dirSizeBytes(it) }
    return total
  }
}

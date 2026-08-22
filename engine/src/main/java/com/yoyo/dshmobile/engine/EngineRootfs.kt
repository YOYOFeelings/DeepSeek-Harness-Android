package com.yoyo.dshmobile.engine

import android.content.Context
import com.yoyo.dshmobile.shell.log.Logs
import java.io.File

/**
 * 引擎运行时 rootfs 解压与校验。
 *
 * 骨架阶段（Task 0 资产未就绪）仅做「资产存在性检查 + 写 done 标记」，
 * 真正的 xz/tar 解压实现在 rootfs.tar.xz 装配后补齐。
 * 全程 runCatching 包裹，rootfs 未就绪时优雅降级，绝不抛异常导致闪退。
 */
object EngineRootfs {

  private const val ROOTFS_ASSET = "rootfs/rootfs.tar.xz"

  private const val DONE_MARKER = ".extracted"

  // 骨架阶段版本号占位，随 done 标记写入；组装真实 rootfs 后由解压逻辑写入真实版本。
  private const val SKELETON_VERSION = "skeleton"

  enum class Result { OK, NO_ASSET, EXTRACTED_ALREADY, FAILED }

  fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

  private fun doneFile(context: Context): File = File(rootfsDir(context), DONE_MARKER)

  fun isExtracted(context: Context): Boolean = doneFile(context).exists()

  /** 返回引擎运行时版本；未解压返回空串。 */
  fun engineVersion(context: Context): String =
    if (isExtracted(context)) {
      runCatching { doneFile(context).readText().trim() }.getOrDefault("")
    } else ""

  /**
   * 确保 rootfs 已解压。骨架阶段：
   *  - 已解压 -> EXTRACTED_ALREADY
   *  - 资产不存在 -> NO_ASSET
   *  - 资产存在 -> mkdirs rootfs 目录 + 写 done 标记 -> OK
   *  - 异常 -> FAILED
   */
  fun ensureExtracted(context: Context): Result = when {
    isExtracted(context) -> Result.EXTRACTED_ALREADY
    else -> {
      runCatching {
        val streamOpt = runCatching { context.assets.open(ROOTFS_ASSET) }.getOrNull()
        if (streamOpt == null) {
          Logs.logEvent(context, "Engine", "rootfs-asset-missing")
          return Result.NO_ASSET
        }
        streamOpt.close()
        val dir = rootfsDir(context)
        dir.mkdirs()
        doneFile(context).writeText(SKELETON_VERSION)
        Result.OK
      }.getOrElse { t ->
        Logs.logEvent(context, "Engine", "ensure-extracted-fail", t)
        Result.FAILED
      }
    }
  }
}
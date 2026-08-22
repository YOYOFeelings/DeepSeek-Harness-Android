package com.yoyo.dshmobile.shell.ui

import android.content.Context
import com.yoyo.dshmobile.shell.log.Logs
import org.json.JSONObject
import java.io.File

/**
 * 已安装插件元数据读取（单一来源）。
 *
 * 数据来源分为两层：
 * 1. 内置插件：打包在 APK 的 assets/plugins 目录下，首次调用时复制到 filesDir/plugins（目标已存在则跳过，保留用户侧更新）。
 * 2. 外置/已安装插件：用户或系统写入 filesDir/plugins 下的 json 文件。
 *
 * 扫描 filesDir/plugins 下的 json 文件统一解析；内置来源用 [PluginInfo.bundled] 标记（依据 assets 同名文件判定）。
 * 解析失败跳过该文件并记结构化日志，不阻断其余插件加载。
 */
data class PluginInfo(
  val name: String,
  val desc: String,
  val version: String,
  val enabled: Boolean,
  val sourceDir: String,
  // 是否为 App 自带（assets 内置）插件。
  val bundled: Boolean = false,
)

private const val LOG_TAG = "Plugin"

// assets 内置插件目录。
private const val BUNDLED_ASSETS_DIR = "plugins"

// 首次访问时把资产内置插件复制到 `filesDir/plugins`（同名已存在则跳过，幂等）。
private fun ensureBundledPlugins(context: Context) {
  runCatching {
    val dir = File(context.filesDir, "plugins")
    if (!dir.isDirectory && !dir.mkdirs()) return
    val assetNames = context.assets.list(BUNDLED_ASSETS_DIR) ?: return
    for (name in assetNames) {
      val target = File(dir, name)
      if (target.exists()) continue
      context.assets.open("$BUNDLED_ASSETS_DIR/$name").use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
      }
      Logs.logEvent(context, LOG_TAG, "copy-bundled name=$name")
    }
  }
}

// 返回 assets 内置插件文件名集合（用于标记 [PluginInfo.bundled]）。
private fun bundledAssetNames(context: Context): Set<String> =
  runCatching { context.assets.list(BUNDLED_ASSETS_DIR)?.toSet() ?: emptySet() }
    .getOrDefault(emptySet())

// 扫描 `filesDir/plugins` 下的 `*.json` 并逐个解析（含内置复制）；无目录/空目录返回空 list。
fun loadPlugins(context: Context): List<PluginInfo> {
  ensureBundledPlugins(context)
  val dir = File(context.filesDir, "plugins")
  if (!dir.isDirectory) return emptyList()

  val bundledNames = bundledAssetNames(context)
  val result = mutableListOf<PluginInfo>()
  val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
    ?.sortedBy { it.name } ?: return result

  for (file in files) {
    try {
      val json = JSONObject(file.readText().trim())
      val info = PluginInfo(
        name = json.optString("name").ifBlank { file.nameWithoutExtension },
        desc = json.optString("desc"),
        version = json.optString("version"),
        enabled = json.optBoolean("enabled", true),
        sourceDir = file.absolutePath,
        bundled = file.name in bundledNames,
      )
      result.add(info)
      Logs.logEvent(context, LOG_TAG, "loaded name=${info.name} ver=${info.version} bundled=${info.bundled}")
    } catch (t: Throwable) {
      Logs.logEvent(context, LOG_TAG, "parse-fail file=${file.name}", t)
    }
  }
  return result
}

// 插件数量（主页计数用，含内置）。
fun pluginCount(context: Context): Int = loadPlugins(context).size
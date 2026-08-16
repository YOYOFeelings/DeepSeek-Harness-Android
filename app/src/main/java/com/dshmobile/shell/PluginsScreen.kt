package com.dshmobile.shell

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

/** 插件页（底部导航 Tab 2）：Flat Minimalist 风格。
 *  列出运行时已装插件（全局 usr/lib/node_modules + 各 profile 的 node_modules），支持：
 *   - 导入本地 .tgz 插件包（宿主提供文件）；
 *   - 卸载（删除目录，二次确认）；
 *   - 停用/启用（目录改名 *.disabled，Node 不再解析，重启引擎生效）。
 *  全局 node_modules 中的系统插件（含引擎核心 @deepseek-ai/dsh）与内置 npm/corepack 不可卸载。 */
class PluginsScreen(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {

  interface Callbacks {
    /** 宿主弹出文件选择器（.tgz 插件包），拿到文件后调用 [importFrom]。 */
    fun onImportPlugin()
  }

  /** 行容器：放在 ScrollView 内，refresh() 时清空重建。 */
  private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

  init {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(resources.getColor(R.color.bg, null))
    setPadding(dp(16), dp(16), dp(16), dp(16))

    // 标题行：标题 + 导入按钮
    val head = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
      setPadding(0, 0, 0, dp(12))
    }
    head.addView(
      TextView(context).apply {
        text = I18n.t(context, "插件", "Plugins")
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
      },
    )
    head.addView(
      flatButton(I18n.t(context, "导入插件", "Import plugin"), accent = true) { callbacks.onImportPlugin() },
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
    )
    addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    // 滚动区：行列表放在 ScrollView 内，条目多时可滚动。
    val scroll = ScrollView(context)
    scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
  }

  /** 导入 .tgz 插件包：解压到 usr/lib/node_modules（根目录为 package/ 时重命名为包名）。
   *  @return 结果信息（成功/失败），宿主写入终端或提示。 */
  fun importFrom(tgzFile: File): String {
    val modulesDir = File(File(context.filesDir, "usr"), "lib/node_modules")
    modulesDir.mkdirs()
    return try {
      GzipCompressorInputStream(tgzFile.inputStream()).use { gz ->
        val tar = TarArchiveInputStream(gz)
        var entry = tar.nextEntry
        if (entry == null) return "压缩包为空"
        val first = entry.name.trimStart('/')
        val stripRoot: String
        val targetRoot: String
        when {
          first == "package" -> { stripRoot = "package"; targetRoot = "package" }
          first == "package/" -> { stripRoot = "package/"; targetRoot = "package" }
          else -> {
            stripRoot = first.substringBefore('/') + "/"
            targetRoot = first.substringBefore('/')
          }
        }
        val dest = File(modulesDir, targetRoot)
        dest.deleteRecursively()
        var pkgName: String? = null
        do {
          val name = entry.name.trimStart('/')
          val rel = if (name.startsWith(stripRoot)) name.removePrefix(stripRoot) else name
          val target = File(dest, rel)
          val destCanonical = dest.canonicalPath
          if (!target.canonicalPath.startsWith(destCanonical + File.separator) && target.canonicalPath != destCanonical) {
            continue
          }
          when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              java.nio.file.Files.deleteIfExists(target.toPath())
              java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
            }
            else -> {
              target.parentFile?.mkdirs()
              target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var n = tar.read(buf)
                while (n >= 0) {
                  out.write(buf, 0, n)
                  n = tar.read(buf)
                }
              }
              target.setReadable(true, true)
              target.setWritable(true, true)
              target.setExecutable(entry.mode and 0x40 != 0, true)
              if (rel == "package.json") pkgName = packageNameOf(target)
            }
          }
          entry = tar.nextEntry
        } while (entry != null)
        tar.close()

        val finalName = pkgName?.takeIf { it.isNotBlank() } ?: targetRoot
        if (targetRoot == "package" && finalName != "package") {
          val renamed = File(modulesDir, finalName)
          renamed.deleteRecursively()
          if (!dest.renameTo(renamed)) return "重命名包目录失败"
        }
        "已导入插件：" + finalName
      }
    } catch (t: Throwable) {
      "导入失败：" + (t.message ?: t.javaClass.simpleName)
    }
  }

  /** 解析 package.json 的 name 字段（尽力而为）。 */
  private fun packageNameOf(json: File): String? = try {
    JSONObject(json.readText()).optString("name").takeIf { it.isNotBlank() }
  } catch (_: Throwable) {
    null
  }

  /** 重新读取并渲染插件列表。 */
  fun refresh() {
    rows.removeAllViews()
    val modulesDir = File(File(context.filesDir, "usr"), "lib/node_modules")
    val plugins = collectPlugins(modulesDir)
    if (plugins.isEmpty()) {
      rows.addView(TextView(context).apply {
        text = I18n.t(context, "暂无插件，点右上角「导入插件」安装本地 .tgz 包", "No plugins yet. Tap \"Import plugin\" to install a local .tgz package.")
        textSize = 13f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(8), 0, dp(8))
      })
      return
    }
    for (p in plugins) {
      addPluginCard(p.name, p.dir, p.disabled, p.source)
    }
  }

  /** 单个插件：名称 + 目录 + 是否停用（目录名带 .disabled 后缀）+ 来源（"系统" 或 profile 目录名）。 */
  private data class Plugin(val name: String, val dir: File, val disabled: Boolean, val source: String)

  /** 扫描单个 node_modules 目录 → 插件列表。node_modules 顶层含 package.json 的包；
   *  @ 作用域目录展开子包；内置 npm/corepack 跳过（系统工具）。
   *  注意：停用态目录名带 .disabled 后缀，必须基于「实际存在的目录」遍历，
   *  否则停用后插件会从列表消失、无法重新启用。 */
  private fun scanModulesDir(dir: File, source: String): List<Plugin> {
    val result = mutableListOf<Plugin>()
    for (entry in (dir.listFiles() ?: emptyArray())) {
      if (!entry.isDirectory) continue
      val raw = entry.name
      if (raw == "npm" || raw == "corepack") continue // 系统工具
      val disabled = raw.endsWith(".disabled")
      val base = raw.removeSuffix(".disabled")
      if (base.startsWith("@")) {
        // 作用域包：entry 即真实目录（可能带 .disabled），展开其子包
        for (sub in (entry.listFiles() ?: emptyArray())) {
          if (!sub.isDirectory) continue
          if (!File(sub, "package.json").exists()) continue
          val subRaw = sub.name
          val subBase = subRaw.removeSuffix(".disabled")
          result.add(Plugin("$base/$subBase", sub, subRaw.endsWith(".disabled"), source))
        }
      } else if (File(entry, "package.json").exists()) {
        result.add(Plugin(base, entry, disabled, source))
      }
    }
    return result
  }

  /** 收集插件：依次扫描全局 node_modules（usr/lib/node_modules，系统插件）与
   *  各 profile 的 node_modules（DSH_HOME/profiles 下各目录，Web/AI 页面安装的插件），
   *  合并结果并按插件名去重（全局优先：已存在同名插件时跳过 profile 的重复项）。 */
  private fun collectPlugins(modulesDir: File): List<Plugin> {
    val result = mutableListOf<Plugin>()
    val seen = mutableSetOf<String>()
    fun merge(list: List<Plugin>) {
      for (p in list) {
        if (seen.add(p.name)) result.add(p)
      }
    }
    // 全局优先
    merge(scanModulesDir(modulesDir, "系统"))
    // 各 profile（DSH_HOME = files/home/.dsh，Web 安装的插件在各 profile 的 node_modules 下）
    val dshHome = File(File(context.filesDir, "home"), ".dsh")
    val profilesDir = File(dshHome, "profiles")
    for (profile in (profilesDir.listFiles() ?: emptyArray())) {
      if (!profile.isDirectory) continue
      val nm = File(profile, "node_modules")
      if (!nm.isDirectory) continue
      merge(scanModulesDir(nm, profile.name))
    }
    return result
  }

  /** 插件卡片：名称 + 来源 + 状态胶囊 + 操作行（卸载 / 停用·启用）。系统包只显示状态。 */
  private fun addPluginCard(name: String, dir: File, disabled: Boolean, source: String) {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), dp(12), dp(14), dp(12))
      background = resources.getDrawable(R.drawable.bg_card, null)
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    val top = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
    }
    top.addView(
      TextView(context).apply {
        text = name
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(resources.getColor(R.color.text, null))
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
      },
    )
    val isSystem = source == "系统"
    val chipText = when {
      isSystem -> I18n.t(context, "核心", "Core")
      disabled -> I18n.t(context, "已停用", "Disabled")
      else -> I18n.t(context, "已启用", "Enabled")
    }
    top.addView(TextView(context).apply {
      text = chipText
      textSize = 11f
      setTextColor(
        when {
          isSystem -> resources.getColor(R.color.text_secondary, null)
          disabled -> resources.getColor(R.color.warn, null)
          else -> resources.getColor(R.color.success, null)
        },
      )
      setPadding(dp(10), dp(4), dp(10), dp(4))
      background = resources.getDrawable(
        when {
          isSystem -> R.drawable.bg_chip_missing
          disabled -> R.drawable.bg_chip_warn
          else -> R.drawable.bg_chip_ok
        }, null,
      )
    })
    card.addView(top)

    if (!isSystem) {
      // 来源小字：区分全局/系统插件与各 profile 安装的插件
      card.addView(TextView(context).apply {
        text = I18n.t(context, "来源：", "Source: ") + "profile " + source
        textSize = 11f
        setTextColor(resources.getColor(R.color.text_secondary, null))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
      })
      val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
      }
      actions.addView(
        flatButton(I18n.t(context, "卸载", "Uninstall"), accent = false) { confirmUninstall(name, dir) },
        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
      )
      actions.addView(
        flatButton(if (disabled) I18n.t(context, "启用", "Enable") else I18n.t(context, "停用", "Disable"), accent = true) { toggleDisabled(name, dir, disabled) },
        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
      )
      card.addView(actions)
    }
    rows.addView(card)
  }

  /** 卸载确认对话框 → 删除插件目录。 */
  private fun confirmUninstall(name: String, dir: File) {
    android.app.AlertDialog.Builder(context)
      .setTitle(I18n.t(context, "卸载插件", "Uninstall plugin"))
      .setMessage(I18n.t(context, "确定卸载 $name 吗？将删除其目录，重启引擎后不再加载。", "Uninstall $name? Its directory will be deleted and it won't load after an engine restart."))
      .setPositiveButton(I18n.t(context, "卸载", "Uninstall")) { _, _ ->
        dir.deleteRecursively()
        val disabledDir = File(dir.parentFile, dir.name + ".disabled")
        disabledDir.deleteRecursively()
        refresh()
      }
      .setNegativeButton(I18n.t(context, "取消", "Cancel"), null)
      .show()
  }

  /** 停用/启用：目录改名为 name.disabled ↔ name（Node 不再解析即停用）。 */
  private fun toggleDisabled(name: String, dir: File, disabled: Boolean) {
    if (disabled) {
      val target = File(dir.parentFile, dir.name.removeSuffix(".disabled"))
      if (dir.renameTo(target)) refresh()
    } else {
      val target = File(dir.parentFile, dir.name + ".disabled")
      if (dir.renameTo(target)) refresh()
    }
  }

  /** 扁平按钮（插件页用，无图标）。 */
  private fun flatButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
    TextView(context).apply {
      text = label
      textSize = 13f
      typeface = Typeface.DEFAULT_BOLD
      gravity = android.view.Gravity.CENTER
      setPadding(dp(10), dp(8), dp(10), dp(8))
      background = resources.getDrawable(if (accent) R.drawable.bg_button_accent else R.drawable.bg_button_ghost, null)
      setTextColor(
        if (accent) resources.getColor(R.color.surface, null) else resources.getColor(R.color.text, null)
      )
      setOnClickListener { onClick() }
    }

  private fun dotDrawable(color: Int): GradientDrawable =
    GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(color)
    }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

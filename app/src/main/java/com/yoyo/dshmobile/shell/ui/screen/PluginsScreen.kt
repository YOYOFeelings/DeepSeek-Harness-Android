package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.ui.PluginInfo
import com.yoyo.dshmobile.shell.ui.SPACE_MD
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.loadPlugins
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.screenTopBar
import com.yoyo.dshmobile.shell.ui.tintId

/**
 * 插件页（原生 View，白色简洁风）：标题栏「插件」+ 插件列表框架。
 * 插件清单来自应用私有目录 `filesDir/plugins` 下的 `*.json`（[loadPlugins]）；
 * 空目录/无插件时展示空态占位卡片，否则渲染插件卡片列表。
 */
class PluginsScreen(
  private val context: Context,
) {

  val rootView: View

  /** 尺寸单位换算（转发到主题密度工具；在 apply 地块内可直接引用）。 */
  private fun dp(v: Int): Int = context.dp(v)

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.nav_plugins)),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val plugins = loadPlugins(context)
    content.addView(
      if (plugins.isEmpty()) buildEmptyCard() else buildPluginList(plugins),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val scroll = ScrollView(context)
    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = root
  }

  /** 插件列表空态卡片：居中图标 + 说明文案。 */
  private fun buildEmptyCard(): View {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(20), dp(32), dp(20), dp(32))
    }
    card.addView(
      ImageView(context).apply {
        setImageResource(R.drawable.ic_plugin)
        tintId(R.color.dh_text_faint)
        contentDescription = null
      },
      LinearLayout.LayoutParams(dp(44), dp(44)),
    )
    card.addView(
      TextView(context).apply {
        text = context.getString(R.string.plugins_empty)
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(16) },
    )
    return card
  }

  /** 插件列表：每个插件一张卡片（图标 + name/desc + 右侧 version 与启用状态）。 */
  private fun buildPluginList(plugins: List<PluginInfo>): View {
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    plugins.forEachIndexed { i, info ->
      list.addView(
        buildPluginCard(info),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          .apply { topMargin = if (i == 0) 0 else dp(SPACE_MD) },
      )
    }
    return list
  }

  private fun buildPluginCard(info: PluginInfo): View {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    row.addView(
      ImageView(context).apply {
        setImageResource(R.drawable.ic_plugin)
        tintId(R.color.dh_primary)
        contentDescription = null
      },
      LinearLayout.LayoutParams(dp(28), dp(28)),
    )

    val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    mid.addView(
      TextView(context).apply {
        text = info.name
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    if (info.desc.isNotBlank()) {
      mid.addView(
        TextView(context).apply {
          text = info.desc
          setTextColor(context.color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
          .apply { topMargin = dp(2) },
      )
    }
    row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      .apply { leftMargin = dp(12) })

    val right = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.END
    }
    // 来源徽标：内置（主题色实心） / 已安装（灰底描边）
    right.addView(
      TextView(context).apply {
        text = context.getString(if (info.bundled) R.string.plugin_bundled else R.string.plugin_installed)
        setTextColor(context.color(if (info.bundled) R.color.dh_on_primary else R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        background = context.roundedBg(
          if (info.bundled) context.color(R.color.dh_primary) else context.color(R.color.dh_divider),
          12,
        )
        setPadding(dp(8), dp(2), dp(8), dp(2))
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    if (info.version.isNotBlank()) {
      right.addView(
        TextView(context).apply {
          text = info.version
          setTextColor(context.color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          gravity = Gravity.END
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
    }
    right.addView(
      TextView(context).apply {
        text = context.getString(
          if (info.enabled) R.string.plugin_enabled else R.string.plugin_disabled)
        setTextColor(
          context.color(if (info.enabled) R.color.dh_success else R.color.dh_text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.END
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    row.addView(right, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })

    return row
  }
}
package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.onboarding.currentMode
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.outlinedEditText
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.roundedInputView
import com.yoyo.dshmobile.shell.ui.screenTopBar
import com.yoyo.dshmobile.shell.ui.tintId
import kotlinx.coroutines.CoroutineScope

/**
 * 设置页：分组展示 关于（跳独立页）、版本更新（跳独立页）、日志入口、自动启动开关、
 * 权限模式，以及最底部「开发者设置」入口（密码 + 协议校验后进入）。
 * 色值与尺寸一律走主题资源。
 */
class SettingsScreen(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onOpenLogs: () -> Unit,
  private val onOpenAbout: () -> Unit,
  private val onOpenUpdate: () -> Unit,
  private val onOpenPermissionMode: () -> Unit,
  private val onOpenDeveloper: () -> Unit,
) {

  /** 开发者设置进入密码（写死在代码中，仅作者自测用，非安全边界）。 */
  private val DEV_PASSWORD = "3197614520"

  val rootView: View

  /** 权限模式当前档位展示文本（点击整行可切换）。 */
  private val permissionModeDesc = TextView(context)

  /** 尺寸单位换算（转发到主题密度工具）。 */
  private fun dp(v: Int): Int = context.dp(v)

  init {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      context.screenTopBar(context.getString(R.string.settings_title)),
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val scroll = ScrollView(context)
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    content.addView(
      buildRow(
        title = context.getString(R.string.settings_about),
        desc = context.getString(R.string.settings_about_desc),
        onClick = { onOpenAbout() },
      ),
    )
    content.addView(
      buildRow(
        title = context.getString(R.string.settings_update),
        desc = context.getString(R.string.settings_update_desc),
        onClick = { onOpenUpdate() },
      ),
      lpVertical(),
    )
    content.addView(
      buildRow(
        title = context.getString(R.string.settings_logs),
        desc = context.getString(R.string.settings_logs_desc),
        onClick = { onOpenLogs() },
      ),
      lpVertical(),
    )
    // 「自动启动引擎」开关：默认关闭，状态持久化于 engine_prefs/auto_start
    val enginePrefs = context.getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
    content.addView(
      buildSwitchRow(
        title = context.getString(R.string.settings_auto_start),
        desc = context.getString(R.string.settings_auto_start_desc),
        checked = enginePrefs.getBoolean("auto_start", false),
        onCheckedChange = { isChecked ->
          enginePrefs.edit().putBoolean("auto_start", isChecked).apply()
        },
      ),
      lpVertical(),
    )
    content.addView(
      buildPermissionModeRow(),
      lpVertical(),
    )
    // 开发者设置入口（最底部，低调置灰）
    content.addView(
      buildDeveloperRow(),
      lpVertical(),
    )

    scroll.addView(content, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rootView = root
  }

  private fun lpVertical(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(14) }

  /** 白色圆角设置行：标题 + 描述 + 右侧箭头。 */
  private fun buildRow(
    title: String,
    desc: String,
    onClick: () -> Unit,
  ): View {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
      setOnClickListener { onClick() }
    }

    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(
      TextView(context).apply {
        text = title
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    labels.addView(
      TextView(context).apply {
        text = desc
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    row.addView(ImageView(context).apply {
      setImageResource(R.drawable.ic_arrow_right)
      tintId(R.color.dh_text_faint)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
    return row
  }

  /** 「自动启动引擎」开关行：标题 + 描述 + 右侧 Switch（主题色 dh_primary），状态持久化于 engine_prefs/auto_start。 */
  private fun buildSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
  ): View {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(
      TextView(context).apply {
        text = title
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    labels.addView(
      TextView(context).apply {
        text = desc
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(
      SwitchCompat(context).apply {
        isChecked = checked
        buttonTintList = ColorStateList.valueOf(context.color(R.color.dh_primary))
        setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    return row
  }

  /** 「权限模式」行：展示当前档位，点击弹出三档选择。 */
  private fun buildPermissionModeRow(): View {
    permissionModeDesc.apply {
      text = context.getString(R.string.permission_mode_desc, currentMode(context))
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(14), dp(16), dp(14))
      setOnClickListener { onOpenPermissionMode() }
    }
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(
      TextView(context).apply {
        text = context.getString(R.string.permission_mode_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    labels.addView(
      permissionModeDesc,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(ImageView(context).apply {
      setImageResource(R.drawable.ic_arrow_right)
      tintId(R.color.dh_text_faint)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
    return row
  }

  /** 开发者设置入口行：低调置灰，点击弹 MD3 密码 + 协议校验对话框。 */
  private fun buildDeveloperRow(): View {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = context.roundedBg(context.color(R.color.dh_surface), 16)
      setPadding(dp(16), dp(12), dp(16), dp(12))
      alpha = 0.7f
      setOnClickListener { showDeveloperDialog() }
    }
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    labels.addView(
      TextView(context).apply {
        text = context.getString(R.string.settings_developer)
        setTextColor(context.color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    labels.addView(
      TextView(context).apply {
        text = context.getString(R.string.settings_developer_desc)
        setTextColor(context.color(R.color.dh_text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(2) },
    )
    row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(ImageView(context).apply {
      setImageResource(R.drawable.ic_arrow_right)
      tintId(R.color.dh_text_faint)
      contentDescription = null
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
    return row
  }

  /** MD3 密码门：描边浮动输入框 + 协议勾选；密码与协议校验通过后进入开发者设置。 */
  private fun showDeveloperDialog() {
    val pad = dp(20)
    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }
    root.addView(
      TextView(context).apply {
        text = context.getString(R.string.dev_password_dialog_title)
        setTextColor(context.color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    val (inputView, inputField) =
      context.roundedInputView(context.getString(R.string.dev_password_hint), password = true)
    root.addView(inputView, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(18) })
    val check = CheckBox(context).apply {
      text = context.getString(R.string.dev_agreement)
      setTextColor(context.color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      buttonTintList = ColorStateList.valueOf(context.color(R.color.dh_primary))
    }
    root.addView(check, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(10) })

    lateinit var dialog: AlertDialog
    val buttons = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.END
    }
    buttons.addView(dialogButton(context.getString(R.string.engine_mirror_close)) {
      dialog.dismiss()
    })
    buttons.addView(dialogButton(context.getString(R.string.dh_ok), primary = true) {
      if (!check.isChecked) {
        toast(context.getString(R.string.dev_agreement_required))
        return@dialogButton
      }
      val pwd = inputField.text?.toString()?.trim()
      if (pwd != DEV_PASSWORD) {
        toast(context.getString(R.string.dev_password_wrong))
        return@dialogButton
      }
      toast(context.getString(R.string.dev_password_ok))
      dialog.dismiss()
      onOpenDeveloper()
    }, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { leftMargin = dp(12) })
    root.addView(buttons, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      .apply { topMargin = dp(18) })

    dialog = MaterialAlertDialogBuilder(context).setView(root).create()
    dialog.show()
  }

  /** 对话框内圆角按钮（与 [showDeveloperDialog] 样式一致）。 */
  private fun dialogButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
    TextView(context).apply {
      this.text = text
      setTextColor(context.color(if (primary) R.color.dh_on_primary else R.color.dh_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      if (primary) typeface = android.graphics.Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = if (primary) {
        context.roundedBg(context.color(R.color.dh_primary), 18)
      } else {
        context.roundedBg(android.graphics.Color.TRANSPARENT, 18, context.color(R.color.dh_primary), 1)
      }
      setPadding(dp(18), dp(8), dp(18), dp(8))
      setOnClickListener { onClick() }
    }

  private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
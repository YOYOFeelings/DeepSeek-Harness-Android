package com.yoyo.dshmobile.shell

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.onboarding.RemotePolicyLoader
import com.yoyo.dshmobile.shell.ui.color
import com.yoyo.dshmobile.shell.ui.dp
import com.yoyo.dshmobile.shell.ui.roundedBg
import com.yoyo.dshmobile.shell.ui.themedDialog
import com.yoyo.dshmobile.shell.ui.tintId
import com.yoyo.dshmobile.shell.ui.screen.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 独立「关于」页（折叠视差滚动）。
 * 点击设置里「关于」后通过 startActivity 进入本页，而非应用内路由。
 * 布局：layout_about_collapsing.xml（CoordinatorLayout 折叠视差 + 白卡链接列表）。
 * 列表项：查看源码 / 项目协议 / QQ群 / 发送日志。
 */
class AboutActivity : AppCompatActivity() {

  /** SAF「保存日志」：用户选位置/文件名后回写打包好的 zip。 */
  private lateinit var saveLogsLauncher: ActivityResultLauncher<String>

  /** 用户未确认保存位置前的暂存 zip（cacheDir/logs_export/logs_export.zip）。 */
  private var pendingSaveZip: File? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.layout_about_collapsing)

    // SAF 保存日志：CreateDocument 让用户选择存放位置与文件名
    saveLogsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
      if (uri == null) return@registerForActivityResult
      copyPendingZipTo(uri)
    }

    // 置顶 Toolbar：返回关闭本页
    val toolbar = findViewById<Toolbar>(R.id.about_toolbar)
    toolbar.setNavigationOnClickListener { finish() }

    // 版本号回填（灰色 14sp 占位由 XML 提供）
    findViewById<TextView>(R.id.about_version)?.text = "v${UpdateManager.currentVersion(this)}"

    // 「查看源码」→ 打开 GitHub 仓库
    findViewById<View>(R.id.about_row_source)?.setOnClickListener {
      openUrl(UpdateManager.REPO_HOME_URL)
    }
    // 「项目协议」→ 弹窗展示远程协议全文
    findViewById<View>(R.id.about_row_policy)?.setOnClickListener { showPolicyDialog() }
    // 「QQ群」→ 弹窗展示群图标/群号(可复制)
    findViewById<View>(R.id.about_row_qq)?.setOnClickListener { showQqDialog() }
    // 「发送日志」→ 弹窗：保存/发送日志
    findViewById<View>(R.id.about_row_logs)?.setOnClickListener { showSendLogsDialog() }
  }

  private fun openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
  }

  private fun toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
  }

  // ---------------- A) 项目协议 ----------------

  /** 项目协议弹窗：MD3 可滚动弹窗，先显示加载态，正文从远程拉取，失败回退内置文本。 */
  private fun showPolicyDialog() {
    val body = TextView(this).apply {
      text = getString(R.string.about_policy_loading)
      setTextColor(color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setLineSpacing(dp(2).toFloat(), 1f)
    }
    val scroll = ScrollView(this).apply {
      isFillViewport = false
      addView(
        body,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
    }
    val dialog = themedDialog(
      title = getString(R.string.about_policy_title),
      contentView = scroll,
      negativeText = "",
      positiveText = getString(R.string.about_policy_close),
    )
    dialog.show()

    // 远程拉取（loadPolicy 内部在 IO 线程执行，绝不打主线程）
    lifecycleScope.launch {
      val loaded = RemotePolicyLoader.loadPolicy(this@AboutActivity)
      body.text = loaded
      if (loaded == RemotePolicyLoader.POLICY_PLACEHOLDER) {
        toast(getString(R.string.about_policy_failed))
      }
    }
  }

  // ---------------- B) QQ 群 ----------------

  /** QQ 群弹窗：图标/标题 + 两个可点击复制的群行(群1/群2)，圆角由 MD3 表层负责。 */
  private fun showQqDialog() {
    val pad = dp(20)
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(pad, dp(28), pad, dp(16))
    }

    content.addView(
      ImageView(this).apply {
        setImageResource(R.drawable.ic_chat)
        tintId(R.color.dh_primary)
      },
      LinearLayout.LayoutParams(dp(56), dp(56)),
    )
    content.addView(
      TextView(this).apply {
        text = getString(R.string.about_qq_group)
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(16) },
    )
    content.addView(qqGroupRow(getString(R.string.about_qq_group_1), getString(R.string.about_qq_group1)))
    content.addView(qqGroupRow(getString(R.string.about_qq_group_2), getString(R.string.about_qq_group2)))
    content.addView(
      TextView(this).apply {
        text = getString(R.string.about_qq_group_hint)
        setTextColor(color(R.color.dh_text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) },
    )

    MaterialAlertDialogBuilder(this).setView(content).create().show()
  }

  /** 单个 QQ 群行：左侧「群N」标签、右侧群号，点击整行复制并提示。 */
  private fun qqGroupRow(label: String, number: String): LinearLayout =
    LinearLayout(this@AboutActivity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      isClickable = true
      isFocusable = true
      // 按下水波纹（使用系统默认 selectableItemBackground，各 API 兜底）
      val out = TypedValue()
      if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true) && out.resourceId != 0) {
        foreground = resources.getDrawable(out.resourceId, theme)
      }
      setOnClickListener { copyGroupNumber(number) }
      addView(
        TextView(this@AboutActivity).apply {
          text = label
          setTextColor(color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
      )
      addView(
        TextView(this@AboutActivity).apply {
          text = number
          setTextColor(color(R.color.dh_primary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
          typeface = Typeface.DEFAULT_BOLD
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
    }

  /** 复制 QQ 群号到剪贴板并提示。 */
  private fun copyGroupNumber(number: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(
      ClipData.newPlainText(
        getString(R.string.about_qq_group),
        number,
      ),
    )
    toast(getString(R.string.about_qq_copied))
  }

  // ---------------- C) 发送日志 ----------------

  /** 发送日志弹窗：标题 + 保存日志/发送日志 两项，精确按 Compose 风格复刻。 */
  private fun showSendLogsDialog() {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      // 不包白底圆角卡片：圆角由 MD3 表层负责。
    }

    lateinit var dialog: AlertDialog

    // 标题：18sp 加粗居中，上下 16dp padding
    root.addView(
      TextView(this).apply {
        text = getString(R.string.about_logs_dialog_title)
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(16))
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    // 「保存日志」56dp
    root.addView(
      buildDialogItem(getString(R.string.about_logs_save), R.drawable.ic_log) {
        dialog.dismiss()
        lifecycleScope.launch { saveLogs() }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
    )
    root.addView(
      View(this).apply { setBackgroundColor(color(R.color.dh_divider)) },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        leftMargin = dp(16)
        rightMargin = dp(16)
      },
    )
    // 「发送日志」56dp
    root.addView(
      buildDialogItem(getString(R.string.about_logs_send), R.drawable.ic_share) {
        dialog.dismiss()
        lifecycleScope.launch { sendLogs() }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
    )

    dialog = MaterialAlertDialogBuilder(this).setView(root).create()
    dialog.show()
  }

  /** 弹窗内 56dp 列表项：图标 + 文字 + 右箭头，点击水波纹用系统默认 foreground。 */
  private fun buildDialogItem(text: String, iconRes: Int, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      isClickable = true
      isFocusable = true
      setPadding(dp(16), 0, dp(16), 0)
      // 按下水波纹（使用系统默认 selectableItemBackground，各 API 兜底）
      val out = TypedValue()
      if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true) && out.resourceId != 0) {
        foreground = resources.getDrawable(out.resourceId, theme)
      }
      setOnClickListener { onClick() }

      val icon = ImageView(this@AboutActivity).apply {
        setImageResource(iconRes)
        tintId(R.color.dh_text_secondary)
      }
      addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })

      val label = TextView(this@AboutActivity).apply {
        this.text = text
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      }
      addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

      val chevron = ImageView(this@AboutActivity).apply {
        setImageResource(R.drawable.ic_arrow_right)
        tintId(R.color.dh_text_faint)
      }
      addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
    }

  /** 将 filesDir/logs 目录打包 zip 到 cacheDir/logs_export/logs_export.zip；失败或日志为空返回 null。
   *  zip 必须位于 cacheDir/logs_export/ 子目录下（FileProvider 根目录须为目录，否则分享抛
   *  StringIndexOutOfBoundsException）。 */
  private suspend fun zipLogs(): File? = withContext(Dispatchers.IO) {
    runCatching {
      val srcDir = File(filesDir, "logs")
      val files = srcDir.listFiles() ?: return@withContext null
      if (!srcDir.exists() || files.none { it.isFile }) return@withContext null
      val dir = File(cacheDir, "logs_export").apply { mkdirs() }
      val target = File(dir, "logs_export.zip")
      ZipOutputStream(FileOutputStream(target)).use { zos ->
        files.filter { it.isFile }.forEach { f ->
          zos.putNextEntry(ZipEntry(f.name))
          f.inputStream().use { it.copyTo(zos) }
          zos.closeEntry()
        }
      }
      target
    }.getOrNull()
  }

  /** 「保存日志」：打包到 cacheDir 后经 SAF 让用户选择目标位置/文件名再落盘。 */
  private suspend fun saveLogs() {
    val zip = zipLogs()
    if (zip == null) {
      toast(getString(R.string.about_logs_none))
      return
    }
    pendingSaveZip = zip
    saveLogsLauncher.launch("logs_export.zip")
  }

  /** 用户在 SAF 中确认保存位置后，把暂存 zip 写入所选文档。 */
  private fun copyPendingZipTo(uri: Uri) {
    val zip = pendingSaveZip ?: return
    runCatching {
      contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
        ?: error("open stream failed")
      toast(getString(R.string.about_logs_saved, uri.lastPathSegment ?: getString(R.string.about_logs_send)))
    }.onFailure { t ->
      toast(getString(R.string.about_logs_save_failed, t.message ?: t.javaClass.simpleName))
    }
  }

  /** 「发送日志」：用 ACTION_SEND 系统分享打包好的 zip（经 FileProvider 暴露）。 */
  private suspend fun sendLogs() {
    val zip = zipLogs()
    if (zip == null) {
      toast(getString(R.string.about_logs_none))
      return
    }
    runCatching {
      val uri: Uri = FileProvider.getUriForFile(this, packageName + ".provider", zip)
      val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, getString(R.string.about_send_logs_desc))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(share, getString(R.string.about_logs_dialog_title)))
    }.onFailure { t ->
      toast(getString(R.string.about_logs_share_fail, t.message ?: t.javaClass.simpleName))
      Logs.logEvent(this@AboutActivity, "App", "share-logs-fail", t)
    }
  }
}
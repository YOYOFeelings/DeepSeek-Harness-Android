package com.yoyo.dshmobile.shell

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.log.Logs
import com.yoyo.dshmobile.shell.log.LogFox
import com.yoyo.dshmobile.shell.onboarding.MODE_ADVANCED
import com.yoyo.dshmobile.shell.onboarding.MODE_SHIZUKU
import com.yoyo.dshmobile.shell.onboarding.ShizukuHelper
import com.yoyo.dshmobile.shell.onboarding.currentMode
import com.yoyo.dshmobile.shell.onboarding.markPermissionGranted
import com.yoyo.dshmobile.shell.onboarding.permissionGranted
import com.yoyo.dshmobile.shell.ui.screen.HomeScreen
import com.yoyo.dshmobile.shell.ui.screen.LogsScreen
import com.yoyo.dshmobile.shell.ui.screen.PermissionModeScreen
import com.yoyo.dshmobile.shell.ui.screen.PluginsScreen
import com.yoyo.dshmobile.shell.ui.screen.SettingsScreen
import com.yoyo.dshmobile.shell.ui.screen.ConversationScreen
import com.yoyo.dshmobile.shell.ui.screen.UpdateScreen
import com.yoyo.dshmobile.shell.ui.screen.DeveloperSettingsScreen
import com.yoyo.dshmobile.shell.ui.screen.WorkspacePrefs
import com.yoyo.dshmobile.shell.ui.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主界面壳：响应式导航（竖屏底部 BottomNavigationView / 横屏·平板侧边 NavigationRailView）。
 * 四个 Tab：主页 / 插件 / 会话 / 设置；另含设置内的「日志」子页（id=5）。
 */
class MainActivity : AppCompatActivity() {

  private lateinit var content: FrameLayout

  /** 当前主页实例：系统目录选择器返回结果时回填工作区卡。 */
  private var homeInstance: HomeScreen? = null

  /** 会话实例缓存：切走时移除视图，切回时复用（状态保留）。 */
  private var conversationInstance: ConversationScreen? = null

  /** 系统目录选择器（SAF）：选中后持久化目录 URI 并把显示名回填工作区卡。 */
  private val dirPicker =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        runCatching {
          contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        val display = uri.lastPathSegment?.let { Uri.decode(it) } ?: uri.toString()
        lifecycleScope.launch { WorkspacePrefs.save(this@MainActivity, uri.toString(), display) }
        homeInstance?.onDirectoryPicked(display)
      }
    }

  /** 页面 id 常量。 */
  private val ID_HOME = 1
  private val ID_PLUGINS = 2
  private val ID_SETTINGS = 4
  private val ID_LOGS = 5
  private val ID_CONVERSATION = 6
  private val ID_UPDATE = 7
  private val ID_PERMISSION = 8
  private val ID_DEVELOPER = 9

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LogFox.trackUser(this, "page", "main_create")
    content = FrameLayout(this)

    val root: LinearLayout = if (isWideLayout()) {
      buildRailLayout()
    } else {
      buildBottomNavLayout()
    }

    setContentView(root)
    showScreen(ID_HOME)
    startPermissionMonitor()
  }

  /**
   * 权限已获取标记监视：进入本页先重置标记，再按秒轮询核对——
   * 每次场景切换/重启后都重新由 currentMode 判断，避免「已授权」状态残留。
   */
  private fun startPermissionMonitor() {
    lifecycleScope.launch { markPermissionGranted(this@MainActivity, false) }
    lifecycleScope.launch {
      while (isActive) {
        val granted = permissionGranted(this@MainActivity)
        val mode = currentMode(this@MainActivity)
        if (!granted) {
          val needGrant = mode == MODE_SHIZUKU || mode == MODE_ADVANCED
          if (needGrant) {
            if (ShizukuHelper.isGranted) {
              markPermissionGranted(this@MainActivity, true)
              Logs.logEvent(this@MainActivity, "PermMonitor", "granted mode=$mode")
            } else if (ShizukuHelper.isRunning) {
              ShizukuHelper.requestPermission()
              Logs.logEvent(this@MainActivity, "PermMonitor", "requesting mode=$mode")
            }
          } else {
            markPermissionGranted(this@MainActivity, true)
          }
        }
        delay(1000)
      }
    }
  }

  /** 宽屏判定：横屏 或 平板/大宽度（最短边亦够宽）。 */
  private fun isWideLayout(): Boolean {
    val c = resources.configuration
    val landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
    val wideEnough = c.screenWidthDp >= 600
    val tablet = c.smallestScreenWidthDp >= 600
    return landscape || wideEnough || tablet
  }

  /** 导航菜单定义：主页 / 插件 / 会话 / 设置（文案走资源）。 */
  private fun menuSpec(): List<Triple<Int, Int, String>> = listOf(
    Triple(ID_HOME, R.drawable.ic_home, getString(R.string.nav_home)),
    Triple(ID_PLUGINS, R.drawable.ic_plugin, getString(R.string.nav_plugins)),
    Triple(ID_CONVERSATION, R.drawable.ic_conversation, getString(R.string.nav_conversation)),
    Triple(ID_SETTINGS, R.drawable.ic_settings, getString(R.string.settings_title)),
  )

  /** 侧边导航布局（横屏/平板宽屏）：左侧 NavigationRail + 右侧内容。 */
  private fun buildRailLayout(): LinearLayout {
    val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    val rail = NavigationRailView(this).apply {
      menuSpec().forEach { (id, icon, label) ->
        menu.add(0, id, 0, label).setIcon(icon)
      }
    }
    rail.setOnItemSelectedListener { item ->
      showScreen(item.itemId)
      true
    }
    rail.selectedItemId = menuSpec().first().first

    // 导航与内容区背景统一为 dh_background（与状态栏一致，消除灰白割裂）
    rail.setBackgroundColor(color(R.color.dh_background))
    content.setBackgroundColor(color(R.color.dh_background))

    root.addView(rail, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    root.addView(content, LinearLayout.LayoutParams(
      0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    return root
  }

  /** 底部导航布局（手机竖屏）：内容 + 底部 BottomNavigationView。 */
  private fun buildBottomNavLayout(): LinearLayout {
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val nav = BottomNavigationView(this).apply {
      // 统一 label 置底、图标+文字整体居中（去掉 Material3 选中态文字顶到图标上的偏移）
      labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
      menuSpec().forEach { (id, icon, label) ->
        menu.add(0, id, 0, label).setIcon(icon)
      }
    }
    nav.setOnItemSelectedListener { item ->
      showScreen(item.itemId)
      true
    }
    nav.selectedItemId = menuSpec().first().first

    // 导航与内容区背景统一为 dh_background（与状态栏一致，消除灰白割裂）
    nav.setBackgroundColor(color(R.color.dh_background))
    content.setBackgroundColor(color(R.color.dh_background))

    root.addView(content, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(nav, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return root
  }

  /** 切换当前 Screen（真实页面装配，直接渲染，不做 shimmer 闸门，避免首帧空白）。 */
  private fun showScreen(id: Int) {
    content.removeAllViews()
    val params = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    when (id) {
      ID_PLUGINS -> addScreen(PluginsScreen(this).rootView, params)
      ID_CONVERSATION -> {
        val screen = conversationInstance ?: ConversationScreen(this, lifecycleScope)
        conversationInstance = screen
        addScreen(screen.rootView, params)
      }
      ID_PERMISSION -> addScreen(
        PermissionModeScreen(this, lifecycleScope, onBack = { showScreen(ID_SETTINGS) }).rootView, params)
      ID_SETTINGS -> addScreen(
        SettingsScreen(
          this,
          lifecycleScope,
          onOpenLogs = { showScreen(ID_LOGS) },
          onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
          onOpenUpdate = { showScreen(ID_UPDATE) },
          onOpenPermissionMode = { showScreen(ID_PERMISSION) },
          onOpenDeveloper = { showScreen(ID_DEVELOPER) },
        ).rootView, params)
      ID_LOGS -> addScreen(
        LogsScreen(this) { showScreen(ID_SETTINGS) }.rootView, params)
      ID_UPDATE -> addScreen(
        UpdateScreen(this, lifecycleScope) { showScreen(ID_SETTINGS) }.rootView, params)
      ID_DEVELOPER -> addScreen(
        DeveloperSettingsScreen(this) { showScreen(ID_SETTINGS) }.rootView, params)
      else -> {
        // 直接渲染主页；构造失败时显示可见错误文本而非空白，便于定位（不静默吞异常）。
        // 保存 homeInstance 以便系统目录选择器（SAF）返回时回填工作区卡；onSwitchDir 打开目录选择器。
        homeInstance = try {
          HomeScreen(
            this,
            lifecycleScope,
            onOpenUpdate = { showScreen(ID_UPDATE) },
            onSwitchDir = { dirPicker.launch(null) },
          ).also { addScreen(it.rootView, params) }
        } catch (t: Throwable) {
          addScreen(homeErrorView(t), params)
          null
        }
      }
    }
  }

  /** 把新页面加入内容区并播放「淡入 + 轻微上移」导航动画（统一切换动效）。 */
  private fun addScreen(view: View, params: FrameLayout.LayoutParams) {
    content.addView(view, params)
    view.startAnimation(buildNavAnimation())
  }

  /** 导航切换动画：淡入 + 由下轻微上移（时长取主题统一的 300ms）。 */
  private fun buildNavAnimation(): Animation =
    AnimationSet(true).apply {
      setDuration(300)
      addAnimation(AlphaAnimation(0f, 1f).apply { duration = 300 })
      addAnimation(TranslateAnimation(0f, 0f, dp(40).toFloat(), 0f).apply { duration = 300 })
    }

  /** 主页渲染失败兜底：把真实异常显示到界面，而不是空白。 */
  private fun homeErrorView(t: Throwable): View =
    TextView(this).apply {
      text = "主页加载失败\n${t.javaClass.simpleName}: ${t.message ?: "(无消息)"}"
      setTextColor(color(R.color.dh_danger))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      gravity = Gravity.CENTER
      setPadding(dp(24), 0, dp(24), 0)
      setBackgroundColor(color(R.color.dh_background))
    }

  /** 从统一主题资源读取颜色（单一来源，页面不硬编码色值）。 */
  private fun color(id: Int): Int = ContextCompat.getColor(this, id)
}
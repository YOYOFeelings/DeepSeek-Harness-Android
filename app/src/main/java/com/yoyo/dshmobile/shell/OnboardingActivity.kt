package com.yoyo.dshmobile.shell

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.yoyo.dshmobile.shell.onboarding.OnboardingScreenView
import com.yoyo.dshmobile.shell.onboarding.OnboardingViewModel
import com.yoyo.dshmobile.shell.log.LogFox
import com.yoyo.dshmobile.shell.onboarding.ShizukuHelper
import com.yoyo.dshmobile.shell.onboarding.firstLaunchFlow
import com.yoyo.dshmobile.shell.onboarding.markOnboardingDone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 引导页宿主（Launcher，原生 View 实现）。
 * - 非首启：直接进入主页 [MainActivity] 并关闭自身；
 * - 首启：显示原生 View 引导页 [OnboardingScreenView]，完成/跳过后写 `isFirstLaunch=false` 再进入主页。
 * 首启状态用 DataStore 持久化；页面 UI 组装在 [OnboardingScreenView] 中完成，
 * 本类只负责生命周期、运行时权限申请与 Shizuku 授权结果监听。
 */
class OnboardingActivity : AppCompatActivity() {

  private val viewModel: OnboardingViewModel by lazy {
    ViewModelProvider(this)[OnboardingViewModel::class.java]
  }

  private var onboardingView: OnboardingScreenView? = null

  /** Shizuku 授权结果监听器（onDestroy 注销；可空：服务未运行时跳过注册）。 */
  private var shizukuRecipient: ShizukuHelper.Recipient? = null

  /** 运行时权限申请（存储分版本 + 通知）。 */
  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { grants ->
    val view = onboardingView ?: return@registerForActivityResult
    view.refreshPermissions()
    view.onPermissionsResultProcessed()
    if (grants.isNotEmpty() && grants.values.any { !it }) {
      view.showPermissionDeniedDialog()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 日志埋点：记录引导页关键阶段，便于定位闪退发生位置（日志在私有目录 filesDir/logs/）
    LogFox.trackUser(this, "page", "onboarding_create")

    // 状态栏/导航栏背景由 themes.xml 的 Theme.Dsh 配置（dh_background），无需 edge-to-edge。

    lifecycleScope.launch {
      val isFirst = firstLaunchFlow(this@OnboardingActivity).first()
      if (!isFirst) {
        goHome()
        return@launch
      }
      LogFox.trackUser(this@OnboardingActivity, "page", "onboarding_first_launch_true")
      val view = OnboardingScreenView(
        context = this@OnboardingActivity,
        viewModel = viewModel,
        onFinish = { finishOnboarding() },
        onRequestPermissions = { permissionLauncher.launch(buildPermissionList()) },
      )
      onboardingView = view
      setContentView(view.rootView)
      LogFox.trackUser(this@OnboardingActivity, "page", "onboarding_content_set")
      view.bind(this@OnboardingActivity)
      LogFox.trackUser(this@OnboardingActivity, "page", "onboarding_bind_done")
      registerShizukuListener()
      view.refreshPermissions()
    }
  }

  override fun onResume() {
    super.onResume()
    // Shizuku 授权弹窗由 Shizuku 管理器弹出，返回本页时会再次 onResume：
    // 重注册监听器 + 直查权限状态，规避「授权成功了但仍显示未授权」。
    val view = onboardingView ?: return
    registerShizukuListener()
    view.refreshPermissions()
    viewModel.refreshShizukuStatus()
  }

  /** 按 SDK 版本组装需申请的运行时权限。 */
  private fun buildPermissionList(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 33) {
      list += Manifest.permission.READ_MEDIA_IMAGES
      list += Manifest.permission.READ_MEDIA_VIDEO
      list += Manifest.permission.READ_MEDIA_AUDIO
    } else {
      list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
  }

  private fun registerShizukuListener() {
    // 幂等：先注销上一次注册，避免重复注册导致回调多次触发。
    // Shizuku 服务未运行/注册失败时 addPermissionResultListener 返回 null（不抛异常），
    // 此处容忍空值；监听仅在授权真正发生时才有意义，无 Shizuku 设备跳过不影响功能。
    shizukuRecipient?.unregister()
    shizukuRecipient = runCatching {
      ShizukuHelper.addPermissionResultListener { requestCode, grantResult ->
        viewModel.onShizukuPermissionResult(requestCode, grantResult)
      }
    }.getOrNull()
  }

  private fun finishOnboarding() {
    lifecycleScope.launch {
      markOnboardingDone(this@OnboardingActivity)
      goHome()
    }
  }

  private fun goHome() {
    startActivity(Intent(this, MainActivity::class.java))
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    shizukuRecipient?.unregister()
    shizukuRecipient = null
  }
}

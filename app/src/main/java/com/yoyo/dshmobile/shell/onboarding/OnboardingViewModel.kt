package com.yoyo.dshmobile.shell.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 引导页状态管理：Shizuku 授权状态、Root 状态、命令输出均用 StateFlow，
 * 保证配置/重组时不丢数据。
 */
class OnboardingViewModel : ViewModel() {

  /** Shizuku 状态枚举。 */
  enum class ShizukuState { UNAVAILABLE, NOT_GRANTED, GRANTED }

  /** Root 状态枚举。 */
  enum class RootState { CHECKING, AVAILABLE, UNAVAILABLE }

  private val _shizukuState = MutableStateFlow(ShizukuState.UNAVAILABLE)
  val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

  private val _shizukuStatusText = MutableStateFlow("Shizuku 服务未运行")
  val shizukuStatusText: StateFlow<String> = _shizukuStatusText.asStateFlow()

  private val _shizukuOutput = MutableStateFlow("")
  val shizukuOutput: StateFlow<String> = _shizukuOutput.asStateFlow()

  private val _shizukuLoading = MutableStateFlow(false)
  val shizukuLoading: StateFlow<Boolean> = _shizukuLoading.asStateFlow()

  private val _rootState = MutableStateFlow(RootState.CHECKING)
  val rootState: StateFlow<RootState> = _rootState.asStateFlow()

  private val _rootOutput = MutableStateFlow("")
  val rootOutput: StateFlow<String> = _rootOutput.asStateFlow()

  private val _rootLoading = MutableStateFlow(false)
  val rootLoading: StateFlow<Boolean> = _rootLoading.asStateFlow()

  /** 存储权限是否已授权。 */
  private val _storageGranted = MutableStateFlow(false)
  val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

  /** 通知权限是否已授权。 */
  private val _notificationGranted = MutableStateFlow(false)
  val notificationGranted: StateFlow<Boolean> = _notificationGranted.asStateFlow()

  /** 用户选择的权限模式（普通 / 高级），默认普通权限用户。 */
  private val _selectedMode = MutableStateFlow(MODE_NORMAL)
  val selectedMode: StateFlow<String> = _selectedMode.asStateFlow()

  /** 设置用户选择的权限模式。 */
  fun setMode(mode: String) {
    _selectedMode.value = mode
  }

  /** 由各页/入口刷新权限状态（UI 计算真实授权后写入）。 */
  fun updatePermissionStates(storage: Boolean, notification: Boolean) {
    _storageGranted.value = storage
    _notificationGranted.value = notification
  }

  /** 刷新 Shizuku 状态（进入页面/授权结果返回后调用）。 */
  fun refreshShizukuStatus() {
    val running = ShizukuHelper.isRunning
    if (!running) {
      _shizukuState.value = ShizukuState.UNAVAILABLE
      _shizukuStatusText.value = "Shizuku 服务未运行"
    } else {
      val granted = ShizukuHelper.isGranted
      _shizukuState.value = if (granted) ShizukuState.GRANTED else ShizukuState.NOT_GRANTED
      _shizukuStatusText.value = if (granted) "已授权" else "未授权"
    }
  }

  /** 授权结果回调（由 UI 注册的 listener 调用）。 */
  fun onShizukuPermissionResult(requestCode: Int, grantResult: Int) {
    if (requestCode != ShizukuHelper.SHIZUKU_REQUEST_CODE) return
    _shizukuState.value = if (grantResult == 1) ShizukuState.GRANTED else ShizukuState.NOT_GRANTED
    _shizukuStatusText.value = if (grantResult == 1) "已授权" else "未授权"
  }

  /**
   * 请求 Shizuku 授权（供「授权 Shizuku」按钮调用）。
   * 先刷新最新状态，再按状态分流：
   * - UNAVAILABLE：不发起请求，返回 false（UI 应提示「请先启动 Shizuku 服务」）；
   * - NOT_GRANTED：发起授权请求，返回是否成功发起；
   * - GRANTED：已授权无需再请求，返回 true。
   */
  fun requestShizukuPermission(): Boolean {
    refreshShizukuStatus()
    return when (_shizukuState.value) {
      ShizukuState.UNAVAILABLE -> false
      ShizukuState.NOT_GRANTED -> ShizukuHelper.requestPermission()
      ShizukuState.GRANTED -> true
    }
  }

  /** 授权成功后演示 `pm list packages -3`。 */
  fun runListPackages() {
    if (_shizukuLoading.value) return
    _shizukuLoading.value = true
    viewModelScope.launch {
      runCatching {
        val text = ShizukuHelper.listThirdPartyPackages()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        _shizukuOutput.value = lines.take(5).joinToString("\n")
      }.onFailure {
        _shizukuOutput.value = "执行失败: ${it.message}"
      }
      _shizukuLoading.value = false
    }
  }

  /** 检测 Root 是否可用。 */
  fun checkRoot() {
    viewModelScope.launch {
      _rootState.value = RootState.CHECKING
      val ok = RootHelper.isRootAvailable()
      _rootState.value = if (ok) RootState.AVAILABLE else RootState.UNAVAILABLE
    }
  }

  /** 执行 Root 命令 `ls /data/data`。 */
  fun runLsData() {
    if (_rootLoading.value) return
    _rootLoading.value = true
    viewModelScope.launch {
      val result = RootHelper.exec("ls /data/data")
      _rootOutput.value = if (result.success) result.output.take(800) else "执行失败: " + result.output.take(200)
      _rootLoading.value = false
    }
  }
}
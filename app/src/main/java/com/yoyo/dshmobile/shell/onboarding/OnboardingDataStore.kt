package com.yoyo.dshmobile.shell.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yoyo.dshmobile.shell.log.Logs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "onboarding")

/** 权限模式常量：普通权限用户 / 高级权限用户 / Shizuku（作为持久化 value 与展示文案）。 */
const val MODE_NORMAL = "普通权限用户"
const val MODE_ADVANCED = "高级权限用户"
const val MODE_SHIZUKU = "Shizuku"

/** 首次启动标记。 */
val KeyFirstLaunch = booleanPreferencesKey("is_first_launch")

/** 权限模式 key。 */
val KeyPermissionMode = stringPreferencesKey("permission_mode")

/** 权限是否已实际获取的标记（随模式/服务状态刷新，重启后重置重新核对）。 */
val KeyPermissionGranted = booleanPreferencesKey("permission_granted")

/** 是否首次启动（默认 true）。 */
fun firstLaunchFlow(context: Context): Flow<Boolean> =
  context.dataStore.data.map { it[KeyFirstLaunch] ?: true }

/** 记录引导完成（isFirstLaunch = false）。 */
suspend fun markOnboardingDone(context: Context) {
  context.dataStore.edit { it[KeyFirstLaunch] = false }
}

/** 已选择权限模式（默认普通权限用户）。 */
fun modeFlow(context: Context): Flow<String> =
  context.dataStore.data.map { it[KeyPermissionMode] ?: MODE_NORMAL }

/** 当前权限模式（同步读取；默认普通权限用户）。 */
fun currentMode(context: Context): String =
  runBlocking { context.dataStore.data.first()[KeyPermissionMode] ?: MODE_NORMAL }

/** 保存权限模式。 */
suspend fun savePermissionMode(context: Context, mode: String) {
  context.dataStore.edit { it[KeyPermissionMode] = mode }
}

/** 权限是否已获取（同步读取；默认未获取）。 */
fun permissionGranted(context: Context): Boolean =
  runBlocking { context.dataStore.data.first()[KeyPermissionGranted] ?: false }

/** 记录权限标记，成功/失败均写日志。 */
suspend fun markPermissionGranted(context: Context, granted: Boolean) {
  try {
    context.dataStore.edit { it[KeyPermissionGranted] = granted }
    Logs.logEvent(context, "PermStore", "markPermissionGranted=$granted")
  } catch (t: Throwable) {
    Logs.logEvent(context, "PermStore", "markPermissionGranted FAILED=$granted", t)
  }
}
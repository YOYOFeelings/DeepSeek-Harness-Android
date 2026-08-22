package com.yoyo.dshmobile.shell.ui.screen

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** 工作区目录偏好：SAF（系统目录选择器）选中的目录 URI 与显示名持久化，重启后仍在。 */
private val Context.workspaceDataStore by preferencesDataStore(name = "workspace")

private val KeyDirUri = stringPreferencesKey("workspace_dir_uri")
private val KeyDirDisplay = stringPreferencesKey("workspace_dir_display")

/** 工作区目录持久化（每次通过系统选择器切换目录后保存）。 */
object WorkspacePrefs {

  /** 保存所选目录 URI 与显示名。 */
  suspend fun save(context: Context, uri: String, display: String) {
    context.workspaceDataStore.edit { p ->
      p[KeyDirUri] = uri
      p[KeyDirDisplay] = display
    }
  }

  /** 读取已保存目录显示名（无则 null）。 */
  suspend fun loadDisplay(context: Context): String? =
    runCatching { context.workspaceDataStore.data.first()[KeyDirDisplay] }.getOrNull()

  /** 读取已保存目录 URI 字符串（无则 null）。 */
  suspend fun loadUri(context: Context): String? =
    runCatching { context.workspaceDataStore.data.first()[KeyDirUri] }.getOrNull()
}
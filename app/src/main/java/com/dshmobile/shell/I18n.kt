package com.dshmobile.shell

import android.content.Context

/**
 * 应用内中英文文案设施（默认中文 + 设置页「语言 / Language」切换）。
 *
 * 实现方式：代码级文案表（不依赖系统资源 locale，规避 OEM 上资源 locale
 * 失效/不跟随的问题）。所有界面文案统一经 [t] 取：
 *   - [lang]：当前语言，读 `dsh_shell/app_lang`（缺省 `"zh"`）；
 *   - [set]：切换语言并持久化，调用方随后重建界面；
 *   - [isZh]：是否为中文（用于格式化逻辑分支）。
 */
object I18n {

  private const val PREFS = "dsh_shell"
  private const val KEY = "app_lang"
  const val LANG_ZH = "zh"
  const val LANG_EN = "en"

  fun lang(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, LANG_ZH) ?: LANG_ZH

  fun set(context: Context, lang: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
  }

  fun isZh(context: Context): Boolean = lang(context) == LANG_ZH

  /** 按当前语言返回中/英文文案。 */
  fun t(context: Context, zh: String, en: String): String = if (isZh(context)) zh else en
}

package com.yoyo.dshmobile.shell.core

/**
 * 语言文案设施（默认中文）。模块内不再依赖 Android Context 做语言开关的强绑定，
 * 而是由注入的 [LangProvider] 提供当前语言，方便单元测试与多 Screen 复用。
 */
object I18n {

  const val LANG_ZH = "zh"
  const val LANG_EN = "en"

  /** 语言提供者：可在 App 启动时注入实现（读取 SharedPreferences 等）。 */
  @Volatile
  var provider: LangProvider? = null

  fun interface LangProvider {
    fun current(): String
  }

  fun isZh(): Boolean = provider?.current() ?: LANG_ZH == LANG_ZH

  /** 按当前语言返回中文/英文文案。 */
  fun t(zh: String, en: String): String = if (isZh()) zh else en

  /** 兼容旧式传 Context 的调用：尽量不使用，模块化后统一走 [t]。 */
  @Deprecated("Use t(zh, en) without Context")
  fun t(context: Any?, zh: String, en: String): String = t(zh, en)
}
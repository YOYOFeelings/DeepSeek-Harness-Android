package com.yoyo.dshmobile.shell.core.logging

/**
 * 日志抽象接口：模块化后各模块通过 [Logger] 记录日志，具体实现（落盘/轮转/logcat）
 * 由 App 层注入，避免模块间直接耦合 Android Log。
 */
interface Logger {
  fun verbose(tag: String, msg: String)
  fun debug(tag: String, msg: String)
  fun info(tag: String, msg: String)
  fun warn(tag: String, msg: String)
  fun error(tag: String, msg: String, throwable: Throwable? = null)
}

/**
 * 空实现：不输出任何日志。供默认注入或单元测试使用；App 层可替换为真实实现。
 */
object NoopLogger : Logger {
  override fun verbose(tag: String, msg: String) {}
  override fun debug(tag: String, msg: String) {}
  override fun info(tag: String, msg: String) {}
  override fun warn(tag: String, msg: String) {}
  override fun error(tag: String, msg: String, throwable: Throwable?) {}
}
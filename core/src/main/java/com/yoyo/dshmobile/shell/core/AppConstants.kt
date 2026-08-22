package com.yoyo.dshmobile.shell.core

/**
 * 应用级常量（模块化后统一收敛于此，避免散落各处魔法值）。
 */
object AppConstants {
  /** 应用包名（applicationId）。 */
  const val PACKAGE_NAME = "com.yoyo.dshmobile.shell"

  /** 最小 SDK / 目标 SDK。 */
  const val MIN_SDK = 26
  const val TARGET_SDK = 34
  const val COMPILE_SDK = 36

  /** 内部 Web/引擎服务端口占位（后续内核迁移时使用）。 */
  const val ENGINE_LOOPBACK_URL = "http://127.0.0.1:3080"
}
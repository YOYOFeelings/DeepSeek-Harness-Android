// 顶层构建脚本：声明插件版本（apply false），供各子模块按需应用。
plugins {
  id("com.android.application") version "8.8.2" apply false
  id("com.android.library") version "8.8.2" apply false
  id("org.jetbrains.kotlin.android") version "2.0.21" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
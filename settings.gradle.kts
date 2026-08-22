pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "dsh-mobile-apk-yoyo"

// 原生 NDK 模块开关：无 NDK/离线环境可设 -PenableNative=false 跳过 :native 编译
val enableNative = (providers.gradleProperty("enableNative").orNull ?: "true").toBoolean()

include(":app")
include(":core")
include(":engine")
if (enableNative) {
  include(":native")
}
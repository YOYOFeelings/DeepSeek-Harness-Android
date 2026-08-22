plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.yoyo.dshmobile.shell.ffi"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
    targetSdk = 34

    // 打包时把按 ABI 生成的 .so 包进 APK
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}
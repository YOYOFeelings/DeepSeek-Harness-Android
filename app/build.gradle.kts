import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

// 项目专用签名：从根目录 keystore.properties 读取密钥库配置。
// debug 与 release 统一使用同一密钥库，保证每次构建 APK 签名一致
// （同一 APK 必须同一签名，否则覆盖安装会因签名不一致失败，规则 8.8）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasKeystore = keystorePropsFile.exists()

android {
  namespace = "com.yoyo.dshmobile.shell"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.yoyo.dshmobile.shell"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    // 当 :native 在 settings 中被启用时才置 true，供运行时反射加载原生桥
    val enableNative = providers.gradleProperty("enableNative").orNull?.toBoolean() ?: true
    buildConfigField("boolean", "ENABLE_NATIVE", enableNative.toString())
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  signingConfigs {
    if (hasKeystore) {
      create("project") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/release.jks"))
        storePassword = keystoreProps.getProperty("storePassword", "")
        keyAlias = keystoreProps.getProperty("keyAlias", "dsh")
        keyPassword = keystoreProps.getProperty("keyPassword", "")
      }
    }
  }

  buildTypes {
    debug {
      // debug 与 release 使用同一项目签名，保证升级安装签名一致。
      if (hasKeystore) signingConfig = signingConfigs.getByName("project")
    }
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      if (hasKeystore) signingConfig = signingConfigs.getByName("project")
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

dependencies {
  implementation(project(":core"))
  implementation(project(":engine"))
  // 原生模块：默认随 build 内置（打包 .so 入 APK）；关闭 enableNative 时由 settings 移出，
  // 依赖与 BuildConfig 均随同一属性联动，保证两种模式都能配置与编译。
  if ((providers.gradleProperty("enableNative").orNull?.toBoolean() ?: true)) {
    implementation(project(":native"))
  }
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("com.google.android.material:material:1.12.0")

  // 原生 View 引导页：ViewPager2 承载 4 页滑动（引导页重构新增）
  implementation("androidx.viewpager2:viewpager2:1.0.0")

  // Jetpack Compose
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Lifecycle / ViewModel
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

  // DataStore（存储 isFirstLaunch）
  implementation("androidx.datastore:datastore-preferences:1.1.1")

  // Shizuku
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")

  // 引擎在线运行时更新：rootfs.tar.xz 解压（XZ + Tar）
  implementation("org.tukaani:xz:1.9")
  implementation("org.apache.commons:commons-compress:1.26.2")
}
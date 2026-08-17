import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// 项目专用签名：从根目录 keystore.properties 读取密钥库配置。
// debug 与 release 统一使用同一密钥库，保证每次构建 APK 签名一致
// （同一 APK 必须同一签名，否则覆盖安装会因签名不一致失败）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasKeystore = keystorePropsFile.exists()

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 34: Android 15+ forbids exec of app-data ELF for targetSdk 35+
    // (the embedded engine, bash, and every child command would need linker64
    // wrappers); 34 keeps native exec working on Android 15/16 devices.
    targetSdk = 34
    versionCode = 27
    versionName = "0.11.7"
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

  androidResources {
    // snapshot.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  buildTypes {
    debug {
      // debug 与 release 使用同一项目签名，保证升级安装签名一致。
      if (hasKeystore) signingConfig = signingConfigs.getByName("project")
    }
    release {
      isMinifyEnabled = false
      if (hasKeystore) signingConfig = signingConfigs.getByName("project")
    }
  }

  // 输出 APK 重命名为带版本号的品牌名（在下方 doLast 复制任务中完成，
  // 因为 AGP 8.8 的 VariantOutput API 已移除 outputFileName 设置）。

  lint {
    // 离线环境无 lint-gradle 依赖缓存（国内网络）；lint 非发布关键路径。
    checkReleaseBuilds = false
    abortOnError = false
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

// 运行时快照来自 GitHub Releases（大文件不入库）；缺失时构建失败并给出获取指引。
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val snap = file("src/main/assets/snapshot.tar.xz")
      if (!snap.exists()) {
        throw GradleException(
          "缺少运行时快照 assets/snapshot.tar.xz —— " +
            "从 GitHub Releases 下载 snapshot-x86_64.tar.xz 后放到 app/src/main/assets/snapshot.tar.xz，" +
            "或按 scripts/make-snapshot.sh 在 Termux 设备自打后拉取（见 README.md）",
        )
      }
    }
  }
}

// 构建完成后将 APK 复制到项目根目录，并按品牌名 + 版本号重命名。
// 版本号在配置期从 defaultConfig 读取（AGP 8.8 的 VariantOutput 已无 outputFileName API）。
val harnessVersionName: String = android.defaultConfig.versionName ?: "0"
tasks.matching { it.name == "assembleDebug" }.configureEach {
  doLast {
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
    apkDir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { f ->
      val dest = file("${rootDir}/deepseek-harness-${harnessVersionName}-debug.apk")
      f.copyTo(dest, overwrite = true)
      println("APK 已复制到: ${dest.absolutePath}")
    }
  }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
  doLast {
    val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    apkDir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { f ->
      val dest = file("${rootDir}/deepseek-harness-${harnessVersionName}-release.apk")
      f.copyTo(dest, overwrite = true)
      println("APK 已复制到: ${dest.absolutePath}")
    }
  }
}

dependencies {
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("org.apache.commons:commons-compress:1.27.1")
  implementation("org.tukaani:xz:1.10")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
}
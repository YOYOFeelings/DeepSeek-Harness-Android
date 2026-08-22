package com.yoyo.dshmobile.shell

import android.app.Application
import android.content.Context
import com.yoyo.dshmobile.engine.EngineRootfs
import com.yoyo.dshmobile.engine.EngineService
import com.yoyo.dshmobile.shell.log.LogFox
import com.yoyo.dshmobile.shell.log.Logs

/**
 * 应用入口。在最早的时机（attachBaseContext）挂载全局未捕获异常处理器，
 * 并在 onCreate 启动 logcat 抓取，确保引导页/主页闪退时能将崩溃快照写入私有目录
 * filesDir/logs/（不提供导出 UI，root 可直接读取）。
 */
class DshApp : Application() {

  override fun attachBaseContext(base: Context?) {
    super.attachBaseContext(base)
    // 尽早挂载崩溃处理器：覆盖 Application.onCreate 及更早的类初始化异常
    runCatching { LogFox.installCrashHandler(this) }
  }

  override fun onCreate() {
    super.onCreate()
    // 启动本进程 logcat 抓取到私有目录 logcat.log
    runCatching { LogFox.startCapture(this) }
    Logs.logE(this, "App", "onCreate")
    // Shizuku 应用识别依赖 AndroidManifest 中的 ShizukuProvider（authorities=${applicationId}.shizuku），
    // 由系统/Shizuku 管理器据此识别应用与包名，无需在代码中手动 bind。
    // 自动启动引擎（默认关闭）：设置页「自动启动引擎」开关开启且 rootfs 已就绪时自动拉起。
    // 全程 runCatching：开启时启动失败不闪退（引擎启动本身也有崩溃防护）。
    runCatching {
      val enginePrefs = getSharedPreferences("engine_prefs", Context.MODE_PRIVATE)
      if (enginePrefs.getBoolean("auto_start", false) && EngineRootfs.isExtracted(this)) {
        EngineService.start(this)
      }
    }
  }
}
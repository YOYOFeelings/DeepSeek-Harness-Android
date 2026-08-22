package com.yoyo.dshmobile.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yoyo.dshmobile.engine.R
import com.yoyo.dshmobile.shell.log.Logs
import java.io.File

/**
 * 引擎前台 Service：负责确保 rootfs 解压 -> 启动 node 引擎 -> 起看门狗保活。
 * rootfs 未就绪时优雅降级（更新通知为「运行时未就绪」），不崩溃；启动失败同样不闪退。
 */
class EngineService : Service() {

  companion object {
    private const val CHANNEL_ID = "engine"
    private const val NOTIFICATION_ID = 1

    // 最近一次 EngineProcess.start 尝试的状态（供会话页 MD3 启动弹窗实时反馈；@Volatile 保证跨线程可见）
    @Volatile var lastStartSeq = 0L
    @Volatile var lastStartFailed = false
    @Volatile var lastStartError: String? = null

    fun start(context: Context) {
      val intent = Intent(context, EngineService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, EngineService::class.java))
    }
  }

  private var handle: EngineHandle? = null
  private var watchdog: EngineWatchdog? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.engine_running)))
    val result = EngineRootfs.ensureExtracted(this)
    when (result) {
      EngineRootfs.Result.OK, EngineRootfs.Result.EXTRACTED_ALREADY -> {
        if (handle == null) {
          // 启动失败不闪退：runCatching 包裹，失败写日志 + 通知，且不起看门狗（避免反复拉起坏引擎）
          val started = runCatching {
            EngineProcess.start(this, EngineRootfs.rootfsDir(this))
          }.onFailure { t ->
            Logs.logEvent(this, "Engine", "engine-start-fail", t)
            updateNotification(getString(R.string.engine_start_fail))
            lastStartSeq++
            lastStartFailed = true
            lastStartError = t?.message ?: "未知错误"
          }.getOrNull()
          if (started != null) {
            handle = started
            lastStartSeq++
            lastStartFailed = false
            lastStartError = null
          }
        }
        if (handle != null && watchdog == null) {
          watchdog = EngineWatchdog(
            context = this,
            onRestart = { ts -> Logs.logEvent(this, "Engine", "watchdog-restart ts=$ts"); restartEngine() },
            onStop = { stopEngine() },
          ).also { it.start() }
        }
      }
      EngineRootfs.Result.NO_ASSET, EngineRootfs.Result.FAILED -> {
        Logs.logEvent(this, "Engine", "runtime-not-ready result=$result")
        updateNotification(getString(R.string.engine_no_rootfs))
      }
    }
    return START_STICKY
  }

  private fun restartEngine() {
    // 重启前预检引擎环境：关键文件缺失则跳过启动（不起新进程），避免无限拉起坏引擎
    val rootfs = EngineRootfs.rootfsDir(this)
    val usr = File(rootfs, "usr")
    val critical = EngineProcess.verifyCriticalFiles(usr)
    if (critical.isNotEmpty()) {
      Logs.logEvent(this, "Engine", "engine-env-broken-skip-restart")
      return
    }
    EngineProcess.stop(handle)
    handle = null
    val started = runCatching {
      EngineProcess.start(this, EngineRootfs.rootfsDir(this))
    }.onFailure { t ->
      Logs.logEvent(this, "Engine", "engine-restart-fail", t)
      updateNotification(getString(R.string.engine_start_fail))
      lastStartSeq++
      lastStartFailed = true
      lastStartError = t?.message ?: "未知错误"
    }.getOrNull()
    if (started != null) {
      handle = started
      lastStartSeq++
      lastStartFailed = false
      lastStartError = null
    }
  }

  private fun stopEngine() {
    EngineProcess.stop(handle)
    handle = null
    updateNotification(getString(R.string.engine_stopped))
  }

  override fun onDestroy() {
    watchdog?.stop()
    watchdog = null
    EngineProcess.stop(handle)
    handle = null
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.engine_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      )
      nm.createNotificationChannel(channel)
    }
  }

  // stat_sys_gears 在 compileSdk 36 的 android.R 中不存在，改用可用的同步图标 stat_notify_sync。
  private fun buildNotification(text: String): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(getString(R.string.engine_notify_title))
      .setContentText(text)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

  private fun updateNotification(text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, buildNotification(text))
  }
}
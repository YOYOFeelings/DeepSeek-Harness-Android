package com.dshmobile.shell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service owning the embedded engine lifecycle: keeps the app
 * process alive while backgrounded (user-visible notification) and restarts
 * the engine process when it dies (watchdog). M2 keep-alive, no root needed.
 */
class EngineService : Service() {

  private lateinit var engineManager: EngineManager
  private var watchdog: ScheduledExecutorService? = null

  override fun onCreate() {
    super.onCreate()
    engineManager = EngineManager(this)
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureEngine()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    watchdog?.shutdownNow()
    watchdog = null
    super.onDestroy()
  }

  /** Start the engine if not running, then arm the watchdog. */
  private fun ensureEngine() {
    // 看门狗无条件初始化：与引擎当前状态解耦（引擎已运行时也必须能接管 crash 恢复）。
    if (watchdog == null) {
      watchdog = Executors.newSingleThreadScheduledExecutor().also { exec ->
        exec.scheduleWithFixedDelay({
          // Web 打开期间不探测计数、不自动重启（用户在主动使用，引擎可能繁忙中）。
          if (!EngineManager.webActive && engineManager.engineReady) {
            if (EngineProbe.check(timeoutMs = 2000).optBoolean("running", false)) {
              failStreak = 0
            } else {
              failStreak++
              if (failStreak >= 3) {
                failStreak = 0
                // force=true：端口已释放才重启；STARTING CAS 仍防并发双启动。
                engineManager.startEngine(force = true)
              }
            }
          }
        }, 5, 5, TimeUnit.SECONDS)
      }
    }
    // 首次立即尝试一次启动（引擎未运行、运行时就绪且 Web 未打开时）。
    if (!EngineManager.webActive && engineManager.engineReady &&
      !EngineProbe.check(timeoutMs = 2000).optBoolean("running", false)
    ) {
      engineManager.startEngine(force = true)
    }
  }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(NotificationChannel("engine", "dsh 引擎", NotificationManager.IMPORTANCE_LOW))
    }
    val pending = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, "engine")
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle("dsh 引擎运行中")
      .setContentText("DeepSeek Harness 正在后台工作")
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val NOTIFICATION_ID = 2

    /** 看门狗连续探测失败计数（>=3 才重启，容忍引擎短暂繁忙）。 */
    @Volatile private var failStreak = 0
  }
}

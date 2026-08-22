package com.yoyo.dshmobile.engine

import android.content.Context
import com.yoyo.dshmobile.shell.log.Logs

/**
 * 引擎看门狗：单线程每 5s 探活 3080。
 * 连续失败 -> 触发一次重启并复位计数；连续失败超 3 次 -> onStop。
 */
class EngineWatchdog(
  private val context: Context,
  private val onRestart: (Long) -> Unit,
  private val onStop: () -> Unit,
) {

  private val thread = Thread { loop() }

  @Volatile
  private var running = false

  private var consecutiveFail = 0

  fun start() {
    if (running) return
    running = true
    thread.name = "dsh-watchdog"
    thread.start()
  }

  fun stop() {
    running = false
    runCatching { thread.interrupt() }
  }

  private fun loop() {
    while (running) {
      try {
        Thread.sleep(5000)
      } catch (_: InterruptedException) {
        break
      }
      if (!running) break
      val alive = runCatching { EngineProcess.probe(context, 1000) }.getOrDefault(false)
      if (alive) {
        if (consecutiveFail != 0) consecutiveFail = 0
        continue
      }
      consecutiveFail += 1
      Logs.logEvent(context, "Engine", "watchdog-probe-fail fail=$consecutiveFail")
      if (consecutiveFail >= 3) {
        Logs.logEvent(context, "Engine", "watchdog-stop")
        running = false
        onStop()
        break
      } else {
        // 触发重启不复位计数：坏引擎连续失败累计达 3 次即停机，防无限重启风暴
        onRestart(System.currentTimeMillis())
      }
    }
  }
}
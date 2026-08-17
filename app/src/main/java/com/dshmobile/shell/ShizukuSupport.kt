package com.dshmobile.shell

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku integration (M2 keep-alive boost, stage 1): detect the
 * Shizuku server and report status. The appops-application step needs the
 * shell-exec API (Shizuku.newProcess is not public in api 13.1.5; upgrade the
 * dependency or route via a user service) — deferred, see docs/M2-NOTES.md.
 * Everything degrades gracefully when Shizuku is absent.
 */
object ShizukuSupport {

  /** True when the Shizuku server binder is reachable. */
  fun isAvailable(): Boolean {
    return try {
      Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
  }

  /** Status text for the UI; never throws.
   *  BUG-修复：原代码在 isAvailable() 与 getVersion() 之间存在 TOCTOU 窗口——
   *  Shizuku server 在这两步之间退出时 getVersion() 抛 NPE 到主线程导致崩溃。
   *  把 getVersion 纳入 try/catch，与 isAvailable 保持同等防护。 */
  fun status(context: Context): String {
    return try {
      if (isAvailable()) "Shizuku 已授权（v" + Shizuku.getVersion() + "）——保活增强就绪"
      else "Shizuku 未运行（可选：后台保活增强需要它）"
    } catch (_: Throwable) {
      "Shizuku 状态未知（server 可能已退出）"
    }
  }
}

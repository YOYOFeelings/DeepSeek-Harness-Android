package com.dshmobile.shell

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Probes the local dsh web engine (127.0.0.1:3080) from the shell side. */
object EngineProbe {

  const val ENGINE_URL = "http://127.0.0.1:3080"

  /**
   * One-shot reachability probe. Safe on any thread (never the main thread).
   * @param timeoutMs connect+read budget per attempt.
   * @returns JSON: {running: Boolean, latencyMs: Int, error?: String, reason?: String}
   *   reason 仅在 running=false 时出现：refused=连接拒绝（引擎确未运行）、
   *   timeout=超时（引擎繁忙/无响应）、其余为异常类简单名。
   */
  fun check(timeoutMs: Int = 2000): JSONObject {
    return try {
      val conn = URL(ENGINE_URL).openConnection() as HttpURLConnection
      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      conn.requestMethod = "GET"
      val start = System.currentTimeMillis()
      val code = conn.responseCode
      conn.disconnect()
      JSONObject()
        .put("running", code == 200)
        .put("latencyMs", System.currentTimeMillis() - start)
    } catch (e: Exception) {
      val reason = when (e) {
        is java.net.ConnectException -> "refused"
        is java.net.SocketTimeoutException -> "timeout"
        else -> e.javaClass.simpleName
      }
      JSONObject().put("running", false).put("reason", reason).put("error", e.message ?: "unknown")
    }
  }
}

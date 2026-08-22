package com.yoyo.dshmobile.shell.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程用户协议加载器：从 GitHub Raw 拉取《用户协议》正文。
 *
 * 设计原则：
 * - 远程拉取失败（网络异常 / 超时 / 非 200 / 空内容）一律回退到内置占位文本，绝不阻断引导流程；
 * - 网络请求只在 IO 线程执行，绝不在主线程做网络；
 * - 对外方法绝不抛异常。
 */
object RemotePolicyLoader {

  /** 远程协议地址：集中配置，方便后期替换为真实协议 URL。 */
  const val POLICY_REMOTE_URL =
    "https://raw.githubusercontent.com/YOYOFeelings/DeepSeek-Harness-Android/main/docs/privacy-policy.md"

  /** 协议标题。 */
  const val POLICY_TITLE = "用户协议"

  /** 内置占位协议正文（远程拉取失败时使用，约 400 字）。 */
  val POLICY_PLACEHOLDER: String = """
    欢迎使用 deepseek HARNESS。在使用本应用前，请仔细阅读并理解以下条款。你点击「同意并继续」或开始使用，即视为已接受本协议全部内容。

    一、服务说明：本应用为用户提供终端指令执行与设备管理能力，并承诺在法律法规允许的范围内提供服务；因不可抗力、网络故障等造成的中断或延迟，予以免责。

    二、用户义务：你应保证使用行为符合法律法规及公序良俗，不得利用本应用从事危害国家安全、侵犯他人合法权益或干扰系统正常运行的违法行为；因个人使用不当造成的后果由你自行承担。

    三、隐私与数据：我们重视你的隐私安全。本应用默认仅在设备本地处理数据，未经你明确授权不会上传你的个人信息、命令记录或敏感数据。

    四、责任限制：在法律允许的最大范围内，因使用本应用产生的直接或间接损失，我们仅在自身故意或重大过失导致的前提下承担责任。

    五、协议更新：我们可能不时修订本协议，修订后将在应用内公示；重大变更前会征得你的同意，继续使用视为接受修订后的协议。

    如对本协议有任何疑问，欢迎通过应用内反馈渠道与我们联系。
  """.trimIndent()

  /**
   * 在 IO 线程拉取远程协议正文。
   *
   * 返回远程正文（HTTP 200 且内容非空），否则返回 [POLICY_PLACEHOLDER]。
   * 本方法绝不抛异常；connect / read 超时各 8 秒。
   */
  suspend fun loadPolicy(context: Context): String = withContext(Dispatchers.IO) {
    try {
      val connection = (URL(POLICY_REMOTE_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        requestMethod = "GET"
      }
      try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          return@withContext POLICY_PLACEHOLDER
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        if (body.isBlank()) POLICY_PLACEHOLDER else body
      } finally {
        connection.disconnect()
      }
    } catch (_: Exception) {
      POLICY_PLACEHOLDER
    }
  }

  /** 用系统浏览器打开完整协议（用于「查看全部协议」链接）。 */
  fun openFullPolicy(context: Context) {
    runCatching {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_REMOTE_URL))
      context.startActivity(intent)
    }
  }
}

package com.yoyo.dshmobile.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 更新源：官方直连或某个国内加速前缀。prefix 为空 = 官方直连。 */
data class Mirror(val id: String, val name: String, val prefix: String) {
  /** 解析 GitHub 家族 URL：命中时前置加速前缀（官方直连代表原样返回）。 */
  fun resolve(url: String): String {
    if (prefix.isEmpty()) return url
    val host = try {
      URL(url).host.lowercase()
    } catch (_: Exception) {
      return url
    }
    return if (host in EngineMirrors.GH_HOSTS) prefix + url else url
  }
}

/**
 * 引擎 rootfs 下载镜像源表。
 * 与旧项目 (dsh-mobile-apk) 的更新源一致；**仅代码内置、无用户自定义入口**
 * （添加镜像是开发者侧的事，终端用户只能「选择」）。
 */
object EngineMirrors {

  /** GitHub 家族域名：命中时加加速前缀。 */
  val GH_HOSTS = setOf(
    "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
    "codeload.github.com", "api.github.com", "release-assets.githubusercontent.com",
    "gist.githubusercontent.com", "user-images.githubusercontent.com",
  )

  /** 内置引擎更新源（官方直连 + 国内加速源）。akaere 为首项（默认展示）；official 为空前缀 = 官方直连。 */
  val BUILTIN_MIRRORS = listOf(
    Mirror("akaere", "cdn.akaere.online", "https://cdn.akaere.online/"),
    Mirror("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/"),
    Mirror("official", "官方直连", ""),
    Mirror("ghproxy.net", "ghproxy.net", "https://ghproxy.net/"),
    Mirror("ghproxy.cn", "ghproxy.cn", "https://ghproxy.cn/"),
    Mirror("ghfast", "ghfast.top", "https://ghfast.top/"),
    Mirror("noki", "gh.noki.icu", "https://gh.noki.icu/"),
    Mirror("fastgit", "fastgit.cc", "https://fastgit.cc/"),
    Mirror("monkeyray", "ghproxy.monkeyray.net", "https://ghproxy.monkeyray.net/"),
    Mirror("669966", "git.669966.xyz", "https://git.669966.xyz/"),
    Mirror("felicity", "gh.felicity.ac.cn", "https://gh.felicity.ac.cn/"),
    Mirror("inkchills", "gh.inkchills.cn", "https://gh.inkchills.cn/"),
    Mirror("cxkpro", "ghproxy.cxkpro.top", "https://ghproxy.cxkpro.top/"),
    Mirror("tvv", "tvv.tw", "https://tvv.tw/"),
    Mirror("078465", "ghm.078465.xyz", "https://ghm.078465.xyz/"),
    Mirror("bugdey", "gh.bugdey.us.kg", "https://gh.bugdey.us.kg/"),
    Mirror("xxooo", "gh.xxooo.cf", "https://gh.xxooo.cf/"),
    Mirror("jasonzeng", "gh.jasonzeng.dev", "https://gh.jasonzeng.dev/"),
    Mirror("dpik", "gh.dpik.top", "https://gh.dpik.top/"),
    Mirror("eqrr82bzpe", "ghf.xn--eqrr82bzpe.top", "https://ghf.xn--eqrr82bzpe.top/"),
    Mirror("927223", "gh.927223.xyz", "https://gh.927223.xyz/"),
    Mirror("imciel", "ghproxy.imciel.com", "https://ghproxy.imciel.com/"),
    Mirror("geekertao", "ghfile.geekertao.top", "https://ghfile.geekertao.top/"),
    Mirror("zkitefly", "gp.zkitefly.eu.org", "https://gp.zkitefly.eu.org/"),
    Mirror("mrhjx", "gitproxy.mrhjx.cn", "https://gitproxy.mrhjx.cn/"),
  )

  fun all(): List<Mirror> = BUILTIN_MIRRORS

  fun byId(id: String): Mirror? = BUILTIN_MIRRORS.firstOrNull { it.id == id }

  /** 探测某镜像对 url 的实际延迟（ms）；失败返回 null。 */
  suspend fun speedTest(url: String, mirror: Mirror, timeoutMs: Int = 4000): Long? =
    withContext(Dispatchers.IO) {
      runCatching {
        val conn = URL(mirror.resolve(url)).openConnection() as HttpURLConnection
        try {
          conn.connectTimeout = timeoutMs
          conn.readTimeout = timeoutMs
          conn.requestMethod = "GET"
          val t0 = System.currentTimeMillis()
          conn.connect()
          val latency = System.currentTimeMillis() - t0
          if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
          // 读首字节确认 body 可达（镜像转发可用）
          conn.inputStream.use { it.skip(8) }
          latency
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { conn.disconnect() }
        }
      }.getOrNull()
    }
}
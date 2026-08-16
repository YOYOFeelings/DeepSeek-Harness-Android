package com.dshmobile.shell

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.jvm.Synchronized
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下载记录（持久化到 files/download-history.json）：
 * 记录运行时快照在线更新、Node/Python 环境安装等下载类操作的历史，
 * 供「主页 → 下载记录」卡片展示。仅用于界面展示，不参与任何业务逻辑；
 * 读写失败一律静默（不影响下载/安装主流程）。
 */
object DownloadHistory {

  /** 单条记录。status：成功 / 失败 / 进行中。 */
  data class Record(
    val time: Long,
    val name: String,
    val size: String,
    val source: String,
    val status: String,
    val detail: String = "",
  ) {
    /** 展示用时间标签（MM-dd HH:mm）。 */
    fun timeLabel(): String =
      SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(time))
  }

  /** 最多保留的记录条数（超出丢弃最旧）。 */
  private const val MAX_RECORDS = 60

  private fun file(ctx: Context): File = File(ctx.filesDir, "download-history.json")

  /** 追加一条记录（最新在前）。线程安全；失败静默。 */
  @Synchronized
  fun add(ctx: Context, record: Record) {
    try {
      val list = loadUnsafe(ctx).toMutableList()
      list.add(0, record)
      if (list.size > MAX_RECORDS) list.subList(MAX_RECORDS, list.size).clear()
      val arr = JSONArray()
      for (r in list) {
        arr.put(
          JSONObject().apply {
            put("time", r.time)
            put("name", r.name)
            put("size", r.size)
            put("source", r.source)
            put("status", r.status)
            put("detail", r.detail)
          },
        )
      }
      file(ctx).writeText(arr.toString())
    } catch (_: Throwable) {
    }
  }

  /** 读取全部记录（最新在前）；文件缺失/损坏返回空列表。 */
  fun list(ctx: Context): List<Record> = try {
    loadUnsafe(ctx)
  } catch (_: Throwable) {
    emptyList()
  }

  /** 清空记录。失败静默。 */
  @Synchronized
  fun clear(ctx: Context) {
    try {
      file(ctx).delete()
    } catch (_: Throwable) {
    }
  }

  /** 无 try 包裹的读取；由 [add]/[list] 捕获异常。 */
  private fun loadUnsafe(ctx: Context): List<Record> {
    val f = file(ctx)
    if (!f.exists()) return emptyList()
    val text = f.readText()
    if (text.isBlank()) return emptyList()
    val arr = JSONArray(text)
    return (0 until arr.length()).mapNotNull { i ->
      val o = arr.optJSONObject(i) ?: return@mapNotNull null
      Record(
        time = o.optLong("time"),
        name = o.optString("name"),
        size = o.optString("size"),
        source = o.optString("source"),
        status = o.optString("status"),
        detail = o.optString("detail"),
      )
    }
  }
}

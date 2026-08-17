package com.dshmobile.shell

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** LogFox - 内置增强日志采集（LogFox-style）。
 *  - 私有目录 `filesDir/logs/` 存储全部日志
 *  - 用户行为跟踪：超详细记录每次导航切换、页面打开、按钮点击（带时间戳）
 *  - 50MB 轮转裁剪：总大小超限时按 `lastModified` 删除最早文件
 *  - `logcat.log` 抓取本应用 UID 系统日志（普通应用可读，无需权限，OEM 拒绝则优雅降级）
 *  - 崩溃快照：崩溃时追加 logcat 尾部 + 用户行为尾部到 `crash-snapshot.txt`
 *  - 累计统计：导航/页面/按钮点击计数，文本持久化
 */
object LogFox {

    private const val MAX_TOTAL_MB = 50
    private const val MAX_LOGBYTES = 8 * 1024 * 1024 // 8MB logcat 自身上限
    private const val FLUSH_INTERVAL_STATS = 10 // 每 10 次 track 刷一次统计到磁盘

    // 新增日志文件名
    private const val USER_ACTIONS = "user-actions.log"
    private const val USER_STATS = "user-stats.txt"
    private const val LOGCAT = "logcat.log"
    private const val CRASH_SNAP = "crash-snapshot.txt"

    // 崩溃标记 sharedPreferences key
    const val PREF_CRASH_MARKER = "logfox_crash_marker"

    // 统计计数（内存 + 磁盘：启动读、每次 track 写、flush 落盘）
    private val stats = ConcurrentHashMap<String, Long>()
    private var tracksSinceFlush = 0
    private var lastTrimTime = 0L

    // logcat 抓取进程（后台线程持续读）
    private var logcatProcess: Process? = null
    private var logcatThread: Thread? = null
    private var captureStarted = false

    // 格式化工具
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun userActionsLog(context: Context): File = File(Logs.dir(context), USER_ACTIONS)
    fun userStatsFile(context: Context): File = File(Logs.dir(context), USER_STATS)
    fun logcatLog(context: Context): File = File(Logs.dir(context), LOGCAT)
    fun crashSnapshot(context: Context): File = File(Logs.dir(context), CRASH_SNAP)

    /** 启动 logcat 抓取（尽早在 onCreate 调用）。 */
    fun startCapture(context: Context) {
        if (captureStarted) return
        captureStarted = true

        // 加载统计从磁盘
        loadStats(context)
        // 启动后台 logcat 进程抓本应用
        try {
            val pid = android.os.Process.myPid()
            // -v threadtime -d 是打印然后退出，我们要持续抓所以不加 -d
            // 只过滤本 PID：logcat -v threadtime *:I | grep ${pid}
            val pb = ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "--pid=$pid")
                .redirectErrorStream(true)
            val logFile = logcatLog(context)
            logFile.parentFile?.mkdirs()
            // 先截断已有 logcat 到零（新会话）
            logFile.delete()
            logFile.createNewFile()

            logcatProcess = pb.start()
            logcatThread = Thread {
                try {
                    val br = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                    val pw = PrintWriter(logFile.bufferedWriter())
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        if (line != null) {
                            pw.println(line)
                            pw.flush()
                        }
                    }
                    pw.flush()
                    pw.close()
                } catch (_: Throwable) {
                    // 进程被杀死正常退出
                }
            }
            logcatThread?.start()
            Logs.logE(context, "LogFox", "logcat capture started (pid=$pid)")
        } catch (e: Throwable) {
            Logs.logE(context, "LogFox", "logcat start failed (OEM may deny, app level logs still available)", e)
            // 不影响主流程
        }
    }

    /** 停止抓取（onDestroy 调用，不影响崩溃日志，本应用退出后自然结束）。 */
    fun stopCapture() {
        captureStarted = false
        logcatProcess?.destroy()
        logcatProcess = null
        logcatThread = null
    }

    /** 记录用户行为：category=nav/page/button/engine/update/other。 */
    @Synchronized
    fun trackUser(context: Context, category: String, detail: String) {
        val line = "[${df.format(Date())}] [$category] $detail"
        Logs.append(userActionsLog(context), line)
        // 统计计数
        val key = when (category) {
            "nav" -> "nav_switches"
            "page" -> "page_$detail"
            "button" -> "button_$detail"
            else -> "other_$category"
        }
        stats[key] = (stats[key] ?: 0) + 1
        tracksSinceFlush++
        // 节流刷统计到磁盘
        if (tracksSinceFlush >= FLUSH_INTERVAL_STATS || (System.currentTimeMillis() - lastTrimTime) > TimeUnit.SECONDS.toMillis(5)) {
            flushStats(context)
            trimIfOverLimit(context)
            tracksSinceFlush = 0
            lastTrimTime = System.currentTimeMillis()
        }
    }

    /** 刷统计到磁盘。 */
    private fun flushStats(context: Context) {
        try {
            val sb = StringBuilder()
            for ((k, v) in stats.toSortedMap(compareBy { it })) {
                sb.append("$k=$v\n")
            }
            val f = userStatsFile(context)
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** 加载统计从磁盘。 */
    private fun loadStats(context: Context) {
        stats.clear()
        val f = userStatsFile(context)
        if (!f.exists()) return
        try {
            f.readLines().forEach { line ->
                val parts = line.split('=').filter { it.isNotEmpty() }
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim().toLongOrNull()
                    if (v != null) {
                        stats[k] = v
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 总日志超过 MAX_TOTAL_MB → 删除最旧直到低于上限。 */
    @Synchronized
    private fun trimIfOverLimit(context: Context) {
        val dir = Logs.dir(context)
        if (!dir.exists()) return

        // 统计总大小
        val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = allFiles.sumOf { it.length() }
        val maxBytes = MAX_TOTAL_MB * 1024 * 1024L
        if (totalBytes <= maxBytes) return

        // logcat.log 自身先截断到 8MB （如果超限）
        val lf = logcatLog(context)
        if (lf.exists() && lf.length() > MAX_LOGBYTES) {
            try {
                val lines = lf.readLines().takeLast(10000) // 留最后 10k 行
                lf.writeText(lines.joinToString("\n"))
            } catch (_: Throwable) {
            }
        }

        // 仍然超 → 按 lastModified 删除最旧文件（保留最新）
        val sorted = allFiles.sortedBy { it.lastModified() } // oldest first
        var remaining = totalBytes
        for (f in sorted) {
            if (remaining <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                remaining -= len
                Logs.logE(context, "LogFox", "trimmed oldest: ${f.name} (${len/1024}KB)")
            }
        }
    }

    /** 写崩溃快照：在 UncaughtExceptionHandler 调用。 */
    fun writeCrashSnapshot(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        engineManager: EngineManager?,
        filesDir: File
    ) {
        try {
            val snap = crashSnapshot(context)
            snap.parentFile?.mkdirs()
            snap.delete()
            val pw = PrintWriter(snap.bufferedWriter())

            // 头信息
            pw.println("===== Crash Snapshot ${df.format(Date())} =====")
            pw.println("thread: ${thread.name} id=${thread.id} priority=${thread.priority}")
            pw.println("message: ${throwable.message ?: "(no message)"}")
            pw.println()

            // 系统信息
            pw.println("--- System Info ---")
            pw.println("SDK=${Build.VERSION.SDK_INT} RELEASE=${Build.VERSION.RELEASE}")
            pw.println("ABI=${Build.SUPPORTED_ABIS.joinToString()}")
            pw.println("versionName=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            pw.println("versionCode=${context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode}")
            pw.println()

            // 引擎状态（若存在）
            engineManager?.let { em ->
                pw.println("--- Engine State ---")
                val usr = File(filesDir, "usr")
                val preload = RuntimePermissions.resolveTermuxExecPreload(usr)
                pw.println("snapshotFresh=${em.snapshotFresh()}")
                pw.println("nodeArchMatchesDevice=${em.nodeArchMatchesDevice()}")
                pw.println("lastStartAttemptAt=${EngineManager.lastStartAttemptAt}")
                pw.println("lastStartedAt=${EngineManager.lastStartedAt}")
                pw.println("termux-exec preload=${preload?.absolutePath ?: "(null)"} size=${preload?.length() ?: -1}")
                pw.println("usr/bin/node exists=${File(usr, "bin/node").exists()} size=${File(usr, "bin/node").length()}")
                val up = File(filesDir, ".update-in-progress")
                pw.println(".update-in-progress=${up.exists()}")
                pw.println()
            }

            // 抓最后一段 logcat（dump 当前）
            pw.println("--- Last logcat (tail 200 lines) ---")
            try {
                // 短命令捞最近输出
                Runtime.getRuntime().exec(arrayOf("/system/bin/logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}"))
                    .inputStream.use { `is` ->
                        val lines = InputStreamReader(`is`).readLines()
                        lines.takeLast(200).forEach { pw.println(it) }
                    }
            } catch (_: Throwable) {
                pw.println("(failed to dump live logcat, reading saved file tail)")
                try {
                    val saved = logcatLog(context)
                    if (saved.exists()) {
                        val lines = saved.readLines()
                        lines.takeLast(200).forEach { pw.println(it) }
                    }
                } catch (_: Throwable) {
                }
            }
            pw.println()

            // 用户行为最后 100 行
            pw.println("--- Last user actions (tail 100 lines) ---")
            try {
                val ua = userActionsLog(context)
                if (ua.exists()) {
                    val lines = ua.readLines()
                    lines.takeLast(100).forEach { pw.println(it) }
                }
            } catch (_: Throwable) {
            }
            pw.println()

            // 完整 stacktrace
            pw.println("--- Full Stacktrace ---")
            throwable.printStackTrace(pw)
            pw.flush()
            pw.close()

            // 标记崩溃（下次启动提示）
            val prefs = context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_CRASH_MARKER, true).apply()

            Logs.logE(context, "LogFox", "Crash snapshot written to: ${snap.absolutePath}")
        } catch (_: Throwable) {
            // 写快照失败不吞崩溃，原 handler 继续
        }
    }

    /** 清空全部日志目录（保留目录结构）。 */
    fun clearAllLogs(context: Context): Boolean {
        return try {
            val dir = Logs.dir(context)
            if (!dir.exists()) return true
            dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
            dir.mkdirs()
            stats.clear()
            flushStats(context)
            // 清除崩溃标记
            val prefs = context.getSharedPreferences("dsh_shell", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_CRASH_MARKER, false).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 生成统计汇总文本（供日志页/导出展示）。 */
    fun statsSummary(context: Context): String {
        loadStats(context) // 刷新磁盘
        val sb = StringBuilder()
        sb.append("LogFox 日志统计\n")
        sb.append("================\n\n")
        // 按类别分组
        val nav = stats.filterKeys { it.startsWith("nav_") }
        val pages = stats.filterKeys { it.startsWith("page_") }
        val buttons = stats.filterKeys { it.startsWith("button_") }
        val others = stats.filterKeys { !it.startsWith("nav_") && !it.startsWith("page_") && !it.startsWith("button_") }

        if (nav.isNotEmpty()) {
            sb.append("导航切换：${nav.values.sum()} 次\n")
        }
        if (pages.isNotEmpty()) {
            sb.append("\n页面打开：\n")
            for ((k, v) in pages.toSortedMap()) {
                sb.append("  ${k.removePrefix("page_")}: $v\n")
            }
        }
        if (buttons.isNotEmpty()) {
            sb.append("\n按钮点击：\n")
            for ((k, v) in buttons.toSortedMap()) {
                sb.append("  ${k.removePrefix("button_")}: $v\n")
            }
        }
        if (others.isNotEmpty()) {
            sb.append("\n其他操作：\n")
            for ((k, v) in others.toSortedMap()) {
                sb.append("  $k: $v\n")
            }
        }
        sb.append("\n")
        // 总大小
        val dir = Logs.dir(context)
        val totalKB = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 else 0
        sb.append("日志目录总大小: ${totalKB} KB (≈ ${totalKB/1024} MB)")
        return sb.toString()
    }

    /** 获取最后 N 行日志（用于在终端展示）。 */
    fun tailLog(context: Context, kind: String, maxLines: Int = 100): String {
        val f = when (kind) {
            "user" -> userActionsLog(context)
            "exceptions" -> Logs.exceptionsLog(context)
            "logcat" -> logcatLog(context)
            "crash" -> crashSnapshot(context)
            else -> return "(unknown log kind $kind)"
        }
        if (!f.exists()) return "(文件不存在: ${f.name})"
        return try {
            f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (_: Throwable) {
            "(读取失败: ${f.name})"
        }
    }
}

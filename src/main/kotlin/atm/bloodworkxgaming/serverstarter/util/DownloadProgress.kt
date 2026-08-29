package atm.bloodworkxgaming.serverstarter.util

import java.util.Locale

/**
 * 单文件下载字节进度（Q6 §6.1-6.2）。
 *
 * 单行 `\r` 刷新，格式 `236.5/500.0 MB @ 3.2 MB/s`（速度 + 当前/总大小，无百分比），约 1s 节流；
 * 行尾补空格 pad 防 \r 覆盖后残留上一行字符；总大小未知（onStart(null)）时省略总数：
 * `236.5 MB @ 3.2 MB/s`；大小/速度单位按量级选择（<1MB 用 KB，否则 MB，1 位小数）。
 *
 * 仅控制台显示：`System.console() == null`（重定向/IDE/CI）时不输出任何内容。
 * 多线程安全：addBytes 内部同步计数，并行下载回调不会计数错乱。
 */
class ByteProgressReporter {
    private val console = System.console() != null

    private var totalBytes: Long? = null
    private var bytes = 0L
    private var startNanos = 0L
    private var lastPrintNanos = 0L
    private var lastPrintBytes = 0L
    private var lastPrintedLength = 0

    private val lock = Any()

    /** 开始下载。totalBytes 为 Content-Length，未知传 null（OkHttp 未知时返回 -1 → null）。 */
    fun onStart(totalBytes: Long?) {
        synchronized(lock) {
            this.totalBytes = totalBytes
            bytes = 0
            startNanos = System.nanoTime()
            lastPrintNanos = startNanos
            lastPrintBytes = 0
            if (console) printLine(startNanos)
        }
    }

    /** 追加已下载字节数（每读一块调用一次）。 */
    fun addBytes(deltaBytes: Long) {
        if (deltaBytes <= 0) return
        synchronized(lock) {
            bytes += deltaBytes
            val now = System.nanoTime()
            if (console && now - lastPrintNanos >= THROTTLE_NANOS) {
                printLine(now)
            }
        }
    }

    /** 下载结束：打印最终汇总行并换行。 */
    fun finish() {
        synchronized(lock) {
            if (console) {
                printLine(System.nanoTime())
                System.out.println()
            }
        }
    }

    private fun printLine(now: Long) {
        val elapsed = now - lastPrintNanos
        val speed = if (elapsed > 0) (bytes - lastPrintBytes) * 1_000_000_000.0 / elapsed else 0.0
        val line = buildLine(speed)
        val out = StringBuilder("\r").append(line)
        if (line.length < lastPrintedLength) {
            out.append(" ".repeat(lastPrintedLength - line.length))
        }
        lastPrintedLength = line.length
        lastPrintNanos = now
        lastPrintBytes = bytes
        System.out.print(out)
        System.out.flush()
    }

    private fun buildLine(speed: Double): String {
        val total = totalBytes
        val sb = StringBuilder()
        if (total != null && total >= 0) {
            val unit = if (total < ONE_MB) UNIT_KB else UNIT_MB
            sb.append(formatSize(bytes, unit)).append('/').append(formatSize(total, unit))
        } else {
            val unit = if (bytes < ONE_MB) UNIT_KB else UNIT_MB
            sb.append(formatSize(bytes, unit))
        }
        sb.append(" @ ").append(formatSpeed(speed))
        return sb.toString()
    }

    private fun formatSize(value: Long, unit: String): String {
        val scaled = if (unit == UNIT_MB) value / ONE_MB.toDouble() else value / ONE_KB.toDouble()
        return String.format(Locale.US, "%.1f %s", scaled, unit)
    }

    private fun formatSpeed(speed: Double): String {
        return if (speed < ONE_MB) {
            String.format(Locale.US, "%.1f KB/s", speed / ONE_KB.toDouble())
        } else {
            String.format(Locale.US, "%.1f MB/s", speed / ONE_MB.toDouble())
        }
    }

    private companion object {
        const val ONE_KB = 1024L
        const val ONE_MB = 1024L * 1024L
        const val THROTTLE_NANOS = 1_000_000_000L
        const val UNIT_KB = "KB"
        const val UNIT_MB = "MB"
    }
}

/**
 * 批量文件下载进度（Q6 §6.2）：显示「已下载 X/Y 文件」，约 2s 节流刷新（\r + pad 防残影），仅控制台。
 * 多线程安全：并发下载时 fileCompleted 可能多线程调用，内部同步计数。
 */
class FileCountProgressReporter(totalFiles: Int) {
    private val console = System.console() != null

    private val totalFiles = totalFiles
    private var completed = 0
    private var lastPrintNanos = 0L
    private var lastPrintedLength = 0

    private val lock = Any()

    /** 一个文件下载完成。 */
    fun fileCompleted() {
        synchronized(lock) {
            completed++
            val now = System.nanoTime()
            if (console && now - lastPrintNanos >= THROTTLE_NANOS) {
                printLine(now)
            }
        }
    }

    /** 全部结束：打印最终行并换行。 */
    fun finish() {
        synchronized(lock) {
            if (console) {
                printLine(System.nanoTime())
                System.out.println()
            }
        }
    }

    private fun printLine(now: Long) {
        val line = "已下载 $completed/$totalFiles 文件"
        val out = StringBuilder("\r").append(line)
        if (line.length < lastPrintedLength) {
            out.append(" ".repeat(lastPrintedLength - line.length))
        }
        lastPrintedLength = line.length
        lastPrintNanos = now
        System.out.print(out)
        System.out.flush()
    }

    private companion object {
        const val THROTTLE_NANOS = 2_000_000_000L
    }
}
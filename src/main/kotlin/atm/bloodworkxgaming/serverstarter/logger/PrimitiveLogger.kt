package atm.bloodworkxgaming.serverstarter.logger

import okio.Okio
import okio.buffer
import okio.sink
import org.fusesource.jansi.Ansi
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
//TODO 目前日志记录、管理不完善-- 自动记录日志不全、不能多日志文件管理
class PrimitiveLogger(outputFile: File) {
    private val pattern = "\\x1b\\[[0-9;]*m".toRegex()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    // 必须先删除旧日志文件，再打开 sink：Windows 上删除已被本 JVM 打开的文件会静默失败
    init {
        if (outputFile.exists()) {
            outputFile.delete()
        }
    }

    private val bufferedSink = outputFile.sink().buffer()

    // JVM 退出兜底：把缓冲中的 info 尾部 flush 落盘并关闭 sink（LOGGER 为单例，只注册一次）
    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            synchronized(this) {
                try {
                    bufferedSink.flush()
                    bufferedSink.close()
                } catch (e: IOException) {
                    // 吞掉，不干扰进程退出
                }
            }
        })
    }

    @JvmOverloads
    fun info(message: Any?, logOnly: Boolean = false) {
        val m = currentTimeAnsi().fgYellow().a("[INFO] ").fgDefault().a(message).reset().newline().toString()

        synchronized(this) {
            try {
                bufferedSink.writeUtf8(stripColors(m))
            } catch (e: IOException) {
                error("Error while logging!", e)
            }

            if (!logOnly) {
                print(m)
            }
        }
    }

    fun warn(message: Any?) {
        val m = currentTimeAnsi().fgMagenta().a("[WARNING] ").bgDefault().a(message).reset().newline().toString()

        synchronized(this) {
            try {
                bufferedSink.writeUtf8(stripColors(m))
                bufferedSink.flush()
            } catch (e: IOException) {
                error("Error while logging!", e)
            }

            print(m)
        }
    }

    fun error(message: Any?, throwable: Throwable? = null) {
        var m = currentTimeAnsi().fgRed().a("[ERROR] ").bgDefault().a(message).reset().newline().toString()

        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            m += "\n" + sw.toString()
        }

        synchronized(this) {
            try {
                bufferedSink.writeUtf8(stripColors(m))
                bufferedSink.flush()
            } catch (e: IOException) {
                System.err.println("Error while logging!")
                e.printStackTrace()
            }

            print(m)
        }
    }

    private fun stripColors(message: String): String {
        return pattern.replace(message, "")
    }

    private fun currentTimeAnsi(): Ansi {
        return Ansi.ansi().fgBrightBlack().a("[" + LocalTime.now().format(dateTimeFormatter) + "] ").fgDefault()
    }
}

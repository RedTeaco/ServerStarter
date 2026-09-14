package atm.bloodworkxgaming.serverstarter.util

import java.io.File
import java.security.MessageDigest

/**
 * 文件哈希工具（Q7 §7.3）：流式计算 SHA-1 / SHA-512，替换 LibraryDownloadTask.sha1Of 与
 * ProcessorRunner.sha1Hex 两份重复实现。十六进制小写输出。
 */
object HashingUtil {
    private val HEX = "0123456789abcdef".toCharArray()

    /** 流式计算文件 SHA-1（十六进制小写）。 */
    fun sha1Hex(file: File): String = hex(file, "SHA-1")

    /** 流式计算文件 SHA-512（十六进制小写）。 */
    fun sha512Hex(file: File): String = hex(file, "SHA-512")

    private fun hex(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder()
        for (b in digest.digest()) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }
}

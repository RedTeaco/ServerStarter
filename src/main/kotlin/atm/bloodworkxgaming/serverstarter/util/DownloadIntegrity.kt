package atm.bloodworkxgaming.serverstarter.util

import java.io.File

/**
 * 下载文件的完整性校验（下载阶段的哈希比对）。
 *
 * 来源：mrpack 的 `files[].hashes`（sha1 + sha512）与 CurseForge `files[].hashes`（algo=1 为 sha1）。
 * 规则：
 * - 提供了 sha512 → 比 sha512；否则提供了 sha1 → 比 sha1；两者都没有 → 视为「无校验信息」，返回 true
 *   （fail-safe：不因为清单缺哈希就丢掉下载）；
 * - 比对忽略大小写（不同来源的十六进制大小写不一致）；
 * - 只处理完整性（防止 CDN 返回错文件、下载被截断），不做安全承诺。
 */
object DownloadIntegrity {

    /** 该文件是否符合给定的哈希；两个哈希都为 null 时返回 true（无从校验）。 */
    fun matches(file: File, sha1: String?, sha512: String?): Boolean = when {
        !file.isFile -> false
        sha512 != null -> strip(sha512) == HashingUtil.sha512Hex(file)
        sha1 != null -> strip(sha1) == HashingUtil.sha1Hex(file)
        else -> true
    }

    /** 日志用：说明本次用了哪个算法（无哈希信息时返回 null）。 */
    fun algorithm(sha1: String?, sha512: String?): String? = when {
        sha512 != null -> "sha512"
        sha1 != null -> "sha1"
        else -> null
    }

    private fun strip(hash: String): String = hash.trim().lowercase()
}

package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import java.io.File

/**
 * 整合包自带（overrides 解压出来、已经在 `mods/` 里）的模组 jar 索引。
 *
 * 目的：包里已经带了的模组不要再从平台下载一遍（既浪费流量，也会在
 * 「清单文件名与 overrides 里的文件名不一致」时让同一个模组出现两份）。
 *
 * 命中规则（按顺序，命中即跳过）：
 * 1. **文件名相同**（忽略大小写）—— Windows 落盘本来也会互相覆盖，包里那份优先；
 * 2. **内容相同**（清单给的 sha1 / sha512 与已有 jar 一致）—— 同一个文件只是被改了名字，
 *    同样跳过，避免落成两份。
 *
 * 只索引 `mods/` 顶层的 `*.jar`：子目录、`.part` 残片、`.disabled` 等一律不参与。
 *
 * 注意本对象**只负责下载去重**，不参与客户端模组判定：包里自带的 jar 与下载下来的 jar 一样，
 * 安装末尾由 [ClientOnlyModFilter] 统一扫描 `mods/`（依据 jar 自己的 mods.toml / fabric.mod.json），
 * 所以包内的客户端模组照样会被检测并删除。
 */
object PackProvidedMods {

    /** 命中结果：[file] 为 `mods/` 里已有的 jar；[viaName] 为 true 表示文件名命中，false 表示内容命中。 */
    data class Match(val file: File, val viaName: Boolean)

    /**
     * 一次扫描的只读快照。哈希按需计算并缓存（只有清单带哈希、且文件名没命中时才会去算）。
     */
    class Snapshot internal constructor(val modsDir: File, private val files: List<File>) {

        private val byName: Map<String, File> = files.associateBy { it.name.lowercase() }
        private val sha1Cache = HashMap<File, String>()
        private val sha512Cache = HashMap<File, String>()

        /** 索引到的 jar 数量。 */
        val size: Int get() = files.size

        /** 索引到的文件名（按名称排序，便于日志/测试）。 */
        fun names(): List<String> = files.map { it.name }

        /**
         * 查找包里已有的对应文件：先按文件名（忽略大小写），再按内容哈希。
         *
         * @param fileName 清单要下载的文件名（落盘名）
         * @param sha1 清单声明的 sha1（可为 null）
         * @param sha512 清单声明的 sha512（可为 null）；两者都有时用更便宜的 sha1 判等
         */
        fun find(fileName: String, sha1: String?, sha512: String?): Match? {
            byName[fileName.lowercase()]?.let { return Match(it, viaName = true) }

            val wantSha1 = sha1?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val wantSha512 = sha512?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (wantSha1 == null && wantSha512 == null) return null

            for (file in files) {
                try {
                    if (wantSha1 != null) {
                        if (sha1Of(file) == wantSha1) return Match(file, viaName = false)
                    } else if (wantSha512 != null) {
                        if (sha512Of(file) == wantSha512) return Match(file, viaName = false)
                    }
                } catch (e: Exception) {
                    // 读不了（被占用/权限）就跳过这一个，不影响其它文件的判定
                    LOGGER.warn("Failed to hash pack mod ${file.name}, ignoring it for duplicate detection: ${e.message}")
                }
            }
            return null
        }

        private fun sha1Of(file: File): String = sha1Cache.getOrPut(file) { HashingUtil.sha1Hex(file) }

        private fun sha512Of(file: File): String = sha512Cache.getOrPut(file) { HashingUtil.sha512Hex(file) }
    }

    /** 扫描 `mods/` 顶层 jar；目录不存在时返回空快照（不算错误）。 */
    fun scan(modsDir: File): Snapshot {
        val files = modsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        return Snapshot(modsDir, files)
    }

    /**
     * 统一的跳过日志：内容命中只说明"同一份文件另存了一个名字"，
     * 文件名命中时若清单哈希对不上，说明包里带的是**另一个版本**，用 warn 明确提示（保留包内版本）。
     */
    fun logSkipped(match: Match, targetName: String, sha1: String?, sha512: String?) {
        if (!match.viaName) {
            LOGGER.info("Skipping download, the pack already ships this file: $targetName (mods/${match.file.name})")
            return
        }

        LOGGER.info("Skipping download, already provided by the pack: mods/${match.file.name}")

        val expected = sha1?.takeIf { it.isNotBlank() } != null || sha512?.takeIf { it.isNotBlank() } != null
        if (expected && !DownloadIntegrity.matches(match.file, sha1, sha512)) {
            LOGGER.warn("The pack ships a different build of $targetName than the manifest lists; keeping the pack copy (mods/${match.file.name})")
        }
    }
}

package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

/**
 * `install.formatSpecific.ignoreProject` 的解析与匹配（modrinth 包型）。
 *
 * 历史上该配置只比对「第一个下载链接里的 Modrinth projectId」，对 `[CF, CF, Modrinth]`
 * 形态的 downloads 数组完全失效；现在改为基于 [ModFileIdentity] 的完整身份匹配，
 * 支持以下写法（前缀大小写不敏感）：
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `263420`（纯数字，无前缀） | CurseForge **项目 ID**（保持历史 curse 语义） |
 * | `AANobbMI` / `sodium`（其他裸串） | Modrinth **项目 ID 或 slug** |
 * | `curseProject:263420` / `cfProject:263420` | CurseForge 项目 ID（显式写法） |
 * | `curseFile:8837013` / `cfFile:8837013` | CurseForge **文件 ID**（可从 forgecdn 链接离线推出） |
 * | `modrinth:AANobbMI` | Modrinth 项目 ID 或 slug（显式写法） |
 * | `name:sodium*.jar` | 文件名 glob（等价 `glob:`；匹配 basename，可带 `mods/` 前缀） |
 * | `glob:sodium*.jar` / `regex:sodium.*\.jar` | 文件名 glob / 正则 |
 *
 * 匹配口径：任一身份命中即忽略该文件。Modrinth slug 与 CurseForge 项目 ID 需要联网解析
 * （见 `ModrinthPackType`），解析失败时退化为字面量比较（fail-safe，绝不误删）。
 */
class IgnoreProjectMatcher(
        private val modrinthProjectIds: Set<String>,
        private val curseProjectIds: Set<String>,
        private val curseProjectIdsByFileId: Map<String, String>,
        private val curseFileIds: Set<String>,
        private val nameMatchers: List<PathMatcher>
) {

    val isEmpty: Boolean
        get() = modrinthProjectIds.isEmpty() && curseProjectIds.isEmpty() &&
                curseFileIds.isEmpty() && nameMatchers.isEmpty()

    /** 该文件身份是否命中任一忽略规则。 */
    fun matches(identity: ModFileIdentity.Identity): Boolean {
        if (modrinthProjectIds.isNotEmpty()) {
            val id = identity.modrinthProjectId
            if (id != null && modrinthProjectIds.contains(id)) return true
        }

        if (curseProjectIds.isNotEmpty()) {
            val project = identity.curseProjectId
            if (project != null && curseProjectIds.contains(project)) return true
            val fileId = identity.curseFileId
            if (fileId != null && curseProjectIdsByFileId[fileId] in curseProjectIds) return true
        }

        if (curseFileIds.isNotEmpty()) {
            val fileId = identity.curseFileId
            if (fileId != null && curseFileIds.contains(fileId)) return true
        }

        if (nameMatchers.isNotEmpty()) {
            for (name in identity.fileNames) {
                val path = Paths.get(name)
                if (nameMatchers.any { it.matches(path) }) return true
            }
        }

        return false
    }

    /** ignoreProject 的解析结果（尚未联网解析 slug / CF 项目 ID）。 */
    data class Spec(
            val modrinthIdOrSlug: Set<String>,
            val curseProjectIds: Set<String>,
            val curseFileIds: Set<String>,
            val nameMatchers: List<PathMatcher>
    ) {
        val isEmpty: Boolean
            get() = modrinthIdOrSlug.isEmpty() && curseProjectIds.isEmpty() &&
                    curseFileIds.isEmpty() && nameMatchers.isEmpty()
    }

    companion object {
        private val NAME_PREFIXES = listOf("name:", "filename:", "glob:", "regex:")
        private val CURSE_FILE_PREFIXES = listOf("cursefile:", "cffile:")
        private val CURSE_PROJECT_PREFIXES = listOf("curseproject:", "cfproject:", "curse:")
        private val MODRINTH_PREFIXES = listOf("modrinth:", "mr:")

        /** Modrinth ID/slug 的合法字符（用于避免把任意配置串拼进请求 URL）。 */
        private val MODRINTH_ENTRY = Regex("""^[A-Za-z0-9_-]{1,64}$""")

        private val PATH_SEPARATORS = Regex("""^(?:mods)[/\\]""")

        /**
         * 解析 ignoreProject 列表。yaml 中未加引号的数字会被读成 [Int]，
         * 视为 CurseForge 项目 ID（与历史行为一致）。
         */
        fun parseSpec(entries: List<Any>?): Spec {
            val modrinth = LinkedHashSet<String>()
            val curseProjects = LinkedHashSet<String>()
            val curseFiles = LinkedHashSet<String>()
            val nameMatchers = ArrayList<PathMatcher>()

            if (entries != null) {
                for (entry in entries) {
                    val raw = when (entry) {
                        is String -> entry.trim()
                        is Number -> entry.toString()
                        else -> {
                            LOGGER.warn("Unsupported ignoreProject entry (expected string/number), skipping: $entry")
                            continue
                        }
                    }
                    if (raw.isEmpty()) continue

                    val lower = raw.lowercase()
                    when {
                        NAME_PREFIXES.any { lower.startsWith(it) } ->
                            parseNameMatcher(raw, nameMatchers)
                        CURSE_FILE_PREFIXES.any { lower.startsWith(it) } -> {
                            val value = raw.substringAfter(':').trim()
                            if (value.isNotEmpty()) curseFiles.add(value)
                        }
                        CURSE_PROJECT_PREFIXES.any { lower.startsWith(it) } -> {
                            val value = raw.substringAfter(':').trim()
                            if (value.isNotEmpty()) curseProjects.add(value)
                        }
                        MODRINTH_PREFIXES.any { lower.startsWith(it) } -> {
                            val value = raw.substringAfter(':').trim()
                            if (value.isNotEmpty()) modrinth.add(value)
                        }
                        raw.all { it.isDigit() } -> curseProjects.add(raw)
                        else -> modrinth.add(raw)
                    }
                }
            }

            return Spec(modrinth, curseProjects, curseFiles, nameMatchers)
        }

        /**
         * 汇总解析结果与联网解析结果（Modrinth 规范 ID、CF 文件 ID → 项目 ID），
         * 生成最终匹配器。
         */
        fun build(
                spec: Spec,
                resolvedModrinthIds: Set<String> = emptySet(),
                curseProjectIdsByFileId: Map<String, String> = emptyMap()
        ): IgnoreProjectMatcher = IgnoreProjectMatcher(
                modrinthProjectIds = spec.modrinthIdOrSlug + resolvedModrinthIds,
                curseProjectIds = spec.curseProjectIds,
                curseProjectIdsByFileId = curseProjectIdsByFileId,
                curseFileIds = spec.curseFileIds,
                nameMatchers = spec.nameMatchers
        )

        /** Modrinth 条目是否值得拿去请求 API（避免把奇怪字符串拼进 URL）。 */
        fun isResolvableModrinthEntry(entry: String): Boolean = MODRINTH_ENTRY.matches(entry)

        /**
         * 单条文件名规则 → [PathMatcher]。`name:` / `glob:` 走 glob，`regex:` 走正则；
         * 值里可带 `mods/` 前缀（自动去除，因为匹配的是 basename）。
         * 非法表达式 → warn 并忽略该条（fail-safe：不因一条坏规则让整个安装失败）。
         */
        private fun parseNameMatcher(raw: String, into: MutableList<PathMatcher>) {
            val lower = raw.lowercase()
            val value = raw.substringAfter(':').trim().replace(PATH_SEPARATORS, "")
            if (value.isEmpty()) {
                LOGGER.warn("Empty ignoreProject file name pattern, skipping: $raw")
                return
            }

            val spec = when {
                lower.startsWith("regex:") -> "regex:$value"
                else -> "glob:$value"
            }

            try {
                into.add(FileSystems.getDefault().getPathMatcher(spec))
            } catch (e: IllegalArgumentException) {
                // PatternSyntaxException 也是 IllegalArgumentException 的子类
                LOGGER.warn("Invalid ignoreProject pattern, skipping: $raw (${e.message})")
            }
        }
    }
}

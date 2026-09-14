package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER

/**
 * `install.formatSpecific.ignoreProject` 的解析与匹配（**只做平台身份**）。
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
 * | `modrinth:AANobbMI` / `mr:AANobbMI` | Modrinth 项目 ID 或 slug（显式写法） |
 *
 * **文件名不再由本配置负责**：`name:` / `filename:` / `glob:` / `regex:` 一律拒绝并 warn，
 * 请改用 `install.ignoreFiles`（见 [FileIgnoreRules]）——那里才是文件名的唯一入口，
 * 且同时匹配全部下载链接名、百分号解码名与 manifest `path` 名。
 *
 * 匹配口径：任一身份命中即忽略该文件。Modrinth slug 与 CurseForge 项目 ID 需要联网解析
 * （见 `ModrinthPackType`），解析失败时退化为字面量比较（fail-safe，绝不误删）。
 */
class IgnoreProjectMatcher(
        private val modrinthProjectIds: Set<String>,
        private val curseProjectIds: Set<String>,
        private val curseProjectIdsByFileId: Map<String, String>,
        private val curseFileIds: Set<String>
) {

    val isEmpty: Boolean
        get() = modrinthProjectIds.isEmpty() && curseProjectIds.isEmpty() && curseFileIds.isEmpty()

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

        return false
    }

    /** ignoreProject 的解析结果（尚未联网解析 slug / CurseForge 项目 ID）。 */
    data class Spec(
            val modrinthIdOrSlug: Set<String>,
            val curseProjectIds: Set<String>,
            val curseFileIds: Set<String>
    ) {
        val isEmpty: Boolean
            get() = modrinthIdOrSlug.isEmpty() && curseProjectIds.isEmpty() && curseFileIds.isEmpty()
    }

    companion object {
        /** 已迁移到 install.ignoreFiles 的前缀：出现即拒绝，避免"配了却不生效"。 */
        private val FILE_NAME_PREFIXES = listOf("name:", "filename:", "glob:", "regex:")

        private val CURSE_FILE_PREFIXES = listOf("cursefile:", "cffile:")
        private val CURSE_PROJECT_PREFIXES = listOf("curseproject:", "cfproject:", "curse:")
        private val MODRINTH_PREFIXES = listOf("modrinth:", "mr:")

        /** Modrinth ID/slug 的合法字符（用于避免把任意配置串拼进请求 URL）。 */
        private val MODRINTH_ENTRY = Regex("""^[A-Za-z0-9_-]{1,64}$""")

        /**
         * 解析 ignoreProject 列表。yaml 中未加引号的数字会被读成 [Int]，
         * 视为 CurseForge 项目 ID（与历史行为一致）。
         */
        fun parseSpec(entries: List<Any>?): Spec {
            val modrinth = LinkedHashSet<String>()
            val curseProjects = LinkedHashSet<String>()
            val curseFiles = LinkedHashSet<String>()

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
                        FILE_NAME_PREFIXES.any { lower.startsWith(it) } ->
                            LOGGER.warn("ignoreProject no longer supports file name rules, skipping: $raw (move it to install.ignoreFiles, e.g. 'mods/${raw.substringAfter(':')}')")
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

            return Spec(modrinth, curseProjects, curseFiles)
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
                curseFileIds = spec.curseFileIds
        )

        /** Modrinth 条目是否值得拿去请求 API（避免把奇怪字符串拼进 URL）。 */
        fun isResolvableModrinthEntry(entry: String): Boolean = MODRINTH_ENTRY.matches(entry)
    }
}

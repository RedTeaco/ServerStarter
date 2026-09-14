package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER

/**
 * `install.formatSpecific.ignoreProject` 的解析与匹配（**只认两个平台的规范项目 ID**）。
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `263420`（纯数字，无前缀） | CurseForge **项目 ID** |
 * | `AANobbMI`（8 位 base62） | Modrinth **项目 ID** |
 * | `curseProject:263420` / `cfProject:263420` | CurseForge 项目 ID（显式写法） |
 * | `modrinth:AANobbMI` / `mr:AANobbMI` | Modrinth 项目 ID（显式写法） |
 *
 * 不再接受的形式（warn 后忽略，避免"配了却不生效"的困惑）：
 * - `name:` / `filename:` / `glob:` / `regex:` → 文件名属于 `install.ignoreFiles`（见 [FileIgnoreRules]）；
 * - `curseFile:` / `cfFile:` → 标识的是**文件**而非项目，同样请用 `install.ignoreFiles` 的文件名规则；
 * - Modrinth **slug**（如 `sodium`）：本配置不做联网解析，slug 永远不会命中项目 ID。
 *   项目 ID 可直接从整合包的 `cdn.modrinth.com/data/<projectId>/...` 链接里取到。
 *
 * 匹配口径：
 * - Modrinth 侧用 [ModFileIdentity.Identity.modrinthProjectId] 与配置字面量比对（不联网）；
 * - CurseForge 侧因为 mrpack 只提供文件 ID（forgecdn 链接里没有项目 ID），需要调用方先把包内
 *   文件 ID 反查成项目 ID（[curseProjectIdByFileId]）。该反查结果是运行时产物，**不属于 [Spec]**。
 */
class IgnoreProjectMatcher(
        private val spec: Spec,
        private val curseProjectIdByFileId: Map<String, String> = emptyMap()
) {

    val isEmpty: Boolean
        get() = spec.isEmpty

    /** 该文件身份是否命中任一忽略规则。 */
    fun matches(identity: ModFileIdentity.Identity): Boolean {
        val modrinthId = identity.modrinthProjectId
        if (modrinthId != null && modrinthId in spec.modrinthProjectIds) return true

        if (spec.curseForgeProjectIds.isNotEmpty()) {
            val curseProjectId = identity.curseProjectId
            if (curseProjectId != null && curseProjectId in spec.curseForgeProjectIds) return true

            val curseFileId = identity.curseFileId
            if (curseFileId != null && curseProjectIdByFileId[curseFileId] in spec.curseForgeProjectIds) return true
        }

        return false
    }

    /** 解析结果：两个平台的**规范项目 ID**（无 slug / fileId / fileName）。 */
    data class Spec(
            val modrinthProjectIds: Set<String>,
            val curseForgeProjectIds: Set<String>
    ) {
        val isEmpty: Boolean
            get() = modrinthProjectIds.isEmpty() && curseForgeProjectIds.isEmpty()
    }

    companion object {
        /** 已迁走到 install.ignoreFiles 的前缀。 */
        private val FILE_NAME_PREFIXES = listOf("name:", "filename:", "glob:", "regex:")

        /** 标识文件而非项目的前缀。 */
        private val CURSE_FILE_PREFIXES = listOf("cursefile:", "cffile:")

        private val CURSE_PROJECT_PREFIXES = listOf("curseproject:", "cfproject:", "curse:")
        private val MODRINTH_PREFIXES = listOf("modrinth:", "mr:")

        /** Modrinth 项目 ID 形状：固定 8 位 base62（大小写敏感）。 */
        private val MODRINTH_ID = Regex("""^[A-Za-z0-9]{8}$""")

        /**
         * 解析 ignoreProject 列表。yaml 中未加引号的数字会被读成 [Int]，视为 CurseForge 项目 ID。
         */
        fun parseSpec(entries: List<Any>?): Spec {
            val modrinth = LinkedHashSet<String>()
            val curseProjects = LinkedHashSet<String>()

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
                            LOGGER.warn("ignoreProject only accepts project ids, skipping file name rule: $raw (move it to install.ignoreFiles, e.g. 'mods/${raw.substringAfter(':')}')")

                        CURSE_FILE_PREFIXES.any { lower.startsWith(it) } ->
                            LOGGER.warn("ignoreProject only accepts project ids, skipping file id: $raw (use install.ignoreFiles with the file name, or a CurseForge project id)")

                        CURSE_PROJECT_PREFIXES.any { lower.startsWith(it) } ->
                            raw.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { curseProjects.add(it) }

                        MODRINTH_PREFIXES.any { lower.startsWith(it) } ->
                            raw.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { addModrinthId(it, modrinth) }

                        raw.all { it.isDigit() } -> curseProjects.add(raw)

                        else -> addModrinthId(raw, modrinth)
                    }
                }
            }

            return Spec(modrinth, curseProjects)
        }

        /** Modrinth 项目 ID 形状校验：不是 8 位 base62 时只 warn，仍按字面量收录（绝不静默丢弃）。 */
        private fun addModrinthId(value: String, into: MutableSet<String>) {
            if (!MODRINTH_ID.matches(value)) {
                LOGGER.warn("ignoreProject entry '$value' is not an 8-character Modrinth project id; slugs are not supported - copy the id from the pack's cdn.modrinth.com/data/<projectId>/ link")
            }
            into.add(value)
        }
    }
}

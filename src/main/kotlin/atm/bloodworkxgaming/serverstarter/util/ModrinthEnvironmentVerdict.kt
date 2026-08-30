package atm.bloodworkxgaming.serverstarter.util

/**
 * Modrinth v3 project 响应中的 `environment` 数组 → [ApiVerdict] 判定（纯函数，无 IO，便于单测）。
 *
 * 判定口径（设计文档 §2.2，已确认"严格 client_only 包含规则"，所有匹配大小写不敏感）：
 * - 数组含 `client_only` → [ApiVerdict.CLIENT_ONLY]（严格规则 A：优先命中，混合数组也不例外）
 * - 否则含任一服务端兼容值（client_and_server / client_only_server_optional / server_only /
 *   server_only_client_optional / dedicated_server_only / client_or_server /
 *   client_or_server_prefers_both）→ [ApiVerdict.DUAL]
 * - 否则（singleplayer_only / unknown / 空数组 / null）→ null（无 API 判定，交给 TOML/Fabric 规则独断）
 *
 * 注意：v3 已移除 `client_side`/`server_side` 回退字段，判定仅依赖 `environment` 数组；
 * 缺失/空数组 → null（fail-safe 保留，不记录判定）。
 */
object ModrinthEnvironmentVerdict {

    private const val CLIENT_ONLY = "client_only"

    private val DUAL_VALUES = setOf(
            "client_and_server",
            "client_only_server_optional",
            "server_only",
            "server_only_client_optional",
            "dedicated_server_only",
            "client_or_server",
            "client_or_server_prefers_both"
    )

    fun toVerdict(environments: List<String>?): ApiVerdict? {
        if (environments.isNullOrEmpty()) return null
        val values = environments.map { it.lowercase() }
        // 严格规则 A：client_only 优先命中
        if (values.any { it == CLIENT_ONLY }) return ApiVerdict.CLIENT_ONLY
        return if (values.any { it in DUAL_VALUES }) ApiVerdict.DUAL else null
    }
}

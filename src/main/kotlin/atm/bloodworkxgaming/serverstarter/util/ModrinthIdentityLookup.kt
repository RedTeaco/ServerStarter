package atm.bloodworkxgaming.serverstarter.util

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder

/**
 * Modrinth 跨平台身份校验查询（任务 2：CF 缺省/default 模组的 Modrinth 身份确认）。
 *
 * - [lookupByHash]：HIGH 路径 —— CF sha1（algo=1）反查 `GET /v2/version_file/{hash}?algorithm=sha1|sha512`
 *   （哈希反查端点仅 v2 提供，v3 不暴露；返回**单个 version 对象**）；同 loader、同 MC 版本
 *   （均忽略大小写）校验通过才视为命中（二进制级身份确认，命中且 client_only 时可直接跳过下载）。
 *   注意：`docs/version_file.json` 实为 `GET /v3/version/{id}` 的响应，其对象结构与 v2 哈希反查
 *   响应一致（project_id / name / game_versions / loaders / environment 单值字符串 / files[].hashes），
 *   仍可作为解析参考。
 * - [lookupByName]：MEDIUM/LOW 路径 —— `GET /v3/search` 名称（+作者）匹配（v3 search hits 含
 *   environment，已确认），仅用于日志提示，不处置。
 *
 * 网络访问通过注入的 `get: (String) -> String` 完成（无网络即可单测）；
 * 网络失败（IOException）向上抛出由调用方记录 warn；JSON 解析失败一律返回 null（fail-safe）。
 */
object ModrinthIdentityLookup {

    private const val BASE_URL = "https://api.modrinth.com/v3"
    private val WHITESPACE_RUN = Regex("\\s+")

    /** 哈希反查命中（HIGH）：Modrinth version 的身份 + 环境（environment 可能缺失 → null）。 */
    data class HashMatch(val projectId: String, val versionName: String, val environment: String?)

    /** 名称搜索命中（MEDIUM/LOW）：Modrinth project 的身份 + 环境（environment 可能缺失 → null）。 */
    data class NameMatch(
        val projectId: String,
        val name: String,
        val slug: String?,
        val author: String?,
        val authorMatched: Boolean,
        val environment: String?
    )

    /** 名称归一化（doc §3.2）：小写、去首尾空白、连续空白折叠为单个空格。 */
    fun normalize(s: String): String = s.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /**
     * 哈希反查（HIGH 路径）：`GET /v2/version_file/{hash}?algorithm=sha1`（v2 端点，v3 不提供；
     * 允许 sha1|sha512，默认 sha1；不使用 multiple 参数，取单个 version 对象）。
     * 解析单个 version 对象，校验同 loader、同 MC 版本（忽略大小写）通过才返回 [HashMatch]，否则 null。
     * environment 缺失 → null（不判定）。
     *
     * @throws IOException get 网络失败时向上抛出（调用方 warn + fail-safe）。
     */
    @Throws(IOException::class)
    fun lookupByHash(sha1: String, mcVersion: String, loaderName: String, get: (String) -> String): HashMatch? {
        val url = "https://api.modrinth.com/v2/version_file/$sha1?algorithm=sha1"
        val body = get(url)
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            val projectId = obj.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val versionName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val gameVersions = obj.get("game_versions")?.takeIf { it.isJsonArray }?.asJsonArray
            val loaders = obj.get("loaders")?.takeIf { it.isJsonArray }?.asJsonArray
            val environment = obj.get("environment")?.takeIf { it.isJsonPrimitive }?.asString

            val mcMatches = gameVersions?.any { it.isJsonPrimitive && it.asString.equals(mcVersion, ignoreCase = true) } == true
            val loaderMatches = loaders?.any { it.isJsonPrimitive && it.asString.equals(loaderName, ignoreCase = true) } == true

            if (!mcMatches || !loaderMatches) null
            else HashMatch(projectId, versionName, environment)
        } catch (e: Exception) {
            // 解析失败 → fail-safe（调用方按未命中处理）
            null
        }
    }

    /**
     * 名称搜索（MEDIUM/LOW 路径，`GET /v3/search`，v3 search hits 含 environment）：
     * 归一化名称相等为候选；`cfAuthors` 非空时作者命中的候选优先，
     * 其次优先 loaders（若存在）含 loaderName 的候选（均忽略大小写）；无候选返回 null。
     *
     * @param mcVersion 预留：本期不参与 search 过滤（new_filters 仅 project_types=["mod"]），
     *                  为后续按 game_versions 过滤保留签名。
     * @throws IOException get 网络失败时向上抛出（调用方 warn + fail-safe）。
     */
    @Throws(IOException::class)
    fun lookupByName(
        name: String,
        cfAuthors: List<String>,
        mcVersion: String,
        loaderName: String,
        get: (String) -> String
    ): NameMatch? {
        val query = URLEncoder.encode(name, "UTF-8")
        val filters = URLEncoder.encode("project_types=[\"mod\"]", "UTF-8")
        val url = "$BASE_URL/search?query=$query&new_filters=$filters&limit=10"
        val body = get(url)
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val hits = root.get("hits")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

            data class Candidate(
                val projectId: String,
                val name: String,
                val slug: String?,
                val author: String?,
                val authorMatched: Boolean,
                val environment: String?,
                val loaderMatched: Boolean
            )

            val normalizedQuery = normalize(name)
            val candidates = hits.mapNotNull { element ->
                val hit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val hitName = hit.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                // 候选 = 归一化名称相等
                if (normalize(hitName) != normalizedQuery) return@mapNotNull null

                val projectId = hit.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val slug = hit.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
                val author = hit.get("author")?.takeIf { it.isJsonPrimitive }?.asString
                val environment = hit.get("environment")?.takeIf { it.isJsonPrimitive }?.asString
                val loaders = hit.get("loaders")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                        ?: emptyList()

                val authorMatched = author != null && cfAuthors.any { normalize(it) == normalize(author) }
                val loaderMatched = loaders.any { it.equals(loaderName, ignoreCase = true) }
                Candidate(projectId, hitName, slug, author, authorMatched, environment, loaderMatched)
            }

            // 作者命中优先，其次 loader 命中；同级并列取 hits 顺序靠前者
            val best = candidates.maxWithOrNull(compareBy({ it.authorMatched }, { it.loaderMatched }))
                    ?: return null
            NameMatch(best.projectId, best.name, best.slug, best.author, best.authorMatched, best.environment)
        } catch (e: Exception) {
            // 解析失败 → fail-safe（调用方按未命中处理）
            null
        }
    }
}
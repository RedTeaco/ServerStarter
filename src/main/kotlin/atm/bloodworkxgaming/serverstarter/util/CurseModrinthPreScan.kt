package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * CurseForge 缺省（default）模组的 Modrinth 跨平台身份预扫描（原 CursePackType.preScanModrinthIdentity）。
 *
 * 判定口径与旧实现完全一致：
 * - **HIGH**：CF sha1（algo=1）→ Modrinth v3 哈希反查（同 loader、同 MC 版本校验通过才算命中）；
 *   命中且 `environment=client_only` → 加入返回的跳过集合（不下载、不记录判定）；
 *   命中但非 client_only → 保留。
 * - **MEDIUM/LOW**：CF 项目名 + 作者 → Modrinth v3 search，仅日志提示、不处置（fail-safe）。
 * - 任何查询失败（IOException / 解析失败）→ warn + 保留，绝不因网络问题丢模组。
 *
 * 与旧实现的差别只有性能与可测性：
 * - 并发（默认 8 线程）+ 三级去重缓存（sha1 → 哈希反查结果；modId → CF 项目信息 + 名称搜索结果），
 *   几百个 default 模组的整合包不再逐个模组串行等待；
 * - 日志在全部查询结束后**单线程**输出，每个模组内部的先后顺序与旧实现一致；
 * - 网络访问通过 [getModrinth] / [getCurseForge] 注入，因此可以离线单测。
 */
object CurseModrinthPreScan {

    /** 待扫描的模组（只带扫描需要的字段）。 */
    data class ModInput(val fileName: String, val modId: Int, val sha1: String?)

    /** CF 项目详情（`GET /v1/mods/{modId}` 的 data 段）。 */
    private data class ProjectInfo(val name: String, val authors: List<String>)

    private data class HashLookup(val hit: ModrinthIdentityLookup.HashMatch?, val error: String? = null)

    private data class ProjectLookup(
            val info: ProjectInfo?,
            val nameMatch: ModrinthIdentityLookup.NameMatch?,
            val error: String? = null
    )

    private data class ModResult(
            val fileName: String,
            val modId: Int,
            val hash: HashLookup,
            val project: ProjectLookup?
    )

    private const val DEFAULT_THREADS = 8

    /**
     * 扫描给定的 default 模组，返回**需要跳过下载**的文件名集合。
     *
     * @param getModrinth Modrinth GET（`InternetManager.get`）
     * @param getCurseForge 带 x-api-key 的 CurseForge GET
     */
    fun scan(
            mods: List<ModInput>,
            mcVersion: String,
            loaderName: String,
            getModrinth: (String) -> String,
            getCurseForge: (String) -> String,
            threads: Int = DEFAULT_THREADS
    ): Set<String> {
        if (mods.isEmpty()) return emptySet()

        // 去重缓存：同一 sha1 / 同一 modId 只查询一次
        val hashCache = ConcurrentHashMap<String, HashLookup>()
        val projectCache = ConcurrentHashMap<Int, ProjectLookup>()

        val executor = Executors.newFixedThreadPool(maxOf(1, threads))
        val results: List<ModResult> = try {
            val futures = mods.map { mod ->
                executor.submit(Callable {
                    ModResult(
                            fileName = mod.fileName,
                            modId = mod.modId,
                            hash = lookupByHash(mod, mcVersion, loaderName, getModrinth, hashCache),
                            project = null   // 命中哈希就不需要名称路径，稍后按需补
                    )
                })
            }
            val hashPhase = futures.map { it.get() }

            // 第二阶段：只对「没命中哈希」的模组查 CF 项目 + 名称搜索
            val secondFutures = hashPhase.map { result ->
                executor.submit(Callable {
                    if (result.hash.hit != null) result
                    else result.copy(project = lookupProject(result.modId, mcVersion, loaderName, getModrinth, getCurseForge, projectCache))
                })
            }
            secondFutures.map { it.get() }
        } finally {
            executor.shutdown()
        }

        // 单线程汇总 + 日志（每个模组内部顺序与旧实现一致）
        val skipFileNames = LinkedHashSet<String>()
        for (result in results) {
            LOGGER.info("CF file ${result.fileName} has no client/server tags (default), checking Modrinth for identity...")

            val error = result.hash.error
            if (error != null) {
                LOGGER.warn("Modrinth lookup for ${result.fileName} failed, keeping (fail-safe): $error")
            }

            val hashHit = result.hash.hit
            if (hashHit != null) {
                LOGGER.info("Matched Modrinth version by file hash (loader=$loaderName, mc=$mcVersion): project=${hashHit.projectId} (${hashHit.versionName}), environment=${hashHit.environment ?: "null"}")
                if (hashHit.environment != null && hashHit.environment.equals("client_only", ignoreCase = true)) {
                    LOGGER.warn("Modrinth environment=client_only for ${result.fileName} (verified by sha1 hash, loader=$loaderName, mc=$mcVersion) -> client-only, skipping download")
                    skipFileNames.add(result.fileName)
                } else {
                    LOGGER.info("Modrinth environment=${hashHit.environment ?: "null"} for ${result.fileName} -> not client-only, keeping")
                }
                continue
            }

            val project = result.project
            if (project?.error != null) {
                LOGGER.warn("CurseForge project info lookup for mod ${result.modId} failed, keeping ${result.fileName} as default: ${project.error}")
            }

            val nameMatch = project?.nameMatch
            when {
                nameMatch == null -> LOGGER.info("No Modrinth match for ${result.fileName}, keeping as default")
                nameMatch.authorMatched ->
                    LOGGER.info("Matched Modrinth project by name/author: ${nameMatch.name} (${nameMatch.slug ?: "null"}), environment=${nameMatch.environment ?: "null"}; keeping ${result.fileName}")
                else ->
                    LOGGER.warn("Possible but unverified Modrinth match (name only): ${nameMatch.name} (${nameMatch.slug ?: "null"}), environment=${nameMatch.environment ?: "null"}; keeping ${result.fileName}")
            }
        }

        return skipFileNames
    }

    private fun lookupByHash(
            mod: ModInput,
            mcVersion: String,
            loaderName: String,
            getModrinth: (String) -> String,
            cache: ConcurrentHashMap<String, HashLookup>
    ): HashLookup {
        val sha1 = mod.sha1 ?: return HashLookup(null)

        // computeIfAbsent：并发下同一 sha1 也只会真正查询一次（check-then-act 会重复请求）
        return cache.computeIfAbsent(sha1) {
            try {
                HashLookup(ModrinthIdentityLookup.lookupByHash(sha1, mcVersion, loaderName, getModrinth))
            } catch (e: IOException) {
                HashLookup(null, e.message)
            }
        }
    }

    private fun lookupProject(
            modId: Int,
            mcVersion: String,
            loaderName: String,
            getModrinth: (String) -> String,
            getCurseForge: (String) -> String,
            cache: ConcurrentHashMap<Int, ProjectLookup>
    ): ProjectLookup = cache.computeIfAbsent(modId) {
        val info = try {
            fetchProjectInfo(modId, getCurseForge)
        } catch (e: IOException) {
            return@computeIfAbsent ProjectLookup(null, null, e.message)
        }

        if (info == null) {
            return@computeIfAbsent ProjectLookup(null, null, null)
        }

        val nameMatch = try {
            ModrinthIdentityLookup.lookupByName(info.name, info.authors, mcVersion, loaderName, getModrinth)
        } catch (e: IOException) {
            // 名称路径只是日志提示，失败不影响处置（fail-safe）
            null
        }

        ProjectLookup(info, nameMatch, null)
    }

    /**
     * CF 项目详情（`GET /v1/mods/{modId}`）：解析 data.name（项目名，用于 Modrinth 搜索，非 displayName）
     * 与 data.authors[].name。网络失败（IOException）向上抛出由调用方处理；响应解析失败 → null（fail-safe）。
     */
    private fun fetchProjectInfo(modId: Int, getCurseForge: (String) -> String): ProjectInfo? {
        val body = getCurseForge("https://api.curseforge.com/v1/mods/$modId")
        return try {
            val data = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
            val name = data.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            val authors = data.get("authors")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { element ->
                        element.takeIf { it.isJsonObject }?.asJsonObject
                                ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    }
                    ?: emptyList()
            if (name.isEmpty()) null else ProjectInfo(name, authors)
        } catch (e: Exception) {
            LOGGER.warn("Failed to parse CurseForge project info for mod $modId, keeping as default: ${e.message}")
            null
        }
    }
}

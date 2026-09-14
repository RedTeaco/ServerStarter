package atm.bloodworkxgaming.serverstarter.packtype.modrinth

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.IgnoreProjectMatcher
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ModFileIdentity
import atm.bloodworkxgaming.serverstarter.util.ModrinthEnvironmentVerdict
import atm.bloodworkxgaming.serverstarter.util.ModrinthIndexManifest
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.collections.ArrayList

open class ModrinthPackType(private val configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String): String {
        return url
    }

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        ZipExtractor(
                basePath = basePath,
                oldFiles = oldFiles,
                pathMatchers = pathMatchers,
                manifestEntryName = "modrinth.index.json",
                overridesPrefix = "overrides/",
                moveModsFolderFirst = true,
                rethrowOnError = true
        ).extract(file)
    }

    /**
     * 从 zip 内 modrinth.index.json 解析原始版本（Q3 的 manifest 侧输入）。
     *
     * 注意：mcVersion 按字符串读取（修复既有 getAsJsonArray("minecraft").asString 对真实
     * mrpack 抛 ClassCastException 的 bug）；loader 提取与 dependencies 键序无关（修复
     * bettermc.mrpack 类问题：键序不保证 minecraft 在最后）：已知 loader 键名
     * （fabric-loader/quilt-loader/forge/neoforge，大小写不敏感）优先，多个已知键取第一个
     * 并 warn；无已知键回退第一个非 minecraft 且值为字符串的键；都没有则 null（yaml 兜底）。
     */
    @Throws(IOException::class)
    override fun readManifestVersions(zip: File): ManifestVersions? {
        ZipFile(zip).use { zipFile ->
            val entry = zipFile.getEntry("modrinth.index.json") ?: return null
            zipFile.getInputStream(entry).use { input ->
                val json = JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                LOGGER.info("manifest JSON Object: $json", true)
                val deps = json.get("dependencies")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return ManifestVersions(null, null)

                val mc = deps.get("minecraft")?.takeIf { it.isJsonPrimitive }?.asString

                // loader 键识别（键序无关）：
                // 1) 已知 loader 键名优先；多个已知键 → 取第一个并 warn
                // 2) 无已知键 → 回退第一个非 minecraft 且值为字符串的键
                // 3) 都没有 → null（走 yaml loaderVersion 兜底）
                val knownLoaderKeys = listOf("fabric-loader", "quilt-loader", "forge", "neoforge")
                var loaderKey: String? = null
                for (key in deps.keySet()) {
                    if (knownLoaderKeys.any { it.equals(key, ignoreCase = true) }) {
                        if (loaderKey == null) {
                            loaderKey = key
                        } else {
                            LOGGER.warn("modrinth.index.json declares multiple loader keys ($loaderKey, $key, ...), using the first: $loaderKey")
                            break
                        }
                    }
                }
                if (loaderKey == null) {
                    loaderKey = deps.keySet().firstOrNull { key -> key != "minecraft" && deps.get(key)?.isJsonPrimitive == true }
                }
                val loader = loaderKey?.let { key -> deps.get(key)?.takeIf { it.isJsonPrimitive }?.asString }

                return ManifestVersions(mc, loader)
            }
        }
    }

    @Throws(IOException::class)
    override fun postProcessing() {
        // ① 解析 modrinth.index.json，收集存活 mods/ 条目
        //    （有序候选链接 + 完整身份：Modrinth projectId / CF 项目 ID / CF 文件 ID / 候选文件名）
        val json = InputStreamReader(FileInputStream(File(basePath + "modrinth.index.json")), "utf-8").use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
        LOGGER.info("manifest JSON Object: $json", true)
        val entries = ModrinthIndexManifest.parse(json)

        // ② ignoreProject：解析规则（必要时联网解析 Modrinth slug / CurseForge 项目 ID）后剔除命中条目
        val ignoreProject = buildIgnoreProjectMatcher(entries)
        val keptEntries = ArrayList<ModrinthIndexManifest.Entry>(entries.size)
        for (entry in entries) {
            if (ignoreProject.matches(entry.identity)) {
                LOGGER.warn("Ignoring mod by ignoreProject: ${entry.fileName} (${entry.identity.describe()})")
            } else {
                keptEntries.add(entry)
            }
        }

        // ③ 并行查询各 project 的环境信息（8 线程 + projectId 去重缓存；查询失败 → null，不缓存）
        val environmentCache = ConcurrentHashMap<String, List<String>?>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val projectIds = keptEntries.mapNotNull { it.identity.modrinthProjectId }.distinct()
            val futures = projectIds.map { projectId ->
                executor.submit(Callable {
                    val environments = queryProjectEnvironment(projectId)
                    if (environments != null) {
                        environmentCache[projectId] = environments
                    }
                })
            }
            // 等待全部查询完成后再进入单线程判定阶段
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        // ④ 单线程逐文件判定：构建下载列表 + 记录平台判定
        // （apiVerdictsByFileMutable 非线程安全，必须在并行查询全部结束后写入）
        val targets = ArrayList<ModDownloader.DownloadTarget>()
        for (entry in keptEntries) {
            val fileName = entry.fileName
            val projectId = entry.identity.modrinthProjectId

            if (projectId == null) {
                LOGGER.warn("No Modrinth project id in any download link of ${entry.path} (${entry.identity.describe()}), keeping without API check: $fileName")
                targets.add(ModDownloader.DownloadTarget(entry.urls, fileName))
                continue
            }

            val environments = environmentCache[projectId]
            if (environments == null) {
                LOGGER.warn("Modrinth API lookup failed for project $projectId, keeping (fail-safe): $fileName")
                targets.add(ModDownloader.DownloadTarget(entry.urls, fileName))
                continue
            }

            val verdict = ModrinthEnvironmentVerdict.toVerdict(environments)
            when (verdict) {
                ApiVerdict.CLIENT_ONLY -> {
                    LOGGER.warn("Skipping client-only mod (Modrinth API environment=client_only): $fileName (projectId=$projectId)")
                    // 剔除：不加入下载列表、不记录判定
                    // index 与 API 判定不一致（doc §2.4）：index 声明了 server env（非 unsupported，否则已在 ① 跳过）
                    // 但 API 判定为 client_only → info 说明分歧
                    val serverEnv = entry.serverEnv
                    if (serverEnv != null) {
                        LOGGER.info("Modrinth index env=$serverEnv differs from API environment=${environments.joinToString()} for $fileName")
                    }
                }
                ApiVerdict.DUAL -> {
                    apiVerdictsByFileMutable[fileName] = verdict
                    LOGGER.info("Keeping mod: $fileName (Modrinth API environment=${environments.joinToString()})")
                    targets.add(ModDownloader.DownloadTarget(entry.urls, fileName))
                }
                else -> {
                    // verdict == null 且 environments 非空（singleplayer_only / unknown）：保留但不记录判定
                    LOGGER.warn("No environment info for project $projectId, keeping without verdict: $fileName")
                    targets.add(ModDownloader.DownloadTarget(entry.urls, fileName))
                }
            }
        }

        // constructs the ignore list（ignoreFiles 中 mods/ 前缀项 → shouldSkip 钩子）
        val ignoreMatchers = ArrayList<PathMatcher>()
        for (ignoreFile in configFile.install.ignoreFiles) {
            if (ignoreFile.startsWith("mods/")) {
                val raw = ignoreFile.removePrefix("mods/")
                val spec = if (raw.startsWith("glob:") || raw.startsWith("regex:")) raw else "glob:$raw"
                ignoreMatchers.add(FileSystems.getDefault().getPathMatcher(spec))
            }
        }

        ModDownloader(basePath, internetManager).downloadTargets(targets) { modName ->
            val path = Paths.get(modName)
            ignoreMatchers.any { it.matches(path) }
        }
    }

    /**
     * 构建 ignoreProject 匹配器：解析配置 → （按需）联网解析 Modrinth slug、CurseForge 文件 ID → 项目 ID。
     * 任何联网失败都退化为字面量比较（fail-safe：不会因为解析失败而误删或误留更多文件）。
     */
    private fun buildIgnoreProjectMatcher(entries: List<ModrinthIndexManifest.Entry>): IgnoreProjectMatcher {
        val spec = IgnoreProjectMatcher.parseSpec(
                configFile.install.getFormatSpecificSettingOrDefault<List<Any>>("ignoreProject", null))

        if (spec.isEmpty) return IgnoreProjectMatcher.build(spec)
        LOGGER.info("ignoreProject entries: modrinthIdOrSlug=${spec.modrinthIdOrSlug}, curseProjectIds=${spec.curseProjectIds}, curseFileIds=${spec.curseFileIds}, namePatterns=${spec.nameMatchers.size}")

        // Modrinth：规范 ID 直接字面量比较；slug（或无法与 URL 中的 ID 对上）需要一次 API 解析
        val knownModrinthIds = entries.mapNotNull { it.identity.modrinthProjectId }.toSet()
        val resolvedModrinthIds = resolveModrinthIds(spec.modrinthIdOrSlug, knownModrinthIds)

        // CurseForge：项目 ID 需要 fileId → modId 反查（离线只能从链接拿到文件 ID）
        val curseProjectIdsByFileId = if (spec.curseProjectIds.isEmpty()) {
            emptyMap()
        } else {
            resolveCurseProjectIdsByFileId(entries.mapNotNull { it.identity.curseFileId })
        }

        return IgnoreProjectMatcher.build(spec, resolvedModrinthIds, curseProjectIdsByFileId)
    }

    /**
     * 把 ignoreProject 中的 Modrinth 条目解析为规范 project id（v3 `GET /project/{id|slug}` 同时接受
     * ID 与 slug）。已经在包内链接里出现过的 ID 跳过请求（字面量已能命中）；解析失败 → warn + 字面量比较。
     */
    private fun resolveModrinthIds(entries: Set<String>, knownIds: Set<String>): Set<String> {
        val resolved = LinkedHashSet<String>()

        for (entry in entries) {
            if (entry in knownIds) continue

            if (!IgnoreProjectMatcher.isResolvableModrinthEntry(entry)) {
                LOGGER.warn("ignoreProject entry '$entry' is not a plausible Modrinth id/slug, matching it literally")
                continue
            }

            try {
                val obj = JsonParser.parseString(internetManager.get("https://api.modrinth.com/v3/project/$entry")).asJsonObject
                val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: obj.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString
                if (id == null) {
                    LOGGER.warn("Modrinth response for ignoreProject '$entry' has no project id, matching it literally")
                } else {
                    resolved.add(id)
                    val slug = obj.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
                    LOGGER.info("ignoreProject '$entry' resolved to Modrinth project $id" + (slug?.let { " (slug=$it)" } ?: ""))
                }
            } catch (e: Exception) {
                LOGGER.warn("Modrinth lookup for ignoreProject '$entry' failed, matching it literally (fail-safe): ${e.message}")
            }
        }

        return resolved
    }

    /**
     * CurseForge 文件 ID → 项目 ID 反查（`POST /v1/mods/files`，分块请求）。
     * 无 API key / 请求失败 → 空 map（只影响 `ignoreProject` 中纯数字 CF 项目 ID 的命中，
     * `curseFile:` 与 `name:` 规则不受影响）。
     */
    private fun resolveCurseProjectIdsByFileId(fileIds: Collection<String>): Map<String, String> {
        val numericFileIds = fileIds.mapNotNull { it.toLongOrNull() }.distinct()
        if (numericFileIds.isEmpty()) return emptyMap()

        val apiKey = configFile.install.curseForgeApiKey
        if (apiKey.isBlank()) {
            LOGGER.warn("curseForgeApiKey is empty, cannot resolve CurseForge project ids from file ids; use 'curseFile:' or 'name:' rules instead")
            return emptyMap()
        }

        val result = HashMap<String, String>()
        val gson = Gson()

        for (chunk in numericFileIds.chunked(CURSE_FILE_QUERY_CHUNK)) {
            try {
                val body = internetManager.postJson(
                        "https://api.curseforge.com/v1/mods/files",
                        gson.toJson(mapOf("fileIds" to chunk)),
                        mapOf(
                                "Content-Type" to "application/json",
                                "Accept" to "application/json",
                                "x-api-key" to apiKey
                        )
                )

                val data = JsonParser.parseString(body).asJsonObject.get("data")
                        ?.takeIf { it.isJsonArray }?.asJsonArray
                if (data == null) {
                    LOGGER.warn("CurseForge file lookup returned no data array, CurseForge project ids in ignoreProject may not match")
                    continue
                }

                for (element in data) {
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val fileId = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                    val modId = obj.get("modId")?.takeIf { it.isJsonPrimitive }?.asString
                    if (fileId != null && modId != null) {
                        result[fileId] = modId
                    }
                }
            } catch (e: Exception) {
                LOGGER.warn("CurseForge file→project lookup failed, CurseForge project ids in ignoreProject may not match: ${e.message}")
            }
        }

        LOGGER.info("Resolved ${result.size} CurseForge file ids to project ids for ignoreProject matching")
        return result
    }

    /**
     * 查询 Modrinth v3 project 的环境信息（`environment` 数组）。
     *
     * 任何失败（网络/HTTP 错误、JSON 解析异常、字段缺失或非数组、数组为空）都返回 null
     * （fail-safe，不在此处打日志，由调用方按"查询失败"处理）。
     */
    fun queryProjectEnvironment(projectId: String): List<String>? {
        return try {
            val body = internetManager.get("https://api.modrinth.com/v3/project/$projectId")
            val environment = JsonParser.parseString(body).asJsonObject.get("environment")
            if (environment == null || !environment.isJsonArray) return null
            val values = environment.asJsonArray.map { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            // 必须是"字符串数组"：任一元素非字符串 → 视为无效（null）
            if (values.any { it == null }) return null
            values.filterNotNull().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 单个下载链接 → Modrinth projectId（base62，大小写敏感）。
     *
     * 历史实现要求链接里含 `data/` 且直接 `substringAfter`；现在委托 [ModFileIdentity]，
     * 兼容 `cdn.modrinth.com` / `cdn-raw.modrinth.com` 等子域，非 Modrinth 链接返回 null。
     */
    fun extractProjectId(url: String): String? = ModFileIdentity.modrinthProjectId(url)

    /**
     * 整个 downloads 数组 → Modrinth projectId：按顺序取第一个能解析出 projectId 的链接。
     * 用于兼容「CF 链接在前、Modrinth 链接在后」的 mrpack。
     */
    fun extractProjectId(downloads: Collection<String>): String? =
            downloads.firstNotNullOfOrNull { ModFileIdentity.modrinthProjectId(it) }

    /** postProcessing 阶段单个 mods/ 文件的工作条目见 [ModrinthIndexManifest.Entry]。 */
    companion object {
        /** CurseForge `POST /v1/mods/files` 单次请求的 fileId 上限（保守分块，避免超长请求体）。 */
        private const val CURSE_FILE_QUERY_CHUNK = 500
    }
}

package atm.bloodworkxgaming.serverstarter.packtype.modrinth

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ModrinthEnvironmentVerdict
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import com.google.gson.JsonParser
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.PathMatcher
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
        // ① 解析 modrinth.index.json，收集存活 mods/ 文件（url / fileName / projectId / index 的 env.server）
        val entries = ArrayList<ModEntry>()

        InputStreamReader(FileInputStream(File(basePath + "modrinth.index.json")), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("manifest JSON Object: $json", true)

            // gets all the mods
            for (jsonElement in json.getAsJsonArray("files")) {
                val obj = jsonElement.asJsonObject
                // env.server == "unsupported" 预过滤（ResourcePack/ShaderPack 等）；
                // null 安全读取：env 缺失 / server 键缺失时按"无 index 判定"处理，不抛异常
                val serverEnv = obj.get("env")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("server")?.takeIf { it.isJsonPrimitive }?.asString
                if (serverEnv != null && serverEnv.equals("unsupported", ignoreCase = true)) {
                    continue
                }
                if (obj.getAsJsonPrimitive("path").asString.substringBefore("/") != "mods") {
                    continue
                } else {
                    val url = obj.getAsJsonArray("downloads").get(0).asString
                    entries.add(ModEntry(url, FilenameUtils.getName(url), extractProjectId(url), serverEnv))
                }
            }
        }

        // ② 并行查询各 project 的环境信息（8 线程 + projectId 去重缓存；查询失败 → null，不缓存）
        val environmentCache = ConcurrentHashMap<String, List<String>?>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val projectIds = entries.mapNotNull { it.projectId }.distinct()
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

        // ③ 单线程逐文件判定：构建下载列表 + 记录平台判定
        // （apiVerdictsByFileMutable 非线程安全，必须在并行查询全部结束后写入）
        val modsUrl = ArrayList<String>()
        for (entry in entries) {
            val url = entry.url
            val fileName = entry.fileName
            val projectId = entry.projectId

            if (projectId == null) {
                LOGGER.warn("Unable to extract project id from url, keeping: $url")
                modsUrl.add(url)
                continue
            }

            val environments = environmentCache[projectId]
            if (environments == null) {
                LOGGER.warn("Modrinth API lookup failed for project $projectId, keeping (fail-safe): $fileName")
                modsUrl.add(url)
                continue
            }

            val verdict = ModrinthEnvironmentVerdict.toVerdict(environments)
            when (verdict) {
                ApiVerdict.CLIENT_ONLY -> {
                    LOGGER.warn("Skipping client-only mod (Modrinth API environment=client_only): $fileName (projectId=$projectId)")
                    // 剔除：不加入下载列表、不记录判定
                }
                ApiVerdict.DUAL -> {
                    apiVerdictsByFileMutable[fileName] = verdict
                    LOGGER.info("Keeping mod: $fileName (Modrinth API environment=${environments.joinToString()})")
                    modsUrl.add(url)
                }
                else -> {
                    // verdict == null 且 environments 非空（singleplayer_only / unknown）：保留但不记录判定
                    LOGGER.warn("No environment info for project $projectId, keeping without verdict: $fileName")
                    modsUrl.add(url)
                }
            }

            // ④ index 与 API 判定不一致（doc §2.4）：index 声明了 server env（非 unsupported，否则已在 ① 跳过）
            // 但 API 判定为 client_only → info 说明分歧
            val serverEnv = entry.serverEnv
            if (serverEnv != null && verdict == ApiVerdict.CLIENT_ONLY) {
                LOGGER.info("Modrinth index env=$serverEnv differs from API environment=${environments.joinToString()} for $fileName")
            }
        }

        ModDownloader(basePath, internetManager).downloadAll(modsUrl)
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
     * url: modrinth.index.json 中的 url。
     * 用于取出下载链接中的 projectId（base62，如 AANobbMI），后续用于从平台获取模组信息
     * 以判定是否是 client-only mod。URL 不含 "data/" 段或提取段为空白时返回 null
     * （调用方 fail-safe 保留下载并 warn）。
     */
    fun extractProjectId(url: String): String? {
        if (!url.contains("data/")) return null
        val projectId = url.substringAfter("data/").substringBefore("/")
        return projectId.takeIf { it.isNotBlank() }
    }

    /** postProcessing 阶段单个 mods/ 文件的工作条目（url 与 index 侧信息）。 */
    private data class ModEntry(
            val url: String,
            val fileName: String,
            val projectId: String?,
            val serverEnv: String?
    )
}

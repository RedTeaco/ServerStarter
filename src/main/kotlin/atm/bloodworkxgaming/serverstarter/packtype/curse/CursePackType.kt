package atm.bloodworkxgaming.serverstarter.packtype.curse

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.CurseModrinthPreScan
import atm.bloodworkxgaming.serverstarter.util.FileIgnoreRules
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.PathMatcher
import java.util.*
import java.util.zip.ZipFile
import kotlin.collections.ArrayList

open class CursePackType(private val configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String): String {
        if (url.contains("curseforge.com") && !url.endsWith("/download"))
            return "$url/download"

        return url
    }

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        ZipExtractor(
                basePath = basePath,
                oldFiles = oldFiles,
                pathMatchers = pathMatchers,
                manifestEntryName = "manifest.json",
                overridesPrefix = "overrides/",
                moveModsFolderFirst = true,
                rethrowOnError = true
        ).extract(file)
    }

    /**
     * 从 zip 内 manifest.json 解析原始版本（Q3 的 manifest 侧输入）。
     */
    @Throws(IOException::class)
    override fun readManifestVersions(zip: File): ManifestVersions? {
        ZipFile(zip).use { zipFile ->
            val entry = zipFile.getEntry("manifest.json") ?: return null
            zipFile.getInputStream(entry).use { input ->
                val json = JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                LOGGER.info("manifest JSON Object: $json", true)
                val mcObj = json.get("minecraft")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return ManifestVersions(null, null)

                val mc = mcObj.get("version")?.takeIf { it.isJsonPrimitive }?.asString
                val loader = mcObj.get("modLoaders")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject
                        ?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.substringAfterLast("-")

                return ManifestVersions(mc, loader)
            }
        }
    }

    @Throws(IOException::class)
    override fun postProcessing() {
        if (!File(basePath + "manifest.json").exists()) {
            LOGGER.info("Pack has no manifest.json, skipping mod download")
            return
        }

        val mods = ArrayList<ModEntryRaw>()

        // 任务2：与文件解析同一遍顺带捕获 MC 版本与 loader 名（Modrinth 身份校验用）
        var mcVersion: String? = null
        var loaderName: String? = null

        InputStreamReader(FileInputStream(File(basePath + "manifest.json")), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("manifest JSON Object: $json", true)

            // minecraft.version / minecraft.modLoaders[0].id（如 forge-43.1.1 → forge、neoforge-21.1.241 → neoforge、fabric-0.16.5 → fabric）
            val mcObj = json.get("minecraft")?.takeIf { it.isJsonObject }?.asJsonObject
            mcVersion = mcObj?.get("version")?.takeIf { it.isJsonPrimitive }?.asString
            val loaderId = mcObj?.get("modLoaders")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.firstOrNull { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            loaderName = loaderId?.substringBefore("-")?.lowercase()

            // gets all the mods
            for (jsonElement in json.getAsJsonArray("files")) {
                val obj = jsonElement.asJsonObject
                mods.add(ModEntryRaw(
                        obj.getAsJsonPrimitive("projectID").asString,
                        obj.getAsJsonPrimitive("fileID").asString))
            }
        }

        downloadMods(mods, mcVersion, loaderName)
    }

    data class GetFilesResponseHashes(
        val value: String,
        val algo: Int
    )

    data class GetFilesResponseMod(
        val id: Int,
        val modId: Int,
        val fileName: String,
        val displayName: String,
        val downloadUrl: String?,
        val hashes: List<GetFilesResponseHashes>,
        val gameVersions: List<String>? = null   // 新增字段
    )


    data class GetFilesResponse(
        val data: List<GetFilesResponseMod>
    )

    /** CF 三态判定：gameVersions 忽略大小写包含判断（缺省/仅客户端/双端）。 */
    private fun apiVerdict(gameVersions: List<String>?): ApiVerdict {
        val hasClient = gameVersions?.any { it.equals("client", ignoreCase = true) } == true
        val hasServer = gameVersions?.any { it.equals("server", ignoreCase = true) } == true
        return when {
            hasClient && !hasServer -> ApiVerdict.CLIENT_ONLY
            hasClient && hasServer -> ApiVerdict.DUAL
            else -> ApiVerdict.DEFAULT
        }
    }

    private fun requestModInformation(mods: List<ModEntryRaw>, ignoreSet: HashSet<String>): GetFilesResponse {
        LOGGER.info("Requesting Download links from curse api.")

        val gson = Gson()
        val url = "https://api.curseforge.com/v1/mods/files"
        val allMods = ArrayList<GetFilesResponseMod>()

        // 分块请求：CF 对大请求体有限制，几百个 fileId 一次发过去可能直接 400
        for (chunk in chunkFileIds(mods.map { it.fileID })) {
            val bodyJson = gson.toJson(GetModFilesRequestBody(chunk))
            LOGGER.info("Request Body: $bodyJson", true)

            val str = internetManager.postJson(
                    url,
                    bodyJson,
                    mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                            "x-api-key" to configFile.install.curseForgeApiKey
                    )
            )
            LOGGER.info("Response Json from fileId query: ${str.length}", true)
            LOGGER.info("Response Json from fileId query: $str", true)

            val jsonRes = gson.fromJson(str, GetFilesResponse::class.java)
            LOGGER.info("Converted Response from manifest query: $jsonRes", true)

            allMods.addAll(jsonRes.data)
        }

        val distinctMods = allMods.distinct()
        val filteredMods = distinctMods.filter { mod ->
            // 1. 忽略列表中的项目
            val isIgnoredById = ignoreSet.contains(mod.modId.toString())
            // 2. 非 jar 文件（比如资源包）
            val isNotJar = !mod.fileName.endsWith(".jar")
            // 3. CF 三态判定：仅客户端（含 Client 且不含 Server，忽略大小写）不下载
            val verdict = apiVerdict(mod.gameVersions)

            // 保留条件：非忽略、是 jar、且不是仅客户端
            !isIgnoredById && !isNotJar && verdict != ApiVerdict.CLIENT_ONLY
        }
        // ignore resource pack and shader pack
        val ignoredMods = distinctMods.filter { it !in filteredMods }
        val ignoredModsString = ignoredMods.joinToString(separator = "\n") { "\t${it.fileName} (${it.modId})" }
        LOGGER.info("Ignoring the following mods:\n $ignoredModsString")

        return GetFilesResponse(filteredMods)
    }

    /**
     * 带 x-api-key 请求头的 CF GET（InternetManager.get 不支持自定义头）。
     * 复用 httpClient 的 UA / 超时配置；非 2xx / 无 body → IOException。
     */
    @Throws(IOException::class)
    private fun cfGet(url: String): String {
        val request = Request.Builder()
                .url(url)
                .get()
                .header("x-api-key", configFile.install.curseForgeApiKey)
                .build()
        internetManager.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error code: ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Message body was null for $url")
            return body.string()
        }
    }

    /**
     * Downloads the mods specified in the manifest
     * Gets the data from cursemeta
     *
     * @param mods List of the mods from the manifest
     * @param mcVersion 包的 MC 版本（manifest minecraft.version，用于 Modrinth 身份校验）
     * @param loaderName 包的 loader 名（如 forge/neoforge/fabric，用于 Modrinth 身份校验）
     */
    private fun downloadMods(mods: List<ModEntryRaw>, mcVersion: String?, loaderName: String?) {
        val ignoreSet = HashSet<String>()
        val ignoreListTemp = configFile.install.getFormatSpecificSettingOrDefault<List<Any>>("ignoreProject", null)
        if (ignoreListTemp != null)
            for (o in ignoreListTemp) {
                if (o is String)
                    ignoreSet.add(o)

                if (o is Int)
                    ignoreSet.add(o.toString())
            }


        val targets = ArrayList<ModDownloader.DownloadTarget>()
        val modsInformation = requestModInformation(mods, ignoreSet)

        // 任务2：对 CF 缺省（default）模组做 Modrinth 跨平台身份预扫描（HIGH 命中 client-only 跳过下载）
        // 并发 + 去重缓存由 CurseModrinthPreScan 负责，详见该对象 KDoc
        val skipFileNames: Set<String> = if (mcVersion != null && loaderName != null) {
            CurseModrinthPreScan.scan(
                    mods = modsInformation.data
                            .filter { apiVerdict(it.gameVersions) == ApiVerdict.DEFAULT }
                            .map { mod ->
                                CurseModrinthPreScan.ModInput(
                                        fileName = mod.fileName,
                                        modId = mod.modId,
                                        sha1 = mod.hashes.firstOrNull { hash -> hash.algo == 1 }?.value)
                            },
                    mcVersion = mcVersion,
                    loaderName = loaderName,
                    getModrinth = { url -> internetManager.get(url) },
                    getCurseForge = { url -> cfGet(url) }
            )
        } else {
            LOGGER.info("Modrinth identity check skipped: manifest has no minecraft version or loader, keeping all default files")
            emptySet()
        }

        modsInformation.data.forEach { mod ->
            // 已确认 client-only（Modrinth 哈希校验）→ 不下载、不记录判定
            if (mod.fileName in skipFileNames) return@forEach
            // 记录 CF 三态判定（fileName → ApiVerdict）。过滤后保留的只可能是缺省/双端，
            // 供安装后 jar 扫描与 TOML 规则做综合决策（冲突时提示用户）。
            apiVerdictsByFileMutable[mod.fileName] = apiVerdict(mod.gameVersions)
            // CF API 的 hashes 里 algo=1 即 sha1（见 CurseForge API 文档），下载后用于校验
            val sha1 = mod.hashes.firstOrNull { it.algo == 1 }?.value
            val url = mod.downloadUrl
                    ?: "https://edge.forgecdn.net/files/${mod.id / 1000}/${mod.id % 1000}/${mod.fileName}"
            targets.add(ModDownloader.DownloadTarget(listOf(url), mod.fileName, sha1 = sha1))
        }
        LOGGER.info("Mods to download: $targets", true)

        // constructs the ignore list（ignoreFiles 中 mods/ 前缀项 → shouldSkip 钩子）
        // （唯一实现在 FileIgnoreRules；非 mods/ 前缀项只影响解压阶段）
        val ignoreMatchers = FileIgnoreRules.downloadMatchers(configFile.install.ignoreFiles)
        ModDownloader(basePath, internetManager).downloadTargets(targets) { modName ->
            FileIgnoreRules.firstMatch(ignoreMatchers, setOf(modName)) != null
        }
    }

    companion object {
        /** CF `POST /v1/mods/files` 单次请求的 fileId 上限（保守分块，避免超长请求体被拒）。 */
        private const val CURSE_FILE_QUERY_CHUNK = 500

        /** fileId 去重后按上限分块（纯函数，便于单测）。 */
        fun chunkFileIds(fileIds: List<String>, chunkSize: Int = CURSE_FILE_QUERY_CHUNK): List<List<String>> =
                fileIds.distinct().chunked(chunkSize)
    }
}

/** CF `POST /v1/mods/files` 的请求体 */
data class GetModFilesRequestBody(val fileIds: List<String>)

/**
 * Data class to keep projectID and fileID together
 */
data class ModEntryRaw(val projectID: String, val fileID: String)

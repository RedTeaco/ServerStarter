package atm.bloodworkxgaming.serverstarter.packtype.curse

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.ModrinthIdentityLookup
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.PathMatcher
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern
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

    /** CF 项目详情（GET /v1/mods/{modId} 的 data 段，任务2 名称降级路径用）。 */
    private data class CfProjectInfo(val name: String, val authors: List<String>)

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

        data class GetModFilesRequestBody(val fileIds: List<String>)
        val fileList = GetModFilesRequestBody(mods.map { it.fileID }.toList())

        val gson = Gson()
        val bodyJson = gson.toJson(fileList)
        LOGGER.info("Request Body: $bodyJson", true)

        val url = "https://api.curseforge.com/v1/mods/files"
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

        val filteredMods = jsonRes.data.distinct().toList()
            .filter { mod ->
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
        val ignoredMods = jsonRes.data.distinct().toList().filter { it !in filteredMods }
        val ignoredModsString = ignoredMods.joinToString(separator = "\n") { "\t${it.fileName} (${it.modId})" }
        LOGGER.info("Ignoring the following mods:\n $ignoredModsString")

        return GetFilesResponse(filteredMods)
    }

    /**
     * 任务2：对 CF 缺省（default）模组做 Modrinth 跨平台身份预扫描（仅当 manifest 提供 MC 版本与 loader 时）。
     *
     * - HIGH（sha1 哈希反查 + 同 loader + 同 MC 版本）：命中且 environment=client_only →
     *   标记跳过下载（不落盘、不记录判定）；命中但非 client_only → 保留下载。
     * - MEDIUM/LOW（名称 + 作者 / 仅名称）：仅 LOGGER 提示，照常下载（fail-safe，绝不因弱匹配丢模组）。
     * - 任何查询失败 → warn + 维持 default（fail-safe）。
     */
    private fun preScanModrinthIdentity(
            mods: List<GetFilesResponseMod>,
            mcVersion: String,
            loaderName: String,
            skipFileNames: MutableSet<String>
    ) {
        for (mod in mods) {
            if (apiVerdict(mod.gameVersions) != ApiVerdict.DEFAULT) continue

            LOGGER.info("CF file ${mod.fileName} has no client/server tags (default), checking Modrinth for identity...")

            // HIGH 路径：CF sha1（algo=1 = Sha1，见 CurseForge API 文档）→ Modrinth v3 哈希反查
            val sha1 = mod.hashes.firstOrNull { it.algo == 1 }?.value
            val hashHit = if (sha1 != null) {
                try {
                    ModrinthIdentityLookup.lookupByHash(sha1, mcVersion, loaderName) { url -> internetManager.get(url) }
                } catch (e: IOException) {
                    LOGGER.warn("Modrinth lookup for ${mod.fileName} failed, keeping (fail-safe): ${e.message}")
                    null
                }
            } else {
                null
            }

            if (hashHit != null) {
                LOGGER.info("Matched Modrinth version by file hash (loader=$loaderName, mc=$mcVersion): project=${hashHit.projectId} (${hashHit.versionName}), environment=${hashHit.environment ?: "null"}")
                if (hashHit.environment != null && hashHit.environment.equals("client_only", ignoreCase = true)) {
                    LOGGER.warn("Modrinth environment=client_only for ${mod.fileName} (verified by sha1 hash, loader=$loaderName, mc=$mcVersion) -> client-only, skipping download")
                    skipFileNames.add(mod.fileName)
                } else {
                    LOGGER.info("Modrinth environment=${hashHit.environment ?: "null"} for ${mod.fileName} -> not client-only, keeping")
                }
                continue
            }

            // MEDIUM/LOW 路径：CF 项目名 + 作者（best-effort）→ Modrinth 搜索（仅日志，不处置）
            val cfProject = try {
                cfProjectInfo(mod.modId)
            } catch (e: IOException) {
                LOGGER.warn("CurseForge project info lookup for mod ${mod.modId} failed, keeping ${mod.fileName} as default: ${e.message}")
                null
            }

            val nameMatch = if (cfProject != null) {
                try {
                    ModrinthIdentityLookup.lookupByName(cfProject.name, cfProject.authors, mcVersion, loaderName) { url -> internetManager.get(url) }
                } catch (e: IOException) {
                    LOGGER.warn("Modrinth lookup for ${mod.fileName} failed, keeping (fail-safe): ${e.message}")
                    null
                }
            } else {
                null
            }

            when {
                nameMatch == null -> LOGGER.info("No Modrinth match for ${mod.fileName}, keeping as default")
                nameMatch.authorMatched ->
                    LOGGER.info("Matched Modrinth project by name/author: ${nameMatch.name} (${nameMatch.slug ?: "null"}), environment=${nameMatch.environment ?: "null"}; keeping ${mod.fileName}")
                else ->
                    LOGGER.warn("Possible but unverified Modrinth match (name only): ${nameMatch.name} (${nameMatch.slug ?: "null"}), environment=${nameMatch.environment ?: "null"}; keeping ${mod.fileName}")
            }
        }
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
     * CF 项目详情（GET /v1/mods/{modId}）：解析 data.name（项目名，用于 Modrinth 搜索，非 displayName）
     * 与 data.authors[].name。网络失败（IOException）向上抛出由调用方处理；响应解析失败 → warn + null（fail-safe）。
     */
    private fun cfProjectInfo(modId: Int): CfProjectInfo? {
        val body = cfGet("https://api.curseforge.com/v1/mods/$modId")
        return try {
            val data = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
            val name = data.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            val authors = data.get("authors")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { element ->
                        element.takeIf { it.isJsonObject }?.asJsonObject
                                ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    }
                    ?: emptyList()
            if (name.isEmpty()) null else CfProjectInfo(name, authors)
        } catch (e: Exception) {
            LOGGER.warn("Failed to parse CurseForge project info for mod $modId, keeping as default: ${e.message}")
            null
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


        val urls = ConcurrentLinkedQueue<String>()
        val modsInformation = requestModInformation(mods, ignoreSet)

        // 任务2：对 CF 缺省（default）模组做 Modrinth 跨平台身份预扫描（HIGH 命中 client-only 跳过下载）
        val skipFileNames = HashSet<String>()
        if (mcVersion != null && loaderName != null) {
            preScanModrinthIdentity(modsInformation.data, mcVersion, loaderName, skipFileNames)
        } else {
            LOGGER.info("Modrinth identity check skipped: manifest has no minecraft version or loader, keeping all default files")
        }

        modsInformation.data.forEach { mod ->
            // 已确认 client-only（Modrinth 哈希校验）→ 不下载、不记录判定
            if (mod.fileName in skipFileNames) return@forEach
            // 记录 CF 三态判定（fileName → ApiVerdict）。过滤后保留的只可能是缺省/双端，
            // 供安装后 jar 扫描与 TOML 规则做综合决策（冲突时提示用户）。
            apiVerdictsByFileMutable[mod.fileName] = apiVerdict(mod.gameVersions)
            if (mod.downloadUrl != null) {
                urls.add(mod.downloadUrl)
            } else {
                val url = "https://edge.forgecdn.net/files/${mod.id / 1000}/${mod.id % 1000}/${mod.fileName}"
                urls.add(url)
            }
        }
        LOGGER.info("Mods to download: $urls", true)

        // constructs the ignore list（ignoreFiles 中 mods/ 前缀项 → shouldSkip 钩子）
        val ignorePatterns = ArrayList<Pattern>()
        for (ignoreFile in configFile.install.ignoreFiles) {
            if (ignoreFile.startsWith("mods/")) {
                ignorePatterns.add(Pattern.compile(ignoreFile.substring(ignoreFile.lastIndexOf('/'))))
            }
        }

        ModDownloader(basePath, internetManager).downloadAll(urls) { modName ->
            ignorePatterns.any { it.matcher(modName).matches() }
        }
    }
}

/**
 * Data class to keep projectID and fileID together
 */
data class ModEntryRaw(val projectID: String, val fileID: String)

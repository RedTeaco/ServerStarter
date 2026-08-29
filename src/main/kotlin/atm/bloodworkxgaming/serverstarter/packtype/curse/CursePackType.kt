package atm.bloodworkxgaming.serverstarter.packtype.curse

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import com.google.gson.Gson
import com.google.gson.JsonParser
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
            LOGGER.info("Pack 无 manifest.json，跳过模组下载")
            return
        }

        val mods = ArrayList<ModEntryRaw>()

        InputStreamReader(FileInputStream(File(basePath + "manifest.json")), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("manifest JSON Object: $json", true)

            // gets all the mods
            for (jsonElement in json.getAsJsonArray("files")) {
                val obj = jsonElement.asJsonObject
                mods.add(ModEntryRaw(
                        obj.getAsJsonPrimitive("projectID").asString,
                        obj.getAsJsonPrimitive("fileID").asString))
            }
        }

        downloadMods(mods)
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
                // 3. 判断是否为客户端专用（包含 Client 且不包含 Server）
                val gameVersions = mod.gameVersions
                val isClientOnly = gameVersions?.contains("Client") == true && gameVersions?.contains("Server") != true

                // 保留条件：非忽略、是 jar、且不是客户端专用
                !isIgnoredById && !isNotJar && !isClientOnly
            }
        // ignore resource pack and shader pack
        val ignoredMods = jsonRes.data.distinct().toList().filter { it !in filteredMods }
        val ignoredModsString = ignoredMods.joinToString(separator = "\n") { "\t${it.fileName} (${it.modId})" }
        LOGGER.info("Ignoring the following mods:\n $ignoredModsString")

        return GetFilesResponse(filteredMods)
    }

    /**
     * Downloads the mods specified in the manifest
     * Gets the data from cursemeta
     *
     * @param mods List of the mods from the manifest
     */
    private fun downloadMods(mods: List<ModEntryRaw>) {
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
        modsInformation.data.forEach { mod ->
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

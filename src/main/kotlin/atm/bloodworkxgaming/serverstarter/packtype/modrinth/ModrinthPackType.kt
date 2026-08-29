package atm.bloodworkxgaming.serverstarter.packtype.modrinth

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ModDownloader
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.PathMatcher
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
     * mrpack 抛 ClassCastException 的 bug）；loader 取 dependencies 中 minecraft 之后的
     * 下一个键（保持既有 keySet().last() "第二个键即 loader" 语义，实现空安全）。
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
                val loaderKey = deps.keySet().lastOrNull()?.takeIf { it != "minecraft" }
                val loader = loaderKey?.let { key -> deps.get(key)?.takeIf { it.isJsonPrimitive }?.asString }

                return ManifestVersions(mc, loader)
            }
        }
    }

    @Throws(IOException::class)
    override fun postProcessing() {
        val modsUrl = ArrayList<String>()

        InputStreamReader(FileInputStream(File(basePath + "modrinth.index.json")), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("mainfest JSON Object: $json", true)

            // gets all the mods
            for (jsonElement in json.getAsJsonArray("files")) {
                val obj = jsonElement.asJsonObject
                // env server: unsupported 可以自动排除ResourcePack and ShaderPack
                if (obj.getAsJsonObject("env").getAsJsonPrimitive("server").asString.equals("unsupported")) {
                    continue
                }
                if (obj.getAsJsonPrimitive("path").asString.substringBefore("/") != "mods") {
                    continue
                } else {
                    modsUrl.add(obj.getAsJsonArray("downloads").get(0).asString)
                }
            }
        }

        ModDownloader(basePath, internetManager).downloadAll(modsUrl)
    }
}

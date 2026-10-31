package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * 整合包格式自动识别（`modpackFormat` 留空 / `auto` / `detect` 时使用）。
 *
 * 只看包内的约定文件，不看文件名与扩展名：
 * - 含 `modrinth.index.json` → [FORMAT_MODRINTH]；
 * - 含**带 `minecraft` 对象**的 `manifest.json` → [FORMAT_CURSE]（普通 zip 里也可能有同名文件，
 *   所以额外要求能解析出 minecraft 段）；
 * - 都没有 → [FORMAT_ZIP]（按纯 zip 解压全部内容）。
 *
 * 清单不一定在包根目录：整合包被重新打包时常常多套一层目录（`pack/manifest.json`），
 * 或者直接就是启动器导出的实例目录（`flame/manifest.json` + 已展开的 `minecraft/`）。
 * 这两种情况也会被识别，并通过 [Inspection.manifestEntry] / [Inspection.overridesPrefix]
 * 告诉调用方实际的清单路径与需要剥离的解压前缀。
 */
object PackFormatDetector {

    const val FORMAT_MODRINTH = "modrinth"
    const val FORMAT_CURSE = "curse"
    const val FORMAT_ZIP = "zip"

    private const val MODRINTH_MANIFEST = "modrinth.index.json"
    private const val CURSE_MANIFEST = "manifest.json"
    private const val DEFAULT_OVERRIDES = "overrides/"
    private const val EXPORT_GAME_DIR = "minecraft/"

    /**
     * 识别结果。
     *
     * @param manifestEntry 清单在包内的实际路径（如 `manifest.json`、`flame/manifest.json`）
     * @param overridesPrefix 解压时要剥掉的前缀；根目录包为 `overrides/`，实例导出为 `minecraft/`
     * @param nestedManifest 清单是否位于子目录（即重新打包时多套了一层）
     * @param instanceExport 是否是启动器导出的实例目录（清单在子目录 + 已展开的 `minecraft/`）
     */
    data class Inspection(
            val format: String,
            val manifestEntry: String? = null,
            val overridesPrefix: String? = null,
            val nestedManifest: Boolean = false,
            val instanceExport: Boolean = false
    )

    /** 识别不出明确格式时按纯 zip 处理。 */
    @Throws(IOException::class)
    fun detectOrZip(zip: File): String = inspect(zip).format

    /**
     * 识别格式；识别不出返回 null。
     *
     * @throws IOException zip 打不开（不是 zip 容器 / 已损坏）时抛出，由调用方给出可读错误
     */
    @Throws(IOException::class)
    fun detect(zip: File): String? = inspect(zip).format.takeIf { it != FORMAT_ZIP }

    /**
     * 完整识别：格式 + 清单路径 + overrides 前缀（自动识别模式下由 `ServerStarter` 注入包型）。
     */
    @Throws(IOException::class)
    fun inspect(zip: File): Inspection {
        ZipFile(zip).use { zipFile ->
            val names = zipFile.entries().asSequence().map { it.name }.toList()

            fun has(entry: String) = zipFile.getEntry(entry) != null
            fun curseManifest(entry: String) = has(entry) && hasMinecraftSection(zipFile, entry, zip.name)

            // ① 清单在包根目录（标准整合包）
            if (has(MODRINTH_MANIFEST)) {
                return Inspection(FORMAT_MODRINTH, MODRINTH_MANIFEST, DEFAULT_OVERRIDES)
            }
            if (curseManifest(CURSE_MANIFEST)) {
                return Inspection(FORMAT_CURSE, CURSE_MANIFEST, DEFAULT_OVERRIDES)
            }

            // ② 清单在一层子目录里
            val topLevelDirs = names.mapNotNull { name ->
                val slash = name.indexOf('/')
                if (slash > 0) name.substring(0, slash) else null
            }.toSortedSet()

            for (dir in topLevelDirs) {
                val entry = "$dir/$MODRINTH_MANIFEST"
                if (has(entry)) {
                    val prefix = if (names.any { it.startsWith("$dir/$DEFAULT_OVERRIDES") }) {
                        "$dir/$DEFAULT_OVERRIDES"
                    } else {
                        "$dir/"
                    }
                    return Inspection(FORMAT_MODRINTH, entry, prefix, nestedManifest = true)
                }
            }

            for (dir in topLevelDirs) {
                val entry = "$dir/$CURSE_MANIFEST"
                if (!curseManifest(entry)) continue

                val hasOwnOverrides = names.any { it.startsWith("$dir/$DEFAULT_OVERRIDES") }
                return when {
                    hasOwnOverrides ->
                        Inspection(FORMAT_CURSE, entry, "$dir/$DEFAULT_OVERRIDES", nestedManifest = true)

                    // 启动器实例导出：清单在子目录，游戏目录已经展开为 minecraft/
                    names.any { it.startsWith(EXPORT_GAME_DIR) } ->
                        Inspection(FORMAT_CURSE, entry, EXPORT_GAME_DIR,
                                nestedManifest = true, instanceExport = true)

                    else ->
                        Inspection(FORMAT_CURSE, entry, "$dir/", nestedManifest = true)
                }
            }

            return Inspection(FORMAT_ZIP)
        }
    }

    /** manifest.json 是否带 `minecraft` 对象（普通 zip 里的同名文件不算整合包清单）。 */
    private fun hasMinecraftSection(zipFile: ZipFile, entry: String, zipName: String): Boolean {
        return try {
            zipFile.getInputStream(zipFile.getEntry(entry)).use { input ->
                JsonParser.parseReader(InputStreamReader(input, "utf-8")).asJsonObject
                        .get("minecraft")?.isJsonObject == true
            }
        } catch (e: Exception) {
            LOGGER.warn("$entry in $zipName could not be parsed, treating the pack as a plain zip: ${e.message}")
            false
        }
    }
}

package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

fun ZipInputStream.writeToFile(file: File) {
    file.outputStream().use { fos ->
        val bytes = this.readBytes()
        fos.write(bytes, 0, bytes.size)
    }
}

/**
 * 共享解压器（Q7 §7.1）：收敛 curse / modrinth / zip 三份几乎逐字重复的解压循环，
 * 格式差异通过构造参数注入，行为与现状一致：
 * - 先删除 OLD_TO_DELETE；[moveModsFolderFirst] 时先把 basePath/mods 移入 OLD_TO_DELETE。
 * - manifest 条目单独落盘；[overridesPrefix] 前缀剥离；ignoreFiles 匹配跳过。
 * - [rethrowOnError] 为 false 时吞掉 IOException（纯 zip 格式现状）。
 */
class ZipExtractor(
        private val basePath: String,
        private val oldFiles: File,
        private val pathMatchers: List<PathMatcher>,
        private val manifestEntryName: String? = null,
        private val overridesPrefix: String? = "overrides/",
        private val moveModsFolderFirst: Boolean = true,
        private val rethrowOnError: Boolean = true
) {
    @Throws(IOException::class)
    fun extract(file: File) {
        // delete old installer folder
        FileUtils.deleteDirectory(oldFiles)

        // start with deleting the mods folder as it is not guaranteed to have override mods
        if (moveModsFolderFirst) {
            val modsFolder = File(basePath, "mods")

            if (modsFolder.exists())
                FileUtils.moveDirectory(modsFolder, File(oldFiles, "mods"))
            LOGGER.info("Moved the mods folder")
        }

        LOGGER.info("Starting to unzip files.")
        // unzip start
        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry

                while (entry != null) {
                    LOGGER.info("Entry in zip: $entry", true)
                    val name = entry.name

                    // special manifest treatment
                    if (manifestEntryName != null && name == manifestEntryName)
                        zis.writeToFile(File(basePath, name))

                    // overrides 前缀剥离；null 表示全量解压（纯 zip 格式）
                    val relPath = if (overridesPrefix != null) {
                        if (name.startsWith(overridesPrefix)) name.substring(overridesPrefix.length) else null
                    } else {
                        name
                    }

                    // relPath 为空串（如 overrides/ 根目录条目）时跳过
                    if (relPath != null && relPath.isNotEmpty()) {
                        when {
                            pathMatchers.any { it.matches(Paths.get(relPath)) } ->
                                LOGGER.info("Skipping $relPath as it is on the ignore List.", true)

                            !name.endsWith("/") -> {
                                val outfile = File(basePath, relPath)
                                LOGGER.info("Copying zip entry to = $outfile", true)

                                outfile.parentFile?.mkdirs()

                                zis.writeToFile(outfile)
                            }

                            else -> {
                                val newFolder = File(basePath, relPath)
                                if (newFolder.exists())
                                    FileUtils.moveDirectory(newFolder, File(oldFiles, relPath))

                                LOGGER.info("Folder moved: " + newFolder.absolutePath, true)
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: IOException) {
            LOGGER.error("Could not unzip files", e)
            if (rethrowOnError)
                throw e
        }

        LOGGER.info("Done unzipping the files.")
    }
}
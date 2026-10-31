import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 共享解压器的返回值与空解压告警测试。
 *
 * 真实故障（2.5.8）：整合包是启动器实例导出（游戏目录在 `minecraft/`），而包型按默认
 * `overrides/` 前缀解压，所有条目都被跳过，日志却照样打印 "Done unzipping the files."，
 * 装完是一台空服务器。现在 extract() 返回写入的文件数，0 时由调用方/日志报警。
 */
class ZipExtractorTest {

    private val tempDir: File = Files.createTempDirectory("zip-extractor").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    private fun installDir(name: String): File = File(tempDir, name).apply { mkdirs() }

    private fun extractor(install: File, prefix: String? = "overrides/",
                          manifestEntry: String? = null, manifestTarget: String? = null) =
            ZipExtractor(
                    basePath = install.absolutePath,
                    oldFiles = File(tempDir, "old-${install.name}"),
                    pathMatchers = emptyList(),
                    manifestEntryName = manifestEntry,
                    manifestTargetName = manifestTarget,
                    overridesPrefix = prefix,
                    moveModsFolderFirst = false)

    @Test
    fun `剥离 overrides 前缀并返回写入的文件数`() {
        val install = installDir("plain")
        val pack = zip("plain.zip", mapOf(
                "overrides/a.txt" to "a",
                "overrides/sub/b.txt" to "b",
                "other/c.txt" to "c"))
        // overrides/ 本身是个目录条目，会被跳过；其它前缀内容不被解压
        val extracted = extractor(install).extract(pack)

        assertEquals(2, extracted)
        assertTrue(File(install, "a.txt").isFile)
        assertTrue(File(install, "sub/b.txt").isFile)
        assertFalse(File(install, "other").exists())
    }

    @Test
    fun `前缀不匹配时返回 0 而不是假装成功`() {
        val install = installDir("export")
        val pack = zip("export.zip", mapOf(
                "minecraft/mods/foo.jar" to "jar",
                "minecraft/config/x.toml" to "cfg"))

        val extracted = extractor(install, prefix = "overrides/").extract(pack)

        assertEquals(0, extracted)
        assertFalse(File(install, "minecraft").exists())
        assertTrue(install.listFiles().isNullOrEmpty())
    }

    @Test
    fun `实例导出的清单会落到根目录并且游戏目录被剥离`() {
        val install = installDir("instance")
        val pack = zip("instance.zip", mapOf(
                "flame/manifest.json" to "{\"minecraft\":{}}",
                "minecraft/config/x.toml" to "cfg",
                "minecraft/mods/foo.jar" to "jar"))

        val extracted = extractor(install, prefix = "minecraft/",
                manifestEntry = "flame/manifest.json", manifestTarget = "manifest.json").extract(pack)

        assertEquals(2, extracted)
        assertTrue(File(install, "manifest.json").isFile)
        assertTrue(File(install, "config/x.toml").isFile)
        assertTrue(File(install, "mods/foo.jar").isFile)
        assertFalse(File(install, "flame").exists())
        assertFalse(File(install, "minecraft").exists())
    }
}

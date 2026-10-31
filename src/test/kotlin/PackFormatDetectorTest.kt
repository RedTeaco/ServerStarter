import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.util.PackFormatDetector
import atm.bloodworkxgaming.serverstarter.util.PackObtainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 整合包格式自动识别与取包的本地部分测试（不联网）。
 *
 * 覆盖：mrpack / CF 包 / 普通 zip 的判定、同名 manifest.json 的区分、坏 zip 的报错，
 * 以及 [PackObtainer] 的本地定位（`./.zip` 扫描、`file://`）、zip 魔数判断、CF 项目页 URL 规整。
 */
class PackFormatDetectorTest {

    private val workDir = File(System.getProperty("java.io.tmpdir"), "pack-format-detector-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val file = File(workDir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `含 modrinth_index_json 判为 modrinth`() {
        val pack = zip("pack.mrpack", "modrinth.index.json" to """{"formatVersion":1,"files":[]}""", "overrides/config/x.toml" to "")

        assertEquals(PackFormatDetector.FORMAT_MODRINTH, PackFormatDetector.detect(pack))
        assertEquals(PackFormatDetector.FORMAT_MODRINTH, PackFormatDetector.detectOrZip(pack))
    }

    @Test
    fun `含带 minecraft 段的 manifest_json 判为 curse`() {
        val pack = zip("curse.zip",
                "manifest.json" to """{"minecraft":{"version":"1.21.1","modLoaders":[{"id":"neoforge-21.1.249"}]},"files":[]}""",
                "overrides/config/x.toml" to "")

        assertEquals(PackFormatDetector.FORMAT_CURSE, PackFormatDetector.detect(pack))
    }

    @Test
    fun `只是同名 manifest_json 不算 curse，回落到 zip`() {
        val pack = zip("plain.zip", "manifest.json" to """{"name":"not a curse manifest"}""", "config/x.toml" to "")

        assertNull(PackFormatDetector.detect(pack))
        assertEquals(PackFormatDetector.FORMAT_ZIP, PackFormatDetector.detectOrZip(pack))
    }

    @Test
    fun `完全普通的 zip 回落为 zip`() {
        val pack = zip("overrides-only.zip", "config/x.toml" to "a=1")

        assertNull(PackFormatDetector.detect(pack))
        assertEquals(PackFormatDetector.FORMAT_ZIP, PackFormatDetector.detectOrZip(pack))
    }

    @Test
    fun `两个 manifest 同时存在时优先 modrinth`() {
        val pack = zip("both.zip",
                "manifest.json" to """{"minecraft":{"version":"1.21.1"}}""",
                "modrinth.index.json" to """{"formatVersion":1,"files":[]}""")

        assertEquals(PackFormatDetector.FORMAT_MODRINTH, PackFormatDetector.detect(pack))
    }

    @Test
    fun `manifest_json 不是合法 JSON 时按普通 zip 处理`() {
        val pack = zip("broken-manifest.zip", "manifest.json" to "{ this is not json")

        assertNull(PackFormatDetector.detect(pack))
    }

    @Test
    fun `不是 zip 容器时抛出 IOException`() {
        val notZip = File(workDir, "not-a-zip.zip").apply { writeText("PK? no, just text") }

        var thrown = false
        try {
            PackFormatDetector.detect(notZip)
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue("非 zip 应抛 IOException", thrown)
    }

    @Test
    fun `newestPackIn 只认 zip 与 mrpack 并取最新`() {
        val dir = File(workDir, "local-scan").apply { mkdirs() }
        File(dir, "old.zip").writeText("x"); File(dir, "old.zip").setLastModified(1_000_000)
        File(dir, "new.mrpack").writeText("x"); File(dir, "new.mrpack").setLastModified(2_000_000)
        File(dir, "ignored.txt").writeText("x"); File(dir, "ignored.txt").setLastModified(9_000_000)

        assertEquals("new.mrpack", PackObtainer.newestPackIn(dir)?.name)
    }

    @Test
    fun `目录里同时有启动器分发包时按内容挑出真正的整合包`() {
        val dir = File(workDir, "pick-by-content").apply { mkdirs() }
        // 启动器分发包：更新的时间戳，但没有 manifest —— 旧逻辑会错误地挑中它
        zip("launcher.zip", "startserver.bat" to "@echo off", "serverstarter-2.5.4.jar" to "jar", "server-setup-config.yaml" to "cfg")
                .renameTo(File(dir, "launcher.zip"))
        File(dir, "launcher.zip").setLastModified(9_000_000)
        zip("pack.mrpack", "modrinth.index.json" to """{"formatVersion":1,"files":[]}""")
                .renameTo(File(dir, "pack.mrpack"))
        File(dir, "pack.mrpack").setLastModified(1_000_000)

        assertTrue(PackObtainer.isLauncherDistribution(File(dir, "launcher.zip")))
        assertEquals("pack.mrpack", PackObtainer.findPackIn(dir)?.name)
        assertEquals("pack.mrpack", PackObtainer.findPackIn(dir, PackFormatDetector.FORMAT_MODRINTH)?.name)
    }

    @Test
    fun `显式格式优先于更新的文件`() {
        val dir = File(workDir, "prefer-format").apply { mkdirs() }
        zip("newer.mrpack", "modrinth.index.json" to """{"formatVersion":1,"files":[]}""")
                .renameTo(File(dir, "newer.mrpack"))
        File(dir, "newer.mrpack").setLastModified(9_000_000)
        zip("older.zip", "manifest.json" to """{"minecraft":{"version":"1.21.1"}}""")
                .renameTo(File(dir, "older.zip"))
        File(dir, "older.zip").setLastModified(1_000_000)

        assertEquals("older.zip", PackObtainer.findPackIn(dir, PackFormatDetector.FORMAT_CURSE)?.name)
        assertEquals("newer.mrpack", PackObtainer.findPackIn(dir, PackFormatDetector.FORMAT_MODRINTH)?.name)
    }

    @Test
    fun `都识别不出格式时回落到最新（纯 zip 包型）`() {
        val dir = File(workDir, "fallback-newest").apply { mkdirs() }
        File(dir, "a.zip").writeText("x"); File(dir, "a.zip").setLastModified(1_000_000)
        File(dir, "b.zip").writeText("x"); File(dir, "b.zip").setLastModified(2_000_000)

        assertEquals("b.zip", PackObtainer.findPackIn(dir)?.name)
    }

    @Test
    fun `目录里只有启动器分发包时仍按旧规则兜底并告警`() {
        val dir = File(workDir, "only-launcher").apply { mkdirs() }
        zip("launcher.zip", "startserver.sh" to "#!/bin/sh")
                .renameTo(File(dir, "launcher.zip"))

        assertEquals("launcher.zip", PackObtainer.findPackIn(dir)?.name)
    }

    @Test
    fun `findPackIn 在空目录返回 null`() {
        assertEquals(null, PackObtainer.findPackIn(File(workDir, "empty-scan")))
    }

    @Test
    fun `newestPackIn 找不到整合包时返回 null`() {
        val dir = File(workDir, "empty-scan").apply { mkdirs() }
        File(dir, "readme.txt").writeText("x")

        assertNull(PackObtainer.newestPackIn(dir))
    }

    @Test
    fun `isZipFile 按魔数判断`() {
        val real = zip("magic.zip", "a.txt" to "hello")
        val fake = File(workDir, "fake.zip").apply { writeText("not a zip") }

        assertTrue(PackObtainer.isZipFile(real))
        assertFalse(PackObtainer.isZipFile(fake))
        assertFalse(PackObtainer.isZipFile(File(workDir, "missing.zip")))
    }

    @Test
    fun `cursePageDownloadUrl 只给项目页地址补 download`() {
        assertEquals("https://www.curseforge.com/minecraft/modpacks/x/files/123/download",
                PackObtainer.cursePageDownloadUrl("https://www.curseforge.com/minecraft/modpacks/x/files/123"))
        assertEquals("https://www.curseforge.com/minecraft/modpacks/x/files/123/download",
                PackObtainer.cursePageDownloadUrl("https://www.curseforge.com/minecraft/modpacks/x/files/123/download"))
        assertEquals("https://cdn.example.com/pack.mrpack",
                PackObtainer.cursePageDownloadUrl("https://cdn.example.com/pack.mrpack"))
    }

    @Test
    fun `obtain 支持 file 协议本地路径`() {
        val pack = zip("local-file.zip", "config/x.toml" to "a=1")
        val internetManager = InternetManager(ConfigFile())

        val obtained = PackObtainer.obtain(
                "file://" + pack.absolutePath,
                workDir.absolutePath + File.separator,
                internetManager)

        assertEquals(pack.absolutePath, obtained.absolutePath)
    }

    @Test
    fun `清单在子目录的 wrapper 包也能识别并给出解压前缀`() {
        val pack = zip("wrapped.zip",
                "PackName/manifest.json" to """{"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20"}]},"files":[]}""",
                "PackName/overrides/config/x.toml" to "")

        val inspection = PackFormatDetector.inspect(pack)
        assertEquals(PackFormatDetector.FORMAT_CURSE, inspection.format)
        assertEquals("PackName/manifest.json", inspection.manifestEntry)
        assertEquals("PackName/overrides/", inspection.overridesPrefix)
        assertTrue(inspection.nestedManifest)
        assertFalse(inspection.instanceExport)
    }

    @Test
    fun `启动器实例导出（flame manifest + minecraft 目录）识别为 curse 并用 minecraft 作为前缀`() {
        val pack = zip("instance-export.zip",
                "flame/manifest.json" to """{"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20"}]},"files":[]}""",
                "flame/overrides.txt" to "x",
                "lunaui/state.json" to "{}",
                "minecraft/config/x.toml" to "a=1")

        val inspection = PackFormatDetector.inspect(pack)
        assertEquals(PackFormatDetector.FORMAT_CURSE, inspection.format)
        assertEquals("flame/manifest.json", inspection.manifestEntry)
        assertEquals("minecraft/", inspection.overridesPrefix)
        assertTrue(inspection.instanceExport)
    }

    @Test
    fun `子目录里的同名 manifest 没有 minecraft 段时仍回落为 zip`() {
        val pack = zip("wrapped-plain.zip",
                "SomeDir/manifest.json" to """{"name":"not a curse manifest"}""",
                "SomeDir/config/x.toml" to "")

        val inspection = PackFormatDetector.inspect(pack)
        assertEquals(PackFormatDetector.FORMAT_ZIP, inspection.format)
        assertNull(inspection.manifestEntry)
    }

    @Test
    fun `ANSI 剥离只去掉转义序列`() {
        assertEquals("hello", atm.bloodworkxgaming.serverstarter.util.ConsoleOutput
                .stripAnsi("[31mhello[0m"))
    }
}

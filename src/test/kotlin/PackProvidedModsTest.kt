import atm.bloodworkxgaming.serverstarter.util.DownloadIntegrity
import atm.bloodworkxgaming.serverstarter.util.HashingUtil
import atm.bloodworkxgaming.serverstarter.util.PackProvidedMods
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 包内已有模组检测（下载去重）测试。
 *
 * 覆盖两条命中路径：文件名（忽略大小写）与内容哈希（sha1 / sha512），
 * 以及"不该参与"的内容（非 jar、临时文件、子目录）与"目录不存在"的退化情况。
 */
class PackProvidedModsTest {

    private val tempDir: File = Files.createTempDirectory("pack-provided-mods").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun modsFolder(): File = File(tempDir, "mods").apply { mkdirs() }

    private fun jar(name: String, content: String, dir: File = modsFolder()): File =
            File(dir, name).apply {
                parentFile.mkdirs()
                writeText(content)
            }

    @Test
    fun `只索引 mods 顶层的 jar，返回排序后的文件名`() {
        val mods = modsFolder()
        jar("b-mod.jar", "b", mods)
        jar("A-mod.JAR", "a", mods)
        jar("readme.txt", "text", mods)

        val snapshot = PackProvidedMods.scan(mods)

        assertEquals(2, snapshot.size)
        assertEquals(listOf("A-mod.JAR", "b-mod.jar"), snapshot.names())
    }

    @Test
    fun `子目录里的 jar 与临时文件不参与索引`() {
        val mods = modsFolder()
        jar("kept.jar", "kept", mods)
        jar("nested.jar", "nested", File(mods, "disabled"))
        jar("half.jar.part", "part", mods)

        val snapshot = PackProvidedMods.scan(mods)

        assertEquals(1, snapshot.size)
        assertNull(snapshot.find("nested.jar", null, null))
        assertNull(snapshot.find("half.jar.part", null, null))
    }

    @Test
    fun `mods 目录不存在时返回空快照`() {
        val snapshot = PackProvidedMods.scan(File(tempDir, "no-such-mods"))

        assertEquals(0, snapshot.size)
        assertNull(snapshot.find("anything.jar", "a".repeat(40), null))
    }

    @Test
    fun `文件名命中（忽略大小写）即跳过，即使清单哈希对不上`() {
        val mods = modsFolder()
        val existing = jar("Sodium-0.5.8.jar", "pack build", mods)
        val snapshot = PackProvidedMods.scan(mods)

        // 清单给的是另一个构建的 sha1：仍按文件名命中（包内版本优先），但哈希校验不通过
        val otherBuildSha1 = HashingUtil.sha1Hex(File(tempDir, "other.bin").apply { writeText("other build") })
        val match = snapshot.find("sodium-0.5.8.jar", otherBuildSha1, null)

        assertTrue(match!!.viaName)
        assertEquals(existing.name, match.file.name)
        assertFalse(DownloadIntegrity.matches(match.file, otherBuildSha1, null))
    }

    @Test
    fun `内容命中：改名后的同一文件也跳过`() {
        val mods = modsFolder()
        val existing = jar("jei-1.20.1-forge.jar", "same bytes", mods)
        val sha1 = HashingUtil.sha1Hex(existing)
        val snapshot = PackProvidedMods.scan(mods)

        val match = snapshot.find("jei-15.2.0.27.jar", sha1, null)

        assertFalse(match!!.viaName)
        assertEquals(existing.name, match.file.name)
    }

    @Test
    fun `只有 sha512 时同样能内容命中`() {
        val mods = modsFolder()
        val existing = jar("renamed-on-disk.jar", "sha512 only", mods)
        val sha512 = HashingUtil.sha512Hex(existing).uppercase()
        val snapshot = PackProvidedMods.scan(mods)

        val match = snapshot.find("manifest-name.jar", null, sha512)

        assertFalse(match!!.viaName)
        assertEquals(existing.name, match.file.name)
    }

    @Test
    fun `名字与哈希都不匹配时不命中`() {
        val mods = modsFolder()
        jar("present.jar", "present", mods)
        val snapshot = PackProvidedMods.scan(mods)

        assertNull(snapshot.find("missing.jar", "0".repeat(40), "1".repeat(128)))
        // 两者都为 null / 空串时只按文件名判断，不去算哈希
        assertNull(snapshot.find("missing.jar", null, null))
        assertNull(snapshot.find("missing.jar", "  ", ""))
    }
}

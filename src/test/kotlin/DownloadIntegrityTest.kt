import atm.bloodworkxgaming.serverstarter.util.DownloadIntegrity
import atm.bloodworkxgaming.serverstarter.util.HashingUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载完整性校验测试：哈希算法输出 + [DownloadIntegrity] 的取舍规则。
 *
 * 哈希向量用标准测试值（"abc" / 空串），避免"自己算自己比"的空转。
 */
class DownloadIntegrityTest {

    private fun tempFile(content: String): File {
        val f = File.createTempFile("download-integrity", ".bin")
        f.deleteOnExit()
        f.writeText(content)
        return f
    }

    @Test
    fun `SHA-1 与 SHA-512 输出标准向量`() {
        val abc = tempFile("abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", HashingUtil.sha1Hex(abc))
        assertEquals(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                        "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                HashingUtil.sha512Hex(abc))

        val empty = tempFile("")
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", HashingUtil.sha1Hex(empty))
    }

    @Test
    fun `提供 sha512 时优先用 sha512`() {
        val f = tempFile("abc")
        val good512 = HashingUtil.sha512Hex(f)

        // sha1 故意给错，但 sha512 正确 → 仍应通过（优先 sha512）
        assertTrue(DownloadIntegrity.matches(f, "deadbeef", good512))
        // sha512 错 → 不通过，即使 sha1 是对的
        assertFalse(DownloadIntegrity.matches(f, HashingUtil.sha1Hex(f), "deadbeef"))
        assertEquals("sha512", DownloadIntegrity.algorithm("deadbeef", good512))
    }

    @Test
    fun `只提供 sha1 时用 sha1，大小写不敏感`() {
        val f = tempFile("abc")
        val sha1 = HashingUtil.sha1Hex(f)

        assertTrue(DownloadIntegrity.matches(f, sha1, null))
        assertTrue(DownloadIntegrity.matches(f, sha1.uppercase(), null))
        assertFalse(DownloadIntegrity.matches(f, "0".repeat(40), null))
        assertEquals("sha1", DownloadIntegrity.algorithm(sha1, null))
    }

    @Test
    fun `没有哈希信息时不校验（fail-safe 保留下载）`() {
        val f = tempFile("abc")

        assertTrue(DownloadIntegrity.matches(f, null, null))
        assertNull(DownloadIntegrity.algorithm(null, null))
    }

    @Test
    fun `文件不存在视为不通过`() {
        assertFalse(DownloadIntegrity.matches(File("no-such-file-xyz.bin"), "a".repeat(40), null))
    }
}

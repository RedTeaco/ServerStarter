import atm.bloodworkxgaming.serverstarter.util.ZipPathSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防 ZipSlip：`ZipPathSafety.resolveInside` 只放行规范化后仍在安装目录内的条目。
 */
class ZipPathSafetyTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "zip-safety-base").apply { mkdirs() }

    @Test
    fun `目录内的普通路径放行`() {
        assertEquals(File(base, "config/foo.toml").canonicalPath, ZipPathSafety.resolveInside(base, "config/foo.toml")?.canonicalPath)
        assertEquals(File(base, "mods/x.jar").canonicalPath, ZipPathSafety.resolveInside(base, "mods/x.jar")?.canonicalPath)
        // 目录内绕一圈仍算目录内
        assertEquals(File(base, "b.txt").canonicalPath, ZipPathSafety.resolveInside(base, "a/../b.txt")?.canonicalPath)
        // 反斜杠写法（部分打包工具会产出）
        assertNotNull(ZipPathSafety.resolveInside(base, "mods\\x.jar"))
    }

    @Test
    fun `向上越界被拒绝`() {
        assertNull(ZipPathSafety.resolveInside(base, "../evil.jar"))
        assertNull(ZipPathSafety.resolveInside(base, "../../evil.jar"))
        assertNull(ZipPathSafety.resolveInside(base, "mods/../../evil.jar"))
        assertNull(ZipPathSafety.resolveInside(base, "a/b/../../../evil.jar"))
        assertNull(ZipPathSafety.resolveInside(base, "..\\evil.jar"))
    }

    @Test
    fun `绝对路径与盘符被拒绝`() {
        val absolute = if (File.separatorChar == '\\') "C:\\Windows\\Temp\\evil.jar" else "/tmp/evil.jar"
        assertNull(ZipPathSafety.resolveInside(base, absolute))
        assertNull(ZipPathSafety.resolveInside(base, File(base, "x").absolutePath))
    }

    @Test
    fun `空路径与安装目录本身被拒绝或规范化`() {
        assertNull(ZipPathSafety.resolveInside(base, ""))
        assertNull(ZipPathSafety.resolveInside(base, "   "))
        // "." 解析成 base 自身，仍在目录内 → 放行（但不会是文件，调用方按目录分支处理）
        val dot = ZipPathSafety.resolveInside(base, ".")
        if (dot != null) assertEquals(base.canonicalPath, dot.canonicalPath)
    }

    @Test
    fun `越界路径不会指向 base 之下`() {
        val escaped = ZipPathSafety.resolveInside(base, "../sibling")
        assertTrue(escaped == null)
    }
}

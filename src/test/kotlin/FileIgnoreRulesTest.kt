import atm.bloodworkxgaming.serverstarter.util.FileIgnoreRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileIgnoreRules] 测试：`install.ignoreFiles` 在下载阶段的匹配口径。
 *
 * 用例取自真实 mrpack（All the Mods 10）里 URL 写 `%2b`、`path` 写 `+` 的条目，
 * 这是「文件名规则从 ignoreProject 迁到 ignoreFiles」后必须保住的能力。
 */
class FileIgnoreRulesTest {

    @Test
    fun `只有 mods 前缀项参与下载阶段匹配`() {
        val matchers = FileIgnoreRules.downloadMatchers(listOf(
                "mods/sodium*.jar",
                "config/*-client.toml",
                "kubejs/client_scripts/**",
                "resources/**"
        ))

        assertEquals(1, matchers.size)
        assertTrue(FileIgnoreRules.firstMatch(matchers, setOf("sodium-neoforge-0.6.0.jar")) != null)
        // 非 mods/ 前缀项不参与下载阶段（它们只影响解压阶段）
        assertNull(FileIgnoreRules.firstMatch(matchers, setOf("some-client.toml")))
    }

    @Test
    fun `无前缀默认 glob，支持 glob 与 regex 前缀`() {
        val matchers = FileIgnoreRules.downloadMatchers(listOf(
                "mods/iris*.jar",
                "mods/glob:optifine*.jar",
                "mods/regex:.*-client\\.jar"
        ))

        assertTrue(FileIgnoreRules.firstMatch(matchers, setOf("iris-neoforge-1.8.0.jar")) != null)
        assertTrue(FileIgnoreRules.firstMatch(matchers, setOf("optifine-1.21.jar")) != null)
        assertTrue(FileIgnoreRules.firstMatch(matchers, setOf("somemod-client.jar")) != null)
        assertNull(FileIgnoreRules.firstMatch(matchers, setOf("sodium-neoforge-0.6.0.jar")))
    }

    @Test
    fun `URL 写百分号编码、规则写加号也能命中`() {
        val matchers = FileIgnoreRules.downloadMatchers(listOf("mods/CTM-1.21-1.2.1+3.jar"))

        // 真实条目：path = mods/CTM-1.21-1.2.1+3.jar，downloads[0] = .../CTM-1.21-1.2.1%2b3.jar
        val candidates = setOf("CTM-1.21-1.2.1%2b3.jar", "CTM-1.21-1.2.1+3.jar")

        assertEquals("CTM-1.21-1.2.1+3.jar", FileIgnoreRules.firstMatch(matchers, candidates))
    }

    @Test
    fun `反过来的写法同样可命中`() {
        val matchers = FileIgnoreRules.downloadMatchers(listOf("mods/CTM-1.21-1.2.1%2b3.jar"))

        val candidates = setOf("CTM-1.21-1.2.1%2b3.jar", "CTM-1.21-1.2.1+3.jar")

        assertEquals("CTM-1.21-1.2.1%2b3.jar", FileIgnoreRules.firstMatch(matchers, candidates))
    }

    @Test
    fun `空规则或空候选不匹配`() {
        assertNull(FileIgnoreRules.firstMatch(emptyList(), setOf("a.jar")))
        assertNull(FileIgnoreRules.firstMatch(FileIgnoreRules.downloadMatchers(listOf("mods/a*.jar")), emptySet()))
    }

    @Test
    fun `非法表达式只被忽略，不影响其它规则`() {
        val matchers = FileIgnoreRules.downloadMatchers(listOf(
                "mods/regex:[unclosed",
                "mods/sodium*.jar",
                "mods/"
        ))

        assertEquals(1, matchers.size)
        assertTrue(FileIgnoreRules.firstMatch(matchers, setOf("sodium-neoforge-0.6.0.jar")) != null)
    }
}

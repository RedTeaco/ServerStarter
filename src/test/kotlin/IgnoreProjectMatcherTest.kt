import atm.bloodworkxgaming.serverstarter.util.IgnoreProjectMatcher
import atm.bloodworkxgaming.serverstarter.util.ModFileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IgnoreProjectMatcher] 规则解析与匹配测试（全部离线，不触发网络）。 */
class IgnoreProjectMatcherTest {

    private fun identity(
            modrinth: String? = null,
            curseProject: String? = null,
            curseFile: String? = null,
            names: Set<String> = emptySet()
    ) = ModFileIdentity.Identity(modrinth, curseProject, curseFile, names)

    @Test
    fun `解析各类写法`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(
                263420,                       // yaml 未加引号的数字 → Int → CF 项目 ID
                "317780",                     // 字符串数字 → CF 项目 ID
                "AANobbMI",                   // 裸串 → Modrinth ID（或 slug）
                "sodium",                     // 裸串 → Modrinth slug
                "curseProject:394468",
                "cfFile:8837013",
                "curseFile:5433036",
                "modrinth:AANobbMI",
                "name:sodium*.jar",
                "glob:iris*.jar",
                "regex:.*-client\\.jar",
                "name:mods/optifine*.jar",    // 可带 mods/ 前缀
                "",                           // 空串忽略
                "   "                         // 空白忽略
        ))

        assertEquals(setOf("263420", "317780", "394468"), spec.curseProjectIds)
        assertEquals(setOf("8837013", "5433036"), spec.curseFileIds)
        assertEquals(setOf("AANobbMI", "sodium"), spec.modrinthIdOrSlug)
        assertEquals(4, spec.nameMatchers.size)
    }

    @Test
    fun `空配置视为空匹配器`() {
        assertTrue(IgnoreProjectMatcher.parseSpec(null).isEmpty)
        assertTrue(IgnoreProjectMatcher.parseSpec(emptyList()).isEmpty)
        assertTrue(IgnoreProjectMatcher.build(IgnoreProjectMatcher.parseSpec(null)).isEmpty)
    }

    @Test
    fun `Modrinth 字面量 ID 命中（无需联网解析）`() {
        val matcher = IgnoreProjectMatcher.build(IgnoreProjectMatcher.parseSpec(listOf<Any>("AANobbMI")))

        assertTrue(matcher.matches(identity(modrinth = "AANobbMI")))
        assertFalse(matcher.matches(identity(modrinth = "laX5CckD")))
    }

    @Test
    fun `slug 经 API 解析为规范 ID 后命中`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("sodium"))
        // 模拟 GET /v3/project/sodium → id=AANobbMI
        val matcher = IgnoreProjectMatcher.build(spec, resolvedModrinthIds = setOf("AANobbMI"))

        assertTrue(matcher.matches(identity(modrinth = "AANobbMI")))
        assertFalse(matcher.matches(identity(modrinth = "laX5CckD")))
    }

    @Test
    fun `CurseForge 项目 ID 经 fileId 反查命中`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(263420))
        // 模拟 POST /v1/mods/files → fileId 5433036 属于 modId 263420
        val matcher = IgnoreProjectMatcher.build(spec, curseProjectIdsByFileId = mapOf("5433036" to "263420"))

        assertTrue(matcher.matches(identity(curseFile = "5433036")))
        assertFalse(matcher.matches(identity(curseFile = "8837013")))
    }

    @Test
    fun `CurseForge 项目 ID 也可直接来自 API 风格链接`() {
        val matcher = IgnoreProjectMatcher.build(IgnoreProjectMatcher.parseSpec(listOf<Any>(394468)))

        assertTrue(matcher.matches(identity(curseProject = "394468")))
        assertFalse(matcher.matches(identity(curseProject = "999999")))
    }

    @Test
    fun `CurseForge 文件 ID 规则离线命中`() {
        val matcher = IgnoreProjectMatcher.build(IgnoreProjectMatcher.parseSpec(listOf<Any>("curseFile:8837013")))

        assertTrue(matcher.matches(identity(curseFile = "8837013")))
        assertFalse(matcher.matches(identity(curseFile = "8837014")))
    }

    @Test
    fun `文件名规则命中 URL 原名与百分号解码名`() {
        val matcher = IgnoreProjectMatcher.build(IgnoreProjectMatcher.parseSpec(listOf<Any>(
                "name:CTM-1.21-1.2.1+3.jar",
                "glob:iris*.jar",
                "regex:.*-client\\.jar"
        )))

        assertTrue(matcher.matches(identity(names = setOf("CTM-1.21-1.2.1%2b3.jar", "CTM-1.21-1.2.1+3.jar"))))
        assertTrue(matcher.matches(identity(names = setOf("iris-neoforge-1.8.0.jar"))))
        assertTrue(matcher.matches(identity(names = setOf("somemod-client.jar"))))
        assertFalse(matcher.matches(identity(names = setOf("sodium-neoforge-0.6.0.jar"))))
    }

    @Test
    fun `非法正则不会导致解析失败，只是被忽略`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("regex:[unclosed"))

        assertEquals(0, spec.nameMatchers.size)
        assertTrue(spec.isEmpty)
    }

    @Test
    fun `不支持的类型只被忽略`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(listOf("nested"), true))

        assertTrue(spec.isEmpty)
    }
}

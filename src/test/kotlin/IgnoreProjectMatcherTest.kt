import atm.bloodworkxgaming.serverstarter.util.IgnoreProjectMatcher
import atm.bloodworkxgaming.serverstarter.util.ModFileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IgnoreProjectMatcher] 规则解析与匹配测试（全部离线，不触发网络）。
 *
 * 注意：`ignoreProject` **只负责平台身份**，文件名规则已迁移到 `install.ignoreFiles`
 * （覆盖见 [FileIgnoreRulesTest]）。
 */
class IgnoreProjectMatcherTest {

    private fun identity(
            modrinth: String? = null,
            curseProject: String? = null,
            curseFile: String? = null,
            names: Set<String> = emptySet()
    ) = ModFileIdentity.Identity(modrinth, curseProject, curseFile, names)

    @Test
    fun `解析各类身份写法`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(
                263420,                       // yaml 未加引号的数字 → Int → CF 项目 ID
                "317780",                     // 字符串数字 → CF 项目 ID
                "AANobbMI",                   // 裸串 → Modrinth ID（或 slug）
                "sodium",                     // 裸串 → Modrinth slug
                "curseProject:394468",
                "cfFile:8837013",
                "curseFile:5433036",
                "modrinth:AANobbMI",
                "mr:laX5CckD",
                "",                           // 空串忽略
                "   "                         // 空白忽略
        ))

        assertEquals(setOf("263420", "317780", "394468"), spec.curseProjectIds)
        assertEquals(setOf("8837013", "5433036"), spec.curseFileIds)
        assertEquals(setOf("AANobbMI", "sodium", "laX5CckD"), spec.modrinthIdOrSlug)
    }

    @Test
    fun `文件名规则被拒绝（已迁移到 ignoreFiles）`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(
                "name:sodium*.jar",
                "filename:iris*.jar",
                "glob:optifine*.jar",
                "regex:.*-client\\.jar"
        ))

        // 既不匹配、也不报错，只是整体为空（日志里会提示迁移到 install.ignoreFiles）
        assertTrue(spec.isEmpty)
        assertTrue(IgnoreProjectMatcher.build(spec).isEmpty)
    }

    @Test
    fun `文件名规则与身份规则混写时身份部分照常生效`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("name:sodium*.jar", "AANobbMI"))
        val matcher = IgnoreProjectMatcher.build(spec)

        assertEquals(setOf("AANobbMI"), spec.modrinthIdOrSlug)
        assertTrue(matcher.matches(identity(modrinth = "AANobbMI")))
        // 文件名不再由 ignoreProject 命中：即便文件名完全一致也不匹配
        assertFalse(matcher.matches(identity(names = setOf("sodium-neoforge-0.6.0.jar"))))
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
    fun `不支持的类型只被忽略`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(listOf("nested"), true))

        assertTrue(spec.isEmpty)
    }
}

import atm.bloodworkxgaming.serverstarter.util.IgnoreProjectMatcher
import atm.bloodworkxgaming.serverstarter.util.ModFileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IgnoreProjectMatcher] 测试（全部离线，不触发网络）。
 *
 * `ignoreProject` **只认两个平台的规范项目 ID**：
 * - Modrinth 项目 ID（8 位 base62）按字面量比对，不做联网解析，因此 slug 不生效；
 * - CurseForge 项目 ID（纯数字）在 mrpack 上需要把包内文件 ID 反查成项目 ID（由调用方注入）；
 * - 文件名归 `install.ignoreFiles`（覆盖见 [FileIgnoreRulesTest]），文件 ID 也不再接受。
 */
class IgnoreProjectMatcherTest {

    private fun identity(
            modrinth: String? = null,
            curseProject: String? = null,
            curseFile: String? = null,
            names: Set<String> = emptySet()
    ) = ModFileIdentity.Identity(modrinth, curseProject, curseFile, names)

    @Test
    fun `解析两个平台的项目 ID`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(
                263420,                       // yaml 未加引号的数字 → Int → CurseForge 项目 ID
                "317780",                     // 字符串数字 → CurseForge 项目 ID
                "AANobbMI",                   // 8 位 base62 → Modrinth 项目 ID
                "laX5CckD",                   // 大小写敏感，原样保留
                "curseProject:394468",
                "cfProject:394469",
                "modrinth:P7dR8mSH",
                "mr:9s6osm5g",
                "",                           // 空串忽略
                "   "                         // 空白忽略
        ))

        assertEquals(setOf("263420", "317780", "394468", "394469"), spec.curseForgeProjectIds)
        assertEquals(setOf("AANobbMI", "laX5CckD", "P7dR8mSH", "9s6osm5g"), spec.modrinthProjectIds)
    }

    @Test
    fun `slug 不被解析，无法命中（只按字面量比对）`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("sodium"))
        val matcher = IgnoreProjectMatcher(spec)

        // 只 warn，仍按字面量收录（绝不静默丢弃配置项）
        assertEquals(setOf("sodium"), spec.modrinthProjectIds)
        // 但真实身份是 8 位项目 ID，所以不会命中
        assertFalse(matcher.matches(identity(modrinth = "AANobbMI")))
    }

    @Test
    fun `文件名规则被拒绝（已迁移到 ignoreFiles）`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(
                "name:sodium*.jar",
                "filename:iris*.jar",
                "glob:optifine*.jar",
                "regex:.*-client\\.jar"
        ))

        assertTrue(spec.isEmpty)
        assertTrue(IgnoreProjectMatcher(spec).isEmpty)
    }

    @Test
    fun `CurseForge 文件 ID 被拒绝（标识文件而非项目）`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("curseFile:8837013", "cfFile:5433036"))

        assertTrue(spec.isEmpty)
    }

    @Test
    fun `拒绝的写法与合法 ID 混写时合法项照常生效`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>("name:sodium*.jar", "curseFile:8837013", "AANobbMI"))
        val matcher = IgnoreProjectMatcher(spec)

        assertEquals(setOf("AANobbMI"), spec.modrinthProjectIds)
        assertTrue(matcher.matches(identity(modrinth = "AANobbMI")))
        // 文件名/文件 ID 都不再由 ignoreProject 命中
        assertFalse(matcher.matches(identity(curseFile = "8837013", names = setOf("sodium-neoforge-0.6.0.jar"))))
    }

    @Test
    fun `空配置视为空匹配器`() {
        assertTrue(IgnoreProjectMatcher.parseSpec(null).isEmpty)
        assertTrue(IgnoreProjectMatcher.parseSpec(emptyList()).isEmpty)
        assertTrue(IgnoreProjectMatcher(IgnoreProjectMatcher.parseSpec(null)).isEmpty)
    }

    @Test
    fun `Modrinth 项目 ID 字面量命中（不联网）`() {
        val matcher = IgnoreProjectMatcher(IgnoreProjectMatcher.parseSpec(listOf<Any>("AANobbMI")))

        assertTrue(matcher.matches(identity(modrinth = "AANobbMI")))
        assertFalse(matcher.matches(identity(modrinth = "laX5CckD")))
    }

    @Test
    fun `CurseForge 项目 ID 经 fileId 反查命中`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(263420))
        // 调用方注入的运行时产物：文件 5433036 属于项目 263420
        val matcher = IgnoreProjectMatcher(spec, mapOf("5433036" to "263420"))

        assertTrue(matcher.matches(identity(curseFile = "5433036")))
        assertFalse(matcher.matches(identity(curseFile = "8837013")))
    }

    @Test
    fun `CurseForge 项目 ID 也可直接来自 API 风格链接`() {
        val matcher = IgnoreProjectMatcher(IgnoreProjectMatcher.parseSpec(listOf<Any>(394468)))

        assertTrue(matcher.matches(identity(curseProject = "394468")))
        assertFalse(matcher.matches(identity(curseProject = "999999")))
    }

    @Test
    fun `未配置 CurseForge 项目 ID 时不做 fileId 比对`() {
        val matcher = IgnoreProjectMatcher(IgnoreProjectMatcher.parseSpec(listOf<Any>("AANobbMI")), mapOf("8837013" to "263420"))

        assertFalse(matcher.matches(identity(curseFile = "8837013")))
    }

    @Test
    fun `不支持的类型只被忽略`() {
        val spec = IgnoreProjectMatcher.parseSpec(listOf<Any>(listOf("nested"), true))

        assertTrue(spec.isEmpty)
    }
}

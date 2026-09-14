import atm.bloodworkxgaming.serverstarter.util.CurseModrinthPreScan
import atm.bloodworkxgaming.serverstarter.util.CurseModrinthPreScan.ModInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * CurseForge 缺省模组 → Modrinth 身份预扫描测试（全部离线：网络访问用假响应注入）。
 *
 * 覆盖：HIGH 哈希路径的 client_only 判定、非 client_only 保留、未命中回落名称路径、
 * 查询失败 fail-safe，以及 sha1 / modId 两级去重。
 */
class CurseModrinthPreScanTest {

    private fun versionFile(
            projectId: String,
            environment: String?,
            gameVersions: String = """["1.21.1"]""",
            loaders: String = """["neoforge"]"""
    ): String {
        val env = if (environment == null) "null" else "\"$environment\""
        return """{"project_id":"$projectId","name":"example-1.0.jar","game_versions":$gameVersions,"loaders":$loaders,"environment":$env}"""
    }

    private fun projectInfo(name: String, authors: List<String>): String {
        val authorJson = authors.joinToString(",") { """{"name":"$it"}""" }
        return """{"data":{"name":"$name","authors":[$authorJson]}}"""
    }

    private fun searchHit(name: String, projectId: String, environment: String?, author: String): String {
        val env = if (environment == null) "null" else "\"$environment\""
        return """{"hits":[{"project_id":"$projectId","name":"$name","slug":"${name.lowercase()}","author":"$author","environment":$env,"loaders":["neoforge"]}]}"""
    }

    @Test
    fun `哈希命中 client_only 的模组被跳过`() {
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(ModInput("client-mod.jar", 111, "a".repeat(40))),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { versionFile("AANobbMI", "client_only") },
                getCurseForge = { error("名称路径不该被调用") }
        )

        assertEquals(setOf("client-mod.jar"), skip)
    }

    @Test
    fun `哈希命中但非 client_only 的模组保留`() {
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(ModInput("server-mod.jar", 222, "b".repeat(40))),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { versionFile("laX5CckD", "server_only") },
                getCurseForge = { error("名称路径不该被调用") }
        )

        assertTrue(skip.isEmpty())
    }

    @Test
    fun `哈希反查的 MC 版本或 loader 不匹配时视为未命中，回落名称路径且不处置`() {
        val curseForgeCalls = AtomicInteger()
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(ModInput("legacy-mod.jar", 333, "c".repeat(40))),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                // 哈希反查返回的是别的 MC 版本 → lookupByHash 视为未命中
                getModrinth = { url ->
                    if (url.contains("/version_file/")) versionFile("AANobbMI", "client_only", gameVersions = """["1.20.1"]""")
                    else searchHit("Example Mod", "P7dR8mSH", "client_only", "someone")
                },
                getCurseForge = { curseForgeCalls.incrementAndGet(); projectInfo("Example Mod", listOf("someone")) }
        )

        // 名称路径只做提示，绝不据此删除模组
        assertTrue(skip.isEmpty())
        assertEquals(1, curseForgeCalls.get())
    }

    @Test
    fun `查询失败时 warn 并保留（fail-safe）`() {
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(ModInput("flaky.jar", 444, "d".repeat(40))),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { throw IOException("boom") },
                getCurseForge = { throw IOException("cf boom") }
        )

        assertTrue(skip.isEmpty())
    }

    @Test
    fun `同一 sha1 只反查一次`() {
        val hashCalls = AtomicInteger()
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(
                        ModInput("a.jar", 1, "e".repeat(40)),
                        ModInput("b.jar", 2, "e".repeat(40))
                ),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { hashCalls.incrementAndGet(); versionFile("AANobbMI", "both") },
                getCurseForge = { error("不该调用") }
        )

        assertTrue(skip.isEmpty())
        assertEquals(1, hashCalls.get())
    }

    @Test
    fun `同一 modId 的项目信息与名称搜索各只查一次`() {
        val cfCalls = AtomicInteger()
        val searchCalls = AtomicInteger()
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(
                        ModInput("x.jar", 555, "1".repeat(40)),
                        ModInput("y.jar", 555, "2".repeat(40))
                ),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { url ->
                    if (url.contains("/version_file/")) """{}"""      // 无 project_id → 视为未命中
                    else { searchCalls.incrementAndGet(); searchHit("Example Mod", "P7dR8mSH", "both", "someone") }
                },
                getCurseForge = { cfCalls.incrementAndGet(); projectInfo("Example Mod", listOf("someone")) }
        )

        assertTrue(skip.isEmpty())
        assertEquals(1, cfCalls.get())
        assertEquals(1, searchCalls.get())
    }

    @Test
    fun `没有 sha1 的模组直接走名称路径`() {
        val skip = CurseModrinthPreScan.scan(
                mods = listOf(ModInput("no-hash.jar", 666, null)),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { searchHit("Example Mod", "P7dR8mSH", "client_only", "someone") },
                getCurseForge = { projectInfo("Example Mod", listOf("someone")) }
        )

        assertTrue(skip.isEmpty())
    }

    @Test
    fun `空输入直接返回空集合`() {
        val skip = CurseModrinthPreScan.scan(
                mods = emptyList(),
                mcVersion = "1.21.1",
                loaderName = "neoforge",
                getModrinth = { error("不该调用") },
                getCurseForge = { error("不该调用") }
        )

        assertTrue(skip.isEmpty())
    }
}

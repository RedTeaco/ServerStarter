import atm.bloodworkxgaming.serverstarter.util.ModFileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModFileIdentity] 纯函数测试：覆盖真实 mrpack 里出现的链接形态
 * （用例取自工作区的 modrinth.index.json / AlltheMods10 增强版）。
 */
class ModFileIdentityTest {

    @Test
    fun `CF 链接在前、Modrinth 链接在后时仍能取到 projectId`() {
        val urls = listOf(
                "https://mediafilez.forgecdn.net/files/5433/36/accelerated-decay-neoforge-21.0.0.jar",
                "https://edge.forgecdn.net/files/5433/36/accelerated-decay-neoforge-21.0.0.jar",
                "https://cdn.modrinth.com/data/laX5CckD/versions/rtgQ5T5Q/accelerated-decay-neoforge-21.0.0.jar"
        )

        val identity = ModFileIdentity.fromEntry(urls, "mods/accelerated-decay-neoforge-21.0.0.jar")

        assertEquals("laX5CckD", identity.modrinthProjectId)
        // 5433 * 1000 + 36
        assertEquals("5433036", identity.curseFileId)
        assertNull(identity.curseProjectId)
        assertTrue(identity.fileNames.contains("accelerated-decay-neoforge-21.0.0.jar"))
    }

    @Test
    fun `Modrinth projectId 大小写敏感，不做 lowercase`() {
        assertEquals("AANobbMI", ModFileIdentity.modrinthProjectId("https://cdn.modrinth.com/data/AANobbMI/versions/x/y.jar"))
        // 子域兼容
        assertEquals("laX5CckD", ModFileIdentity.modrinthProjectId("https://cdn-raw.modrinth.com/data/laX5CckD/versions/a/b.jar"))
        // 非 Modrinth 链接
        assertNull(ModFileIdentity.modrinthProjectId("https://edge.forgecdn.net/files/5433/36/x.jar"))
        // 形似但不是 /data/ 段
        assertNull(ModFileIdentity.modrinthProjectId("https://cdn.modrinth.com/other/laX5CckD/x.jar"))
    }

    @Test
    fun `纯 CF 条目：能拿到文件 ID，拿不到 Modrinth 身份`() {
        val urls = listOf(
                "https://mediafilez.forgecdn.net/files/8837/13/Ad-Astra-Giselle-Addon-neoforge-1.21.1-8.4.jar",
                "https://edge.forgecdn.net/files/8837/13/Ad-Astra-Giselle-Addon-neoforge-1.21.1-8.4.jar"
        )

        val identity = ModFileIdentity.fromEntry(urls, "mods/Ad-Astra-Giselle-Addon-neoforge-1.21.1-8.4.jar")

        assertNull(identity.modrinthProjectId)
        assertEquals("8837013", identity.curseFileId)
    }

    @Test
    fun `CurseForge 只解析直链：API 直链给出项目 ID，项目页地址不解析`() {
        // 项目页地址不是下载直链、也不是 API：不从中猜身份（要排除这类条目请用 install.ignoreFiles 的文件名规则）
        assertNull(ModFileIdentity.curseFileId("https://www.curseforge.com/minecraft/mc-mods/sodium/download/1234567"))
        assertNull(ModFileIdentity.modrinthProjectId("https://www.curseforge.com/minecraft/mc-mods/sodium/download/1234567"))

        val apiUrls = listOf("https://api.curseforge.com/v1/mods/394468/files/1234567/download")
        val identity = ModFileIdentity.fromEntry(apiUrls, "mods/sodium.jar")
        assertEquals("394468", identity.curseProjectId)
        assertEquals("1234567", identity.curseFileId)
    }

    @Test
    fun `候选文件名同时包含 URL 原名、百分号解码名与 manifest path 名`() {
        val urls = listOf("https://edge.forgecdn.net/files/5587/515/CTM-1.21-1.2.1%2b3.jar")

        val identity = ModFileIdentity.fromEntry(urls, "mods/CTM-1.21-1.2.1+3.jar")

        assertTrue(identity.fileNames.contains("CTM-1.21-1.2.1%2b3.jar"))
        assertTrue(identity.fileNames.contains("CTM-1.21-1.2.1+3.jar"))
    }

    @Test
    fun `无身份时 hasAnyId 为 false 且 describe 给出 none`() {
        val identity = ModFileIdentity.fromEntry(listOf("https://example.invalid/files/x.jar"), "mods/x.jar")

        assertEquals(false, identity.hasAnyId)
        assertEquals("none", identity.describe())
    }
}

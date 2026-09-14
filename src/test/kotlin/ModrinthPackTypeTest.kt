import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.modrinth.ModrinthPackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归测试：mrpack 的 `downloads[]` 为「CF 在前、Modrinth 在后」时，
 * projectId 必须能从**整个数组**中取到（历史实现只检查 `downloads[0]`）。
 */
class ModrinthPackTypeTest {

    private fun packType(): ModrinthPackType {
        val config = ConfigFile()
        return ModrinthPackType(config, InternetManager(config))
    }

    @Test
    fun `单链接仍按历史语义解析`() {
        val packType = packType()

        assertEquals("laX5CckD", packType.extractProjectId("https://cdn.modrinth.com/data/laX5CckD/versions/rtgQ5T5Q/x.jar"))
        assertNull(packType.extractProjectId("https://mediafilez.forgecdn.net/files/5433/36/x.jar"))
        assertNull(packType.extractProjectId("https://edge.forgecdn.net/files/5433/36/x.jar"))
    }

    @Test
    fun `CF 链接在前时从数组后续链接取到 projectId`() {
        val packType = packType()

        val downloads = listOf(
                "https://mediafilez.forgecdn.net/files/5433/36/accelerated-decay-neoforge-21.0.0.jar",
                "https://edge.forgecdn.net/files/5433/36/accelerated-decay-neoforge-21.0.0.jar",
                "https://cdn.modrinth.com/data/laX5CckD/versions/rtgQ5T5Q/accelerated-decay-neoforge-21.0.0.jar"
        )

        assertEquals("laX5CckD", packType.extractProjectId(downloads))
    }

    @Test
    fun `纯 CF 数组取不到 projectId（交由 CF 侧规则处理）`() {
        val packType = packType()

        val downloads = listOf(
                "https://mediafilez.forgecdn.net/files/8837/13/Ad-Astra-Giselle-Addon-neoforge-1.21.1-8.4.jar",
                "https://edge.forgecdn.net/files/8837/13/Ad-Astra-Giselle-Addon-neoforge-1.21.1-8.4.jar"
        )

        assertNull(packType.extractProjectId(downloads))
    }
}

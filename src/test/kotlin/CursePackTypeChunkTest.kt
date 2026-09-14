import atm.bloodworkxgaming.serverstarter.packtype.curse.CursePackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CurseForge `POST /v1/mods/files` 的请求分块（`CursePackType.chunkFileIds`）测试。
 *
 * 背景：以前把所有 fileId 一次性塞进一个请求体，几百个模组的包有被 CF 拒绝的风险。
 */
class CursePackTypeChunkTest {

    @Test
    fun `去重后按上限分块`() {
        val ids = (1..1200).map { "id$it" } + listOf("id1", "id2")   // 1202 个，去重后 1200

        val chunks = CursePackType.chunkFileIds(ids, chunkSize = 500)

        assertEquals(3, chunks.size)
        assertEquals(listOf(500, 500, 200), chunks.map { it.size })
        assertEquals(1200, chunks.sumOf { it.size })
        // 去重且不丢项
        assertEquals(1200, chunks.flatten().toSet().size)
    }

    @Test
    fun `空输入与不足一块的输入`() {
        assertTrue(CursePackType.chunkFileIds(emptyList()).isEmpty())
        assertEquals(1, CursePackType.chunkFileIds(listOf("a", "b")).size)
        assertEquals(listOf(listOf("a", "b")), CursePackType.chunkFileIds(listOf("a", "b", "a")))
    }

    @Test
    fun `保持首次出现的顺序`() {
        val chunks = CursePackType.chunkFileIds(listOf("c", "a", "b") + listOf("a"), chunkSize = 2)

        assertEquals(listOf(listOf("c", "a"), listOf("b")), chunks)
    }
}

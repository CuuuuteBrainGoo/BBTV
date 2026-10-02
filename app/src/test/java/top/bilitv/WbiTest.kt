package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Test
import top.bilitv.data.auth.Wbi

/**
 * 用例取自 bilibili-API-collect 官方文档示例值。
 * 任何一个字符的编码差异都会让这里失败，是签名正确性的唯一防线。
 */
class WbiTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun mixinKeyMatchesDoc() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", Wbi.mixinKey(imgKey, subKey))
    }

    @Test
    fun signMatchesDoc() {
        val params = mapOf("foo" to "114", "bar" to "514", "zab" to "1919810")
        val signed = Wbi.sign(params, imgKey, subKey, wts = 1702204169L)
        assertEquals("1702204169", signed["wts"])
        assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4", signed["w_rid"])
    }

    @Test
    fun keyFromUrlStripsPathAndExt() {
        assertEquals(
            imgKey,
            Wbi.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png")
        )
    }
}

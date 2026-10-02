package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.*
import top.bilitv.ui.player.OkHold
import top.bilitv.ui.player.PlayerBarButton

class VideoInteractionTest {
    @Test fun `互动协议缺状态不能消费账号 原创转载不超投`() {
        assertEquals(VideoRelation(true, 1, false), parseVideoRelation("""{"code":0,"data":{"like":true,"coin":1,"favorite":false}}"""))
        assertEquals(VideoRelation(false, 2, true), parseVideoRelation("""{"code":0,"data":{"like":0,"coin":2,"favorite":1}}"""))
        listOf("{}", "null", "", """{"code":-101}""", """{"code":0.5}""", """{"code":"0"}""").forEach {
            assertTrue("不完整响应被误当成功: $it", runCatching { requireActionResponse(it) }.isFailure)
        }
        listOf("""{"like":false,"coin":0}""", """{"like":false,"coin":3,"favorite":true}""",
            """{"like":0.5,"coin":1,"favorite":false}""", """{"like":true,"coin":1.5,"favorite":false}""").forEach {
            assertTrue(runCatching { parseVideoRelation("""{"code":0,"data":$it}""") }.isFailure)
        }
        assertEquals(listOf(2,1,0), (0..2).map { coinsToAdd(it, 1) })
        assertEquals(listOf(1,0,0), (0..2).map { coinsToAdd(it, 2) })
        assertTrue(runCatching { coinsToAdd(0, 0) }.isFailure)
        assertTrue(runCatching { coinsToAdd(-1, 1) }.isFailure)
    }

    @Test fun `OK长按精确边界 重复DOWN 松手和焦点取消不补发`() {
        val hold = OkHold()
        assertTrue(hold.down(100))
        assertFalse(hold.down(500))
        assertFalse(hold.longPress(1599))
        assertTrue(hold.longPress(1600))
        assertFalse(hold.longPress(3000))
        assertFalse(hold.down(3001))
        assertEquals(0, hold.up(3100))
        assertEquals(0, hold.up(3200))
        hold.down(4000)
        assertEquals(1, hold.up(5499))
        hold.down(6000)
        assertEquals(2, hold.up(7500)) // 主线程定时回调稍迟，松手仍只发一次三连。
        hold.down(8000)
        hold.cancel()
        assertFalse(hold.longPress(10000))
        assertEquals(0, hold.up(10001))
    }

    @Test fun `旧控制栏迁移保留排序 新隐藏项不会每次解析补回`() {
        val migrated = PlayerBarButton.migrateLegacy(listOf("log","speed","play","aspect","danmaku","back"))
        assertEquals(listOf("speed","play","danmaku","quality","like","coin","favorite","up","line"), migrated)
        assertEquals(listOf(PlayerBarButton.SPEED, PlayerBarButton.PLAY), PlayerBarButton.parse(listOf("speed", "play")))
        listOf("back","log","aspect","danmaku_panel").forEach { assertNull(PlayerBarButton.byId(it)) }
    }
}

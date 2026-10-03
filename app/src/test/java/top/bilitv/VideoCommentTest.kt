package top.bilitv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.*

class VideoCommentTest {
    @Test fun `opaque cursor pinned dedup and basic comments ignore nested replies`() {
        val item = """{"rpid":9001,"member":{"uname":"作者"},"content":{"message":"正文[doge]"},"rcount":3,"replies":[{"rpid":9002,"content":{"message":"回复"},"replies":[{"rpid":9003}]}]}"""
        val page = parseCommentPage("""{"code":0,"data":{"top_replies":[$item],"replies":[$item,{"rpid":9004,"invisible":true}],"cursor":{"all_count":9,"is_end":false,"pagination_reply":{"next_offset":"CAEiAggC+=="}}}}""")
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().pinned)
        assertEquals("正文[doge]", page.items.single().message)
        assertEquals(3, page.items.single().replyCount)
        assertEquals(9001L, page.items.single().id)
        assertEquals("CAEiAggC+==", JSONObject(commentPagination(page.nextOffset!!)).getString("offset"))
        assertTrue(page.hasMore)
        val end = parseCommentPage("""{"code":0,"data":{"replies":null,"cursor":{"is_end":true,"all_count":0}}}""")
        assertFalse(end.hasMore)
    }

    @Test fun `business failure and incomplete cursor are not an empty comment section`() {
        listOf("{}", """{"code":12002}""", """{"code":-352}""", """{"code":"0","data":{}}""",
            """{"code":0,"data":{}}""", """{"code":0,"data":{"cursor":{"is_end":false}}}""",
            """{"code":0,"data":{"cursor":{"is_end":"false"}}}""").forEach {
            assertTrue(it, runCatching { parseCommentPage(it) }.isFailure)
        }

    }
}

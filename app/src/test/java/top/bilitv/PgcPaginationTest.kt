package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.parsePgcIndexPage

class PgcPaginationTest {
    @Test fun `real index response pagination survives filtered and duplicate cards`() {
        // Captured index shape: data contains has_next, list, num, size and total.
        val first = parsePgcIndexPage("""{"code":0,"data":{"has_next":1,"num":2,"size":30,"total":6761,
            "list":[{"title":"missing identifier"},{"season_id":4},{"season_id":4}]}}""", 2, 30)
        assertTrue(first.hasMore)
        assertEquals(listOf(4L, 4L), first.items.map { it.seasonId })
        assertTrue(parsePgcIndexPage("""{"code":0,"data":{"has_next":"true","list":[{}]}}""", 1, 30).hasMore)
        val last = parsePgcIndexPage("""{"code":0,"data":{"has_next":0,"total":9999,"list":[{"season_id":5}]}}""", 2, 30)
        assertFalse(last.hasMore) // Server flag, rather than count, decides the boundary.
        assertEquals(5L, last.items.single().seasonId)
        assertFalse(parsePgcIndexPage("""{"code":0,"data":{"has_next":false,"list":null}}""", 1, 30).hasMore)
    }

    @Test fun `legacy count uses raw slots and malformed response is retryable failure`() {
        assertTrue(parsePgcIndexPage("""{"code":0,"data":{"list":[{}],"total":"31"}}""", 1, 30).hasMore)
        assertFalse(parsePgcIndexPage("""{"code":0,"data":{"list":[{}],"total":31}}""", 2, 30).hasMore)
        assertTrue(parsePgcIndexPage("""{"code":0,"data":{"list":[{},{}]}}""", 1, 2).hasMore)
        assertFalse(parsePgcIndexPage("""{"code":0,"data":{"list":[]}}""", 1, 2).hasMore)
        assertFalse(parsePgcIndexPage("""{"code":0,"data":{"list":[],"total":1}}""", Int.MAX_VALUE, Int.MAX_VALUE).hasMore)
        for (raw in listOf(
            """{"code":-101,"data":{"list":[]}}""", """{"code":0,"data":{}}""",
            """{"code":0,"data":{"has_next":7,"list":[]}}""",
            """{"code":0,"data":{"list":{},"has_next":false}}""",
            """{"code":0,"data":{"list":[],"total":-1}}"""
        )) assertThrows(Exception::class.java) { parsePgcIndexPage(raw, 1, 30) }
    }
}

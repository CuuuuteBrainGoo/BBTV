package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.parseSearchVideoPage

class SearchPaginationTest {
    @Test fun `server page boundary survives invalid types missing BV duplicates and empty intermediate pages`() {
        val raw = """[{"type":"bili_user"},{"type":"video"},{"type":"video","bvid":"BV1"},{"type":"video","bvid":"BV1"}]"""
        val first = parseSearchVideoPage("""{"code":0,"data":{"page":1,"pagesize":20,"numPages":3,"numResults":45,"result":$raw}}""", 1)
        assertTrue(first.hasMore); assertEquals(listOf("BV1", "BV1"), first.items.map { it.bvid })
        assertTrue(parseSearchVideoPage("""{"code":0,"data":{"page":2,"numPages":3,"result":[]}}""", 2).hasMore)
        assertFalse(parseSearchVideoPage("""{"code":0,"data":{"page":3,"numPages":3,"result":$raw}}""", 3).hasMore)
        assertFalse(parseSearchVideoPage("""{"code":0,"data":{"numPages":0,"numResults":0,"result":null}}""", 1).hasMore)
    }

    @Test fun `legacy totals and raw slots work but failed or mismatched pages never look like an end`() {
        assertTrue(parseSearchVideoPage("""{"code":0,"data":{"numResults":21,"result":[]}}""", 1).hasMore)
        assertFalse(parseSearchVideoPage("""{"code":0,"data":{"numResults":21,"result":[]}}""", 2).hasMore)
        val filtered = List(20) { """{"type":"bili_user"}""" }.joinToString(",")
        val full = parseSearchVideoPage("""{"code":0,"data":{"result":[$filtered]}}""", 1)
        assertTrue(full.items.isEmpty()); assertTrue(full.hasMore)
        assertFalse(parseSearchVideoPage("""{"code":0,"data":{"result":[]}}""", 1).hasMore)
        for (raw in listOf(
            """{"code":-412,"data":{"result":[]}}""",
            """{"code":0,"data":{"page":1,"result":[]}}""",
            """{"code":0,"data":{"numPages":-1,"result":[]}}""",
            """{"code":0,"data":{"pagesize":0,"result":[]}}""",
            """{"code":0,"data":{"numPages":"bad","result":[]}}""",
            """{"code":0,"data":{"result":null}}""",
            """{"code":0,"data":{"result":{}}}""",
            """{"code":0,"data":{}}"""
        )) {
            try { parseSearchVideoPage(raw, 2); fail("invalid response accepted: $raw") }
            catch (_: IllegalStateException) { }
            catch (_: java.io.IOException) { }
        }
    }
}

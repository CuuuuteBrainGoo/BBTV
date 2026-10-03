package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.bilitv.data.api.requireFeedSuccess

class FeedFailureTest {
    @Test fun `only successful empty responses can mark a page as exhausted`() {
        val empty = "{\"code\":0,\"data\":{\"list\":[]}}"
        assertEquals(empty, requireFeedSuccess(empty))
        for (raw in listOf("{\"code\":-101}", "{\"code\":-352}", "{\"data\":null}", "{\"code\":0,\"data\":null}")) {
            assertThrows(java.io.IOException::class.java) { requireFeedSuccess(raw) }
        }
        assertThrows(org.json.JSONException::class.java) { requireFeedSuccess("not JSON") }
    }
}

package top.bilitv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.FavResourcePage
import top.bilitv.data.model.FeedItem
import top.bilitv.player.PlaylistQueue

class PlaylistQueueTest {
    private fun video(id: String) = FeedItem(bvid = id, title = id, cover = "", ownerName = "", durationSec = 1, viewCount = 0)

    @Test fun `real sequence crosses filtered pages with bounded retry and never wraps`() = runBlocking {
        val queue = PlaylistQueue()
        queue.record(7, 1, FavResourcePage(listOf(video("a"), video("b")), true))
        assertEquals("b", queue.next("a") { error("Cached next must not fetch") }?.bvid)
        assertNull(queue.next("unknown") { error("Unknown current must not start from page one") })
        val requests = mutableListOf<Int>()
        assertEquals("c", queue.next("b") { page ->
            requests += page
            if (page == 2) FavResourcePage(emptyList(), true)
            else FavResourcePage(listOf(video("b"), video("c")), false)
        }?.bvid)
        assertEquals(listOf(2, 3), requests)
        assertEquals("b", queue.adjacent("c", -1)?.bvid)
        assertNull(queue.next("c") { error("Terminal page must not fetch") })
        queue.record(8, 1, FavResourcePage(listOf(video("x")), true))
        assertTrue(runCatching { queue.next("x") { error("offline") } }.isFailure)
        assertEquals(1, queue.page)
        requests.clear()
        assertNull(queue.next("x") { page -> requests += page; FavResourcePage(emptyList(), true) })
        assertEquals(listOf(2, 3, 4), requests)
        assertTrue(queue.hasMore)
        queue.record(9, 1, FavResourcePage((0..512).map { video("v$it") }, false))
        assertTrue(queue.truncated)
        assertFalse(queue.contains("v0"))
        assertEquals("v511", queue.adjacent("v512", -1)?.bvid)
        Unit
    }

    @Test fun `cancelled delayed response cannot advance queue`() = runBlocking {
        val queue = PlaylistQueue()
        queue.record(7, 1, FavResourcePage(listOf(video("a")), true))
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<FavResourcePage>()
        val job = launch { queue.next("a") {
            entered.complete(Unit)
            try { response.await() } catch (_: CancellationException) { FavResourcePage(listOf(video("b")), false) }
        } }
        entered.await(); job.cancelAndJoin()
        response.complete(FavResourcePage(listOf(video("b")), false))
        assertEquals(1, queue.page)
        assertTrue(queue.hasMore)
        assertNull(queue.adjacent("a", 1))
    }
}

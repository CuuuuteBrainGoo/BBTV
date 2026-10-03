package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.PgcEpisode
import top.bilitv.data.model.VideoPage
import top.bilitv.player.NextEpisode

class AdjacentPlaybackTest {
    @Test fun `previous uses real identity order and never wraps or picks an unknown current`() {
        val pages = listOf(VideoPage(100, 1, "one", 10), VideoPage(200, 8, "two", 20))
        assertEquals(100L, NextEpisode.nextPage(pages, 200, -1)?.cid)
        assertNull(NextEpisode.nextPage(pages, 100, -1))
        assertNull(NextEpisode.nextPage(pages, 999, -1))
        val episodes = listOf(PgcEpisode(1, 77, "1", "", "", 20), PgcEpisode(2, 77, "2", "", "", 20))
        assertEquals(1L, NextEpisode.nextPgc(episodes, 2, -1)?.epId)
        assertNull(NextEpisode.nextPgc(episodes, 1, -1))
        assertNull(NextEpisode.nextPgc(episodes, 3, -1))
        assertNull(NextEpisode.adjacent(listOf(1, 2), 0) { it == 1 })
        assertNull(NextEpisode.adjacent(listOf(1, 2), 1) { it == 8 })
    }

}

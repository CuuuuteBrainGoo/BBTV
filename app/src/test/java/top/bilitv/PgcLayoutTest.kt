package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.ui.pgc.portraitBannerWidth
import top.bilitv.ui.pgc.useSideBanner

class PgcLayoutTest {
    @Test fun `content aspect switches only wide layouts and has hysteresis`() {
        assertFalse(useSideBanner(880f, 480f, false)) // Typical 16:9 after navigation.
        assertFalse(useSideBanner(1000f, 480f, false))
        for (width in listOf(1120f, 1260f, 1840f)) assertTrue(useSideBanner(width, 480f, false))
        assertFalse(useSideBanner(1050f, 500f, false))
        assertTrue(useSideBanner(1050f, 500f, true))
        assertFalse(useSideBanner(1020f, 500f, true))
        assertEquals(260f, portraitBannerWidth(1000f), 0f)
        assertEquals(200f, portraitBannerWidth(300f), .001f)
    }

    @Test fun `short windows and large fonts keep the single scroll area`() {
        assertFalse(useSideBanner(1000f, 120f, false))
        assertFalse(useSideBanner(1000f, 220f, false, 1.5f))
        assertTrue(useSideBanner(1200f, 320f, false, 1.5f))
        assertFalse(useSideBanner(Float.NaN, 400f, true))
        assertFalse(useSideBanner(1000f, Float.POSITIVE_INFINITY, true))
        assertFalse(useSideBanner(1000f, 400f, true, 0f))
    }
}

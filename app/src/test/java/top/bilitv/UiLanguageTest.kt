package top.bilitv

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.settings.UiLanguage
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.components.formatPubDate

class UiLanguageTest {
    @Test fun unsupportedPreferenceFallsBackToDeviceLanguage() {
        assertEquals(UiLanguage.SYSTEM, UiLanguage.of(null))
        assertEquals(UiLanguage.SYSTEM, UiLanguage.of("obsolete"))
        assertNull(UiLanguage.SYSTEM.locale)
        assertEquals("en", UiLanguage.of("en").locale?.language)
        assertEquals("CN", UiLanguage.of("zh-CN").locale?.country)
    }

    @Test fun countUnitsFollowSelectedLanguageAtBoundaries() {
        assertEquals("999", formatCount(999, Locale.ENGLISH))
        assertEquals("1.0K", formatCount(1_000, Locale.ENGLISH))
        assertEquals("1.0M", formatCount(1_000_000, Locale.ENGLISH))
        assertEquals("1.0B", formatCount(1_000_000_000, Locale.ENGLISH))
        assertEquals("1.0万", formatCount(10_000, Locale.CHINA))
        assertEquals("1.0亿", formatCount(100_000_000, Locale.CHINA))
    }

    @Test fun englishDatesPreserveMissingAndFutureTimeRules() {
        val now = 1_790_000_000L
        assertEquals("", formatPubDate(0, now, Locale.ENGLISH))
        assertEquals("", formatPubDate(now + 1, now, Locale.ENGLISH))
        assertEquals("Just now", formatPubDate(now - 59, now, Locale.ENGLISH))
        assertEquals("1 min ago", formatPubDate(now - 60, now, Locale.ENGLISH))
        assertEquals("1 h ago", formatPubDate(now - 3600, now, Locale.ENGLISH))
        assertEquals("Yesterday", formatPubDate(now - 86_400, now, Locale.ENGLISH))
        assertFalse(formatPubDate(now - 200_000, now, Locale.ENGLISH).contains("月"))
    }
}

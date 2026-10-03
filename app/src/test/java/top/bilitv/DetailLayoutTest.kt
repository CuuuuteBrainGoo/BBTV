package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Test
import top.bilitv.data.settings.DetailLayout
import top.bilitv.data.settings.InterfaceTuning

class DetailLayoutTest {
    @Test fun playHeaderCannotBeRemovedWhileOptionalContentKeepsOrder() {
        assertEquals(listOf("HERO"), DetailLayout.sections(emptyList()))
        assertEquals(listOf("HERO", "DESC", "PARTS"), DetailLayout.sections(listOf("DESC", "junk", "HERO", "PARTS", "DESC")))
        assertEquals(DetailLayout.ALL, DetailLayout.sections(DetailLayout.ALL))
        assertEquals(setOf("ui_detail_sections", "ui_detail_meta", "ui_recommend_backtrack"),
            InterfaceTuning.appearanceKeys(setOf("ui_detail_sections", "ui_detail_meta", "ui_recommend_backtrack", "history", "ask_resume")).toSet())
    }
}

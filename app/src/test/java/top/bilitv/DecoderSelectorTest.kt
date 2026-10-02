package top.bilitv

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.bilitv.player.DecoderSelector

/**
 * 「解码器」档位归一化 + 安全阀。
 *
 * 这块的价值全在**边界**上：
 *  - 老版本存的是中文文案，升级后必须认得出来（否则用户会以为"设置了没生效"）
 *  - 认不出的脏值必须回到 AUTO，绝不能拿着怪值去过滤候选表
 *  - 过滤结果为空必须回落（否则用户选软解 = 全黑屏）
 */
@UnstableApi
class DecoderSelectorTest {

    // ---- normalize：合法 id 原样保留

    @Test
    fun knownIdsPassThrough() {
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize(DecoderSelector.AUTO))
        assertEquals(DecoderSelector.SOFTWARE, DecoderSelector.normalize(DecoderSelector.SOFTWARE))
        assertEquals(DecoderSelector.VENDOR, DecoderSelector.normalize(DecoderSelector.VENDOR))
    }

    // ---- normalize：0.2.0 之前存的中文文案要认出来（升级兼容）

    @Test
    fun legacyChineseLabelsAreMigrated() {
        assertEquals(DecoderSelector.SOFTWARE, DecoderSelector.normalize("系统软解 (c2.android)"))
        assertEquals(DecoderSelector.VENDOR, DecoderSelector.normalize("厂商硬解"))
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize("自动"))
    }

    // ---- normalize：脏值 / null / 空白 一律回落 AUTO

    @Test
    fun junkFallsBackToAuto() {
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize(null))
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize(""))
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize("   "))
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize("c2.qti.avc.decoder"))
        assertEquals(DecoderSelector.AUTO, DecoderSelector.normalize("随便写的"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals(DecoderSelector.SOFTWARE, DecoderSelector.normalize("  software  "))
        assertEquals(DecoderSelector.VENDOR, DecoderSelector.normalize("\tvendor\n"))
    }

    // ---- label / fromLabel：界面文案与 id 一一对应（存 id 不存文案）

    @Test
    fun labelRoundTripsThroughFromLabel() {
        for (id in listOf(DecoderSelector.AUTO, DecoderSelector.SOFTWARE, DecoderSelector.VENDOR)) {
            assertEquals(id, DecoderSelector.fromLabel(DecoderSelector.label(id)))
        }
    }

    @Test
    fun labelOfUnknownIdIsAutoLabel() {
        assertEquals(DecoderSelector.label(DecoderSelector.AUTO), DecoderSelector.label("garbage"))
    }

    // ---- describe：三档各有一句人话，且都不是空串

    @Test
    fun describeIsNeverBlank() {
        for (id in listOf(DecoderSelector.AUTO, DecoderSelector.SOFTWARE, DecoderSelector.VENDOR, null)) {
            assert(DecoderSelector.describe(id).isNotBlank()) { "$id 的说明不该为空" }
        }
    }

    // ---- ★ 安全阀：过滤成空必须回落，绝不能返回空表

    @Test
    fun emptyFilterResultFallsBackToOriginal() {
        val all = listOf("a", "b", "c")
        val kept = DecoderSelector.keepOrFallback(all) { false }
        assertEquals(all, kept)
        assertSame(all, kept)   // 回落时原样返回同一个对象，不额外拷贝
    }

    @Test
    fun nonEmptyFilterResultIsKept() {
        val all = listOf("c2.android.avc.decoder", "c2.qti.avc.decoder")
        val kept = DecoderSelector.keepOrFallback(all) { it.startsWith("c2.android.") }
        assertEquals(listOf("c2.android.avc.decoder"), kept)
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<String>(), DecoderSelector.keepOrFallback(emptyList<String>()) { true })
    }
}

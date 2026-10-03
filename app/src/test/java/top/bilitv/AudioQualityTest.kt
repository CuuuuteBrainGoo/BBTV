package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.parsePlayInfo
import top.bilitv.data.model.DashStream
import top.bilitv.data.settings.AudioQuality
import top.bilitv.data.settings.PlaybackTuning
import top.bilitv.player.StreamSelector

class AudioQualityTest {
    private fun audio(id: Int, bitrate: Long, codec: String = "mp4a.40.2") =
        DashStream(id, codec, bitrate, 0, 0, "https://example.com/$id", emptyList())

    @Test fun `preference respects real ids availability and decoder fallback`() {
        val low = audio(30216, 65001)
        val mid = audio(30232, 132888)
        val high = audio(30280, 192100)
        val dolby = audio(30250, 448000, "ec-3")
        val flac = audio(30251, 900000, "fLaC")
        val all = listOf(low, high, dolby, flac, mid)
        assertEquals(high, StreamSelector.pickAudio(all, { true }))
        assertEquals(mid, StreamSelector.pickAudio(all, { true }, 30232))
        assertEquals(low, StreamSelector.pickAudio(listOf(low, high), { true }, 30232))
        assertEquals(mid, StreamSelector.pickAudio(listOf(mid, high), { true }, 30216))
        assertEquals(dolby, StreamSelector.pickAudio(all, { true }, 30250))
        assertEquals(high, StreamSelector.pickAudio(all, { it.codecs.startsWith("mp4a") }, 30251))
        assertNull(StreamSelector.pickAudio(all, { false }, 30280))
        assertEquals(AudioQuality.AUTO, AudioQuality.of(-123))
        assertEquals(listOf("preferred_audio_quality"), PlaybackTuning.resetKeys(setOf("preferred_audio_quality", "credentials", "history")))
    }

    @Test fun `special audio comes only from valid returned dash tracks`() {
        val parsed = parsePlayInfo("""{"code":0,"data":{"dash":{"duration":60,
          "video":[{"id":80,"codecs":"avc1","base_url":"https://example.com/video"}],
          "audio":[{"id":30280,"codecs":"mp4a.40.2","base_url":"https://example.com/aac"}],
          "dolby":{"audio":[{"id":30250,"codecs":"ec-3","base_url":"https://example.com/dolby"},{}]},
          "flac":{"audio":{"id":30251,"codecs":"fLaC","base_url":"https://example.com/flac"}}}}}""")!!
        assertEquals(listOf(30280, 30250, 30251), parsed.audios.map { it.qualityId })
        assertEquals(30280, StreamSelector.select(parsed, { true }, { it.qualityId != 30251 }, audioQualityId = 30251)?.audio?.qualityId)
        assertTrue(parsePlayInfo("""{"code":0,"data":{"dash":{"video":[{"base_url":"v"}],"dolby":{"audio":null},"flac":null}}}""")!!.audios.isEmpty())
    }
}

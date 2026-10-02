package top.bilitv.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import java.util.concurrent.ConcurrentHashMap

/**
 * 编码字符串 → 标准 MIME。
 *
 * B 站返回的 `codecs` 形如 `hev1.1.6.L120.90` / `avc1.64001F` / `av01.0.08M.08` /
 * `mp4a.40.2`，取前缀即可判定编码族。
 */
fun videoMimeOf(codecs: String): String = when {
    codecs.startsWith("hev", true) || codecs.startsWith("hvc", true) -> MimeTypes.VIDEO_H265
    codecs.startsWith("av01", true) -> MimeTypes.VIDEO_AV1
    codecs.startsWith("avc", true) -> MimeTypes.VIDEO_H264
    else -> MimeTypes.VIDEO_H264
}

fun audioMimeOf(codecs: String): String = when {
    codecs.startsWith("mp4a", true) -> MimeTypes.AUDIO_AAC
    codecs.startsWith("ec-3", true) -> MimeTypes.AUDIO_E_AC3
    codecs.startsWith("ac-3", true) -> MimeTypes.AUDIO_AC3
    codecs.startsWith("flac", true) -> MimeTypes.AUDIO_FLAC
    codecs.startsWith("opus", true) -> MimeTypes.AUDIO_OPUS
    else -> MimeTypes.AUDIO_AAC
}

/**
 * 解码能力检测。
 *
 * **为什么必须做**：`docs/06` §2.1 实测同一个清晰度会同时给 AV1 / AVC / HEVC 三种编码，
 * 播放器必须挑本机真解得了的那一种。目标机型是中低端电视 SoC（Mali-G51 MP4 级）：
 *  - HEVC 硬解强（官方标称 4K@120）
 *  - AVC 标称支持但**高分辨率实测易黑屏**（`docs/01` newBV #288 是同类问题）
 *  - AV1 基本没有硬解，软解会卡
 *
 * 检测走系统 [MediaCodecList]，结果按 mime 缓存 —— 一次播放里同一编码只会查一次。
 */
object DecoderSupport {

    /**
     * AVC 超过 1080p 一律拒绝。
     *
     * 这是**针对真实硬件的保守校准**，不是理论推断：芯片标称支持不等于实测能播。
     * 宁可在选流阶段退到 1080P 的 AVC 或同清晰度的 HEVC，也不要让用户看到黑屏。
     */
    private const val AVC_MAX_WIDTH = 1920
    private const val AVC_MAX_HEIGHT = 1080

    private val cache = ConcurrentHashMap<String, Boolean>()

    /** 设备上存在能解码该 mime 的硬/软解码器 */
    fun hasDecoder(mime: String): Boolean = cache.getOrPut(mime) {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /** 设备上存在能在给定分辨率下解码该 mime 的解码器 */
    fun canDecodeVideo(mime: String, width: Int, height: Int): Boolean {
        if (mime == MimeTypes.VIDEO_H264 && (width > AVC_MAX_WIDTH || height > AVC_MAX_HEIGHT)) {
            return false
        }
        // 尺寸未知（少数片源不给 width/height）→ 只判断编码族有没有解码器
        if (width <= 0 || height <= 0) return hasDecoder(mime)

        val key = "$mime@${width}x$height"
        return cache.getOrPut(key) { probeSize(mime, width, height) }
    }

    fun canDecodeAudio(mime: String): Boolean = hasDecoder(mime)

    private fun probeSize(mime: String, width: Int, height: Int): Boolean = runCatching {
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (sizeSupported(caps, width, height)) return@runCatching true
        }
        false
    }.getOrDefault(false)

    private fun sizeSupported(
        caps: MediaCodecInfo.CodecCapabilities,
        width: Int,
        height: Int,
    ): Boolean {
        val v = caps.videoCapabilities ?: return true // 音频解码器误入，交给调用方
        return runCatching { v.isSizeSupported(width, height) }.getOrDefault(false)
    }
}

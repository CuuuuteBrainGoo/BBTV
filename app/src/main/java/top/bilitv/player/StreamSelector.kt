package top.bilitv.player

import top.bilitv.data.model.DashStream
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.settings.AudioQuality

/**
 * 选流 —— **纯逻辑，无 Android 依赖，可单测**。
 *
 * 目标：在"设备真能解"的前提下，拿到画质最好的那一组音视频。
 *
 * 规则（顺序即优先级）：
 *  1. **清晰度从高到低**尝试；
 *  2. 同一清晰度内按编码优先级排序：HEVC → AVC → AV1
 *     （HEVC 硬解强；AV1 在这类 SoC 上基本没有硬解，软解会卡，所以排最后而不是中间）；
 *  3. 逐个丢给解码能力检测，**第一个能被解出来的就用它**；
 *  4. 某个清晰度全军覆没 → 自动降到下一档，而不是直接报错。
 *
 * 音频单独选：优先所选可解码档位，缺失就近回退；自动优先常规AAC。全解不了返回空，画面照常播（静音）
 * —— 宁可有画面没声音，也别整个黑屏。
 *
 * ## ★ 「只要 AVC」这条逃生路
 * `onlyAvc = true` 时**完全不考虑 HEVC / AV1**。这不是画质偏好，是**避雷**：
 * Media3 1.5.1 的 HEVC 解析器在 B 站 HEVC 流上会崩（`HevcConfig.parseImpl`，见 `docs/08` §8），
 * 而 AVC 走的是完全不同的解析路径，不受影响。
 *
 * 触发时机：HEVC 起播失败且已确认是"数据拿到了、解析不了"（不是网络问题）。
 * 详见 [top.bilitv.player.BiliPlayer] 的降级链。
 */
object StreamSelector {

    /** 至少五秒采样且掉帧达 8%，避免一次起播抖动触发降档。 */
    fun shouldLowerQuality(dropped: Int, elapsedMs: Long, frameRate: Float): Boolean {
        if (dropped <= 0 || elapsedMs < 5_000L) return false
        val fps = frameRate.takeIf { it.isFinite() && it > 0f } ?: 30f
        return dropped >= fps * elapsedMs / 1000f * 0.08f
    }

    /** 下一档必须减少像素；同尺寸优先常规帧率，避免 720P60 增加解码负担。 */
    fun lowerResolution(videos: List<DashStream>, current: DashStream, canVideo: (DashStream) -> Boolean): DashStream? =
        videos.filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height < current.width.toLong() * current.height }
            .sortedWith(compareByDescending<DashStream> { it.width.toLong() * it.height }
                .thenBy { if (it.qualityId == 74 || it.qualityId == 116) 1 else 0 }
                .thenByDescending { it.qualityId })
            .firstOrNull(canVideo)

    data class Selection(
        val video: DashStream,
        val audio: DashStream?,
    )

    /**
     * @param canVideo 解码能力检测（真实实现见 [DecoderSupport.canDecodeVideo]）
     * @param canAudio 音频解码检测
     * @param qualityId 用户指定的清晰度；null = 自动；指定档位不存在时回退为自动
     * @param preferHevc 同清晰度是否优先 HEVC
     * @param onlyAvc **只挑 AVC**。HEVC 解析崩了之后的保险丝，见类注释
     */
    fun select(
        play: PlayInfo,
        canVideo: (DashStream) -> Boolean,
        canAudio: (DashStream) -> Boolean = { true },
        qualityId: Int? = null,
        preferHevc: Boolean = true,
        onlyAvc: Boolean = false,
        audioQualityId: Int = 0,
    ): Selection? {
        val video = pickVideo(play.videos, canVideo, qualityId, preferHevc, onlyAvc) ?: return null
        return Selection(video, pickAudio(play.audios, canAudio, audioQualityId))
    }

    fun pickVideo(
        videos: List<DashStream>,
        canVideo: (DashStream) -> Boolean,
        qualityId: Int? = null,
        preferHevc: Boolean = true,
        onlyAvc: Boolean = false,
    ): DashStream? {
        if (videos.isEmpty()) return null
        val eligible = if (onlyAvc) videos.filter { it.isAvc } else videos
        if (eligible.isEmpty()) return null

        val requested = qualityId?.takeIf { it > 0 }?.let { q -> eligible.filter { it.qualityId <= q } }.orEmpty()
        val pool = requested.ifEmpty { eligible }

        // 清晰度降序；同档内按编码优先级
        val byQuality = pool.groupBy { it.qualityId }.toSortedMap(compareByDescending { it })
        for ((_, group) in byQuality) {
            val ordered = group.sortedBy { codecRank(it, preferHevc) }
            ordered.firstOrNull(canVideo)?.let { return it }
        }
        return null
    }

    fun pickAudio(audios: List<DashStream>, canAudio: (DashStream) -> Boolean, audioQualityId: Int = 0): DashStream? {
        val compatible = audios.filter(canAudio).sortedByDescending { it.bandwidth }
        val desired = AudioQuality.of(audioQualityId).id
        if (desired != 0) compatible.firstOrNull { it.qualityId == desired }?.let { return it }
        val normal = compatible.filter { it.qualityId in AudioQuality.NORMAL_IDS }
        val rank = AudioQuality.NORMAL_IDS.indexOf(desired)
        if (rank >= 0) {
            normal.filter { AudioQuality.NORMAL_IDS.indexOf(it.qualityId) <= rank }
                .maxByOrNull { AudioQuality.NORMAL_IDS.indexOf(it.qualityId) }?.let { return it }
            normal.minByOrNull { AudioQuality.NORMAL_IDS.indexOf(it.qualityId) }?.let { return it }
        }
        // Special tracks are opt-in when ordinary AAC is available; no account rights are invented.
        return normal.firstOrNull() ?: compatible.firstOrNull()
    }

    /** 越小越优先 */
    private fun codecRank(s: DashStream, preferHevc: Boolean): Int = when {
        s.isHevc -> if (preferHevc) 0 else 1
        s.isAvc -> if (preferHevc) 1 else 0
        s.isAv1 -> 2
        else -> 3
    }
}

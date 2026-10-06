package top.bilitv.ui.player

import top.bilitv.R
import top.bilitv.data.settings.AudioQuality
import top.bilitv.data.settings.PlaybackTuning
import top.bilitv.data.sponsor.SponsorCategory

/*
 * 播放页里那些"枚举档位"的显示名。
 *
 * 这些枚举自己带着中文 `label`（纯数据层，不能引用 R）。播放页只取**资源 ID**，
 * 让系统按当前语言翻译，避免把译好的文本缓存进状态里。
 */

internal fun PlaybackTuning.EndAction.labelRes(): Int = when (this) {
    PlaybackTuning.EndAction.PAUSE -> R.string.end_pause
    PlaybackTuning.EndAction.NEXT -> R.string.end_next
    PlaybackTuning.EndAction.LOOP -> R.string.end_loop
}

internal fun PlaybackTuning.SideAction.labelRes(): Int = when (this) {
    PlaybackTuning.SideAction.CATALOGUE -> R.string.remote_catalogue
    PlaybackTuning.SideAction.RECOMMEND -> R.string.section_recommend
    PlaybackTuning.SideAction.UP_UPLOADS -> R.string.player_side_up
}

internal fun AudioQuality.labelRes(): Int = when (this) {
    AudioQuality.AUTO -> R.string.audio_auto
    AudioQuality.AAC_192 -> R.string.audio_192
    AudioQuality.AAC_132 -> R.string.audio_132
    AudioQuality.AAC_64 -> R.string.audio_64
    AudioQuality.DOLBY -> R.string.audio_dolby
    AudioQuality.HI_RES -> R.string.audio_hires
}

internal fun SponsorCategory.labelRes(): Int = when (this) {
    SponsorCategory.SPONSOR -> R.string.sponsor_paid
    SponsorCategory.SELF_PROMO -> R.string.sponsor_self
    SponsorCategory.EXCLUSIVE_ACCESS -> R.string.sponsor_brand
    SponsorCategory.INTRO -> R.string.sponsor_intro
    SponsorCategory.OUTRO -> R.string.sponsor_outro
    SponsorCategory.INTERACTION -> R.string.sponsor_interaction
    SponsorCategory.PREVIEW -> R.string.sponsor_preview
    SponsorCategory.FILLER -> R.string.sponsor_filler
    SponsorCategory.MUSIC_OFFTOPIC -> R.string.sponsor_music
    SponsorCategory.POI_HIGHLIGHT -> R.string.sponsor_highlight
    SponsorCategory.OTHER -> R.string.sponsor_other
}

/** 画面比例档位 id → 资源 ID（未知 id 回落「适应」）。 */
internal fun aspectLabelRes(id: String): Int = when (id) {
    "fill" -> R.string.aspect_fill
    "zoom" -> R.string.aspect_crop
    else -> R.string.aspect_fit
}

/** 默认清晰度档位 → 资源 ID；服务端自带名字的档位返回 null，调用方直接用服务端名字。 */
internal fun qualityOptionLabelRes(id: Int): Int? = when (id) {
    0 -> R.string.value_auto
    126 -> R.string.quality_dolby
    112 -> R.string.quality_high_bitrate
    else -> null
}

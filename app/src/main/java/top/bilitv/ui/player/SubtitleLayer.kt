package top.bilitv.ui.player

import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import top.bilitv.R
import top.bilitv.data.settings.SubtitleStyle

/** 使用已包含的 Media3 字幕视图；不添字体、转换库或第二个播放内核。 */
@Composable
internal fun SubtitleLayer(text: String, enabled: Boolean, style: SubtitleStyle, modifier: Modifier = Modifier) {
    val shown = if (enabled) text else ""
    val description = if (shown.isNotEmpty()) stringResource(R.string.player_subtitle_description, shown) else null
    val cue = remember(shown) {
        if (shown.isEmpty()) emptyList() else {
            listOf(Cue.Builder().setText(shown).setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                .setPosition(.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                .setLine(.94f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END)
                .setSize(.9f).build())
        }
    }
    AndroidView(factory = { context -> SubtitleView(context).apply {
        isFocusable = false; isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false); setBottomPaddingFraction(0f)
    } }, update = { view ->
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, SubtitleStyle.FONT_SP[style.font])
        view.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE,
            ((style.background * 255 / 100) shl 24), android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, Typeface.DEFAULT))
        view.setCues(cue)
        view.contentDescription = description
    }, modifier = modifier)
}

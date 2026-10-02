package top.bilitv.ui.player

import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import top.bilitv.data.settings.SubtitleStyle

/** 使用已包含的 Media3 字幕视图；不添字体、转换库或第二个播放内核。 */
@Composable
internal fun SubtitleLayer(text: String, enabled: Boolean, style: SubtitleStyle, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf("") }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(text, enabled, style.fade) {
        val next = if (enabled) text else ""
        if (!style.fade || !enabled) {
            shown = next; alpha.snapTo(if (next.isEmpty()) 0f else 1f)
        } else {
            if (shown.isNotEmpty() && shown != next) alpha.animateTo(0f, tween(80))
            shown = next
            if (next.isNotEmpty()) alpha.animateTo(1f, tween(160))
        }
    }
    val cue = remember(shown, style.position, style.x, style.y) {
        if (shown.isEmpty()) emptyList() else {
            val left = style.position == 2 || style.position == 4
            val right = style.position == 3 || style.position == 5
            val top = style.position in 1..3
            val custom = style.position == 6
            val x = if (custom) style.x / 100f else if (left) .04f else if (right) .96f else .5f
            val y = if (custom) style.y / 100f else if (top) .04f else .94f
            val anchor = if (left) Cue.ANCHOR_TYPE_START else if (right) Cue.ANCHOR_TYPE_END else Cue.ANCHOR_TYPE_MIDDLE
            listOf(Cue.Builder().setText(shown).setTextAlignment(if (left) Layout.Alignment.ALIGN_NORMAL else if (right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_CENTER)
                .setPosition(x).setPositionAnchor(anchor).setLine(y, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(if (custom) Cue.ANCHOR_TYPE_MIDDLE else if (top) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
                .setSize(if (custom) (2f * minOf(x - .04f, .96f - x)).coerceIn(.02f, .9f) else .9f).build())
        }
    }
    AndroidView(factory = { context -> SubtitleView(context).apply {
        isFocusable = false; isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false); setBottomPaddingFraction(0f)
    } }, update = { view ->
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, SubtitleStyle.FONT_SP[style.font])
        view.setStyle(CaptionStyleCompat(SubtitleStyle.COLOR_ARGB[style.color],
            ((style.background * 255 / 100) shl 24), android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, Typeface.DEFAULT))
        view.setCues(cue)
        view.contentDescription = if (shown.isNotEmpty()) "字幕：$shown" else null
    }, modifier = modifier.alpha(alpha.value))
}

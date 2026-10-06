package top.bilitv.ui.player

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.bilitv.R

@Composable
internal fun PlaybackStats(vm: PlayerViewModel, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(vm, context) {
        while (isActive) {
            val exo = vm.player.exo
            val counters = exo.videoDecoderCounters?.also { it.ensureUpdated() }
            val video = exo.videoFormat; val audio = exo.audioFormat
            val bufferState = when {
                exo.isPlaying -> context.getString(R.string.player_playing)
                exo.playWhenReady -> context.getString(R.string.action_loading)
                else -> context.getString(R.string.player_paused)
            }
            text = "${vm.qualityLabel} · ${vm.speedLabel}\n" +
                "${video?.width ?: 0}×${video?.height ?: 0} · ${video?.sampleMimeType ?: context.getString(R.string.player_stats_waiting_video)}\n" +
                context.getString(
                    R.string.player_stats_frame_rate,
                    video?.frameRate?.takeIf { it > 0 }?.toInt() ?: 0,
                    counters?.renderedOutputBufferCount ?: 0,
                    counters?.droppedBufferCount ?: 0,
                ) + "\n" +
                context.getString(R.string.player_stats_buffer, exo.totalBufferedDuration / 1000, bufferState) + "\n" +
                context.getString(
                    R.string.player_stats_audio,
                    audio?.sampleMimeType ?: context.getString(R.string.player_stats_none),
                    audio?.channelCount?.coerceAtLeast(0) ?: 0,
                )
            delay(1000)
        }
    }
    AndroidView(factory = { ctx -> TextView(ctx).apply {
        setTextColor(android.graphics.Color.WHITE); textSize = 14f
        setBackgroundColor(0xB3000000.toInt()); setPadding(12, 8, 12, 8)
        isFocusable = false
    } }, update = { it.text = text }, modifier = modifier)
}


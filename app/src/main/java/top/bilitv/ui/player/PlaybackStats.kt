package top.bilitv.ui.player

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun PlaybackStats(vm: PlayerViewModel, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(vm) {
        while (isActive) {
            val exo = vm.player.exo
            val counters = exo.videoDecoderCounters?.also { it.ensureUpdated() }
            val video = exo.videoFormat; val audio = exo.audioFormat
            text = "${vm.qualityLabel} · ${vm.speedLabel}\n" +
                "${video?.width ?: 0}×${video?.height ?: 0} · ${video?.sampleMimeType ?: "等待视频"}\n" +
                "帧率 ${video?.frameRate?.takeIf { it > 0 }?.toInt() ?: 0} · 渲染 ${counters?.renderedOutputBufferCount ?: 0} · 掉帧 ${counters?.droppedBufferCount ?: 0}\n" +
                "缓冲 ${exo.totalBufferedDuration / 1000}s · ${if (exo.isPlaying) "播放中" else if (exo.playWhenReady) "加载中" else "已暂停"}\n" +
                "音频 ${audio?.sampleMimeType ?: "无"} · ${audio?.channelCount?.coerceAtLeast(0) ?: 0} 声道"
            delay(1000)
        }
    }
    AndroidView(factory = { ctx -> TextView(ctx).apply {
        setTextColor(android.graphics.Color.WHITE); textSize = 14f
        setBackgroundColor(0xB3000000.toInt()); setPadding(12, 8, 12, 8)
        isFocusable = false
    } }, update = { it.text = text }, modifier = modifier)
}


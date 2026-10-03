package top.bilitv.data.settings

/** Resource budgets, not a guarantee of device frame rate or total process memory. */
enum class PlaybackPerformance(
    val label: String, val bufferMiB: Int, val minBufferMs: Int, val maxBufferMs: Int,
    val danmakuFps: Int, val danmakuLimit: Int, val textCache: Int,
) {
    HIGH("高性能", 64, 20_000, 60_000, 120, 180, 512),
    BALANCED("均衡", 48, 20_000, 40_000, 60, 180, 512),
    MEMORY("省内存", 24, 10_000, 20_000, 30, 90, 128);

    fun drawFrame(last: Long, now: Long): Boolean = last == 0L || now - last >= 1_000_000_000L / danmakuFps - 500_000L

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.name == id } ?: BALANCED
    }
}

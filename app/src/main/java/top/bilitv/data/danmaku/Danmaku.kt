package top.bilitv.data.danmaku

/**
 * 弹幕数据与解析。
 *
 * 为什么手写 protobuf 读取器，而不是引入 protobuf-javalite：
 *  - 只需要 5 个字段（时间/模式/字号/颜色/内容），为它拉 protoc 生成代码属于用大炮打蚊子；
 *  - 电视端存储普遍紧张，能不加依赖就不加；
 *  - 手写读取器天然容忍未知字段（跳过即可），正好符合本项目"宽松解析"的要求。
 *
 * ⚠️ 弹幕是**不可信的网络数据**：读取器对每一个长度、每一次越界都做校验。
 * 最坏情况是丢掉一条甚至一段弹幕，**绝不允许异常抛到界面上**。
 *
 * 协议（B 站 `DmSegMobileReply`）：
 * ```
 * DmSegMobileReply { repeated DanmakuElem elems = 1; }
 * DanmakuElem {
 *   int64  id       = 1;
 *   int32  progress = 2;   // 出现时间，毫秒
 *   int32  mode     = 3;
 *   int32  fontsize = 4;
 *   uint32 color    = 5;   // RGB
 *   string midHash  = 6;
 *   string content  = 7;
 *   int64  ctime    = 8;
 *   ... 9~13 本阶段用不到
 * }
 * ```
 */

/** 一条弹幕 */
data class DanmakuItem(
    /** 出现时间（毫秒） */
    val timeMs: Long,
    /** 1/2/3 滚动；4 底端；5 顶端；6 逆向滚动；7 高级；8 代码；9 BAS */
    val mode: Int,
    /** 字号，实测只有 18 / 25 / 36 三种 */
    val fontsize: Int,
    /** RGB 颜色，无 alpha 分量 */
    val color: Int,
    val content: String,
    /** protobuf 9；0／缺字段表示未评分，不能冒充高质量或恶意弹幕。 */
    val weight: Int = 0,
    /** protobuf 6，用户的匿名 CRC32 标识。 */
    val midHash: String = "",
    val advanced: AdvancedDanmaku? = null,
    /** 来自元数据 CommandDm，只显示文字，不能执行指令或打开链接。 */
    val interaction: Boolean = false,
) {
    val isScroll: Boolean get() = mode in 1..3
    val isReverse: Boolean get() = mode == 6
    val isTop: Boolean get() = mode == 5
    val isBottom: Boolean get() = mode == 4

    /** 高级(7) / 代码弹幕(8) / BAS(9) 本阶段不渲染 —— 只占极少数，且实现成本极高 */
    val isRenderable: Boolean get() = mode in 1..6

    /** 需要在屏幕上横向移动 */
    val isMoving: Boolean get() = isScroll || isReverse
}

// ------------------------------------------------------------------ protobuf

/** 最小 protobuf 读取器：只支持 varint 与 length-delimited，够用且不会有意外 */
internal class Pbf(private val buf: ByteArray) {
    var pos = 0
        private set

    val exhausted: Boolean get() = pos >= buf.size

    /** 读 varint（最多 64 位） */
    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= buf.size) throw IndexOutOfBoundsException("varint 越界")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalArgumentException("varint 过长")
        }
    }

    fun lengthDelimited(): ByteArray {
        val n = varint()
        if (n < 0 || n > buf.size - pos) throw IndexOutOfBoundsException("length-delimited 越界")
        val end = pos + n.toInt()
        val out = buf.copyOfRange(pos, end)
        pos = end
        return out
    }

    /** 跳过不认识的字段（wire type 0 varint / 1 fixed64 / 2 bytes / 5 fixed32） */
    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                val n = varint()
                if (n < 0 || n > buf.size - pos) throw IndexOutOfBoundsException("skip 越界")
                pos += n.toInt()
            }
            5 -> pos += 4
            else -> throw IllegalArgumentException("不支持的 wire type $wire")
        }
        if (pos > buf.size) throw IndexOutOfBoundsException("skip 越界")
    }

    fun fields(number: (Int, Long) -> Unit = { _, _ -> }, bytes: (Int, ByteArray) -> Unit = { _, _ -> }) {
        while (!exhausted) {
            val key = varint().toInt()
            require(key ushr 3 > 0)
            when (key and 7) {
                0 -> number(key ushr 3, varint())
                2 -> bytes(key ushr 3, lengthDelimited())
                else -> skip(key and 7)
            }
        }
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_LEN = 2

/**
 * 解析一个弹幕分段（`seg.so` 的完整响应体）。
 *
 * 解析失败时**返回已解出的部分**，不抛异常。
 */
fun parseDanmakuSeg(bytes: ByteArray): List<DanmakuItem> {
    val out = ArrayList<DanmakuItem>(128)
    runCatching {
        val r = Pbf(bytes)
        while (!r.exhausted) {
            val key = r.varint().toInt()
            val field = key ushr 3
            val wire = key and 7
            if (field == 1 && wire == WIRE_LEN) {
                parseElem(r.lengthDelimited())?.let(out::add)
            } else {
                r.skip(wire)
            }
        }
    }
    return out
}

private fun parseElem(bytes: ByteArray): DanmakuItem? {
    val r = Pbf(bytes)
    var timeMs = -1L
    var mode = 1
    var fontsize = 25
    var color = 0xFFFFFF
    var content = ""
    var weight = 0
    var midHash = ""

    while (!r.exhausted) {
        val key = r.varint().toInt()
        val field = key ushr 3
        when (key and 7) {
            WIRE_VARINT -> {
                val v = r.varint()
                when (field) {
                    2 -> timeMs = v
                    3 -> mode = v.toInt()
                    4 -> fontsize = v.toInt()
                    5 -> color = v.toInt()
                    9 -> weight = if (v in 1L..10L) v.toInt() else 0
                }
            }

            WIRE_LEN -> {
                val v = r.lengthDelimited()
                if (field == 7) content = String(v, Charsets.UTF_8)
                if (field == 6) midHash = String(v, Charsets.UTF_8).take(32)
            }

            else -> r.skip(key and 7)
        }
    }

    // 缺少时间或内容的条目直接丢弃：渲染不出东西，留着只会占内存
    if (timeMs < 0 || content.isEmpty()) return null
    return DanmakuItem(
        timeMs = timeMs,
        mode = mode,
        fontsize = fontsize.coerceIn(MIN_FONTSIZE, MAX_FONTSIZE),
        color = color and 0xFFFFFF,
        content = content,
        weight = weight,
        midHash = midHash,
        advanced = if (mode == 7) parseAdvancedDanmaku(content) else null,
    )
}

private const val MIN_FONTSIZE = 12
private const val MAX_FONTSIZE = 48

// ------------------------------------------------------------------ 分段

/** 一段弹幕覆盖的时长：B 站按 6 分钟切分 */
const val DANMAKU_SEGMENT_MS = 6 * 60 * 1000L

/** 时间点落在第几段（从 1 开始） */
fun danmakuSegmentIndex(timeMs: Long): Int =
    (timeMs.coerceAtLeast(0L) / DANMAKU_SEGMENT_MS).toInt() + 1

/** 整条视频一共几段 */
fun danmakuSegmentCount(durationMs: Long): Int =
    if (durationMs <= 0L) 1
    else ((durationMs + DANMAKU_SEGMENT_MS - 1) / DANMAKU_SEGMENT_MS).toInt().coerceAtLeast(1)

// ------------------------------------------------------------------ 查询

/**
 * 取出 `[fromMs, toMs]` 区间的弹幕。列表必须按 [DanmakuItem.timeMs] 升序。
 *
 * 用二分而不是线性过滤：弹幕动辄上万条，且这个方法会被高频调用。
 */
fun List<DanmakuItem>.window(fromMs: Long, toMs: Long): List<DanmakuItem> {
    if (isEmpty() || toMs < fromMs) return emptyList()
    val lo = lowerBound(fromMs)
    val hi = lowerBound(toMs + 1)
    return subList(lo, hi)
}

private fun List<DanmakuItem>.lowerBound(target: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timeMs < target) lo = mid + 1 else hi = mid
    }
    return lo
}

/** 按时间升序合并（新加载的分段是后一段，直接拼接后重排即可） */
fun mergeDanmaku(existing: List<DanmakuItem>, incoming: List<DanmakuItem>): List<DanmakuItem> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.sortedBy { it.timeMs }
    return (existing + incoming).sortedBy { it.timeMs }
}

package top.bilitv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 「指定解码器」偏好的落点 —— 把设置页里那一项，真正接到播放链路。
 *
 * ## 为什么是"过滤"而不是"精确匹配"
 *
 * 设置页只给三个粗档（自动 / 软解 / 硬解），**不给用户手打具体解码器名**。
 * 理由写在 `SettingsScreen.DECODERS` 旁边：解码器名是机型相关的，
 * 打错一个字母就"界面显示已设置、实际播放全崩" —— 这是最难排查的一类问题。
 *
 * 所以这里的做法是：**先问系统要它本来会给的候选表，再按档位过滤**。
 * 好处是"系统认可的解码器"这层判断始终由 Media3 自己做，我们只做减法。
 *
 * ## ★ 安全阀：过滤成空必须回落
 *
 * 极端情况下（比如某台设备压根没有 `c2.android.*` 软解器），过滤后候选表会是空的。
 * 空表意味着**连试都没得试**，直接放不了 —— 比"用户选了个不合适的档"糟糕得多。
 * 所以 [forStored] 里一旦发现过滤后为空，就原样返回不过滤的表。
 *
 * 这和 `StreamSelector` 的"某个清晰度全军覆没就降档"是同一个思路：
 * **用户的偏好不该成为播放不出来的理由**。
 */
@UnstableApi
object DecoderSelector {

    // ---- 落盘值（英文 id，改界面文案不会丢配置）

    /** 让系统自己挑（默认） */
    const val AUTO = "auto"

    /** 只用 Android 自带的 `c2.android.*` 软解器 */
    const val SOFTWARE = "software"

    /** 只用厂商硬解（`c2.android.*` 之外的那些） */
    const val VENDOR = "vendor"

    /**
     * 把任意历史落盘值归一到一个合法 id。
     *
     * **为什么要认中文文案**：这个设置项在 0.2.0 之前**存的就是界面文案本身**
     * （"自动" / "系统软解 (c2.android)" / "厂商硬解"）。老用户升级上来，
     * 磁盘里躺着的是中文串。不认它们的话，用户会看到"设置里明明选着软解、实际没生效"
     * —— 正是 `docs/99` 里反复出现的那类"默认值当真结论"的坑。
     *
     * 认不出的一律当 [AUTO]：宁可回到默认，也不要拿着一个看不懂的值去过滤候选表。
     */
    fun normalize(id: String?): String = when (id?.trim()) {
        SOFTWARE, VENDOR -> id.trim()
        "系统软解 (c2.android)" -> SOFTWARE
        "厂商硬解" -> VENDOR
        else -> AUTO
    }

    /** 界面文案（设置页 chip 直接用它） */
    fun label(id: String?): String = when (normalize(id)) {
        SOFTWARE -> "系统软解 (c2.android)"
        VENDOR -> "厂商硬解"
        else -> "自动"
    }

    /** 从界面上看到的那段文字反推 id（回填 `ChoiceRow.selected` 用） */
    fun fromLabel(label: String?): String = normalize(label)

    /**
     * 详情文案里那句"现在这套设置会怎么挑解码器"。给设置页的 `HintText` 用。
     */
    fun describe(id: String?): String = when (normalize(id)) {
        SOFTWARE -> "只用系统自带软解（c2.android.*）。硬解异常、某些编码花屏时用。CPU 占用会明显变高"
        VENDOR -> "只用厂商硬解。想让画面走硬件省电时用；机型太老可能没有可用的硬解器，届时自动回落到系统默认"
        else -> "让系统挑（推荐）。绝大多数情况不用改"
    }

    /**
     * 生成交给 ExoPlayer 的选择器。
     *
     * 注意：**不用 `MediaCodecSelector.DEFAULT` 直接外传**，而是包一层 ——
     * 因为要过滤就得先拿到默认候选表，拿到之后才能做减法。
     */
    fun forStored(id: String?): MediaCodecSelector = filter(predicateFor(normalize(id)))

    /**
     * ★ 给 `BiliPlayer` 用的**活设置**版本：每次挑解码器时**现读**设置。
     *
     * 为什么必须这样：`MediaCodecSelector` 由 `DefaultRenderersFactory` 在**构造 ExoPlayer 时**
     * 绑进去，而 `PlayerViewModel` 是长生命周期对象 —— 如果这里把档位"快照"在构造那一刻，
     * 用户改完设置**必须杀掉 App 重开**才生效。这类"设置了不生效"是少爷最讨厌的体验。
     *
     * 逐次读一个 SharedPreferences 值，代价在纳秒级（同一进程内有内存缓存），
     * 而它换来的行为是"改完设置，下次进播放页就生效"。
     */
    fun live(readPref: () -> String?): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            keepOrFallback(all, predicateFor(normalize(readPref())))
        }

    /** 档位 id → 该保留哪些解码器。`AUTO` 用"全都要"表示不过滤。 */
    private fun predicateFor(id: String): (MediaCodecInfo) -> Boolean = when (id) {
        SOFTWARE -> { info -> info.name.startsWith("c2.android.", ignoreCase = true) }
        VENDOR -> { info -> !info.name.startsWith("c2.android.", ignoreCase = true) }
        else -> { _ -> true }
    }

    /**
     * 按谓词过滤默认候选表；**结果是空表就放弃过滤**（见类注释的"安全阀"）。
     *
     * 这条路径留给"档位在别处已经确定、不需要读设置"的调用方（测试、将来可能的固定配置）。
     */
    private fun filter(predicate: (MediaCodecInfo) -> Boolean): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            keepOrFallback(all, predicate)
        }

    /**
     * 安全阀本身 —— 抽成**泛型纯函数**，好让单测能验它（真跑 [forStored] 要 Android 环境）。
     *
     * 返回过滤结果；若过滤后为空，则原样返回输入。
     */
    internal fun <T> keepOrFallback(all: List<T>, predicate: (T) -> Boolean): List<T> {
        val kept = all.filter(predicate)
        return kept.ifEmpty { all }
    }
}

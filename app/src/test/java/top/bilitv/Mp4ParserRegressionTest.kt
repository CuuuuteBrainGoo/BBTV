package top.bilitv

import androidx.media3.common.DataReader
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.NoOpExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import org.junit.Test
import java.io.File

/**
 * Media3 MP4 解析回归验证 —— **把真机黑屏搬到 JVM 里离线复现**。
 *
 * ## 为什么要这个测试
 * 真机日志：数据源 `open()` **成功**，14 毫秒后抛
 * `UnexpectedLoaderException: Unexpected IndexOutOfBoundsException: index (0) must be less than size (0)`。
 * 网络、UA、Referer、CDN、换源时机、`MergingMediaSource` 全部被逐个排除之后，
 * 剩下的只能是「数据拿到了，解析器自己炸了」。
 *
 * 这个测试把"猜"变成"看"：不依赖真机、不依赖 Gradle 之外任何东西，
 * 几秒出结果，**能直接说出崩在哪个类的哪一行**。真机一轮测试要少爷装包-截图-转发，
 * 成本太高，能离线定位的绝不上真机。
 *
 * ## 结论（2026-09-28 实测，见 `docs/08` §8）
 * **Media3 1.5.1 的 HEVC 解析器在 B 站 HEVC 流上必崩。**
 *
 * 崩点在 `HevcConfig.parseImpl`：
 * ```
 * stereoMode = (seiData.leftViewId == currentVpsData.layerInfos.get(0).viewId) ? ... : ...
 * ```
 * 触发三件套：
 *  1. `hvcC` 里带 **PREFIX_SEI（nal_unit_type=39）**；
 *  2. 该 SEI 能被 `parseH265Sei3dRefDisplayInfo` 解出来（非 null）；
 *  3. **VPS 解析出的 `layerInfos` 是空表**（`vps_max_layers_minus1 == 0` 时就是空的）。
 *
 * 三者凑齐 → Guava `RegularImmutableList.get(0)` 在空表上越界。
 *
 * 致命的是 `HevcConfig.parseImpl` 的兜底 catch **只接 `ArrayIndexOutOfBoundsException`**
 * —— 而 `IndexOutOfBoundsException` 是它的**兄弟类**，不是子类，所以溜出去了。
 * 没有被转成 `ParserException`，于是 `Loader` 把它包成 `UnexpectedLoaderException`，
 * 最终表现成 `ERROR_CODE_IO_UNSPECIFIED` —— 一个看起来像"网络问题"的错误码。
 *
 * ## 上游修了吗
 * 1.4.1 没有这行（是 1.5.0 为 L-HEVC 立体视频新加的检测）；
 * 1.5.0 / 1.5.1 / 1.8.0 / 1.9.0 / **1.11.1（当前最新）全都有，至今未修**。
 *
 * ## 样本怎么来
 * ```
 * python tools/probe_mp4.py <bvid> <cid> --save ./tmp/samples
 * ```
 * `tmp/` 已在 `.gitignore`。没有样本时只打印一行提示，不会变红 ——
 * 这个测试的价值在"人工看结论"，不是 CI 门禁。
 */
class Mp4ParserRegressionTest {

    /**
     * JVM 单测必须接管 media3 的日志。
     *
     * ## 为什么
     * media3 自带的默认 logger 会在每条日志后面附上"这行日志从哪打的"——
     * 靠的是 `android.util.Log.getStackTraceString()`。而单测跑在纯 JVM 上，
     * AGP 给的 `android.jar` 是空壳，这个方法**返回 null**，紧接着就是一句
     * `null.replace(...)` → `NullPointerException`。
     *
     * 后果不是"少几条日志"，而是**真凶被完全盖住**：实测 7 个样本全部只报
     * `NullPointerException: "throwableString" is null`，位置在 `MetadataUtil` 打日志那一行，
     * 媒体解析有没有问题一个字都看不到。
     *
     * ## ⚠️ 绝对不能用「空实现」
     * 这是本测试踩过的第二个坑，比第一个更阴。
     * `AtomParsers.parseTraks` 把 `parseTrak` 包在 try/catch 里：
     *
     * ```java
     * } catch (ParserException e) {
     *   Log.w(TAG, "Failed to parse track " + trackId + " will skip it.", e);
     *   continue;              // ← 解析失败就静默跳过这条轨道
     * }
     * ```
     *
     * 所以把 logger 换成空实现，就等于**把唯一的证据销毁**：
     * 结果是"0 次崩溃、0 条轨道"，看起来风平浪静，实际每个样本都被整个跳过了。
     * 必须**原样打印**，否则这个测试自己会骗自己。
     */
    private object PrintingLogger : Log.Logger {
        override fun d(tag: String, message: String, throwable: Throwable?) =
            emit("D", tag, message, throwable)

        override fun i(tag: String, message: String, throwable: Throwable?) =
            emit("I", tag, message, throwable)

        override fun w(tag: String, message: String, throwable: Throwable?) =
            emit("W", tag, message, throwable)

        override fun e(tag: String, message: String, throwable: Throwable?) =
            emit("E", tag, message, throwable)

        private fun emit(level: String, tag: String, message: String, throwable: Throwable?) {
            println("        media3 $level/$tag: $message")
            throwable?.stackTrace?.take(6)?.forEach { f ->
                println("            at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})")
            }
        }
    }

    init {
        Log.setLogger(PrintingLogger)
    }

    /** 把字节数组伪装成 media3 的流读取器 */
    private class ByteArrayReader(private val bytes: ByteArray) : DataReader {
        private var pos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= bytes.size) return -1 // ExtractorInput/RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    /**
     * 在 media3 自带的 [NoOpExtractorOutput] 外面套一层计数。
     *
     * 不自己实现 `TrackOutput` —— 那个接口要覆写 5 个方法，还要处理 `TrackOutput.CryptoData`
     * 这种内部类，纯属造轮子。media3 已经提供了"把数据读掉但不保存"的实现。
     */
    private class CountingOutput : ExtractorOutput {
        private val delegate = NoOpExtractorOutput()

        var trackCount = 0
            private set
        var seekable = false
            private set
        var durationUs = 0L
            private set

        override fun track(id: Int, type: Int): TrackOutput {
            trackCount++
            return delegate.track(id, type)
        }

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: SeekMap) {
            seekable = seekMap.isSeekable
            durationUs = seekMap.durationUs
            delegate.seekMap(seekMap)
        }
    }

    /**
     * 跑完一个抽取器。
     *
     * 必须处理 `RESULT_SEEK`：样本是从 0 开始的整段字节，
     * 抽取器要求跳到某处时重建一个 `ExtractorInput` 即可（`DefaultExtractorInput` 不能回退）。
     */
    private fun drive(extractor: Extractor, bytes: ByteArray): String {
        val output = CountingOutput()
        extractor.init(output)
        var input = DefaultExtractorInput(ByteArrayReader(bytes), 0, bytes.size.toLong())
        val holder = PositionHolder()

        var guard = 0
        while (guard++ < 200_000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    input = DefaultExtractorInput(
                        ByteArrayReader(bytes),
                        holder.position,
                        bytes.size.toLong(),
                    )
                }
            }
        }
        return "轨道=${output.trackCount} 可seek=${output.seekable} 时长=${output.durationUs / 1000}ms"
    }

    @Test
    fun parseCapturedBiliFragmentedMp4() {
        val dir = File("F:/WorkBuddy/BiliBiliTv/tmp/samples")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4s") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) {
            println("[跳过] 没有样本。先跑：python tools/probe_mp4.py <bvid> <cid> --save ./tmp/samples")
            return
        }

        println("=".repeat(90))
        println("B 站流 MP4 解析离线复现 —— Media3 1.5.1，共 ${files.size} 个样本")
        println("=".repeat(90))

        var crashed = 0
        for (file in files) {
            val bytes = file.readBytes()
            val sizeMb = "%.1f".format(bytes.size / 1024.0 / 1024.0)
            println()
            println("### ${file.name}  ($sizeMb MB)")

            /*
             * 只跑 `Mp4Extractor`。
             *
             * 真机上 `DefaultExtractorsFactory` 会把 `FragmentedMp4Extractor` 排在前面
             * （B 站流里有 `moof`，sniff 会命中它）。但两者最后都走
             * `BoxParser.parseVideoSampleEntry` → `HevcConfig.parse`，**同一个崩点**。
             *
             * 所以验证 `Mp4Extractor` 足以证明崩点在不在。
             * 不跑 `FragmentedMp4Extractor` 的原因很实际：它用了 `android.util.Pair`
             * 和 `android.util.SparseArray`，这两个在 JVM 单测里是空壳 ——
             * `Pair.create()` 直接返回 null，还没走到媒体解析就先 NPE 了，
             * 纯属给测试环境排障，测不出任何关于流的信息。
             */
            val extractor: Extractor = Mp4Extractor(0)
            try {
                println("    OK    [Mp4Extractor] ${drive(extractor, bytes)}")
            } catch (e: Throwable) {
                crashed++
                println("    崩溃  [Mp4Extractor] ${e.javaClass.name}: ${e.message}")
                // 真机日志里 `UnexpectedLoaderException` 把真凶埋了一层，
                // 这里直接把最上面几帧打出来 —— 崩在哪个类的哪一行一目了然。
                e.stackTrace.take(8).forEach { f ->
                    println("            at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})")
                }
                var cause = e.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    println("          ← ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(6).forEach { f ->
                        println("            at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})")
                    }
                    cause = cause.cause
                    depth++
                }
            }
        }

        println()
        println("-".repeat(90))
        println("结果：$crashed 次崩溃 / ${files.size} 次解析")
        println("=".repeat(90))
    }

    // ------------------------------------------------------------------ 定点复现
    //
    // 上面那个"跑完整抽取器"的测试有两层不可靠：
    //   1. 抽取器可能在更早的地方就退出，根本没走到 codec 解析；
    //   2. `AtomParsers.parseTraks` 会把 `parseTrak` 的 `ParserException` 吞掉并跳过轨道，
    //      于是"没崩"可能只是"什么都没解"（实测正是如此：轨道数 0、无任何报错）。
    //
    // 所以补下面这个**定点**测试：自己从流里把 `hvcC` / `avcC` 抠出来，
    // 直接喂给 `HevcConfig.parse` / `AvcConfig.parse` —— 也就是真机崩的那一行。
    // 没有中间层，没有 try/catch 吞异常，结论只有"过"或"崩在哪个类哪一行"。

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    /** 在同级 box 里找指定类型，返回 payload 区间 `[start, end)`；`skip` 用于跳过固定字段 */
    private fun child(b: ByteArray, start: Int, end: Int, type: String, skip: Int = 0): IntArray? {
        var p = start
        while (p + 8 <= end) {
            val size = u32(b, p)
            if (size < 8 || p + size > end) return null
            if (String(b, p + 4, 4, Charsets.US_ASCII) == type) {
                return intArrayOf(p + 8 + skip, p + size)
            }
            p += size
        }
        return null
    }

    /**
     * 抠出第一条视频轨的 codec 配置 box。
     *
     * 路径：`moov/trak/mdia/minf/stbl/stsd/<sampleEntry>/hvcC|avcC|av1C`
     *
     * `VisualSampleEntry`（`hvc1`/`avc1` 这些）在 8 字节 box 头之后还有 **78 字节固定字段**
     * （reserved 6 + data_reference_index 2 + 16 + 宽高 4 + 分辨率 8 + reserved 4 +
     * frame_count 2 + compressorname 32 + depth 2 + pre_defined 2），
     * 子 box 从第 86 字节才开始 —— 少了这 78 就会把尺寸字段当成 box size 去解析。
     */
    private fun extractCodecConfig(file: File): Pair<String, ByteArray>? {
        val b = file.readBytes()
        val moov = child(b, 0, b.size, "moov") ?: return null

        var p = moov[0]
        while (p + 8 <= moov[1]) {
            val size = u32(b, p)
            if (size < 8) return null
            if (String(b, p + 4, 4, Charsets.US_ASCII) == "trak") {
                configInTrak(b, p + 8, p + size)?.let { return it }
            }
            p += size
        }
        return null
    }

    /** 在一条 `trak` 里找 `mdia/minf/stbl/stsd/<sampleEntry>/<codecConfig>` */
    private fun configInTrak(b: ByteArray, start: Int, end: Int): Pair<String, ByteArray>? {
        val mdia = child(b, start, end, "mdia") ?: return null
        val minf = child(b, mdia[0], mdia[1], "minf") ?: return null
        val stbl = child(b, minf[0], minf[1], "stbl") ?: return null
        val stsd = child(b, stbl[0], stbl[1], "stsd") ?: return null

        // stsd: version+flags(4) + entry_count(4)，之后才是 sample entry
        val entriesStart = stsd[0] + 8
        for (entryType in listOf("hvc1", "hev1", "avc1", "avc3", "av01")) {
            val entry = child(b, entriesStart, stsd[1], entryType) ?: continue
            val kidsStart = entry[0] + 78
            for (cfg in listOf("hvcC", "avcC", "av1C")) {
                val c = child(b, kidsStart, entry[1], cfg) ?: continue
                return cfg to b.copyOfRange(c[0], c[1])
            }
        }
        return null
    }

    private fun dumpConfig(type: String, payload: ByteArray): String = when (type) {
        "hvcC" -> HevcConfig.parse(ParsableByteArray(payload)).let {
            "nalUnitLength=${it.nalUnitLengthFieldLength} " +
                "${it.width}x${it.height} codecs=${it.codecs} " +
                "initializationData=${it.initializationData.size} 条"
        }

        "avcC" -> AvcConfig.parse(ParsableByteArray(payload)).let {
            "nalUnitLength=${it.nalUnitLengthFieldLength} " +
                "${it.width}x${it.height} codecs=${it.codecs} " +
                "initializationData=${it.initializationData.size} 条"
        }

        else -> "（$type 不解析）"
    }

    @Test
    fun parseCapturedCodecConfig() {
        val dir = File("F:/WorkBuddy/BiliBiliTv/tmp/samples")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4s") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) {
            println("[跳过] 没有样本。先跑：python tools/probe_mp4.py <bvid> <cid> --save ./tmp/samples --full")
            return
        }

        println("=".repeat(90))
        println("定点复现：直接把流里的 hvcC / avcC 喂给解析器（media3 版本见 app/build.gradle.kts）")
        println("=".repeat(90))

        var crashed = 0
        for (file in files) {
            val got = extractCodecConfig(file)
            if (got == null) {
                println("  跳过  ${file.name}  —— 没找到 codec 配置 box")
                continue
            }
            val (type, payload) = got
            try {
                println("  OK    ${file.name}")
                println("        $type ${payload.size}B -> ${dumpConfig(type, payload)}")
            } catch (e: Throwable) {
                crashed++
                println("  崩溃  ${file.name}")
                println("        $type ${payload.size}B")
                println("        ${e.javaClass.name}: ${e.message}")
                var c: Throwable? = e
                var depth = 0
                while (c != null && depth < 5) {
                    c.stackTrace.take(5).forEach { f ->
                        println("            at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})")
                    }
                    c = c.cause
                    depth++
                    if (c != null) println("          ←")
                }
            }
        }

        println()
        println("-".repeat(90))
        println("结果：$crashed 次崩溃 / ${files.size} 个样本")
        println("=".repeat(90))
    }
}

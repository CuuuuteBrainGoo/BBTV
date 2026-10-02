package top.bilitv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.bilitv.data.model.FeedItem
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import androidx.compose.foundation.layout.fillMaxHeight

/*
 * ════════════════════════════════════════════════════════════════════════════
 * 视频卡片 —— 按 `chinasoul.bt` 的实测布局重做
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## 和上一版的根本差别
 *
 * 上一版是"卡片式"：每张卡有一块 `surface` 底色 + 圆角 + 描边，像一张张卡片。
 * BT 不是这样 —— 它**没有卡片底**，缩略图直接贴在页面背景上，
 * 信息靠"图 + 压在图上的小药丸 + 图下方的两行字"表达。
 *
 * 差别不在好不好看，在**信息密度**：去掉卡片底和内外边距，同样一屏能多放一列。
 * 在 960×540dp 的画布上，BT 一屏是 4 列 × 2 行 = 8 张；
 * 上一版的 `GridCells.Adaptive(380.dp)` 只算出 **2 列**，一屏 4 张。
 *
 * ## 焦点态
 *
 * 垫一层主色 + 描边（走 `focusRing`）。放大倍率来自皮肤 `focusScale`（1.03）——
 * `docs/31` §5.1 定的值，4 列 / 20dp 列距下不会压到邻居。
 *
 * ## 尺寸
 *
 * 字号见下方常量；列距 / 行距 / 圆角 / 文字区高度全部走皮肤（`Theme.kt`），本文件不写死。
 *
 * ## 为什么文字区高度写死
 *
 * 标题一行还是两行、UP 主名长短，都会让卡片高度不一样；
 * 网格里高度不齐会一眼看出来。BT 的做法是**标题占满两行的位置、
 * UP 主那一行钉在文字区底部**。这里照做。
 */

/** 卡片标题字号（对齐 BT 实测 18sp）。 */
// ★ 2026-09-30 少爷拍板：18 -> 16sp（BT 实测 17sp，他要"封面再大一些"，所以取 16 省高度）
private val CardTitleSize = 16.sp

/** 标题行高。必须跟字号一起改（`docs/99` §E：行高独立，只改 fontSize 会叠字）。 */
private val TITLE_LINE_HEIGHT = 22.sp

/**
 * 标题框固定高度 = 两行 **+ 4dp 余量**。
 * 这是"同排卡片 UP 主行对齐"的唯一保证（高度不随实际行数变）。
 *
 * ## ⛔ 为什么是 52 不是 48（2026-09-29 实机踩坑）
 *
 * 算术上 `2 × 行高 24sp = 48dp`，看着够。**但那是错的**：字体自带
 * `includeFontPadding`（ascent/descent 超出行盒的部分），两行实际占的高度**大于** 48dp
 * → 盒子装不下第二行 → `maxLines = 2` 形同虚设。
 *
 * **实测症状**（不是猜的）：标题第一行只排了约 8 个字就出省略号，
 * 而正常应该先排满一行再换行 —— 说明布局判定"只放得下 1 行"，
 * 于是把省略号加在了唯一那一行末尾。
 *
 * 这个坑的恶劣之处：**不报错、不崩溃、单测全绿、编译 0 警告**，
 * 只有真机上肉眼看标题才知道。**我和设计师**都按 48 算过一遍，都漏了 padding。
 *
 * 留 4dp 余量而不是精确卡 48：字体在不同设备上 metrics 有差异，
 * 卡死会在某些机器上重新退化成 1 行 —— 那个代价比多 4dp 高得多。
 */
// 随字号同步收：2 × 22sp + 4dp 余量。⛔ 这个数**必须跟着 TITLE_LINE_HEIGHT 一起改**，
// 理由见下面那段"52 不是 48"的踩坑记录（少 4dp 就会退化成 1 行 + 省略号）。
private val TITLE_BOX_HEIGHT = 48.dp

/** 卡片元信息（UP 主 / 日期）字号（BT 实测 28px @2x = 14sp）。 */
private val MetaSize = 14.sp

/**
 * 一张视频卡片。
 *
 * @param badge 缩略图左上角的角标（如「大会员」「独家」）。null 表示不显示。
 * @param focusRequester 非空表示"这张是页面默认焦点的落点"。只有一张卡会拿到它。
 * @param onFocused 焦点变化回调，用来记"上次焦点在哪"，返回时好落回原处。
 */
@Composable
fun FeedCard(
    item: FeedItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 角标。**默认从数据里取**（`item.badge`），所以六处调用点都不用改 ——
     * 这正是把它做成默认值而不是必填参数的原因（见 FeedItem.badge 的说明）。
     */
    badge: String? = item.badge.ifBlank { null },
    focusRequester: FocusRequester? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    /**
     * **观看进度**，0~1。null = 不画。
     *
     * 2026-09-30 少爷要求：「历史里的视频应该能读到播放进度，在视频卡上做进度条」。
     * 数据本来就有（`HistoryEntry.fraction`），只是从来没画出来过。
     *
     * 画在**缩略图最下沿**（不是卡片底部）—— 那是所有视频网站的共同位置，
     * 用户扫一眼就知道"这条我看到哪儿了"。
     */
    progress: Float? = null,
    /**
     * **管理模式的外观**：缩略图蒙一层暗罩 + 右上角一个主色「✕」。
     *
     * 2026-09-30 历史页补回单条删除时加的。语义是"现在按 OK 会删掉这张卡"，
     * 真正的删除动作在调用方（历史页）。
     *
     * ## 为什么这个状态必须**看得见**
     *
     * 管理模式里 OK 是**破坏性操作**，和正常模式下"点开视频"完全相反。
     * 如果屏幕上没有明显区别，用户会以为自己在正常模式，顺手一点就删了 ——
     * 这是"破坏性操作必须可预告"的最基本要求。
     *
     * 所以两层提示一起给：**卡片蒙罩（局部）+ 页面头部一句「管理模式：按 OK 删除这一条」（全局）**。
     * 只给其中一层都不够 —— 蒙罩说不清"按下去会怎样"，头部文案在滚动后可能看不见。
     */
    manageMode: Boolean = false,
) {
    val theme = AppTheme.current
    val context = LocalContext.current

    val desc = buildString {
        append(item.title)
        if (!badge.isNullOrBlank()) append("，$badge")
        if (item.ownerName.isNotBlank()) append("，${item.ownerName}")
        if (item.viewCount > 0) append("，${formatCount(item.viewCount)}播放")
        if (item.durationSec > 0) append("，${formatDuration(item.durationSec)}")
    }

    Column(
        modifier = modifier
            // ★ 必须在 focusRing **之前**：requester 挂到"它后面最近的焦点目标"（focusRing 里的
            //   clickable）。放后面就永远挂不上，requestFocus() 会一直抛异常。
            //   2026-09-29 此参数曾被漏接进链里 —— 编译器不吭声，"自动给第一张卡焦点"整条静默失效。
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            // 焦点链已收编进 focusRing（`docs/audit/A9` §7.3）。
            // onFocused 用来记"上次焦点在哪"，返回时好落回原处。
            //
            // elevateOnFocus：焦点描边+垫色会压到相邻卡片，必须抬到最上层。
            // 不放大（密排网格放大 = 卡片互相撞），所以 scaleOnFocus 保持默认 1f。
            .observeFocus { onFocused?.invoke(it) }
            .focusRing(contentDescription = desc, elevateOnFocus = true, onClick = onClick)
            .padding(FOCUS_RING_INSET),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(theme.cardCorner - FOCUS_RING_INSET)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.cover.fixedScheme())
                    // 按实际显示尺寸解码，绝不原图解码（低内存的生命线）
                    .size(480, 270)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            /*
             * 底部渐变压暗 —— 角标去掉底框之后的**可读性兜底**。
             * 和 BT 一样：没有小药丸，但底部有一层渐变。缺少它，白字压在亮封面上等于看不见。
             */
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xA6000000)),
                        )
                    ),
            )

            if (!badge.isNullOrBlank()) {
                ContentBadge(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }

            // 右下角：时长
            if (item.durationSec > 0) {
                Pill(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    background = Color(0xA60A0A10),
                    text = formatDuration(item.durationSec),
                )
            }

            /*
             * 观看进度条（只有传了 progress 才画）。
             *
             * 用 `Box` + `fillMaxWidth(fraction)` 而不是 Canvas：**不用自己算像素**，
             * 而且 fraction 自动被 coerce 到 0~1，传进来的脏数据不会画出界。
             * 高度 3dp —— 再细在 3 米外看不见，再粗会盖住缩略图内容。
             */
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(theme.primary),
                    )
                }
            }

            // 左下角：播放量 + 弹幕数
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.viewCount > 0) {
                    Pill(background = Color(0xA60A0A10), text = formatCount(item.viewCount)) {
                        PlayGlyph()
                    }
                }
                if (item.danmakuCount > 0) {
                    Pill(background = Color(0xA60A0A10), text = formatCount(item.danmakuCount)) {
                        DanmakuGlyph()
                    }
                }
            }

            /*
             * 管理模式的蒙罩 + ✕。**画在最后**（= 盖在上面），
             * 这样连右下角的时长药丸、左下角的播放量也一起被压暗 ——
             * 整张卡都在"即将被删"的状态里，视觉上没有歧义。
             */
            if (manageMode) {
                Box(modifier = Modifier.matchParentSize().background(ManageScrim))
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(theme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        style = TextStyle(fontSize = AppType.Body3, fontWeight = FontWeight.Bold),
                        color = theme.onPrimary,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(theme.cardTextHeight)
                .padding(top = 6.dp),
        ) {
            /*
             * ★ 标题固定占两行高（`TITLE_BOX_HEIGHT`）+ 去掉弹性 Spacer。
             *
             * 审计配方说"删掉 `Spacer(weight(1f))`"（它造 21~34dp 死空白），但那句**不能
             * 照字面执行**：直接删会让单行标题把 UP 主行顶上去，同排卡片的 UP 主行就错位了
             * （比死空白难看得多）。改成"标题占两行高"——位置恒定，空白也没了。
             *
             * ## ⛔ 2026-09-29 实机踩坑：光"占两行高"还不够，必须同时**裁掉字体自带留白**
             *
             * `TITLE_BOX_HEIGHT = 48.dp` 是按 `2 × 行高 24sp = 48dp` 算的。
             * **但 Android 的字体默认带 `includeFontPadding`**（ascent/descent 超出行盒的那部分），
             * 两行实际要的高度**大于** 48dp → 盒子装不下第二行 → `maxLines=2` 形同虚设，
             * **所有标题被静默压成一行**（长标题只剩省略号）。
             *
             * 这个坑的恶劣之处：**不报错、不崩溃、单测全绿**，只有真机上肉眼看标题才知道。
             * 我和设计师都按 48dp 算过一遍、都漏了 padding —— 是前端实测抓出来的。
             *
             * 修法选「**让两行真的等于 48dp**」而不是「把盒子改大」：
             * `LineHeightStyle.Trim.Both` 裁掉首行顶部 / 末行底部的字体留白，
             * 于是 2 行 = 2 × 24sp = 48dp 精确吻合 —— **设计师那份 48dp 的规范不用改**，
             * 卡片总高也不用长（少爷正嫌首屏卡片太少，卡片不能被撑大）。
             */
            Text(
                text = item.title,
                style = TextStyle(
                    fontSize = CardTitleSize,
                    fontWeight = FontWeight.Medium,
                    lineHeight = TITLE_LINE_HEIGHT,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
                color = theme.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(TITLE_BOX_HEIGHT),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.ownerName,
                    style = TextStyle(fontSize = MetaSize),
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val date = formatPubDate(item.pubDateSec)
                if (date.isNotBlank()) {
                    Text(
                        text = date,
                        style = TextStyle(fontSize = MetaSize),
                        color = theme.textTertiary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 焦点描边内侧留的余量。
 *
 * 不留的话描边会被缩略图压住看不见 —— 描边是画在 `border()` 那一层的，
 * 内容会盖在上面。留 3dp 让主色圈露出来，这也是 BT 的样子。
 */
private val FOCUS_RING_INSET = 3.dp

/** 顶部身份角标保留主色底；底部播放量／时长仍为无底的白字。 */
@Composable
fun ContentBadge(text: String, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    Text(text, color = theme.onPrimary, style = TextStyle(fontSize = AppType.Caption, fontWeight = FontWeight.Medium),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(3.dp)).background(theme.primary).padding(horizontal = 6.dp, vertical = 3.dp))
}

/**
 * 管理模式下盖在缩略图上的暗罩。
 *
 * 60% 黑是试出来的：再浅了"这张卡处于另一种状态"看不出来，
 * 再深了缩略图内容完全消失、用户认不出自己要删的是哪一条。
 */
private val ManageScrim = Color(0x99000000)

/** 缩略图上的小药丸：半透明黑底 + 白字，可选前置一个小图标 */
@Composable
private fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color(0xA60A0A10),
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        /*
         * ★ 2026-09-30 少爷：「底部渲染的半透明底框不一样大，这个半透明底框不要了」。
         *
         * 所以这里**不再 clip / background / padding** —— 角标就是"裸白字 + 小图标"，
         * 和 BT 一致。⛔ 代价是亮色封面上会看不清，**由封面底部那层渐变压暗兜住**
         * （见 `ThumbScrim`），两层要一起存在，不能只删不加。
         */
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = TextStyle(fontSize = AppType.Caption, fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/*
 * 两个 11dp 的小图标，用 Canvas 画而不是引图标库。
 *
 * 理由：`material-icons-core` 里只有 PlayArrow，没有弹幕气泡；
 * 为一个小图标引 `material-icons-extended`（几千个矢量图，包体和内存都涨），
 * 在低内存电视上不划算。这两个图形简单到直接画更省。
 */

/** 播放三角 */
@Composable
private fun PlayGlyph() {
    Canvas(modifier = Modifier.size(8.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, Color.White)
    }
}

/** 弹幕气泡：圆角矩形 + 左下角的小尾巴 */
@Composable
private fun DanmakuGlyph() {
    Canvas(modifier = Modifier.size(9.dp)) {
        val r = size.height * 0.25f
        val bodyH = size.height * 0.72f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, 0f),
            size = Size(size.width, bodyH),
            cornerRadius = CornerRadius(r, r),
        )
        val tail = Path().apply {
            moveTo(size.width * 0.22f, bodyH)
            lineTo(size.width * 0.22f, size.height)
            lineTo(size.width * 0.52f, bodyH)
            close()
        }
        drawPath(tail, Color.White)
    }
}

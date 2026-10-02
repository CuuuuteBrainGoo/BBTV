package top.bilitv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.bilitv.data.settings.ThemeSkin

/**
 * 一套皮肤的**全部数值**。
 *
 * ## 为什么要有这个类
 *
 * 少爷要「以 B 为主，A 做成可换皮肤」。做法不是写两套界面，而是：
 * **把两套界面之间所有会变的东西，全部提成这个类的字段**，组件只读字段、不做分支。
 * 于是"换皮肤"= 换一个构造好的 [BiliTheme] 实例，一行 `if` 都不用写。
 *
 * 这条约束很硬：**组件里一旦出现 `if (皮肤 == ...)`，这套设计就失败了**。
 * 所以下面每个字段都必须是"直接能用"的最终值，而不是"需要组件再加工一下"的原料
 * —— 比如侧栏选中态，给的是一对 `navSelectedFill` / `navSelectedText`，
 * 而不是一个 `usePrimaryAsFill: Boolean` 让组件自己去拼。
 *
 * **能**做成皮肤的是"数值"（颜色、间距、圆角、列数、焦点缩放）；
 * **不能**的是"结构"（有没有侧栏、卡片横竖、有没有 Banner）—— 结构变了得另写一套界面。
 *
 * @param skin 身份标识兼持久化键，见 [ThemeSkin]。
 * @param background 窗口最底层。比 [surface] 暗一档，让卡片"浮"起来。
 * @param surface 卡片面。新卡片布局里几乎用不到（BT 是"缩略图直贴页面"），留给设置页那种面板。
 * @param surfaceHigh 抬起的表面：菜单、面板、侧栏选中项。
 * @param primary 品牌主色。**用法两套不同**：影院只给焦点，经典大面积铺。
 * @param onPrimary 主色上的文字色。
 * @param focusRing 焦点描边色。电视上"看不见焦点"是致命伤（`docs/03` §2.4），必须和背景拉开明度。
 * @param focusFill 焦点态的填充色（半透明主色）：照 BT，焦点卡不是"放大"，是"整块垫主色 + 描边"。
 *   半透明而非不透明 —— 卡片底下是缩略图，不透就把图盖掉了。
 * @param focusSurface 焦点态卡片底色，给**带底板**的旧组件（设置页那种）用。
 * @param textPrimary 标题、可读正文。
 * @param textSecondary 副标题、元信息（UP 主 / 播放量）。
 * @param textTertiary 更弱的提示（时间码、占位说明）。
 * @param divider 分隔线，也用作**未聚焦卡片**的描边。
 * @param cardColumns **一屏几列，是"内容密度"的唯一开关。**
 *   ⚠️ 2026-09-29 从 `cardMinWidth`（自适应）改成固定列数：自适应在 960dp 画布上只算出 2 列，
 *   两张巨型卡片，和 BT 差得远。BT 就是固定列数（960×540dp 上 4 列、每列 206dp），照做。
 * @param screenPadding 页面左右留白。BT 实测只有 13dp，很紧。
 * @param cardGap 同一行内卡片之间的**横向**距离。
 * @param rowGap 网格行之间的**纵向**距离。比 [cardGap] 小（BT 实测 20 : 10）。
 * @param sectionGap 两个内容分区之间的纵向距离。
 * @param cardCorner 卡片圆角。
 * @param cardTextHeight 卡片**文字区的固定高度**。必须写死：标题一行还是两行会让卡片高度不一致、
 *   网格参差不齐；BT 的做法是把 UP 主那行钉在文字区底部，标题留两行的位置。
 * @param focusScale 焦点放大倍率。**两套皮肤都是 1.03**（`docs/31` §5.1）—— 4 列 / 20dp 列距下
 *   试出来的上限，再大就压到邻居。垫主色 + 描边已足够显眼；孤立小控件可显式传更大的值。
 * @param focusBorderWidth 焦点描边粗细。
 * @param navWidth 侧栏宽度。
 */
data class BiliTheme(
    val skin: ThemeSkin,

    // ------------------------------------------------------------ 底色阶梯

    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,

    // ------------------------------------------------------------ 主题色

    val primary: Color,
    val onPrimary: Color,
    val focusRing: Color,
    val focusFill: Color,
    val focusSurface: Color,

    // ------------------------------------------------------------ 文字

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,

    // ------------------------------------------------------------ 侧栏

    val navBackground: Color,
    val navText: Color,
    val navSelectedFill: Color,
    val navSelectedText: Color,

    // ------------------------------------------------------------ 布局密度

    val cardColumns: Int,
    val screenPadding: Dp,
    val cardGap: Dp,
    val rowGap: Dp,
    val sectionGap: Dp,
    val cardCorner: Dp,
    val cardTextHeight: Dp,
    val focusScale: Float,
    val focusBorderWidth: Dp,
    val navWidth: Dp,

    /** 内容区顶部子标签栏（推荐 / 热门 / 每周必看）的高度。 */
    val tabBarHeight: Dp,

    /**
     * **首页**主视觉高度。★ 2026-09-29 定 **160dp**（原 260dp）。
     *
     * 少爷在真机截图上手绘标注：
     * > **这个头部视频区域太大了** …… **左边查看详情按钮不要了**，**标题靠下**，
     * > **从左下做个透明过渡**
     *
     * 四条里三条是在把这个区域**变小**。去掉按钮之后，文字块只剩「标题 + 元信息」，
     * 刚性高度从约 240dp 掉到约 143dp —— 160dp 就有富余了。
     * 不继续压到 143dp：文字之下要留一点"图还在继续"的余地，
     * 贴着字切会让主视觉像被裁坏；而且主视觉得能看出是一张**图**，
     * 只剩一条缝就不成立了。
     *
     * 视口是**实测**的（1080p / density 2.0，uiautomator 量 + 截图核）：
     * ```
     * 网格视口 = 540dp（屏）− 58dp（子标签栏）= 482dp
     *   58 = tabBarHeight(40) + HomeScreen 的 padding(top 8 + bottom 10)
     *   ⚠️ 482 已在子标签栏**之下**，别再减一次 —— `482 − 48 = 434` 是重复扣减。
     * 160dp 时首行卡顶 = 58 + 160 + 10 = 228dp
     *   → 首行（约 209dp）完整可见，第 2 行露出大半（这才是"首屏效率"）
     * ```
     *
     * ⚠️ **它和 [detailHeaderHeight] 是两个字段，不要合并**：详情页页头**有「播放」按钮**，
     * 比首页多一整块内容。原来合成一个字段的前提是"两处内容结构相同"——
     * 少爷去掉首页按钮之后，这个前提就不成立了。
     */
    val heroHeight: Dp,

    /**
     * **详情页**页头高度。**280dp**（原 208dp，2026-09-30 实测不够，改大）。
     *
     * 详情页页头**必须有主操作**（「播放」）—— 用户进来第一件想做的事就是开始看，
     * 让他先找半天按钮是反的。
     *
     * ## 算式（⛔ 原注释少算了一项，实测踩到）
     *
     * 页头文字块是 `Arrangement.spacedBy(10.dp)` 的 **3 个子项**（标题 / 元信息 / 按钮），
     * 所以有 **2 个** 10dp 间距，不是 1 个：
     * ```
     * 标题 2 行 92 + 间距 10 + 元信息 21 + 间距 10 + 按钮上间距 4 + 按钮 52 = 189dp（内容）
     * 内容 189 + 底部内衬 26 = 215dp        ← 这是"内容刚好不被裁"的下限
     * ```
     * 原注释写成 `92 + 10 + 21 + 4 + 52 + 26 ≈ 205`，**漏了标题和元信息之间那一个 10dp**，
     * 于是定了 208dp —— 实际差 7dp，溢出后标题被顶到页头最上沿。
     *
     * ## ⛔ 真正决定高度的是左上角那颗「返回」按钮
     *
     * 文字块是**左下角对齐**的，而「返回」按钮**浮在左上角**
     * （`screenPadding` 14dp 起、约 48dp 高 → 占掉 `y 14~62dp`）。
     * 标题也是左对齐，所以**它必须从 62dp 以下才开始**，否则两个叠在一起。
     * ```
     * 62（避让「返回」）+ 189（内容）+ 26（底部内衬）= 277dp  → 取整 280dp
     * ```
     * 实测症状（模拟器 960×540dp，`docs/99` §E 同名条）：**标题压在「返回」上**，
     * 两行字和返回按钮糊成一团；同时「播放」按钮的文字被挤到看不清。
     *
     * ## 为什么不是"把标题缩成 1 行"或"把返回挪走"
     *
     * - 详情页的标题**本身就是这一页要看的信息**，缩成 1 行会把大半标题砍掉；
     * - 「返回」浮在主视觉上是常规做法（影视页主视觉同样如此），
     *   挪到内容区里会变成一个和页面脱节的按钮。
     *
     * 280dp 和影视页那份已验证好看的 300dp 主视觉是同一个量级，不是新发明一个数。
     */
    val detailHeaderHeight: Dp,
)

/**
 * B · 影院 / 内容优先（默认）。
 *
 * 主色 `#FB7299` **只出现在焦点上**，其余全是灰阶 —— 这样一屏里唯一有颜色的东西
 * 就是"你现在在哪"，遥控器用户一眼就能找到自己。
 *
 * 布局数值全部来自 `chinasoul.bt` 的实测（模拟器同为 960×540dp 画布）：
 * 卡片 206.5dp、列距 20、行距 10、内边距 13、文字区 79.5dp。
 */
private val CinemaTheme = BiliTheme(
    skin = ThemeSkin.CINEMA,

    background = Color(0xFF0A0A0C),
    surface = Color(0xFF15151A),
    surfaceHigh = Color(0xFF1E1E25),

    primary = Color(0xFFFB7299),
    onPrimary = Color(0xFF1A1015),
    focusRing = Color(0xFFFB7299),
    focusFill = Color(0x54FB7299),
    focusSurface = Color(0xFF1E1E25),

    textPrimary = Color(0xFFF2F2F5),
    textSecondary = Color(0xA6F2F2F5),
    textTertiary = Color(0x80F2F2F5),
    divider = Color(0x14FFFFFF),

    navBackground = Color(0xFF101014),
    navText = Color(0xB3F2F2F5),
    navSelectedFill = Color(0xFF23232B),
    navSelectedText = Color(0xFFFB7299),

    cardColumns = 4,
    screenPadding = 14.dp,
    cardGap = 20.dp,
    rowGap = 10.dp,
    sectionGap = 26.dp,
    cardCorner = 10.dp,      // 2026-09-30 少爷：圆角小一些（BT 实测 ~6.5dp）
    cardTextHeight = 78.dp,
    focusScale = 1.0f,      // 2026-09-30 少爷：不做放大动画，只做颜色
    focusBorderWidth = 3.dp,
    navWidth = 88.dp,
    tabBarHeight = 32.dp,   // 2026-09-30 少爷「上下空出来的太多了」→ 40 收到 32（BT 实测更矮）
    heroHeight = 160.dp,
    detailHeaderHeight = 280.dp,
)

/**
 * A · 经典 / 贴近官方。
 *
 * 和影院的三处差别：
 * 1. 侧栏选中态从"表面色高亮 + 主色文字"变成"主色填充 + 白字" —— 主色铺开了。
 * 2. 一屏 4 列 → **5 列**，卡片更小更密。
 * 3. 内边距和间距各收窄一档。
 */
private val ClassicTheme = BiliTheme(
    skin = ThemeSkin.CLASSIC,

    background = Color(0xFF14141A),
    surface = Color(0xFF22222B),
    surfaceHigh = Color(0xFF2C2C37),

    primary = Color(0xFFFB7299),
    onPrimary = Color(0xFFFFFFFF),
    focusRing = Color(0xFFFF9DBA),
    focusFill = Color(0x54FB7299),
    focusSurface = Color(0xFF2C2C37),

    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xB3FFFFFF),
    textTertiary = Color(0x80FFFFFF),
    divider = Color(0x1AFFFFFF),

    navBackground = Color(0xFF1B1B22),
    navText = Color(0xCCFFFFFF),
    navSelectedFill = Color(0xFFFB7299),
    navSelectedText = Color(0xFFFFFFFF),

    cardColumns = 5,
    screenPadding = 12.dp,
    cardGap = 14.dp,
    rowGap = 10.dp,
    sectionGap = 22.dp,
    cardCorner = 9.dp,       // 同上
    cardTextHeight = 78.dp,
    focusScale = 1.0f,      // 2026-09-30 少爷：不做放大动画，只做颜色
    focusBorderWidth = 3.dp,
    navWidth = 96.dp,
    tabBarHeight = 32.dp,   // 同上
    heroHeight = 160.dp,
    detailHeaderHeight = 280.dp,
)

/** 皮肤 → 数值。两张表，没有第三处地方需要知道有几种皮肤。 */
fun ThemeSkin.values(): BiliTheme = when (this) {
    ThemeSkin.CINEMA -> CinemaTheme
    ThemeSkin.CLASSIC -> ClassicTheme
    ThemeSkin.PORNHUB -> CinemaTheme.copy(
        skin = this, background = Color(0xFF000000), surface = Color(0xFF161616), surfaceHigh = Color(0xFF252525),
        primary = Color(0xFFFF9900), onPrimary = Color.Black, focusRing = Color(0xFFFFB347),
        focusFill = Color(0x66FF9900), focusSurface = Color(0xFF292015),
        navBackground = Color(0xFF101010), navSelectedFill = Color(0xFFFF9900), navSelectedText = Color.Black)
}

/**
 * 当前皮肤。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：皮肤只在设置页里换，
 * 频率极低；而 static 版本在值变化时会**重组整个 provider 子树**，
 * 这恰好是换肤想要的效果（全部重绘），也省掉了逐读取点追踪的开销。
 */
val LocalBiliTheme = staticCompositionLocalOf { CinemaTheme }

/** 取当前皮肤。写成 `AppTheme.current` 比一路 `LocalBiliTheme.current` 短。 */
object AppTheme {
    val current: BiliTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalBiliTheme.current
}

/**
 * 只做暗色：电视是"暗房间 + 大屏"，亮色主题没有使用场景。
 *
 * 字体整体比手机默认放大一档 —— 10 英尺观看距离下，material3 的默认字号（正文 14sp）
 * 在 3 米外基本看不清。这是电视应用的硬需求，不是审美偏好。
 */
private val TvTypography = Typography(
    headlineSmall = TextStyle(fontSize = AppType.Big, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    titleMedium = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = AppType.Body2, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = AppType.Meta, lineHeight = 18.sp),
)

/**
 * 把皮肤数值映射到 Material3 的颜色槽位。
 *
 * 项目里不少地方直接写 `MaterialTheme.colorScheme.onSurface`；有了这层映射，
 * **那些地方不改代码也会跟着换肤**，新老写法混用不会出现"卡片变了、文字还是旧色"的割裂。
 * 新代码仍应优先读 [AppTheme].current —— 它能表达的比 colorScheme 多
 * （焦点色、侧栏色、间距、圆角这些 Material3 里没有槽位）。
 */
private fun BiliTheme.toColorScheme() = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    secondary = if (skin == ThemeSkin.PORNHUB) primary else Color(0xFF00AEEC),
    background = background,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = textSecondary,
    outline = divider,
)

/**
 * 主题入口。
 *
 * @param skin 当前皮肤。它一变，整棵树重组重绘 —— 这就是"即时换肤"的全部实现。
 */
@Composable
fun BiliTvTheme(
    skin: ThemeSkin = ThemeSkin.CINEMA,
    content: @Composable () -> Unit,
) {
    val theme = skin.values()
    CompositionLocalProvider(LocalBiliTheme provides theme) {
        MaterialTheme(
            colorScheme = theme.toColorScheme(),
            typography = TvTypography,
            content = content,
        )
    }
}

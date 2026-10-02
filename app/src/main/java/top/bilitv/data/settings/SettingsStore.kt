package top.bilitv.data.settings

import android.content.Context
import android.content.SharedPreferences
import top.bilitv.data.sponsor.SponsorCategory

enum class StartupFocus(val label: String) {
    RAIL("侧边栏"), TABS("分区标签"), CONTENT("内容卡片");
    companion object {
        fun fromName(name: String?): StartupFocus = entries.firstOrNull { it.name == name } ?: RAIL
    }
}

/**
 * 应用设置。
 *
 * 用 SharedPreferences 而不是 DataStore：项目里 `CredentialStore` 已经是这个模式，
 * 复用同一套读写习惯，少一个依赖、少一层异步包装。设置项不多，没有性能问题。
 *
 * 默认值原则：
 *  - 弹幕**默认开**（大多数人的预期）
 *  - 广告跳过**默认开** —— ⚠️ 2026-09-29 少爷明确改过。
 *    原来是"默认关"（理由是三款常用客户端都默认关、避免误跳吓到人），
 *    但少爷把广告跳过当成本项目**重点功能**：默认关等于装上没用，得自己进设置打开。
 *    默认开的风险（视频莫名其妙跳一段）用两个补偿动作兜住，见 `PlayerScreen`：
 *    跳过时给一个可撤销的角标；类别默认勾选保持 `SponsorCategory.DEFAULT_SKIP`
 *    那五项（都是社区投票校验过的、误判率最低的类别）。
 *  - 界面广告过滤**默认开**（无副作用，只是少显示几张卡）
 *  - 皮肤**默认影院**（B 案），理由见 `ThemeSkin.CINEMA`
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoLowerQuality: Boolean
        get() = prefs.getBoolean("auto_lower_quality", true)
        set(v) = prefs.edit().putBoolean("auto_lower_quality", v).apply()

    var seekSeconds: Int
        get() = PlaybackTuning.seekSeconds(prefs.getInt("seek_seconds", 10))
        set(v) = prefs.edit().putInt("seek_seconds", PlaybackTuning.seekSeconds(v)).apply()

    var playerUpEnabled: Boolean
        get() = prefs.getBoolean("player_up_enabled", true)
        set(v) = prefs.edit().putBoolean("player_up_enabled", v).apply()
    var playerDownEnabled: Boolean
        get() = prefs.getBoolean("player_down_enabled", true)
        set(v) = prefs.edit().putBoolean("player_down_enabled", v).apply()
    var playerUpAction: PlaybackTuning.SideAction
        get() = PlaybackTuning.SideAction.of(prefs.getString("player_up_action", null), PlaybackTuning.SideAction.RECOMMEND)
        set(v) = prefs.edit().putString("player_up_action", v.name).apply()
    var playerDownAction: PlaybackTuning.SideAction
        get() = PlaybackTuning.SideAction.of(prefs.getString("player_down_action", null), PlaybackTuning.SideAction.CATALOGUE)
        set(v) = prefs.edit().putString("player_down_action", v.name).apply()

    // ---------------------------------------------------------------- 弹幕

    var danmakuEnabled: Boolean
        get() = prefs.getBoolean(KEY_DM_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_DM_ON, v).apply()

    /*
     * 下面四个弹幕细项的**边界与默认值全部来自 [DanmakuTuning]**，这里不再各写一遍数字。
     * 播放页的弹幕面板和设置页的高级模式共用同一套档位 —— 边界值散在三处时，
     * 改一处忘两处的表现是"同一个设置在两个页面按一下效果不一样"。
     */

    /** 不透明度 0.1 ~ 1.0 */
    var danmakuAlpha: Float
        get() = DanmakuTuning.clampAlpha(prefs.getFloat(KEY_DM_ALPHA, DanmakuTuning.DEF_ALPHA))
        set(v) = prefs.edit().putFloat(KEY_DM_ALPHA, DanmakuTuning.clampAlpha(v)).apply()

    /** 字号倍率 0.5 ~ 2.0 */
    var danmakuScale: Float
        get() = DanmakuTuning.clampScale(prefs.getFloat(KEY_DM_SCALE, DanmakuTuning.DEF_SCALE))
        set(v) = prefs.edit().putFloat(KEY_DM_SCALE, DanmakuTuning.clampScale(v)).apply()

    /** 弹幕显示行数上限；`0` = 不限，超过上限就丢新弹幕（防止刷屏弹幕把画面糊死） */
    var danmakuMaxLines: Int
        get() = if (prefs.getInt("danmaku_area_schema", 0) < 2) 0
            else DanmakuTuning.clampLines(prefs.getInt(KEY_DM_LINES, DanmakuTuning.DEF_LINES))
        set(v) = prefs.edit().putInt("danmaku_area_schema", 2).putInt(KEY_DM_LINES, DanmakuTuning.clampLines(v)).apply()

    /** 滚动弹幕横穿屏幕所需时长（毫秒）。越大越慢 */
    var danmakuDurationMs: Long
        get() = DanmakuTuning.clampSpeed(prefs.getLong(KEY_DM_DURATION, DanmakuTuning.DEF_SPEED_MS))
        set(v) = prefs.edit().putLong(KEY_DM_DURATION, DanmakuTuning.clampSpeed(v)).apply()

    /**
     * 去重合并：同一句话在屏幕上只占一条，重复的并进去计数（`前方高能 ×12`）。
     *
     * 默认开。对应 chinasoul.bt 的 `Hide duplicate danmaku` + `Danmaku merge` 两项
     * （`docs/11` §6.3）—— 我们合成一个开关，因为它俩要解决的其实是同一件事：
     * **复读弹幕把画面糊死**。
     *
     * 注意这和 `docs/10` 的"弹幕流畅性"是两码事：那个管的是**动得顺不顺**，
     * 这个管的是**屏幕上同时有多少条**。
     */
    var danmakuMerge: Boolean
        get() = prefs.getBoolean(KEY_DM_MERGE, true)
        set(v) = prefs.edit().putBoolean(KEY_DM_MERGE, v).apply()

    var danmakuFilter: top.bilitv.data.danmaku.DanmakuFilterOptions
        get() = top.bilitv.data.danmaku.DanmakuFilterOptions(
            level = prefs.getInt("danmaku_level", 0).coerceIn(0, 5),
            allowScroll = prefs.getBoolean("danmaku_scroll", true),
            allowReverse = prefs.getBoolean("danmaku_reverse", true),
            allowTop = prefs.getBoolean("danmaku_top", true),
            allowBottom = prefs.getBoolean("danmaku_bottom", true),
            allowColor = prefs.getBoolean("danmaku_color", true),
            allowAdvanced = prefs.getBoolean("danmaku_advanced", false),
            cloud = prefs.getBoolean("danmaku_cloud", false),
            allowInteraction = prefs.getBoolean("danmaku_interaction", true),
        )
        set(v) = prefs.edit().putInt("danmaku_level", v.level.coerceIn(0, 5))
            .putBoolean("danmaku_scroll", v.allowScroll).putBoolean("danmaku_reverse", v.allowReverse)
            .putBoolean("danmaku_top", v.allowTop).putBoolean("danmaku_bottom", v.allowBottom)
            .putBoolean("danmaku_color", v.allowColor).putBoolean("danmaku_advanced", v.allowAdvanced)
            .putBoolean("danmaku_cloud", v.cloud).putBoolean("danmaku_interaction", v.allowInteraction).apply()

    var danmakuArea: Int
        get() = prefs.getInt("danmaku_area_fifths", DanmakuTuning.migrateArea(prefs.getInt("danmaku_area", 8))).coerceIn(1, 5)
        set(v) = prefs.edit().putInt("danmaku_area_fifths", v.coerceIn(1, 5)).apply()
    var danmakuOverlap: Boolean
        get() = prefs.getBoolean("danmaku_overlap", false)
        set(v) = prefs.edit().putBoolean("danmaku_overlap", v).apply()
    var danmakuOutline: Float
        get() = prefs.getFloat("danmaku_outline", 2f).coerceIn(1.2f, 5f)
        set(v) = prefs.edit().putFloat("danmaku_outline", v.coerceIn(1.2f, 5f)).apply()
    var danmakuOutlineAlpha: Int
        get() = prefs.getInt("danmaku_outline_alpha", 180).coerceIn(120, 220)
        set(v) = prefs.edit().putInt("danmaku_outline_alpha", v.coerceIn(120, 220)).apply()
    var danmakuTrackHeight: Float
        get() = prefs.getFloat("danmaku_track_height", 1.4f).coerceIn(1f, 2.2f)
        set(v) = prefs.edit().putFloat("danmaku_track_height", v.coerceIn(1f, 2.2f)).apply()
    var danmakuHideRepeated: Boolean
        get() = prefs.getBoolean("danmaku_hide_repeated", false)
        set(v) = prefs.edit().putBoolean("danmaku_hide_repeated", v).apply()
    var danmakuKeywords: List<String>
        get() = prefs.getString("danmaku_keywords", "").orEmpty().lines().filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("danmaku_keywords", v.take(128).joinToString("\n") { it.take(256) }).apply()
    var danmakuRegexes: List<String>
        get() = prefs.getString("danmaku_regexes", "").orEmpty().lines().filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("danmaku_regexes", v.take(32).joinToString("\n") { it.take(256) }).apply()
    var danmakuBlockedUsers: Set<String>
        get() = prefs.getString("danmaku_blocked_users", "").orEmpty().lines().filter { it.isNotBlank() }.toSet()
        set(v) = prefs.edit().putString("danmaku_blocked_users", v.take(512).joinToString("\n")).apply()

    var subtitleEnabled: Boolean
        get() = prefs.getBoolean("subtitle_on", false)
        set(v) = prefs.edit().putBoolean("subtitle_on", v).apply()
    var subtitleLanguage: String
        get() = prefs.getString("subtitle_language", "").orEmpty()
        set(v) = prefs.edit().putString("subtitle_language", v.take(64)).apply()
    var subtitleStyle: SubtitleStyle
        get() = SubtitleStyle(
            prefs.getInt("subtitle_font", 2).coerceIn(0, 4), prefs.getInt("subtitle_color", 0).coerceIn(0, 7),
            prefs.getInt("subtitle_position", 0).coerceIn(0, 6), prefs.getInt("subtitle_background", 60).coerceIn(0, 100),
            prefs.getBoolean("subtitle_fade", false), prefs.getInt("subtitle_x", 50).coerceIn(5, 95),
            prefs.getInt("subtitle_y", 90).coerceIn(5, 95))
        set(v) = prefs.edit().putInt("subtitle_font", v.font.coerceIn(0, 4)).putInt("subtitle_color", v.color.coerceIn(0, 7))
            .putInt("subtitle_position", v.position.coerceIn(0, 6)).putInt("subtitle_background", v.background.coerceIn(0, 100))
            .putBoolean("subtitle_fade", v.fade).putInt("subtitle_x", v.x.coerceIn(5, 95)).putInt("subtitle_y", v.y.coerceIn(5, 95)).apply()

    /** 只重置弹幕／字幕，不清登录、播放默认值、首页或观看记录。 */
    fun resetDanmakuPage() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("danmaku_") || it.startsWith("subtitle_") }.forEach(edit::remove)
        edit.apply()
    }

    // ---------------------------------------------------------------- 广告

    /** 总开关，默认**开**（少爷 2026-09-29 定；改动的理由见类注释） */
    var sponsorEnabled: Boolean
        get() = prefs.getBoolean(KEY_SB_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_SB_ON, v).apply()

    /** 勾选要自动跳过的类别 */
    var sponsorCategories: Set<SponsorCategory>
        get() {
            val raw = prefs.getString(KEY_SB_CATS, null)
                ?: return SponsorCategory.DEFAULT_SKIP
            if (raw.isBlank()) return emptySet()
            val ids = raw.split(',').toSet()
            return SponsorCategory.entries.filter { it.id in ids }.toSet()
        }
        set(v) = prefs.edit().putString(KEY_SB_CATS, v.joinToString(",") { it.id }).apply()

    /** 界面层广告卡过滤，默认开 */
    /**
     * **首页分区的顺序与显示** —— 一个有序的分区 id 名单。
     *
     * ## 为什么是一个「名单」而不是一堆开关
     *
     * 逆向 BT 学到的模型（`docs/33` §四）：**顺序本身就是"显示/隐藏"** ——
     * 不在名单里就是不显示。所以一份 `List<String>` 同时表达了顺序和显隐两件事，
     * 不需要 `List<{id, enabled, order}>` 那种三字段结构（多一个字段就多一处能写错）。
     *
     * ## 存的为什么是 id 而不是名字
     *
     * 名字是给人看的，随时可能改（"运动"→"体育"之类）；id 是给代码认的，改了就等于
     * 让所有用户的配置失效。**存 id、显示名字**是唯一稳的做法。
     *
     * ⚠️ 空值/认不出的 id 由 [top.bilitv.ui.home.HomeSection.parse] 兜底 ——
     * 这里不重复那套逻辑，只负责"存和取"。
     */
    var homeSections: List<String>
        get() {
            val raw = prefs.getString(KEY_HOME_SECTIONS, null) ?: return emptyList()
            return if (raw.isBlank()) emptyList() else raw.split(',').filter { it.isNotBlank() }
        }
        set(v) = prefs.edit().putString(KEY_HOME_SECTIONS, v.joinToString(",")).apply()

    /**
     * **侧栏显示哪些项、什么顺序** —— 和 [homeSections] 同一个模型（有序 id 名单）。
     *
     * 少爷 2026-09-29 第 8 条：「逆向学习 BT 的**自定义配置**，不只首页，
     * **侧栏 / 播放器控制栏也能配**」。
     *
     * 存的是 [top.bilitv.ui.NavTab] 的**枚举名**（`"HOME"` / `"CINEMA"` …），
     * 不是序号 —— 序号会在枚举插项时整体错位（和 `themeSkin` 存字符串 id 同一个理由）。
     *
     * ⚠️ 认不出的名字、以及缺失的固定项（首页/设置）由
     * [top.bilitv.ui.NavTab.parse] 兜底，这里只负责"存和取"。
     */
    var navTabs: List<String>
        get() {
            val raw = prefs.getString(KEY_NAV_TABS, null) ?: return emptyList()
            return if (raw.isBlank()) emptyList() else raw.split(',').filter { it.isNotBlank() }
        }
        set(v) = prefs.edit().putString(KEY_NAV_TABS, v.joinToString(",")).apply()

    /** 升级只迁移一次，用户之后隐藏新按钮不会被重新补回。 */
    var playerButtons: List<String>
        get() {
            val ids = prefs.getString(KEY_PLAYER_BUTTONS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val schema = prefs.getInt("player_buttons_schema", 0)
            if (schema < 3) {
                val migrated = if (schema < 1) top.bilitv.ui.player.PlayerBarButton.migrateLegacy(ids)
                    else if (schema < 2) (ids + listOf("up", "line")).distinct() else ids
                val updated = if (migrated.isEmpty()) top.bilitv.ui.player.PlayerBarButton.DEFAULT.map { it.id }
                    else (migrated + "subtitle").distinct()
                prefs.edit().putString(KEY_PLAYER_BUTTONS, updated.joinToString(","))
                    .putInt("player_buttons_schema", 3).apply()
                return updated
            }
            return ids
        }
        set(v) = prefs.edit().putString(KEY_PLAYER_BUTTONS, v.joinToString(","))
            .putInt("player_buttons_schema", 3).apply()

    var filterUiAds: Boolean
        get() = prefs.getBoolean(KEY_AD_FILTER, true)
        set(v) = prefs.edit().putBoolean(KEY_AD_FILTER, v).apply()

    // ---------------------------------------------------------------- 播放

    /**
     * 同清晰度优先 HEVC。
     *
     * 默认开：中低端电视 SoC 的 HEVC 硬解远强于 AVC 高分辨率。
     * 留开关是因为少数片源 HEVC 只有低帧率版本，用户可以手动切回 AVC 对比。
     */
    var preferHevc: Boolean
        get() = prefs.getBoolean(KEY_PREFER_HEVC, true)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_HEVC, v).apply()

    /** 记忆的清晰度选择；见 [QualityOptions]（`0` = 自动，取设备能解的最高档） */
    var preferredQuality: Int
        get() = prefs.getInt(KEY_QUALITY, QualityOptions.AUTO_ID)
        set(v) = prefs.edit().putInt(KEY_QUALITY, v).apply()

    /**
     * 「只用 AVC（兼容模式）」—— 高级模式的**逃生开关**。
     *
     * 默认关。默认关的理由：正常设备上 HEVC 硬解更省电、码率更低，
     * 而这条开关是给"HEVC 起播就黑屏"那类机器准备的（`docs/08` §8）。
     *
     * ★ 它**不是**"偏好 AVC"，而是"完全不碰 HEVC / AV1" —— 用的是
     * [top.bilitv.player.StreamSelector] 里那条 `onlyAvc` 路径，
     * 和播放器自动降级时走的是同一条。也就是说：自动降级能不能修好的机器，
     * 这个开关就能**直接绕过**，不用等它崩一次。
     */
    var forceAvc: Boolean
        get() = prefs.getBoolean(KEY_FORCE_AVC, false)
        set(v) = prefs.edit().putBoolean(KEY_FORCE_AVC, v).apply()

    /**
     * 「跳过 P2P 加速节点」—— 高级模式的线路策略开关。
     *
     * 默认关：正常情况下 P2P 节点被排在候选表**最后**（见
     * [top.bilitv.player.CdnOrder]），连不上自然会落到正规 CDN，
     * 不用用户操心。开着它只是省掉那一次失败的尝试。
     *
     * ★ 全部候选都是 P2P 时会**回落成不过滤**（理由写在 `CdnOrder.order` 里）——
     * 一个偏好开关不该把能播的视频变成不能播。
     */
    var skipP2p: Boolean
        get() = prefs.getBoolean(KEY_SKIP_P2P, false)
        set(v) = prefs.edit().putBoolean(KEY_SKIP_P2P, v).apply()

    // ---------------------------------------------------------------- 容错（advanced 分组）

    /**
     * 指定 CDN 关键词。**空串 = 不干预**（默认）。
     *
     * 语义是"优先匹配到的镜像"，不是"只用它" —— 见
     * [top.bilitv.player.PlayTolerance.orderByPreference]。留空即保持 B 站给的原始顺序。
     */
    var cdnPreference: String
        get() = prefs.getString(KEY_CDN_PREF, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_CDN_PREF, v.trim()).apply()

    /**
     * 指定解码器名。**空串 = 让系统自己挑**（默认）。
     *
     * 只在一台机器上"别的都没问题、就某个编码起播崩"时才用得上 ——
     * 那类问题的正确解法是精确指到另一个能用的解码器，而不是整片换成软解。
     */
    var decoderName: String
        get() = prefs.getString(KEY_DECODER, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_DECODER, v.trim()).apply()

    /**
     * 起播卡住时**自动跳过 P2P 节点再试一次**。默认**开**。
     *
     * ★ 和 [skipP2p] 的区别，别混淆：
     *  - [skipP2p]（线路策略）：**从一开始就不排** P2P 节点。是偏好。
     *  - 本项（容错）：**正常排、正常试**，只有当这一轮确实播不出来时，
     *    才把候选表里的 P2P 去掉再试一轮。是退路。
     *
     * 默认开的理由：它只在失败路径上生效，成功时一次都不执行 ——
     * 没有副作用的保险，没理由让用户自己去发现。
     */
    var autoRetryWithoutP2p: Boolean
        get() = prefs.getBoolean(KEY_RETRY_NO_P2P, true)
        set(v) = prefs.edit().putBoolean(KEY_RETRY_NO_P2P, v).apply()

    // ---------------------------------------------------------------- 播放（2026-09-29 三连增强）

    /**
     * 播放倍速的**档位下标**（不是倍速值本身）。
     *
     * 存下标而不是浮点值：浮点在落盘/读回时有精度损耗（`1.1f` 存了可能读回
     * `1.1000001`），用它去 `indexOf` 匹配档位会失配。存下标最稳。
     * 表见 [PlaybackTuning.SPEEDS]。
     *
     * 消费者：`PlayerScreen` 起播时设置 ExoPlayer 的播放速度。
     */
    var playbackSpeedIndex: Int
        get() = PlaybackTuning.speedIndexOf(prefs.getInt(KEY_SPEED_INDEX, PlaybackTuning.DEFAULT_SPEED_INDEX))
        set(v) = prefs.edit().putInt(KEY_SPEED_INDEX, PlaybackTuning.speedIndexOf(v)).apply()

    /**
     * 画面比例档位 id。**存的是字符串 id 不是 Media3 常量**，理由见 [PlaybackTuning]。
     *
     * 消费者：`PlayerScreen` 的 `PlayerView.resizeMode`。
     */
    var aspectMode: String
        get() = PlaybackTuning.aspectOf(prefs.getString(KEY_ASPECT, null)).id
        set(v) = prefs.edit().putString(KEY_ASPECT, PlaybackTuning.aspectOf(v).id).apply()

    /**
     * 播完自动连播下一集 / 下一 P。默认**开**。
     *
     * 为什么默认开：它就是电视上看连续剧的正常期待行为。关掉的理由（"想停下来"）
     * 属于少数派，而且关掉的成本很低（在设置里一下），开着却是多数人的默认预期。
     *
     * 边界：**直播不参与**（直播没有"下一集"）；列表里没有下一项时不动作、不报错。
     *
     * 消费者：`PlayerScreen` 的 `STATE_ENDED` 回调。
     */
    var autoNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_NEXT, v).apply()

    /**
     * ★ 2026-09-30 新增（少爷反馈 6）：「**单击返回键退出**」。
     *
     * 这是**照 BT 做的开关** —— BT 播放设置里有同名项，默认**开**（见 `docs/37` §1）。
     * 但少爷要的是"按两次才退"，所以**我们的默认取 `false`**：
     *
     * | 值 | 第一次按返回 | 第二次按返回 |
     * |---|---|---|
     * | `true`（BT 的默认） | 直接退出播放 | —— |
     * | `false`（**我们的默认**） | 弹 2 秒提示「再按一下返回退出当前视频」 | 退出播放 |
     *
     * ⛔ **不受这个开关影响的一条**：控制栏开着的时候按返回 = **先收起控制栏**，
     * 不退出播放。否则"控制栏开着按返回就退出去"会显得毫无预兆
     * （少爷反馈 6 后半句就是这么要求的）。
     *
     * 消费者：`PlayerScreen` 的返回键处理。
     */
    var singleBackExit: Boolean
        get() = prefs.getBoolean(KEY_SINGLE_BACK_EXIT, false)
        set(v) = prefs.edit().putBoolean(KEY_SINGLE_BACK_EXIT, v).apply()

    /**
     * ★ 2026-09-30 新增（少爷反馈 7）：**视频详情页**是否保留。
     *
     * 少爷原话（逐字）：
     * > 把所有的详情页先关了，在设置里做成开关可选选项，设置里打开了再显示详情页，
     * > 平时点击视频卡直接进入播放。
     *
     * 默认**关** —— 即"点视频卡直接进播放"，详情页不出现。
     *
     * | 值 | 点一张 UGC 视频卡之后 |
     * |---|---|
     * | `false`（**默认**） | 直接进播放页 |
     * | `true` | 先进视频详情页（改之前的行为） |
     *
     * 关掉它**不会缺东西**：播放页自己会拉一次 `view`（拿标题 / UP 主 / 真实 cid / 分 P 列表），
     * 详情页能给的信息它都有（见 `PlayerViewModel.load` 的 UGC 分支，`cid=0` 时会自己补）。
     * 所以跳过详情页只是"少一屏",不是"少信息"。
     *
     * ⚠️ **只作用于 UGC 视频卡**。番剧 / 影视（PGC）不适用 —— 那里点一张卡得到的是"一整部剧",
     * 不先选一集就没有 `epId`，没法直接播；`Screen.PgcDetail` 保留不动（`docs/38` §0 记录）。
     *
     * 消费者：`Nav.kt` 里的 `Screen.targetForVideoCard(...)`。
     */
    var detailPageEnabled: Boolean
        get() = prefs.getBoolean(KEY_DETAIL_PAGE, false)
        set(v) = prefs.edit().putBoolean(KEY_DETAIL_PAGE, v).apply()

    // ---------------------------------------------------------------- 界面

    /** 默认保留已经确认的 OK 切换行为，避免遥控器移动时连续加载页面。 */
    var lowMemoryMode: Boolean
        get() = prefs.getBoolean("ui_low_memory", true)
        set(v) = prefs.edit().putBoolean("ui_low_memory", v).apply()
    var startupPage: String
        get() = prefs.getString("ui_startup_page", "HOME").orEmpty()
        set(v) = prefs.edit().putString("ui_startup_page", v).apply()
    var startupFocus: StartupFocus
        get() = StartupFocus.fromName(prefs.getString("ui_startup_focus", null))
        set(v) = prefs.edit().putString("ui_startup_focus", v.name).apply()
    var showClock: Boolean
        get() = prefs.getBoolean("ui_show_clock", true)
        set(v) = prefs.edit().putBoolean("ui_show_clock", v).apply()

    /** 原生偏好监听；离开页面时必须取消，不增加另一套设置存储。 */
    fun observeChanges(onChanged: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * 界面皮肤。
     *
     * 存的是**字符串 id** 而不是 enum 的序号 —— 序号会在枚举里插项时整体错位，
     * 那样老用户升级后皮肤会莫名其妙变掉。[ThemeSkin.fromId] 认不出来就回落到默认。
     */
    var themeSkin: ThemeSkin
        get() = ThemeSkin.fromId(prefs.getString(KEY_SKIN, null))
        set(v) = prefs.edit().putString(KEY_SKIN, v.id).apply()

    /** 设置页的「高级模式」是否已开启（对应少爷要的"BIOS advanced mode"） */
    var advancedMode: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED, false)
        set(v) = prefs.edit().putBoolean(KEY_ADVANCED, v).apply()

    private companion object {
        const val PREFS_NAME = "bilitv_settings"
        const val KEY_DM_ON = "danmaku_on"
        const val KEY_DM_ALPHA = "danmaku_alpha"
        const val KEY_DM_SCALE = "danmaku_scale"
        const val KEY_DM_LINES = "danmaku_max_lines"
        const val KEY_DM_DURATION = "danmaku_duration_ms"
        const val KEY_DM_MERGE = "danmaku_merge"
        const val KEY_SB_ON = "sponsor_on"
        const val KEY_SB_CATS = "sponsor_categories"

        /** 首页分区名单。**逗号分隔的 id 串**，见 [homeSections]。 */
        const val KEY_HOME_SECTIONS = "home_sections"

        /** 侧栏项名单。**逗号分隔的枚举名串**，见 [navTabs]。 */
        const val KEY_NAV_TABS = "nav_tabs"

        /** 播放器控制栏按钮名单。**逗号分隔的 id 串**，见 [playerButtons]。 */
        const val KEY_PLAYER_BUTTONS = "player_buttons"
        const val KEY_AD_FILTER = "filter_ui_ads"
        const val KEY_PREFER_HEVC = "prefer_hevc"
        const val KEY_QUALITY = "preferred_quality"
        const val KEY_FORCE_AVC = "force_avc"
        const val KEY_SKIP_P2P = "skip_p2p_nodes"
        const val KEY_CDN_PREF = "cdn_preference"
        const val KEY_DECODER = "decoder_name"
        const val KEY_RETRY_NO_P2P = "retry_without_p2p"
        const val KEY_SPEED_INDEX = "playback_speed_index"
        const val KEY_ASPECT = "aspect_mode"
        const val KEY_AUTO_NEXT = "auto_next"
        const val KEY_SINGLE_BACK_EXIT = "single_back_exit"

        /** 视频详情页是否保留（默认关，见 [detailPageEnabled]）。 */
        const val KEY_DETAIL_PAGE = "detail_page_on"
        const val KEY_SKIN = "theme_skin"
        const val KEY_ADVANCED = "advanced_mode"
    }
}

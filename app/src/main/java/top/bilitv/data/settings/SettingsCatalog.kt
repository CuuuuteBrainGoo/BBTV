package top.bilitv.data.settings

/** 设置项的分类和高级模式显隐；页面用穷尽 when 渲染，新增项不能悄悄漏掉。 */
object SettingsCatalog {
    enum class SettingsCategory(val label: String, val advancedOnly: Boolean = false) {
        APPEARANCE("界面设置"),
        PLAYBACK("播放设置"),
        DANMAKU("弹幕/字幕设置"),
        AD("广告跳过"),
        ADVANCED("高级模式"),
        TUNING("解码与线路", true),
        STORAGE("存储与日志", true),
        ABOUT("关于软件");

        companion object { val DEFAULT = APPEARANCE }
    }

    enum class SettingsItem(val category: SettingsCategory, val advancedOnly: Boolean = false) {
        INTERFACE_GENERAL(SettingsCategory.APPEARANCE),
        SKIN(SettingsCategory.APPEARANCE),
        HOME_SECTIONS(SettingsCategory.APPEARANCE),
        RAIL_TABS(SettingsCategory.APPEARANCE),
        PLAYER_BUTTONS(SettingsCategory.APPEARANCE),
        DANMAKU_ON(SettingsCategory.DANMAKU),
        DANMAKU_ALPHA(SettingsCategory.DANMAKU),
        DANMAKU_SCALE(SettingsCategory.DANMAKU),
        DANMAKU_AREA(SettingsCategory.DANMAKU),
        DANMAKU_OVERLAP(SettingsCategory.DANMAKU),
        DANMAKU_SPEED(SettingsCategory.DANMAKU),
        DANMAKU_SCROLL(SettingsCategory.DANMAKU),
        DANMAKU_REVERSE(SettingsCategory.DANMAKU),
        DANMAKU_TOP(SettingsCategory.DANMAKU),
        DANMAKU_BOTTOM(SettingsCategory.DANMAKU),
        DANMAKU_COLOR(SettingsCategory.DANMAKU),
        DANMAKU_ADVANCED(SettingsCategory.DANMAKU),
        DANMAKU_MERGE(SettingsCategory.DANMAKU),
        DANMAKU_REPEAT(SettingsCategory.DANMAKU),
        DANMAKU_LEVEL(SettingsCategory.DANMAKU),
        DANMAKU_CLOUD(SettingsCategory.DANMAKU),
        DANMAKU_INTERACTION(SettingsCategory.DANMAKU),
        DANMAKU_OUTLINE(SettingsCategory.DANMAKU),
        DANMAKU_OUTLINE_ALPHA(SettingsCategory.DANMAKU),
        DANMAKU_TRACK(SettingsCategory.DANMAKU),
        DANMAKU_LINES(SettingsCategory.DANMAKU),
        DANMAKU_KEYWORDS(SettingsCategory.DANMAKU),
        DANMAKU_REGEX(SettingsCategory.DANMAKU),
        DANMAKU_USERS(SettingsCategory.DANMAKU),
        SUBTITLE_ON(SettingsCategory.PLAYBACK),
        SUBTITLE_LANGUAGE(SettingsCategory.DANMAKU),
        SUBTITLE_FONT(SettingsCategory.DANMAKU),
        SUBTITLE_COLOR(SettingsCategory.DANMAKU),
        SUBTITLE_POSITION(SettingsCategory.DANMAKU),
        SUBTITLE_BACKGROUND(SettingsCategory.DANMAKU),
        SUBTITLE_FADE(SettingsCategory.DANMAKU),
        DANMAKU_RESET(SettingsCategory.DANMAKU),
        SPONSOR_ON(SettingsCategory.AD),
        AD_FILTER(SettingsCategory.AD),
        SPONSOR_CATEGORIES(SettingsCategory.AD, true),
        QUALITY(SettingsCategory.PLAYBACK),
        SEEK_SECONDS(SettingsCategory.PLAYBACK),
        AUTO_LOWER_QUALITY(SettingsCategory.PLAYBACK),
        PREFER_HEVC(SettingsCategory.PLAYBACK),
        AUTO_NEXT(SettingsCategory.PLAYBACK),
        DETAIL_PAGE(SettingsCategory.PLAYBACK),
        BACK_EXIT(SettingsCategory.PLAYBACK),
        PLAYER_UP_KEY(SettingsCategory.PLAYBACK),
        PLAYER_DOWN_KEY(SettingsCategory.PLAYBACK),
        SPEED_DEFAULT(SettingsCategory.PLAYBACK, true),
        ASPECT_DEFAULT(SettingsCategory.PLAYBACK, true),
        ADVANCED_SWITCH(SettingsCategory.ADVANCED),
        FORCE_AVC(SettingsCategory.TUNING),
        SKIP_P2P(SettingsCategory.TUNING),
        CDN_PREF(SettingsCategory.TUNING),
        DECODER(SettingsCategory.TUNING),
        RETRY_NO_P2P(SettingsCategory.TUNING),
        IMAGE_CACHE(SettingsCategory.STORAGE),
        RUN_LOG(SettingsCategory.STORAGE),
        ABOUT_VERSION(SettingsCategory.ABOUT);
    }

    fun visibleCategories(advanced: Boolean): List<SettingsCategory> =
        SettingsCategory.entries.filter { advanced || !it.advancedOnly }

    fun itemsIn(category: SettingsCategory, advanced: Boolean): List<SettingsItem> =
        if (category !in visibleCategories(advanced)) emptyList()
        else SettingsItem.entries.filter { it.category == category && (advanced || !it.advancedOnly) }
}

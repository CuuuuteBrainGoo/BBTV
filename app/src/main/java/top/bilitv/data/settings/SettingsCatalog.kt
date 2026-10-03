package top.bilitv.data.settings

import top.bilitv.R

/** 设置项的分类和高级模式显隐；页面用穷尽 when 渲染，新增项不能悄悄漏掉。 */
object SettingsCatalog {
    enum class SettingsCategory(val labelRes: Int, val advancedOnly: Boolean = false) {
        APPEARANCE(R.string.setting_appearance),
        PLAYBACK(R.string.setting_playback),
        DANMAKU(R.string.setting_danmaku),
        AD(R.string.setting_ad),
        OTHER(R.string.setting_other),
        ADVANCED(R.string.setting_advanced),
        TUNING(R.string.setting_tuning, true),
        STORAGE(R.string.setting_storage, true),
        ABOUT(R.string.setting_about);

        companion object { val DEFAULT = APPEARANCE }
    }

    enum class SettingsItem(val category: SettingsCategory, val advancedOnly: Boolean = false) {
        INTERFACE_GENERAL(SettingsCategory.APPEARANCE),
        SKIN(SettingsCategory.APPEARANCE),
        BACKGROUND_COLOR(SettingsCategory.APPEARANCE),
        CARD_LAYOUT(SettingsCategory.APPEARANCE),
        INTERFACE_LAYOUT(SettingsCategory.APPEARANCE),
        RECOMMEND_BACKTRACK(SettingsCategory.APPEARANCE),
        DETAIL_LAYOUT(SettingsCategory.APPEARANCE),
        HOME_SECTIONS(SettingsCategory.APPEARANCE),
        RAIL_TABS(SettingsCategory.APPEARANCE),
        PLAYER_BUTTONS(SettingsCategory.APPEARANCE),
        APPEARANCE_RESET(SettingsCategory.APPEARANCE),
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
        SUBTITLE_BACKGROUND(SettingsCategory.DANMAKU),
        DANMAKU_RESET(SettingsCategory.DANMAKU),
        SPONSOR_ON(SettingsCategory.AD),
        AD_FILTER(SettingsCategory.AD),
        SPONSOR_CATEGORIES(SettingsCategory.AD, true),
        QUALITY(SettingsCategory.PLAYBACK),
        PERFORMANCE(SettingsCategory.PLAYBACK),
        AUDIO_QUALITY(SettingsCategory.PLAYBACK),
        PLAYBACK_DISPLAY(SettingsCategory.PLAYBACK),
        TOUCH_GESTURES(SettingsCategory.PLAYBACK),
        SEEK_SECONDS(SettingsCategory.PLAYBACK),
        AUTO_LOWER_QUALITY(SettingsCategory.PLAYBACK),
        PREFER_HEVC(SettingsCategory.PLAYBACK),
        AUTO_NEXT(SettingsCategory.PLAYBACK),
        SKIP_OFFICIAL_INTRO_OUTRO(SettingsCategory.PLAYBACK),
        DETAIL_PAGE(SettingsCategory.PLAYBACK),
        RETURN_DETAILS(SettingsCategory.PLAYBACK),
        RESUME_CHOICE(SettingsCategory.PLAYBACK),
        BACK_EXIT(SettingsCategory.PLAYBACK),
        REMOTE_KEYS(SettingsCategory.PLAYBACK),
        SPEED_DEFAULT(SettingsCategory.PLAYBACK),
        ASPECT_DEFAULT(SettingsCategory.PLAYBACK, true),
        PLAYBACK_RESET(SettingsCategory.PLAYBACK),
        RECOMMEND_SOURCE(SettingsCategory.OTHER),
        RECOMMEND_PERSONALIZED(SettingsCategory.OTHER),
        VIDEO_API_SOURCE(SettingsCategory.OTHER),
        ADVANCED_SWITCH(SettingsCategory.ADVANCED),
        FORCE_AVC(SettingsCategory.TUNING),
        SKIP_P2P(SettingsCategory.TUNING),
        CDN_PREF(SettingsCategory.TUNING),
        DECODER(SettingsCategory.TUNING),
        PLAYBACK_STATS(SettingsCategory.TUNING),
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

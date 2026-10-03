package top.bilitv.data.settings

/**
 * 界面皮肤。
 *
 * 存在的理由：少爷的原话是「以 B 为主默认视觉外观，A 可以做成可换的皮肤吗」。
 * 能换的只有**数值**，换不了**结构** —— 一屏放 4 张还是 6 张能换，
 * "有没有侧栏"换不了。所以两套皮肤共用同一批组件，只是喂进去的 token 不同
 * （见 `ui/theme/Theme.kt`）。
 *
 * 放在 `data` 层而不是 `ui.theme`：它要被 [SettingsStore] 持久化，
 * 让 data 去 import ui 是反向依赖。
 *
 * @param id 存进 SharedPreferences 的稳定标识。**不要改** —— 改了老用户的设置会丢。
 */
enum class ThemeSkin(
    val id: String,
    val label: String,
    val desc: String,
) {
    /**
     * B · 影院 / 内容优先。默认。
     *
     * 底色近黑，主色只出现在焦点上，卡片大、留白多。
     * 选它当默认的三条理由（见 `docs/13`）：① 和 `docs/03` §2.4 已经定过的
     * "现代大屏观感、不要传统 Android TV 模板感"是同一件事；
     * ② 低内存 + 遥控器 + 远距离观看这三个硬约束它全扛得住；
     * ③ 它是两套里唯一的"底座" —— 想更热闹，加大主色用量就是 [CLASSIC]。
     */
    CINEMA("cinema", "影院（更黑的黑色，护眼）", "更深黑底、主色只给焦点、卡片大留白多"),

    /**
     * A · 经典 / 贴近官方。
     *
     * B 站粉大面积铺（侧栏选中项、顶栏），一屏塞更多卡片，观感更接近官方。
     *
     * ⚠️ 已知代价，不是 bug：**卡片变小 → 3 米外字更小、遥控器更难选准**。
     * 这是物理距离决定的，皮肤能换长相但换不了观看距离。
     */
    CLASSIC("classic", "经典", "B 站粉大面积、一屏更多卡片、更接近官方观感"),
    PORNHUB("pornhub", "Pornhub 黄黑", "橙黄强调色、黑底、浅色文字"),
    WECHAT("wechat", "Wechat 绿", "微信绿强调色，经典深灰底"),
    ALIPAY("alipay", "Alipay 蓝", "支付宝蓝强调色，经典深灰底"),
    ;

    companion object {
        /** 从持久化的字符串还原。认不出来一律回落到默认皮肤，不抛异常。 */
        fun fromId(raw: String?): ThemeSkin =
            entries.firstOrNull { it.id == raw } ?: CINEMA
    }
}

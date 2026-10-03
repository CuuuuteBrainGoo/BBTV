package top.bilitv.ui.home

import top.bilitv.data.model.PgcType
import top.bilitv.R

/**
 * 首页（以及未来的其它页面）能放上去的**内容分区**。
 *
 * ## 这个类是为了"可配置"而生的（2026-09-30）
 *
 * 少爷的要求：
 * > 首页的分类除了"推荐""热门""每周必看"还应该有其他分区……做成可以**自定义顺序**
 * > 和**选择是否显示**某个分区的形式。
 *
 * 做法照**逆向 BT 得到的模型**（`docs/33` §四）：
 * **一个页面 = 一份有序的 id 名单，顺序就是显示顺序，不在名单里就是不显示。**
 * 不用 `List<{id, enabled, order}>` 那种三字段结构 —— 顺序本身就表达了"显示/隐藏"，
 * 多一个字段就多一处能写错的地方。
 *
 * ## ⛔ id 是**字符串**，不是序号
 *
 * 序号会在"中间插一个新分区"时整体错位：用户升级后会看到自己关掉的分区又冒出来
 * （和 `NavTab` 那条"存名字不存序号"是同一个道理）。
 *
 * ## 三类数据源，混在同一份名单里
 *
 * | 类型 | 例子 | 取数 |
 * |---|---|---|
 * | UGC 信息流 | 推荐 / 热门 / 每周必看 | `feedRecommend` / `popular` / `weeklyOne` |
 * | **PGC 分类** | 番剧 / 电影 / 电视剧 / 纪录片 / 国创 | `pgcIndex(type)`，见 [pgcType] |
 * | **UGC 分区** | 动画 / 音乐 / 舞蹈 / 游戏 / 知识 / 科技 / 美食 / 运动 / 汽车 | `regionNewList(rid)`，见 [regionId] |
 *
 * 三者对使用者是**一样的**（都是"一个能往下翻的卡片列表"），
 * 差异只在 [HomeViewModel] 那一处 `when` 里。
 *
 * @param id 稳定标识。**存配置用的就是它**，改名字等于让所有用户的配置失效。
 * @param pageable 能不能往下翻。见 [WEEKLY] 的说明。
 * @param pgcType 非空 = 这一项走 PGC 索引接口。
 * @param regionId 非 0 = 这一项走 UGC 分区最新投稿接口。
 */
enum class HomeSection(
    val id: String,
    val labelRes: Int,
    val pageable: Boolean = true,
    val pgcType: PgcType? = null,
    val regionId: Int = 0,
) {
    // ---------------------------------------------------------------- UGC 信息流

    RECOMMEND("recommend", R.string.section_recommend),
    POPULAR("popular", R.string.section_popular),

    /**
     * 每周必看**不能翻页**：它是一期一期的固定名单（`popular/series/one`），
     * 没有"下一页"这个概念。硬给它加无限加载只会做出一个滚到底也不动的假动作。
     */
    WEEKLY("weekly", R.string.section_weekly, pageable = false),

    // ---------------------------------------------------------------- PGC 分类

    BANGUMI("bangumi", R.string.section_bangumi, pgcType = PgcType.BANGUMI),
    MOVIE("movie", R.string.section_movie, pgcType = PgcType.MOVIE),
    TV("tv", R.string.section_tv, pgcType = PgcType.TV),
    DOC("doc", R.string.section_documentary, pgcType = PgcType.DOCUMENTARY),
    GUOCHUANG("guochuang", R.string.section_guochuang, pgcType = PgcType.GUOCHUANG),

    // ---------------------------------------------------------------- UGC 分区

    ANIMATION("animation", R.string.section_animation, regionId = 1),
    MUSIC("music", R.string.section_music, regionId = 3),
    DANCE("dance", R.string.section_dance, regionId = 129),
    GAME("game", R.string.section_game, regionId = 4),
    KNOWLEDGE("knowledge", R.string.section_knowledge, regionId = 36),
    TECH("tech", R.string.section_tech, regionId = 188),

    /**
     * ⚠️ **它叫"运动"，不叫"体育"。**
     *
     * B 站自己没有"体育"这个分区名，对应的是 **`运动`（rid=234）**。
     * 少爷说的是"体育"，但那是个口语说法 —— 界面上显示"运动"是为了和 B 站一致，
     * 少一层"用户看到的词 ↔ 接口要的值"的对不上的映射（那种映射迟早有人写反）。
     */
    SPORT("sport", R.string.section_sport, regionId = 234),
    FOOD("food", R.string.section_food, regionId = 211),
    CAR("car", R.string.section_car, regionId = 223),
    ;

    companion object {

        /**
         * **默认开启的分区与顺序**（少爷 2026-09-30 定的）。
         *
         * 顺序是有讲究的：前三个是"算法/榜单/编辑精选"，也就是**最常看的**；
         * 接着是 PGC 五个大类；最后是 UGC 分区。
         * 用户可以随意改，这里只是"出厂设置"。
         */
        val DEFAULT: List<HomeSection> = listOf(
            /*
             * ★ 2026-09-30 少爷（截图批注原话）：
             * 「这里第一个做推荐、第二个是热门，**不要每周必看**」。
             * → 顺序保持 推荐→热门；**把 WEEKLY 从默认名单里去掉**。
             * ⚠️ 只从默认名单去掉，**枚举条目保留** —— 用户在设置里仍可自己打开它。
             */
            RECOMMEND, POPULAR,
            BANGUMI, MOVIE, TV, DOC, GUOCHUANG,
            ANIMATION, MUSIC, DANCE, GAME, KNOWLEDGE, TECH, FOOD, SPORT, CAR,
        )

        /** 按 id 找。**认不出来就返回 null** —— 老配置里可能有已经删掉的分区。 */
        fun byId(id: String): HomeSection? = entries.firstOrNull { it.id == id }

        /**
         * 把存下来的 id 名单还原成分区名单。
         *
         * ⚠️ **认不出的 id 直接丢掉，不报错** —— 这是"删掉一个分区之后老配置不炸"的保证。
         * ⚠️ **空结果回落到默认值**：如果用户把分区全关了，界面上就一个选项卡都没有，
         * 那一页等于死掉（遥控器没地方落）。宁可强塞回默认，也不要做出一个空页。
         */
        fun parse(ids: List<String>): List<HomeSection> {
            val list = ids.mapNotNull { byId(it) }.distinct()
            return if (list.isEmpty()) DEFAULT else list + listOf(RECOMMEND, POPULAR).filterNot { it in list }
        }
    }
}

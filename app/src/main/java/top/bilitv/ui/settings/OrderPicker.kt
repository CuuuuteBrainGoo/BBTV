package top.bilitv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/*
 * ════════════════════════════════════════════════════════════════════════════
 * 通用「可配置项排序 + 显隐」选择器
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## 为什么要有它（而不是再抄一份）
 *
 * 少爷 2026-09-29 第 8 条要求："逆向学习 BT 的**自定义配置**（不只首页，
 * 播放器控制栏 / 侧栏也能配）"。也就是说这**不是一次性的页面**，
 * 而是一种会重复出现的设置形态 —— 首页分区要配、侧栏要配、以后播放器控制栏还要配。
 *
 * 第一版是直接把交互写死在 `HomeSectionPicker` 里。如果侧栏那份再抄一遍，
 * 就会变成三处各自演化：改了一处的手感，另两处还是旧的，
 * 表现是"同一个交互在不同设置项里按下去反应不一样"——这类不一致最难被用户描述清楚。
 *
 * 所以抽出这一份：**只认 `(id, 名字, 是否可隐藏)`，不认业务**。
 *
 * ## 交互（都是遥控器上真能按的）
 *
 * | 按键 | 做什么 |
 * |---|---|
 * | **上 / 下** | ★ **按名单顺序逐个移动焦点**（到首尾时自然离开这个选择器） |
 * | **左 / 右** | ★ **和相邻的胶囊调换位置**（这就是"改顺序"） |
 * | OK | 显示 / 隐藏 |
 *
 * ### ⛔⛔ 为什么「上下」是"逐个移动"而不是"上下换行"（2026-09-30 实测推翻原设计）
 *
 * 第一版写的是"上下换行、左右调顺序"，注释里还写着"胶囊只有 2~3 行，
 * 上下换行足够走到任意一个"。**那句话是错的**，而且在模拟器上被完整证伪了。
 *
 * 根因：**左右键被"调顺序"占掉之后，焦点在同一个行内就无法水平移动了。**
 * 而 Compose 默认的"上下"是**按几何位置找正上方/正下方的节点** ——
 * 于是只有"和上一行某个胶囊 x 坐标大致对齐"的那几个能被走到，其余**永远走不到**。
 *
 * 实测（模拟器，侧栏 8 个胶囊排成一行）：
 * ```
 * 焦点在「动态」(第 2 个) 按「下」→ 直接跳出选择器，落到「皮肤」
 * → 「历史/直播/搜索/我的/设置/影视」这 6 个胶囊按不到
 * → 也就是说：少爷要的"关掉某个侧栏项"，8 项里有 6 项做不到
 * ```
 * （原始路由：`每周必看 → 知识 → 影视`，`皮肤 → 影视 → 知识 → 每周必看` ——
 *  能进出的入口只有那一个，"影视"以外的侧栏项全在几何盲区里。）
 *
 * **修法是给选择器一条显式的 1-D 焦点链**：第 i 个胶囊的 `up/down` 钉到
 * 第 i-1 / i+1 个（首尾不钉，于是能自然离开）。这样**每一个胶囊都必然可达**，
 * 与它排在第几行、x 坐标多少完全无关 —— 不再依赖几何搜索。
 *
 * **代价（要说清楚）**：在第 1 行里按「下」，焦点会往**右**走一格。
 * 这是"左右被调顺序占掉"之后唯一自洽的取舍 —— 想要"下=往下走"就必须把
 * 调顺序换成上下键，那样又无法离开选择器（第一行的「上」要去调顺序）。
 * 四个方向键要干三件事（移动焦点 / 调顺序 / 离开），必须有取舍。
 * 提示文字里把交互写清楚（"↑↓ 逐个移动 · ←→ 调顺序"），用户按两下就明白了。
 *
 * ⚠️ `onPreviewKeyEvent` **必须写在 `focusRing` 的 `clickable` 之前** ——
 * 否则默认的二维焦点搜索会先把左右键用掉，焦点跑到别的胶囊上去
 * （`docs/99` §D：`onKeyEvent` 抢不过 `clickable`）。
 *
 * ## 「隐藏」为什么是移出而不是交换
 *
 * 隐藏某一项时把它挪到"启用区"末尾，**其余项的相对顺序一个都不变**。
 * 第一版用交换实现，结果隐藏第 1 项把最后一项顶到了第 1 位（实测踩到）。
 *
 * ## 「固定项」为什么必须有
 *
 * 侧栏如果把「设置」关掉，用户就再也进不来改回来了 —— 这是**把自己锁在门外**。
 * 所以 [PickItem.pinned] 的项点到手软也关不掉（但可以调顺序）。
 */

/**
 * 一个可配置项。
 *
 * @param id 落盘用的稳定标识。**永远不要用显示名当 id** —— 名字随时会改
 *   （"运动"→"体育"），改了就等于让所有用户的配置失效。
 * @param label 显示名。
 * @param pinned true = **不允许隐藏**（仍可排序）。侧栏的「首页」「设置」就是这种。
 */
data class PickItem(
    val id: String,
    val label: String,
    val pinned: Boolean = false,
    /**
     * ★ 2026-09-30 少爷第 3 条新增：**钉在位置上，不能移动**。
     *
     * 和 [pinned] 是**两个维度**，别合并：
     * | | 能隐藏吗 | 能移动吗 |
     * |---|---|---|
     * | 普通项 | ✅ | ✅ |
     * | `pinned` | ❌ | ✅ |
     * | `locked` | ❌ | ❌ |
     *
     * 侧栏现在两种都要：「搜索」「我的」是不许隐藏但**可以**挪位置；
     * 「首页」必须钉在第 1 位、「设置」必须钉在最后 —— 它们就是 `locked`。
     *
     * 为什么要钉这两位（少爷定的一头一尾）：
     * - **首页在首位** —— 它是返回键的落点，位置固定用户才形成肌肉记忆；
     *   挪到中间之后"一路按左回到首页"就不成立了。
     * - **设置在末位** —— 它是唯一能改回来的地方；固定在末尾意味着
     *   "一路按到最后就是出口"，这是遥控器上最省力的自救姿势。
     */
    val locked: Boolean = false,
)

/**
 * 一屏铺开的「长方圆角」小胶囊选择器。
 *
 * ## ⛔⛔ 它是**完全受控**的组件（2026-09-30 实测后改）
 *
 * 第一版在内部留了一份 `remember` 的 `order` 列表（`mutableStateListOf`），
 * 改顺序时**既**改这份内部状态、**又**通过 `onChange` 通知父级。
 * 结果实测到一个非常典型的故障（模拟器，侧栏选择器）：
 *
 * ```
 * 按「←」把「我的」往左挪 → SharedPreferences 确实写成了 ...LIVE,MINE,SEARCH...
 * 屏幕上却纹丝不动
 * ```
 *
 * **盘上的数据变了、界面没变** —— 典型的"两份状态"：父级传来的新名单
 * 和组件内部那份 `order` 打了一架，内部那份赢了（或者 `remember` 的 key 没按预期重算）。
 * 判据还是那一条：**同一件事存在两份副本时，先问"另一份谁同步"。**
 *
 * 所以现在**内部一份状态都不存**：
 * - 显示顺序**每次重组现算**（`enabledIds` → 已启用在前、其余在后）
 * - 任何交互都只做一件事：算出**新的 id 名单**，回调给父级
 * - 父级（设置页的本地 state / `MainActivity`）是唯一的事实来源
 *
 * **代价**：每次重组多几次列表运算（十几到几十个元素），可以忽略。
 * **收益**：不可能再出现"盘上改了、界面没改"。
 *
 * @param all 全部可选项（含未启用的）。它们在"未启用区"里按**这个列表的顺序**排，
 *   所以调用方给的顺序决定了隐藏项怎么摆。
 * @param enabledIds 已启用的 id 名单，**顺序 = 显示顺序**。空/认不出时回落到全部启用。
 * @param onChange 改动后回调，参数是**新的完整启用名单**。调用方负责存下来 + 让界面重画。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderPicker(
    all: List<PickItem>,
    enabledIds: List<String>,
    onChange: (List<String>) -> Unit,
    /** 至少保留几项。首页至少 1 个分区，侧栏至少... 由调用方说了算。 */
    minEnabled: Int = 1,
    /**
     * ★ 最后一个胶囊按「下」是不是**钉死不动**。**默认 false**。
     *
     * ⛔⛔ 这一项是 2026-09-30 实测 P0 之后才加的，**不是可选的美化**。
     *
     * ## 不处理会怎样（模拟器实测：冷启动 → 设置页 → 一路按「下」）
     *
     * 焦点在选择器里**永远出不来**：如果此时它下面是"没被组合"的项，
     * 几何搜索没有候选可去，就退化成"找最近的节点" = **往回跳**，
     * 于是和倒数第二个胶囊无限来回弹。实测连按 36 次都在 `设置 ↔ 影视` 之间打转，
     * **它下面的一切（外观 / 皮肤 / 弹幕 / 广告 / 高级模式 / 存储）遥控器够不到**。
     *
     * ## 根因：`LazyColumn` 只组合视口内的项
     *
     * 光标从上面跳进选择器时，`LazyColumn` 只把那一格滚到**刚好可见 = 屏幕最底边**。
     * 那一刻选择器所在项的下半截还没进视口，**它下面的项根本没被组合**。
     *
     * ## 所以选择器的使用约束（**改设置页时务必遵守**）
     *
     * 1. **一页里可以有多个选择器，但它们必须在同一个 `LazyColumn` item 里。**
     *    同 item 的内容会**一起被组合**，所以上一个选择器末尾按「下」
     *    能落到下一个选择器的胶囊上（实测通过）—— 这正是 [pinTail] 要留 false 的原因。
     * 2. **最后一个选择器之后不能再有可聚焦内容**，并给它 [pinTail] = true，
     *    它就会**停住而不是往回跳**。
     *
     * ⚠️ 反过来最要命：**给一个"后面还有东西"的选择器传 pinTail = true，等于把它变成陷阱** ——
     * 用户走到它末尾就再也下不去了（实测踩过：首页分区末尾被钉住，
     * 「侧栏」和「控制栏」两处选择器完全够不到）。
     */
    pinTail: Boolean = false,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    val theme = AppTheme.current

    /** 认得出、去过重的启用名单；一个都认不出时回落到"全部启用"。**不 remember，直接算。** */
    val enabled = run {
        val known = all.mapTo(HashSet()) { it.id }
        enabledIds.filter { it in known }.distinct().ifEmpty { all.map { it.id } }
    }

    /*
     * 界面上要显示**全部**项（没启用的也要能看到，才能再打开）。
     * 布局 = 已启用（按用户的顺序）在前、未启用的在后（按 `all` 的顺序）。
     * 只有前 [enabledCount] 个是"已启用"，这就是"一份名单同时表达顺序和显隐"。
     */
    val order = enabled.mapNotNull { id -> all.firstOrNull { it.id == id } } +
        all.filterNot { it.id in enabled }
    val enabledCount = enabled.size

    /*
     * ★ 显式的 1-D 焦点链。
     *
     * ## requester 按 **id** 建（不是按位置）
     *
     * 因为「调顺序」会**改变位置**，而焦点要跟着**被移动的那一项**走。
     * 按 id 建之后，"把焦点还给刚才那一项"只需要 `requesters[id]`，
     * 不用去算它现在排第几。
     */
    val requesters = remember(all) { all.associate { it.id to FocusRequester() } }

    /*
     * ⛔⛔ **不要给这些胶囊加 `key(item.id)`**（2026-09-30 实测踩到）。
     *
     * 加 key 的直觉是"让 Compose 把节点搬到新位置，焦点自动跟着走"。
     * **实测结果是反的**：`FlowRow` 是 `SubcomposeLayout`，它的子项在
     * 重新排序时**不会**按 key 搬位置 —— 表现是
     * **顺序改了、屏幕纹丝不动**（盘上已经写成 `...MINE,SEARCH,LIVE...`，界面上还是 `...LIVE,SEARCH...`）。
     *
     * 更迷惑的是**同一个选择器里"显隐"却正常刷新**（因为那只是同一个槽位里换了个颜色），
     * 于是很容易误判成"数据没传到"。**判据：拿盘上的值去对屏幕** ——
     * 盘上变了、屏幕没变，且"同槽位的内容更新"是正常的，那就是**重排**这一件事坏了。
     *
     * 所以这里**不加 key**，老老实实按位置渲染（顺序变了 → 每个槽位的内容跟着变），
     * 焦点则**显式地**在 [pendingFocus] 里还给被移动的那一项。两件事分开做，都确定。
     *
     * ## 调完顺序之后，焦点要**落在被移动的那一项上**（而不是留在原来的槽位上）
     *
     * 为什么必须显式做：不给 key 时节点的身份是"位置"，位置没变、内容变了，
     * 于是焦点会留在原地、而那一格已经换成别人 —— 用户手里刚拿起的东西跑掉了。
     *
     * 用 `LaunchedEffect` 而不是在 `swap` 里直接 `requestFocus()`：那一刻重组还没发生，
     * 新位置的节点还没挂上 requester，直接调会打空。
     */
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFocus, order) {
        val id = pendingFocus ?: return@LaunchedEffect
        runCatching { requesters[id]?.requestFocus() }
        pendingFocus = null
    }

    /** 把 [id] 这一项在启用名单里挪 [delta] 格（-1 左 / +1 右），回调出去。**只在启用区内**。 */
    fun moveItem(id: String, delta: Int) {
        val from = enabled.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in enabled.indices) return

        /*
         * ★ 「锁位」的两道闸（少爷 2026-09-30 第 3 条）。缺一不可：
         *
         * 1. **自己锁着 → 不动。** 首页不许离开第 1 位、设置不许离开末位。
         * 2. **目标位置锁着 → 也不动。** 这一条是容易漏的：
         *    只判第 1 条的话，用户把「动态」往左挪就**把首页挤到第 2 位去了** ——
         *    锁的是"谁"，不是"第几格"，所以被别人换位同样要拦。
         *
         * 界面上的 `canMoveLeft/Right` 已经按同样的规则把这两个键**预先关掉**了
         * （按下去什么也不发生，而不是按下再失败）。这里是第二道闸，
         * 防止以后有人从别的入口调进来。
         */
        val movingLocked = all.firstOrNull { it.id == id }?.locked == true
        if (movingLocked) return
        val targetLocked = all.firstOrNull { it.id == enabled[to] }?.locked == true
        if (targetLocked) return

        val ids = enabled.toMutableList()
        ids.removeAt(from)
        ids.add(to, id)
        pendingFocus = id
        onChange(ids)
    }

    /**
     * 第 [i] 个胶囊的显示 / 隐藏。
     *
     * - 关掉（i 在启用区）：从名单里**移除** —— 其余项相对顺序一个都不变。
     *   （第一版用"交换到末尾"实现，结果关第 1 项把最后一项顶到了第 1 位，实测踩到。）
     * - 打开（i 在未启用区）：**追加到末尾**，用户的现有顺序同样一个都不动。
     */
    fun toggle(i: Int) {
        val item = order.getOrNull(i) ?: return
        if (item.pinned) return          // ★ 固定项不给关，见文件头说明
        val ids = enabled.toMutableList()
        if (i < enabledCount) {
            if (enabledCount <= minEnabled) return
            ids.removeAt(i)
        } else {
            ids.add(item.id)
        }
        onChange(ids)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            order.forEachIndexed { index, item ->
                OrderChip(
                    modifier = Modifier
                        .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (index == order.lastIndex && lastFocus != null) Modifier.focusRequester(lastFocus) else Modifier),
                    label = item.label,
                    enabled = index < enabledCount,
                    pinned = item.pinned,
                    locked = item.locked,
                    onToggle = { toggle(index) },
                    // ★ 按 id 移动，不按下标 —— 下标在重组后可能已经指向别人
                    onMoveLeft = { moveItem(item.id, -1) },
                    onMoveRight = { moveItem(item.id, +1) },
                    /*
                     * 能不能往左 / 右挪。**三个条件缺一不可**：
                     * 1. 自己没锁（锁位项自己不动）
                     * 2. 没到启用区的头 / 尾
                     * 3. **邻居没锁** —— 否则会把锁位项挤走（`moveItem` 里也拦了一道）
                     */
                    canMoveLeft = !item.locked && index in 1 until enabledCount &&
                        !order[index - 1].locked,
                    canMoveRight = !item.locked && index + 1 < enabledCount &&
                        !order[index + 1].locked,
                    /*
                     * 1-D 焦点链。首尾优先接调用方的 headUp / tailDown；未指定时交回默认的
                     * 几何搜索，用户因此能自然地"走出去"到上一个/下一个设置项。
                     * 两头都钉死过，结果是人被关在选择器里出不来（实测）。
                     *
                     * 每一步都从**当前的 `order`** 现算相邻项，所以调完顺序链自动成立。
                     */
                    self = requesters.getValue(item.id),
                    up = order.getOrNull(index - 1)?.let { requesters.getValue(it.id) } ?: headUp,
                    /*
                     * 「下」三种情况：
                     * - 不是最后一个 → 钉到链上的下一个
                     * - 最后一个 + [pinTail] → 钉给自己（按「下」什么也不发生，不是往回跳）
                     * - 最后一个 + 没 pin → **不钉**，交给几何搜索去找下面那个选择器
                     *   （它们都在同一个 item 里，所以那个节点一定被组合过）
                     */
                    down = order.getOrNull(index + 1)?.let { requesters.getValue(it.id) }
                        ?: tailDown ?: if (pinTail) requesters.getValue(item.id) else null,
                )
            }
        }

    }
}

/** 一个胶囊。交互说明见文件头。 */
@Composable
private fun OrderChip(
    modifier: Modifier,
    label: String,
    enabled: Boolean,
    pinned: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    /** 自己的焦点 requester。 */
    self: FocusRequester,
    /** 链上的前一个。null = 到头了，**不钉**，让方向键能走出去。 */
    up: FocusRequester?,
    /** 链上的后一个。**非 null 才会钉住**；最后一个胶囊由 [OrderPicker] 传自己或 tailDown。 */
    down: FocusRequester?,
) {
    val theme = AppTheme.current
    var focused by remember { mutableStateOf(false) }

    /*
     * 读屏/无障碍用的状态描述。**锁位项要说全**（"不可隐藏、不可移动"）——
     * 只说"不可隐藏"的话，用户按左右没反应会以为坏了。
     * 视觉上只给一个小圆点，全话在这里。
     */
    val stateText = when {
        locked -> "固定，不可隐藏、不可移动"
        pinned -> "固定，不可隐藏"
        enabled -> "已显示"
        else -> "已隐藏"
    }

    Row(
        modifier = modifier
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> if (canMoveLeft) { onMoveLeft(); true } else false
                    Key.DirectionRight -> if (canMoveRight) { onMoveRight(); true } else false
                    else -> false
                }
            }
            // ★ 必须在 focusRing 的 clickable **之前**（同 focusRequester 的道理）
            .focusRequester(self)
            .focusProperties {
                if (up != null) this.up = up
                if (down != null) this.down = down
                // 左右钉给自己 —— 它们是"调顺序"的键，不该移动焦点。
                // 钉住（而不是放行）是为了让"按到头的左右键"什么也不做，
                // 而不是突然跳到某个几何上碰巧相邻的地方（那正是这一版要消灭的行为）。
                left = self
                right = self
            }
            .focusRing(
                contentDescription = "$label，$stateText",
                shape = RoundedCornerShape(10.dp),
                restFill = if (enabled) theme.focusFill else theme.background,
                elevateOnFocus = true,
                onClick = onToggle,
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = AppType.Body3,
                // 启用的正常字重、隐藏的细一点 —— 一眼看出哪些开着
                fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal,
            ),
            // 隐藏的灰掉但**还在**（不是删掉），所以还能再打开
            color = when {
                !enabled -> theme.textTertiary
                focused -> theme.primary
                else -> theme.primary
            },
        )
        if (pinned || locked) {
            // 一个小圆点，配合下方提示说明"固定项"。不解释的话用户会以为这个键坏了。
            Text(
                text = "·",
                style = TextStyle(fontSize = AppType.Body3, fontWeight = FontWeight.Bold),
                color = if (focused) theme.primary else theme.textTertiary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

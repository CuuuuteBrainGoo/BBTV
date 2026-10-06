package top.bilitv.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.bilitv.R
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme

/**
 * 简介正文 + 「展开 / 收起」。抽成独立文件是为了让 `DetailScreen.kt` 留在 300 行以内。
 *
 * ## 为什么要折叠，以及为什么「展开」按钮是**唯一**的焦点目标
 *
 * `docs/31` §3.4 把这条列为"**读不到**级别"的必改项，两个后果：
 *
 * 1. `VideoDetail.desc` 没有长度上限。带时间轴 / staff 表 / 推广信息的简介几百字很常见，
 *    不折叠会把页面撑到十几屏。
 * 2. **在遥控器上简介根本到不了**：这一页原来可聚焦的只有页头「播放」和分P chips，
 *    简介区一个焦点目标都没有。而本项目实测（`docs/13` §4.1）`verticalScroll` **不会**
 *    在焦点移出视口时自动滚动（只有 `LazyColumn` 才会）—— 于是焦点最远只能停到分P 那一行，
 *    再往下按没有任何反应，简介既滚不动也读不到。
 *
 * 所以这个按钮不只是"好看"：它是简介区**唯一**的焦点目标。有了它，用户按 DOWN 才有落点，
 * 页面才有机会滚到简介下方 —— **一个按钮同时修掉"读不完"和"到不了"。**
 *
 * ## ⚠️ 展开判据用 `hasVisualOverflow`，不是 `lineCount`（对 §3.4 原文的一处更正）
 *
 * `docs/31` §3.4 第 2 条原文写的是 `onTextLayout { it.lineCount > 4 }`。
 * **照字面实现会做出一个"点了没反应"的死按钮**：当 `maxLines = 4` 时，
 * `TextLayoutResult.lineCount` 已经被 `maxLines` 截到 ≤ 4，永远不可能 `> 4`，
 * 于是 `overflows` 永远为 false，按钮永远不出现。
 *
 * 正确的判据是 `hasVisualOverflow` —— 它表示"文本被 `maxLines` 视觉裁掉了"，
 * 恰好就是"需要展开"这件事。
 *
 * 而且只在**收起态**写入：展开后文本不再溢出，若继续写入会把 `overflows` 翻成 false，
 * 按钮就会消失，用户反而没法「收起」。
 *
 * @param modifier 由调用方给（左右内衬走 `theme.screenPadding`）。
 */
@Composable
fun DescriptionSection(desc: String, modifier: Modifier = Modifier) {
    val theme = AppTheme.current

    // 展开状态。用 rememberSaveable：从详情页进播放页再返回时不至于归零（§3.4 第 6 条）。
    var expanded by rememberSaveable { mutableStateOf(false) }
    // "正文超过 [COLLAPSED_LINES] 行"。只在收起态写入，展开后保持 true，按钮才能变成「收起」。
    var overflows by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )

        // 不超过 4 行就**不渲染**按钮（不显示一个点了没反应的按钮，§3.4 第 7 条）。
        if (overflows) {
            Text(
                text = stringResource(if (expanded) R.string.description_collapse else R.string.description_expand),
                style = MaterialTheme.typography.bodySmall,
                color = theme.textPrimary,
                modifier = Modifier
                    .padding(top = TOGGLE_GAP)
                    .focusRing(
                        contentDescription = stringResource(if (expanded) R.string.description_collapse_description else R.string.description_expand_description),
                        shape = RoundedCornerShape(TOGGLE_CORNER),
                        restFill = theme.surfaceHigh,
                        focusedFill = theme.focusFill,
                        focusedBorder = theme.primary,
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = TOGGLE_PAD_H, vertical = TOGGLE_PAD_V),
            )
        }
    }
}

/** 收起时显示的正文行数（`docs/31` §3.4 第 1 条：4 行）。 */
private const val COLLAPSED_LINES = 4

/** 「展开 / 收起」与正文的间距（§3.4 第 4 条：12dp）。 */
private val TOGGLE_GAP = 12.dp
private val TOGGLE_PAD_H = 16.dp
private val TOGGLE_PAD_V = 8.dp
private val TOGGLE_CORNER = 6.dp

package top.bilitv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/**
 * 全屏二级页面左上角的「返回」。
 *
 * 抽出来共用：详情页和影视详情页都要它，各写一份迟早会改歪一处。
 */
@Composable
fun BackChip(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    TvCard(
        onClick = onBack,
        modifier = modifier,
        background = theme.surfaceHigh,
        contentDescription = "返回",
    ) {
        Text(
            text = "←  返回",
            style = TextStyle(fontSize = AppType.Body2, fontWeight = FontWeight.Medium),
            color = theme.textPrimary,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
        )
    }
}

/**
 * 实心主色按钮（影院主视觉上的「开始观看」那类）。
 *
 * ## 为什么不复用 `TvCard` 的焦点语言
 *
 * `TvCard` 的焦点态是"半透明主色垫底 + 主色描边" —— 那是**给深底上的卡片**用的。
 * 这个按钮本身就是实心主色，再垫一层半透明主色等于没变化，焦点会"看不见"。
 * 所以它的焦点态要反过来：**底色提亮 + 浅色外描边**。
 *
 * 一条通用判据：**焦点变化必须在明度上明显**。
 * 同色叠同色 = 用户看不见 = 等于没有焦点。
 */
@Composable
fun FilledActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current

    Box(
        modifier = modifier
            // 见上方注释：实心按钮的焦点态要"反过来"（底色提亮 + 亮描边），
            // 用 focusRing 传值表达，不再手抄一遍链（`docs/audit/A9` §7.3）。
            //
            // 描边粗细**不写死**：以前这里写 `borderWidth = 3.dp`，等于把皮肤的
            // `focusBorderWidth` 又抄了一遍（改皮肤这一处不跟着变）。删掉之后走默认值 = 皮肤值。
            .focusRing(
                contentDescription = text,
                shape = RoundedCornerShape(6.dp),
                focusedFill = theme.primary,
                restFill = theme.primary.copy(alpha = 0.88f),
                focusedBorder = theme.textPrimary,
                onClick = onClick,
            )
            .padding(horizontal = 30.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = AppType.Body1, fontWeight = FontWeight.SemiBold),
            color = theme.onPrimary,
        )
    }
}

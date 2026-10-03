package top.bilitv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.bilitv.ui.theme.AppTheme

/** A visible, focusable retry stays beside the content that already loaded. */
@Composable
internal fun LoadFeedback(loading: Boolean, message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    val retry = stringResource(R.string.action_retry)
    val description = message?.let { stringResource(R.string.error_retry_description, it) }
    if (loading) Row(modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = theme.primary, strokeWidth = 2.dp)
        Text(stringResource(R.string.loading), modifier = Modifier.padding(start = 8.dp), color = theme.textSecondary)
    } else if (message != null) TvCard(onClick = onRetry, focusedScale = 1f,
        contentDescription = description, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = theme.textSecondary)
            Text(retry, color = theme.primary)
        }
    }
}

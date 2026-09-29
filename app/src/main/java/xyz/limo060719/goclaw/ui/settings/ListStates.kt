package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/* Shared list-screen states for the admin pages: pull-to-refresh + a pullable empty state. */

/** Pull down to refresh. Pass `refreshing` only while content is showing — the first load keeps its spinner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier, content = content)
}

/**
 * Centers an empty/error message while staying vertically scrollable — pull-to-refresh only
 * triggers from scrollable content, so a plain centered Column would make empty lists un-refreshable.
 */
@Composable
internal fun ScrollableCenter(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewport).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

package com.selimdurmus.dictionary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.selimdurmus.dictionary.ui.theme.DividerSubtle
import com.selimdurmus.dictionary.ui.theme.Gold
import com.selimdurmus.dictionary.ui.theme.OnMedium
import kotlinx.coroutines.delay

/**
 * Banner shown for [durationMs] milliseconds after the user taps Clear. A thin progress line
 * underneath shrinks linearly from full to zero over the window. The remaining fraction is
 * computed from [startMs] (wall-clock) so the animation stays correct even if the composable
 * leaves and re-enters composition (e.g., when the user swipes to another pager tab mid-undo).
 */
@Composable
fun UndoClearBanner(
    startMs: Long,
    durationMs: Long,
    message: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fraction by remember(startMs) { mutableFloatStateOf(1f) }
    LaunchedEffect(startMs) {
        while (true) {
            val elapsed = System.currentTimeMillis() - startMs
            val remaining = ((durationMs - elapsed).coerceAtLeast(0L).toFloat() / durationMs)
            fraction = remaining
            if (remaining <= 0f) break
            delay(16)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) {
                Text(
                    text = "Undo",
                    color = Gold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(DividerSubtle)) {
            Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Gold))
        }
    }
}

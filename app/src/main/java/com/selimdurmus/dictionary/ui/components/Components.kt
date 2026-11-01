package com.selimdurmus.dictionary.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selimdurmus.dictionary.R
import com.selimdurmus.dictionary.ui.model.WordCard
import com.selimdurmus.dictionary.ui.model.langLabel
import com.selimdurmus.dictionary.ui.model.posLabel

/** Small pill showing the headword's language ("EN" / "TR"). */
@Composable
fun LangBadge(lang: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (lang == "tr") MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (lang == "tr") MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = langLabel(lang),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Marks machine-made or noisy rows ("AI translation", "machine translation", "PanLex"). */
@Composable
fun SourceTag(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** One result card: headword, language, and up to three numbered lines of translations. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordCardView(
    card: WordCard,
    onClick: () -> Unit,
    onSpeak: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    copyText(clipboard, context, "${card.word} → " + card.lines.joinToString("; ") { it.terms.joinToString(", ") })
                },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.word,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                LangBadge(card.lang)
                Spacer(Modifier.weight(1f))
                if (onSpeak != null) {
                    IconButton(onClick = onSpeak) {
                        Icon(
                            painterResource(R.drawable.ic_volume_up),
                            contentDescription = "Pronounce ${card.word}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            card.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp, end = 8.dp),
                )
            }
            card.lines.forEachIndexed { i, line ->
                Row(
                    modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(18.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        posLabel(line.pos)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = line.terms.joinToString(" · "),
                            style = if (card.definitionsOnly) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodyLarge,
                            color = if (card.definitionsOnly) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        line.sourceLabel?.let { SourceTag(it, Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            if (card.hiddenCount > 0 || card.definitionsOnly) {
                Text(
                    text = buildString {
                        if (card.definitionsOnly) append("Definition only")
                        if (card.definitionsOnly && card.hiddenCount > 0) append(" · ")
                        if (card.hiddenCount > 0) append("${card.hiddenCount} more")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, start = 18.dp),
                )
            }
        }
    }
}

/** Centered icon + message for empty lists and "no results". */
@Composable
fun EmptyState(icon: Painter, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun painter(id: Int): Painter = painterResource(id)

fun copyText(clipboard: ClipboardManager, context: Context, text: String) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

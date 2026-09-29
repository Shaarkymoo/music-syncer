package com.musicsyncer.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.log.LogSession
import com.musicsyncer.app.log.Sessionizer
import com.musicsyncer.engine.JournalOp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val sessionFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val opTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Journal log grouped into per-device sessions (see [Sessionizer]), newest
 * first. Each card summarizes a session; tapping it expands to the member ops.
 */
@Composable
fun LogScreen(vm: MusicViewModel) {
    val sessions by produceState(initialValue = emptyList<LogSession>()) {
        value = withContext(Dispatchers.IO) {
            Sessionizer.group(vm.store.journalSince(0).takeLast(500)).reversed()
        }
    }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    LazyColumn {
        itemsIndexed(sessions) { index, session ->
            SessionCard(
                session = session,
                expanded = expandedIndex == index,
                onToggle = { expandedIndex = if (expandedIndex == index) null else index },
            )
        }
    }
}

@Composable
private fun SessionCard(session: LogSession, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
        ) {
            Text(sessionSummary(session), style = MaterialTheme.typography.titleMedium)
            Text(
                sessionFmt.format(Date(session.startTsNs / 1_000_000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                session.ops.forEach { op ->
                    Text(
                        opLine(op),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

/** e.g. "phone · Sync · +250 songs in 7 playlists · −25 · edited 3". */
private fun sessionSummary(s: LogSession): String {
    val parts = mutableListOf<String>()
    if (s.created > 0) parts.add("+${s.created} songs in ${s.playlists} playlists")
    if (s.deleted > 0) parts.add("−${s.deleted}")
    if (s.modified > 0) parts.add("edited ${s.modified}")
    if (parts.isEmpty()) parts.add("no changes")
    return "${s.device} · Sync · ${parts.joinToString(" · ")}"
}

/** e.g. "02:18  CREATE  playlists/lowkey/song.mp3". */
private fun opLine(op: JournalOp): String =
    "${opTimeFmt.format(Date(op.tsNs / 1_000_000))}  ${op.op}  ${op.path}"
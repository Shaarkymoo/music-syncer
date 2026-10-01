package com.musicsyncer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.engine.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StatsScreen(vm: MusicViewModel) {
    var lines by remember { mutableStateOf(listOf("Loading…")) }
    var conflicts by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var conflictsError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val fs = vm.fs

    LaunchedEffect(vm.fs, refreshTick) {
        lines = withContext(Dispatchers.IO) {
            val manifest = vm.store.manifestAll()
            val files = runCatching { vm.fs?.list() ?: emptyList() }.getOrDefault(emptyList())
            val playlists = runCatching { vm.fs?.listDir("playlists") ?: emptyList() }.getOrDefault(emptyList())
                .filter { it.isDirectory }
                .map { it.rel.substringAfterLast('/') }
                .sorted()
            buildList {
                add("Total songs: ${manifest.size}")
                add("Total size: ${"%.2f GB".format(manifest.sumOf { it.size } / 1073741824.0)}")
                add("Playlists: ${playlists.size}")
                for (p in playlists) {
                    add("  $p: ${files.count { it.rel.startsWith("playlists/$p/") }}")
                }
            }
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { vm.fs?.list()?.filter { it.rel.contains(".sync-conflict-") } ?: emptyList() }
        }
        conflicts = result.getOrDefault(emptyList()).sortedBy { it.rel }
        conflictsError = result.exceptionOrNull()?.message
    }

    fun reload() {
        refreshTick++
        vm.controller?.scan()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Library stats", style = MaterialTheme.typography.titleLarge)
        for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)

        Text("Conflicts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Losers of last-writer-wins are kept as hidden .sync-conflict files. Listen, keep, or delete them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        conflictsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (conflicts.isEmpty()) {
            Text("No conflicts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for (c in conflicts) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.rel.substringAfterLast('/'),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { vm.playInExternalPlayer(c.rel) }) { Text("Listen") }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val original = originalName(c.rel)
                                if (original != null && fs != null && !fs.exists(original)) {
                                    fs.rename(c.rel, original)
                                }
                            }
                            reload()
                        }
                    }) { Text("Keep") }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { fs?.delete(c.rel) }
                            reload()
                        }
                    }) { Text("Delete") }
                }
            }
        }
    }
}

/** ".A.mp3.sync-conflict-123.mp3" -> "A.mp3"; null if the name isn't a conflict file. */
internal fun originalName(rel: String): String? {
    val base = rel.substringAfterLast('/')
    val m = Regex("^\\.(.+?)\\.sync-conflict-\\d+(\\.\\w+)?$").find(base) ?: return null
    return m.groupValues[1]
}
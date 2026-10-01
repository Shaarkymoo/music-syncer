package com.musicsyncer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatsScreen(vm: MusicViewModel) {
    var lines by remember { mutableStateOf(listOf("Loading…")) }
    LaunchedEffect(vm.fs) {
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
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Library stats", style = MaterialTheme.typography.titleLarge)
        for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}
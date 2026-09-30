package com.musicsyncer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.BuildConfig
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.sync.ProgressState
import com.musicsyncer.app.sync.SyncState
import com.musicsyncer.engine.SyncPhase
import com.musicsyncer.app.sync.Updater
import com.musicsyncer.app.sync.displayName
import com.musicsyncer.app.sync.isNewerVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(vm: MusicViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.pickFolder(uri, android.net.Uri.decode(uri.lastPathSegment ?: "music"))
    }
    val scope = rememberCoroutineScope()
    val controller = vm.controller
    val state by controller?.state?.collectAsState() ?: rememberStableState()
    val context = LocalContext.current
    val updater = remember { Updater(context) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var pendingVersion by remember { mutableStateOf<String?>(null) }
    var elapsedTick by remember { mutableStateOf(0L) }
    LaunchedEffect(state?.busy, state?.progress?.phase) {
        while (state?.busy == true) {
            val phaseStart = state?.progress?.phaseStartNs ?: 0L
            elapsedTick = if (phaseStart > 0L) (System.nanoTime() - phaseStart) / 1_000_000 else 0L
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Music Sync")
        vm.folderName?.let { Text("Folder: $it") } ?: Button(onClick = { picker.launch(null) }) { Text("Choose music folder") }
        state?.let { s ->
            Text("Server: ${s.server ?: "not found"}")
            Text("Last sync: ${s.lastSync ?: "-"}")
            Text("Summary: ${s.lastSummary ?: "-"}")
            s.error?.let { Text("Error: $it") }
            if (s.discovering) {
                DiscoveryLine()
            } else if (s.busy || s.progress != null) {
                ProgressCard(s.progress, elapsedTick)
            }
        }
        vm.controller?.let { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.scan() }, enabled = !(state?.busy ?: false)) { Text("Scan") }
                Button(onClick = { c.sync(c.state.value.server) }, enabled = !(state?.busy ?: false)) { Text("Sync") }
                Button(onClick = { c.verify() }, enabled = !(state?.busy ?: false)) { Text("Verify") }
            }
            Button(onClick = { c.discover { vm.discovery.find() } }, enabled = !(state?.busy ?: false)) { Text("Find laptop & sync") }
            TextButton(onClick = {
                scope.launch {
                    val url = c.state.value.server
                    if (url == null) { updateMsg = "Laptop not found"; return@launch }
                    val serverVersion = updater.check(url)
                    if (serverVersion == null) { updateMsg = "Update check failed"; return@launch }
                    val current = BuildConfig.VERSION_NAME
                    if (isNewerVersion(serverVersion, current)) pendingVersion = serverVersion
                    else updateMsg = "Up to date ($current)"
                }
            }) { Text("Check for update") }
            updateMsg?.let { Text(it) }
        }
        pendingVersion?.let { v ->
            AlertDialog(
                onDismissRequest = { pendingVersion = null },
                title = { Text("Update available") },
                text = { Text("Update to $v?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingVersion = null
                        scope.launch {
                            val url = controller?.state?.value?.server ?: return@launch
                            updater.downloadAndInstall(url)
                                .onSuccess { updateMsg = "Installing $v…" }
                                .onFailure { updateMsg = "Install failed: ${it.message}" }
                        }
                    }) { Text("Update") }
                },
                dismissButton = { TextButton(onClick = { pendingVersion = null }) { Text("Cancel") } },
            )
        }
    }
}

/** tqdm-style live progress card: phase (with stage timing), playlist, and song bars. */
@Composable
private fun ProgressCard(progress: ProgressState?, elapsedMs: Long) {
    // Per-playlist song counts within the current phase, derived from `rel` (UI-side).
    val playlistCounts = remember { mutableStateMapOf<String, Int>() }
    var lastRel by remember { mutableStateOf<String?>(null) }
    var lastPhase by remember { mutableStateOf<SyncPhase?>(null) }

    if (progress != null && progress.phase != lastPhase) {
        playlistCounts.clear()
        lastPhase = progress.phase
        lastRel = null
    }
    if (progress != null && progress.rel.isNotBlank() && progress.rel != lastRel) {
        val parent = progress.rel.substringBeforeLast('/', "")
        playlistCounts[parent] = (playlistCounts[parent] ?: 0) + 1
        lastRel = progress.rel
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Bar 1 — phase + per-stage elapsed time.
            val phaseLabel = progress?.phase?.displayName() ?: "Working"
            val count = progress?.let { if (it.total > 0) " · ${it.done}/${it.total}" else "" } ?: ""
            Text(
                text = "$phaseLabel · ${formatElapsed(elapsedMs)}$count",
                style = MaterialTheme.typography.titleMedium,
            )
            val fraction = progress?.let { if (it.total <= 0) 0f else it.done.toFloat() / it.total } ?: 0f
            ProgressBar(fraction, indeterminate = progress == null || progress.total <= 0)

            // Bar 2 — playlist (parent dir of the current file).
            if (progress == null || progress.rel.isBlank()) {
                Text(
                    text = blankRelLabel(progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressBar(0f, indeterminate = true)
            } else {
                val parent = progress.rel.substringBeforeLast('/', "")
                val playlist = parent.ifBlank { "root" }
                val seen = playlistCounts[parent] ?: 0
                Text(
                    text = "on playlist: $playlist · $seen song${if (seen == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressBar(0f, indeterminate = true)
            }

            // Bar 3 — song (basename of the current file) + phase counts.
            if (progress == null || progress.rel.isBlank()) {
                Text(
                    text = blankRelLabel(progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressBar(0f, indeterminate = true)
            } else {
                val song = progress.rel.substringAfterLast('/')
                Text(
                    text = "on song $song · ${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressBar(fraction, indeterminate = progress.total <= 0)
            }
        }
    }
}

/** Honest label for bars 2/3 when there's no current file: only the SCAN walk has no file yet. */
private fun blankRelLabel(progress: ProgressState?): String = when {
    progress == null || (progress.phase == SyncPhase.SCAN && progress.rel.isBlank()) -> "walking folder tree…"
    progress.phase == SyncPhase.PLAN -> "Planning…"
    progress.phase == SyncPhase.DONE -> "Final check…"
    else -> progress.phase.displayName()
}

/** Indeterminate "looking for laptop" line shown while mDNS discovery runs. */
@Composable
private fun DiscoveryLine() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Looking for laptop…", style = MaterialTheme.typography.titleMedium)
            ProgressBar(0f, indeterminate = true)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, indeterminate: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    if (indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color, trackColor = track)
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = color, trackColor = track)
    }
}

/** mm:ss elapsed time for the current stage. */
private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Null-initialized stable state used before a folder is picked (no controller yet). */
@Composable
private fun rememberStableState(): MutableState<SyncState?> =
    remember { mutableStateOf<SyncState?>(null) }
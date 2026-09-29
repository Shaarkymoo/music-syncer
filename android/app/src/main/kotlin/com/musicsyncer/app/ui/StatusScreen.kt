package com.musicsyncer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.sync.SyncState
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(vm: MusicViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.pickFolder(uri, android.net.Uri.decode(uri.lastPathSegment ?: "music"))
    }
    val scope = rememberCoroutineScope()
    val state by vm.controller?.state?.collectAsState() ?: rememberStableState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Music Sync")
        vm.folderName?.let { Text("Folder: $it") } ?: Button(onClick = { picker.launch(null) }) { Text("Choose music folder") }
        state?.let { s ->
            Text("Server: ${s.server ?: "not found"}")
            Text("Last sync: ${s.lastSync ?: "-"}")
            Text("Summary: ${s.lastSummary ?: "-"}")
            s.error?.let { Text("Error: $it") }
            if (s.busy) CircularProgressIndicator()
        }
        vm.controller?.let { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.scan() }, enabled = !(state?.busy ?: false)) { Text("Scan") }
                Button(onClick = { c.sync(c.state.value.server) }, enabled = !(state?.busy ?: false)) { Text("Sync") }
                Button(onClick = { c.verify() }, enabled = !(state?.busy ?: false)) { Text("Verify") }
            }
            Button(onClick = { scope.launch { discoverAndSync(vm) } }, enabled = !(state?.busy ?: false)) { Text("Find laptop & sync") }
        }
    }
}

/** Runs mDNS discovery on a coroutine, then points the controller at the found laptop and syncs. */
private suspend fun discoverAndSync(vm: MusicViewModel) {
    val url = vm.discovery.find()
    if (url != null) {
        vm.controller?.let { c ->
            c.setServer(url)
            c.sync(url)
        }
    }
}

/** Null-initialized stable state used before a folder is picked (no controller yet). */
@Composable
private fun rememberStableState(): MutableState<SyncState?> =
    remember { mutableStateOf<SyncState?>(null) }
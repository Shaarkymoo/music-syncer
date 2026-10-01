package com.musicsyncer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.sync.MediaRescan
import com.musicsyncer.app.tag.SongTags
import com.musicsyncer.app.tag.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Metadata editor for one song: mp3 tags + lyrics, loaded on entry and saved
 * back through [TagEditor] (cache-file roundtrip, since SAF cannot write in
 * place). Saving journals the change via [MusicViewModel.controller]'s scan
 * and asks the media scanner to re-index the file, then closes.
 *
 * @param rel relative path of the song within the SAF tree
 * @param onClose invoked after a successful save, or when the user backs out
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MusicViewModel, rel: String, onClose: () -> Unit) {
    val fs = vm.fs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var form by remember { mutableStateOf<SongTags?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var fileInfo by remember { mutableStateOf<Pair<Long, Long>?>(null) } // (size, mtimeNs)
    var addedDate by remember { mutableStateOf<Long?>(null) }            // epoch seconds from the media index

    LaunchedEffect(rel, fs) {
        if (fs == null) return@LaunchedEffect
        loadError = null
        try {
            withContext(Dispatchers.IO) {
                fileInfo = fs.stat(rel).let { it.size to it.mtimeNs }
                addedDate = vm.mediaAddedDate(rel)
                form = TagEditor.read(fs, rel)
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to read tags"
        }
    }

    fun save() {
        val tags = form ?: return
        val targetFs = fs ?: return
        scope.launch {
            saving = true
            saveError = null
            try {
                withContext(Dispatchers.IO) {
                    TagEditor.write(context, targetFs, rel, tags)
                    vm.controller?.scan()
                    MediaRescan.rescan(context, listOf(rel))
                }
                onClose()
            } catch (e: Exception) {
                saveError = e.message ?: "Save failed"
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rel.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tags = form
            when {
                tags == null && loadError == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                tags == null -> {
                    Text(loadError ?: "Failed to load", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onClose) { Text("Back") }
                }

                else -> {
                    val tags = form!!
                    fileInfo?.let { (size, mtimeNs) ->
                        val lines = buildList {
                            add("Size: %.1f MB".format(size / 1_048_576.0))
                            add("Modified: ${formatDate(mtimeNs / 1_000_000)}")
                            addedDate?.let { add("Added to library: ${formatDate(it * 1_000)}") }
                        }
                        Text(
                            lines.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TagField("Title", tags.title) { form = tags.copy(title = it) }
                    TagField("Artist", tags.artist) { form = tags.copy(artist = it) }
                    TagField("Album", tags.album) { form = tags.copy(album = it) }
                    TagField("Album artist", tags.albumArtist) { form = tags.copy(albumArtist = it) }
                    TagField("Genre", tags.genre) { form = tags.copy(genre = it) }
                    TagField("Track", tags.track) { form = tags.copy(track = it) }
                    OutlinedTextField(
                        value = tags.lyrics ?: "",
                        onValueChange = { form = tags.copy(lyrics = it) },
                        label = { Text("Lyrics") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                    )
                    saveError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onClose, enabled = !saving) { Text("Cancel") }
                        Button(onClick = { save() }, enabled = !saving) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(if (saving) "Saving…" else "Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagField(label: String, value: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value ?: "",
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private fun formatDate(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
package com.musicsyncer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.tag.m3uContent
import com.musicsyncer.engine.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the browser: a subdirectory (drill-down) or a song file. */
private sealed interface BrowserEntry {
    val name: String

    data class Dir(override val name: String, val count: Int = 0) : BrowserEntry

    data class Song(val entry: FsEntry) : BrowserEntry {
        override val name: String get() = entry.rel.substringAfterLast('/')
    }
}

private enum class SortMode { NAME, SIZE, DATE }

/** "" for the root, otherwise "$dir/" — the prefix shared by every entry under [dir]. */
private fun dirPrefix(dir: String): String = if (dir.isEmpty()) "" else "$dir/"

/**
 * Folder browser over the SAF tree: breadcrumb-navigable directories, with
 * rename / move / delete actions on songs. Every file operation is journaled
 * through [MusicViewModel.controller]'s scan.
 *
 * @param onEditSong invoked with a song's relative path when "Edit metadata"
 *   is chosen — Task 7's metadata editor hooks in here.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(vm: MusicViewModel, onEditSong: (String) -> Unit = {}) {
    val fs = vm.fs
    if (fs == null) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pick a folder on the Status screen first")
        }
        return
    }

    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf("") }                  // current directory ("" = root)
    var refreshTick by remember { mutableStateOf(0) }           // bumped after each file op to reload the tree
    var menuRel by remember { mutableStateOf<String?>(null) }   // song whose action menu is open
    var renameRel by remember { mutableStateOf<String?>(null) } // song being renamed
    var deleteRel by remember { mutableStateOf<String?>(null) } // song awaiting delete confirmation
    var moveTargets by remember { mutableStateOf<Set<String>?>(null) } // song(s) awaiting playlist pick
    var newPlaylist by remember { mutableStateOf(false) }        // creating a playlist from the move dialog
    var folderMenuRel by remember { mutableStateOf<String?>(null) } // playlist awaiting an action
    var renameFolderRel by remember { mutableStateOf<String?>(null) } // playlist awaiting a new name
    var selectionMode by remember { mutableStateOf(false) }     // batch-selection active
    var selection by remember { mutableStateOf(setOf<String>()) }
    var multiDelete by remember { mutableStateOf(false) }       // delete confirmation open for the selection
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var query by remember { mutableStateOf("") }                // search box text
    var results by remember { mutableStateOf<List<FsEntry>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, fs) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        val q = query.trim()
        results = withContext(Dispatchers.IO) {
            runCatching { fs.list() }.getOrDefault(emptyList())
                .filter { it.rel.substringAfterLast('/').contains(q, ignoreCase = true) }
                .sortedBy { it.rel }
        }
        searching = false
    }

    val entries by produceState(initialValue = emptyList<BrowserEntry>(), dir, refreshTick, fs, sortMode) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val all = fs.list()
                val children = fs.listDir(dir)
                val prefix = if (dir.isEmpty()) "" else "$dir/"
                val songs = children.filter { !it.isDirectory }
                    .map { BrowserEntry.Song(FsEntry(it.rel, it.size, it.mtimeNs)) }
                    .let { list ->
                        when (sortMode) {
                            SortMode.NAME -> list.sortedBy { it.entry.rel }
                            SortMode.SIZE -> list.sortedByDescending { it.entry.size }
                            SortMode.DATE -> list.sortedByDescending { it.entry.mtimeNs }
                        }
                    }
                val dirs = children.filter { it.isDirectory }
                    .map { entry ->
                        val name = entry.rel.substringAfterLast('/')
                        BrowserEntry.Dir(name, all.count { it.rel.startsWith("$prefix$name/") })
                    }
                    .sortedBy { it.name }
                dirs + songs
            }
        }
        result.exceptionOrNull()?.let { error = it.message ?: "Failed to list folder" }
        value = result.getOrDefault(emptyList())
    }

    fun navigateTo(target: String) {
        menuRel = null
        dir = target
    }

    /** Runs a file operation off the main thread, journals it, then reloads the tree. */
    fun runOp(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try {
                withContext(Dispatchers.IO) {
                    block()
                    vm.controller?.scan()
                }
            } catch (e: Exception) {
                error = e.message ?: "Operation failed"
            }
            refreshTick++
        }
    }

    fun startMove(rel: String) { moveTargets = setOf(rel) }

    fun startMultiMove() { moveTargets = selection }

    fun toggleSelect(rel: String) {
        selection = if (rel in selection) selection - rel else selection + rel
        if (selection.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selection = emptySet()
    }

    fun confirmRename(rel: String, newName: String) {
        val newRel = dirPrefix(rel.substringBeforeLast('/')) + newName
        renameRel = null
        runOp { fs.rename(rel, newRel) }
    }

    fun confirmDelete(rel: String) {
        deleteRel = null
        runOp { fs.delete(rel) }
    }

    fun confirmMultiDelete() {
        multiDelete = false
        val rels = selection.toList()
        exitSelection()
        if (rels.isEmpty()) return
        runOp { rels.forEach { fs.delete(it) } }
    }

    /** Copies [rels] into the target playlist folder and removes the originals (SAF can only rename within a dir). */
    fun confirmMove(playlist: String, rels: Set<String>) {
        moveTargets = null
        if (rels.isEmpty()) return
        val targets = rels.filter { "playlists/$playlist/${it.substringAfterLast('/')}" != it }
        exitSelection()
        if (targets.isEmpty()) return
        runOp {
            targets.forEach { rel ->
                fs.write("playlists/$playlist/${rel.substringAfterLast('/')}", fs.read(rel))
                fs.delete(rel)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumbs: Root / a / b — each segment jumps back to that level.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dir.isNotEmpty()) {
                IconButton(onClick = { navigateTo(dir.substringBeforeLast('/')) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Breadcrumb("Root") { navigateTo("") }
            var acc = ""
            for (part in dir.split('/')) {
                Text(" / ")
                acc = if (acc.isEmpty()) part else "$acc/$part"
                Breadcrumb(part) { navigateTo(acc) }
            }
        }

        // Move-to-playlist dialog: pick one target playlist, stay on this folder.
        moveTargets?.let { targets ->
            val playlists by produceState(initialValue = emptyList<Pair<String, Int>>(), fs) {
                value = withContext(Dispatchers.IO) {
                    val all = runCatching { fs.list() }.getOrDefault(emptyList())
                    runCatching { fs.listDir("playlists") }.getOrDefault(emptyList())
                        .filter { it.isDirectory }
                        .map { it.rel.substringAfterLast('/') }
                        .map { name -> name to all.count { it.rel.startsWith("playlists/$name/") } }
                        .sortedBy { it.first }
                }
            }
            AlertDialog(
                onDismissRequest = { moveTargets = null },
                title = {
                    Text(
                        if (targets.size == 1) "Move \"${targets.first().substringAfterLast('/')}\" to playlist"
                        else "Move ${targets.size} songs to playlist",
                    )
                },
                text = {
                    if (playlists.isEmpty()) {
                        Text("No playlists found under playlists/")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(playlists) { (name, count) ->
                                val alreadyThere = targets.all { it.substringBeforeLast('/') == "playlists/$name" }
                                TextButton(
                                    onClick = { confirmMove(name, targets) },
                                    enabled = !alreadyThere,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("$name  ·  $count song${if (count == 1) "" else "s"}", Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { newPlaylist = true; moveTargets = null }) { Text("+ New playlist") }
                        TextButton(onClick = { moveTargets = null }) { Text("Cancel") }
                    }
                },
            )
        }

        if (newPlaylist) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { newPlaylist = false },
                title = { Text("New playlist") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val n = name.trim()
                            newPlaylist = false
                            if (n.isNotEmpty()) {
                                val targets = selection
                                exitSelection()
                                runOp {
                                    fs.mkdirs("playlists/$n")
                                    targets.forEach { rel ->
                                        fs.write("playlists/$n/${rel.substringAfterLast('/')}", fs.read(rel))
                                        fs.delete(rel)
                                    }
                                }
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("Create & move") }
                },
                dismissButton = { TextButton(onClick = { newPlaylist = false }) { Text("Cancel") } },
            )
        }

        // Playlist actions: rename a playlist folder, or export it as .m3u.
        folderMenuRel?.let { folderRel ->
            AlertDialog(
                onDismissRequest = { folderMenuRel = null },
                title = { Text(folderRel.substringAfterLast('/')) },
                text = {
                    Column {
                        TextButton(
                            onClick = { folderMenuRel = null; renameFolderRel = folderRel },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Rename playlist") }
                        TextButton(
                            onClick = {
                                folderMenuRel = null
                                val name = folderRel.substringAfterLast('/')
                                runOp {
                                    val songs = fs.list().filter { it.rel.startsWith("$folderRel/") }.map { it.rel }
                                    fs.write("$folderRel/$name.m3u", m3uContent(name, songs).encodeToByteArray())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export as .m3u") }
                    }
                },
                confirmButton = { TextButton(onClick = { folderMenuRel = null }) { Text("Cancel") } },
            )
        }

        renameFolderRel?.let { folderRel ->
            var name by remember { mutableStateOf(folderRel.substringAfterLast('/')) }
            AlertDialog(
                onDismissRequest = { renameFolderRel = null },
                title = { Text("Rename playlist") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val n = name.trim()
                            renameFolderRel = null
                            if (n.isNotEmpty() && n != folderRel.substringAfterLast('/')) {
                                runOp { fs.rename(folderRel, dirPrefix(folderRel.substringBeforeLast('/')) + n) }
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renameFolderRel = null }) { Text("Cancel") } },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search songs…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${selection.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { startMultiMove() }, enabled = selection.isNotEmpty()) { Text("Move to playlist") }
                TextButton(onClick = { multiDelete = true }, enabled = selection.isNotEmpty()) { Text("Delete") }
                IconButton(onClick = { exitSelection() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit selection") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sort:", style = MaterialTheme.typography.bodySmall)
                listOf(SortMode.NAME to "Name", SortMode.SIZE to "Size", SortMode.DATE to "Date").forEach { (mode, label) ->
                    FilterChip(selected = sortMode == mode, onClick = { sortMode = mode }, label = { Text(label) })
                }
            }
        }

        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }

        if (query.isNotBlank()) {
            when {
                searching -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text("Searching…")
                }

                results.isNullOrEmpty() -> Text(
                    "No songs match \"${query.trim()}\"",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results!!) { e ->
                        SearchRow(e) {
                            navigateTo(e.rel.substringBeforeLast('/'))
                            query = ""
                        }
                    }
                }
            }
        } else if (entries.isEmpty()) {
            Text(
                "No songs in this folder",
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { (if (it is BrowserEntry.Dir) "d:" else "s:") + it.name }) { entry ->
                    when (entry) {
                        is BrowserEntry.Dir -> Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { navigateTo(dirPrefix(dir) + entry.name) },
                                    onLongClick = { folderMenuRel = dirPrefix(dir) + entry.name },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(entry.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            if (entry.count > 0) {
                                Text(
                                    "(${entry.count})",
                                    Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is BrowserEntry.Song -> SongRow(
                            entry = entry,
                            menuOpen = menuRel == entry.entry.rel && !selectionMode,
                            enabled = true,
                            selected = selectionMode && entry.entry.rel in selection,
                            selectionMode = selectionMode,
                            onPlay = { vm.playInExternalPlayer(entry.entry.rel) },
                            onOpenMenu = { if (selectionMode) toggleSelect(entry.entry.rel) else menuRel = entry.entry.rel },
                            onLongPress = {
                                selectionMode = true
                                selection = setOf(entry.entry.rel)
                            },
                            onToggle = { toggleSelect(entry.entry.rel) },
                            onDismissMenu = { menuRel = null },
                            onRename = { renameRel = entry.entry.rel },
                            onMove = { startMove(entry.entry.rel) },
                            onDelete = { deleteRel = entry.entry.rel },
                            onEdit = { onEditSong(entry.entry.rel) },
                        )
                    }
                }
            }
        }
    }

    renameRel?.let { rel ->
        RenameDialog(rel, onConfirm = { newName -> confirmRename(rel, newName) }, onDismiss = { renameRel = null })
    }
    deleteRel?.let { rel ->
        DeleteDialog(rel, onConfirm = { confirmDelete(rel) }, onDismiss = { deleteRel = null })
    }
    if (multiDelete) {
        AlertDialog(
            onDismissRequest = { multiDelete = false },
            title = { Text("Delete ${selection.size} songs?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmMultiDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { multiDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Breadcrumb(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable { onClick() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    entry: BrowserEntry.Song,
    menuOpen: Boolean,
    enabled: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(enabled = enabled, onClick = onOpenMenu, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play in external player",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { onPlay() },
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(entry.entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismissMenu(); onRename() })
            DropdownMenuItem(text = { Text("Move to playlist") }, onClick = { onDismissMenu(); onMove() })
            DropdownMenuItem(text = { Text("Edit metadata") }, onClick = { onDismissMenu(); onEdit() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onDismissMenu(); onDelete() })
        }
    }
}

@Composable
private fun SearchRow(entry: FsEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(entry.rel.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "in ${entry.rel.substringBeforeLast('/').ifEmpty { "root" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(rel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val currentName = rel.substringAfterLast('/')
    var name by remember(rel) { mutableStateOf(currentName) }
    val valid = name.isNotBlank() && '/' !in name && name != currentName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("New name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = valid) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(rel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete") },
        text = { Text("Delete \"${rel.substringAfterLast('/')}\"? This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
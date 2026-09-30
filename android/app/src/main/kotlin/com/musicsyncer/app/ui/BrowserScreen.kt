package com.musicsyncer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.musicsyncer.engine.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the browser: a subdirectory (drill-down) or a song file. */
private sealed interface BrowserEntry {
    val name: String

    data class Dir(override val name: String) : BrowserEntry

    data class Song(val entry: FsEntry) : BrowserEntry {
        override val name: String get() = entry.rel.substringAfterLast('/')
    }
}

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
    var moveRel by remember { mutableStateOf<String?>(null) }   // song being moved (non-null = move-picker mode)
    var moveTargetDir by remember { mutableStateOf("") }        // folder navigated to while picking a move target
    var error by remember { mutableStateOf<String?>(null) }

    // While picking a move target the tree navigates moveTargetDir instead of dir.
    val currentDir = if (moveRel != null) moveTargetDir else dir

    val entries by produceState(initialValue = emptyList<BrowserEntry>(), currentDir, refreshTick, fs) {
        val result = withContext(Dispatchers.IO) { runCatching { fs.listDir(currentDir) } }
        result.exceptionOrNull()?.let { error = it.message ?: "Failed to list folder" }
        val children = result.getOrDefault(emptyList())
        val songs = children.filter { !it.isDirectory }
            .map { BrowserEntry.Song(FsEntry(it.rel, it.size, it.mtimeNs)) }
            .sortedBy { it.entry.rel }
        val dirs = children.filter { it.isDirectory }
            .map { BrowserEntry.Dir(it.rel.substringAfterLast('/')) }
            .sortedBy { it.name }
        value = dirs + songs
    }

    fun navigateTo(target: String) {
        menuRel = null
        if (moveRel != null) moveTargetDir = target else dir = target
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

    fun startMove(rel: String) {
        moveRel = rel
        moveTargetDir = rel.substringBeforeLast('/') // start at the song's current folder
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

    fun confirmMove(rel: String) {
        val targetRel = dirPrefix(moveTargetDir) + rel.substringAfterLast('/')
        moveRel = null
        if (targetRel == rel) return // already in the target folder
        runOp {
            fs.write(targetRel, fs.read(rel))
            fs.delete(rel)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumbs: Root / a / b — each segment jumps back to that level.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentDir.isNotEmpty()) {
                IconButton(onClick = { navigateTo(currentDir.substringBeforeLast('/')) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Breadcrumb("Root") { navigateTo("") }
            var acc = ""
            for (part in currentDir.split('/')) {
                Text(" / ")
                acc = if (acc.isEmpty()) part else "$acc/$part"
                Breadcrumb(part) { navigateTo(acc) }
            }
        }

        // Move-picker banner: choose a destination folder, then confirm.
        moveRel?.let { rel ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Moving \"${rel.substringAfterLast('/')}\"", style = MaterialTheme.typography.titleSmall)
                    Text("Pick a destination folder, then confirm.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { confirmMove(rel) },
                    enabled = dirPrefix(moveTargetDir) + rel.substringAfterLast('/') != rel,
                ) { Text("Move here") }
                TextButton(onClick = { moveRel = null }) { Text("Cancel") }
            }
        }

        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { (if (it is BrowserEntry.Dir) "d:" else "s:") + it.name }) { entry ->
                when (entry) {
                    is BrowserEntry.Dir -> Row(
                        Modifier.fillMaxWidth().clickable { navigateTo(dirPrefix(currentDir) + entry.name) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(entry.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }

                    is BrowserEntry.Song -> SongRow(
                        entry = entry,
                        menuOpen = menuRel == entry.entry.rel,
                        enabled = moveRel == null,
                        onOpenMenu = { menuRel = entry.entry.rel },
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

    renameRel?.let { rel ->
        RenameDialog(rel, onConfirm = { newName -> confirmRename(rel, newName) }, onDismiss = { renameRel = null })
    }
    deleteRel?.let { rel ->
        DeleteDialog(rel, onConfirm = { confirmDelete(rel) }, onDismiss = { deleteRel = null })
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

@Composable
private fun SongRow(
    entry: BrowserEntry.Song,
    menuOpen: Boolean,
    enabled: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onOpenMenu() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(entry.entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismissMenu(); onRename() })
            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { onDismissMenu(); onMove() })
            DropdownMenuItem(text = { Text("Edit metadata") }, onClick = { onDismissMenu(); onEdit() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onDismissMenu(); onDelete() })
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
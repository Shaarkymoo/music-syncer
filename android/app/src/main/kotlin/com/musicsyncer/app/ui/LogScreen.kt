package com.musicsyncer.app.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.musicsyncer.app.MusicViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LogScreen(vm: MusicViewModel) {
    val entries by produceState(initialValue = emptyList<String>()) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        value = withContext(Dispatchers.IO) {
            vm.store.journalSince(0).takeLast(200).map { op ->
                "${fmt.format(Date(op.tsNs / 1_000_000))}  ${op.device}  ${op.op}  ${op.path}"
            }
        }
    }
    LazyColumn { items(entries) { Text(it) } }
}
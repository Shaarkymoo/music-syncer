package com.musicsyncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.musicsyncer.app.ui.BrowserScreen
import com.musicsyncer.app.ui.EditorScreen
import com.musicsyncer.app.ui.StatusScreen

private enum class Screen { Status, Browser }

class MainActivity : ComponentActivity() {
    private val vm: MusicViewModel by lazy { ViewModelProvider(this)[MusicViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.restoreFolder()
        vm.discovery.start()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf(Screen.Status) }
                    var editRel by rememberSaveable { mutableStateOf<String?>(null) }
                    val editing = editRel != null
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when {
                                editing -> EditorScreen(vm, editRel!!, onClose = { editRel = null })
                                screen == Screen.Status -> StatusScreen(vm)
                                else -> BrowserScreen(vm, onEditSong = { editRel = it })
                            }
                        }
                        if (!editing) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.Status,
                                    onClick = { screen = Screen.Status },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    label = { Text("Status") },
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.Browser,
                                    onClick = { screen = Screen.Browser },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    label = { Text("Browse") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
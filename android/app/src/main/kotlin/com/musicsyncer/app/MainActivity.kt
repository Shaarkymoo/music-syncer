package com.musicsyncer.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.musicsyncer.app.ui.BrowserScreen
import com.musicsyncer.app.ui.EditorScreen
import com.musicsyncer.app.ui.LogScreen
import com.musicsyncer.app.ui.StatusScreen
import com.musicsyncer.app.ui.theme.MusicSyncTheme

private enum class Screen(val title: String) { Status("Status"), Browser("Browse"), Log("Log") }

class MainActivity : ComponentActivity() {
    private val vm: MusicViewModel by lazy { ViewModelProvider(this)[MusicViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: content draws under the system bars; both bars are
        // transparent with light (dark-theme) icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        vm.restoreFolder()
        vm.discovery.start()
        setContent {
            MusicSyncTheme {
                MusicSyncApp(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSyncApp(vm: MusicViewModel) {
    Surface(modifier = Modifier.fillMaxSize()) {
        var screen by rememberSaveable { mutableStateOf(Screen.Status) }
        var editRel by rememberSaveable { mutableStateOf<String?>(null) }
        val editing = editRel != null
        Scaffold(
            topBar = {
                if (!editing) {
                    Column {
                        // Keep the notification bar visually distinct from the app bar:
                        // the status-bar strip shows the background, the bar sits below it.
                        Spacer(Modifier.statusBarsPadding())
                        TopAppBar(
                            title = { Text(screen.title) },
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            },
            bottomBar = {
                if (!editing) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavigationBarItem(
                            selected = screen == Screen.Status,
                            onClick = { screen = Screen.Status },
                            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            label = { Text("Status") },
                            colors = navColors(),
                        )
                        NavigationBarItem(
                            selected = screen == Screen.Browser,
                            onClick = { screen = Screen.Browser },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("Browse") },
                            colors = navColors(),
                        )
                        NavigationBarItem(
                            selected = screen == Screen.Log,
                            onClick = { screen = Screen.Log },
                            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            label = { Text("Log") },
                            colors = navColors(),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                if (editing) Modifier.fillMaxSize().systemBarsPadding()
                else Modifier.fillMaxSize().padding(padding)
            ) {
                Crossfade(targetState = screen, label = "screen") { s ->
                    when {
                        editing -> EditorScreen(vm, editRel!!, onClose = { editRel = null })
                        s == Screen.Status -> StatusScreen(vm)
                        s == Screen.Browser -> BrowserScreen(vm, onEditSong = { editRel = it })
                        else -> LogScreen(vm)
                    }
                }
            }
        }
    }
}

/** Nav item colors: orange when selected (with a burnt-orange indicator pill), gray otherwise. */
@Composable
private fun navColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
package com.musicsyncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.musicsyncer.app.ui.StatusScreen

class MainActivity : ComponentActivity() {
    private val vm: MusicViewModel by lazy { ViewModelProvider(this)[MusicViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.restoreFolder()
        vm.discovery.start()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen(vm)
                }
            }
        }
    }
}
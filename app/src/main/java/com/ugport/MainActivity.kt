package com.ugport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ugport.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as UgPortApp).container
        setContent {
            AppRoot(container)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            (application as UgPortApp).container.playback.stopAll()
        }
        super.onDestroy()
    }
}

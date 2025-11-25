package com.example.cardsnow

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    // Use the new KtorViewModel instead of Firebase GameViewModel
    private val viewModel: KtorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Connect to Ktor backend server
        viewModel.connectToServer()
        
        setContent {
            CardGameApp(viewModel, this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // KtorViewModel handles cleanup automatically in onCleared()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        println("Configuration changed: ${newConfig.screenHeightDp}x${newConfig.screenWidthDp}, orientation=${newConfig.orientation}")
    }
}
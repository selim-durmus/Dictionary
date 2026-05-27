package com.example.translate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.translate.ui.HomePager
import com.example.translate.ui.theme.Background
import com.example.translate.ui.theme.TranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as TranslateApp).container.repository

        setContent {
            TranslateTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background),
                    color = Background,
                ) {
                    HomePager(repository = repository)
                }
            }
        }
    }
}

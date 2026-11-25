package com.dfalling.tareas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.dfalling.tareas.ui.theme.TareasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TareasTheme {
                // Placeholder until the real UI lands in Phase 4.
                Box(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Tareas", fontSize = 28.sp)
                }
            }
        }
    }
}

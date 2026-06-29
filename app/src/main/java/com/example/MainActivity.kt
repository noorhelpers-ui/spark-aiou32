package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enforce full bleed layout edges drawing under status/navigation bars safely
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                MainLayoutApp(this)
            }
        }
    }
}

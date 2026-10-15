package com.example.frame_trip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.frame_trip.ui.map.PhotoMapRoute
import com.example.frame_trip.ui.theme.Frame_tripTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Frame_tripTheme {
                PhotoMapRoute()
            }
        }
    }
}

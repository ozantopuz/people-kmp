package com.example.people

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.people.di.androidDataModule

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val platformModule = androidDataModule(applicationContext)
        setContent {
            App(platformModule)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    val context = LocalContext.current.applicationContext
    val platformModule = remember(context) { androidDataModule(context) }
    App(platformModule)
}

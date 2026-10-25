package com.example.people

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.example.people.designsystem.theme.PeopleTheme
import com.example.people.di.appModule
import com.example.people.presentation.UsersRoute
import org.koin.compose.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

@Composable
fun App(platformModule: Module) {
    KoinApplication(configuration = koinConfiguration { modules(appModule, platformModule) }) {
        LaunchedEffect(Unit) {
            onPlatformKoinReady()
        }
        PeopleTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                UsersRoute()
            }
        }
    }
}

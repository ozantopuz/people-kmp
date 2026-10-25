package com.example.people

import androidx.compose.ui.window.ComposeUIViewController
import com.example.people.di.iosDataModule
import com.example.people.lifecycle.AppLifecycleManager
import org.koin.mp.KoinPlatformTools
import platform.UIKit.UIViewController

private var iosPendingForeground = false
private var iosIsForeground = false

fun MainViewController(): UIViewController {
    val platformModule = iosDataModule()
    return ComposeUIViewController { App(platformModule) }
}

fun onIosAppForeground() {
    iosPendingForeground = true
    iosIsForeground = true
    checkAndNotifyIosForeground()
}

fun onIosAppBackground() {
    iosPendingForeground = false
    iosIsForeground = false
    getAppLifecycleManager()?.onBackground()
}

internal fun checkAndNotifyIosForeground() {
    if (iosIsForeground && iosPendingForeground) {
        val manager = getAppLifecycleManager()
        if (manager != null) {
            manager.onForeground()
            iosPendingForeground = false
        }
    }
}

private fun getAppLifecycleManager(): AppLifecycleManager? {
    return KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<AppLifecycleManager>()
}

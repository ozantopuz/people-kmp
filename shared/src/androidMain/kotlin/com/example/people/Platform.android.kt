package com.example.people

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

object AndroidKoinReadyRegistry {
    private var listener: (() -> Unit)? = null

    fun register(onKoinReady: () -> Unit) {
        listener = onKoinReady
    }

    internal fun notifyKoinReady() {
        listener?.invoke()
    }
}

actual fun onPlatformKoinReady() {
    AndroidKoinReadyRegistry.notifyKoinReady()
}
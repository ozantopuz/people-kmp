package com.example.people

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun onPlatformKoinReady()
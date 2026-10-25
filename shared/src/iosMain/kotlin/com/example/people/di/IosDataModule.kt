package com.example.people.di

import androidx.room.Room
import com.example.people.data.connectivity.IosConnectivityObserver
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.buildPeopleDatabase
import com.example.people.domain.connectivity.ConnectivityObserver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal fun iosDataModule(): Module = module {
    single<ConnectivityObserver> { IosConnectivityObserver() }
    single<HttpClientEngine> { Darwin.create() }
    single {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        val path = requireNotNull(directory?.path) { "Application Support directory unavailable" }
        buildPeopleDatabase(Room.databaseBuilder<PeopleDatabase>(name = "$path/people.db"))
    }
}

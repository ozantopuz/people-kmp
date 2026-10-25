package com.example.people.di

import android.content.Context
import androidx.room.Room
import com.example.people.data.connectivity.AndroidConnectivityObserver
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.buildPeopleDatabase
import com.example.people.domain.connectivity.ConnectivityObserver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidDataModule(context: Context): Module {
    val applicationContext = context.applicationContext
    return module {
        single<ConnectivityObserver> { AndroidConnectivityObserver(applicationContext) }
        single<HttpClientEngine> { OkHttp.create() }
        single {
            buildPeopleDatabase(
                Room.databaseBuilder<PeopleDatabase>(
                    context = applicationContext,
                    name = applicationContext.getDatabasePath("people.db").absolutePath,
                )
            )
        }
    }
}

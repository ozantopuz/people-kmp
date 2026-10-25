package com.example.people.di

import com.example.people.data.local.PeopleDatabase
import com.example.people.data.delete.DeleteProcessor
import com.example.people.data.delete.DeleteTransactionProcessor
import com.example.people.data.config.GoRestConfig
import com.example.people.data.local.UserCache
import com.example.people.data.remote.GoRestApi
import com.example.people.data.remote.UsersRemoteDataSource
import com.example.people.data.remote.createGoRestHttpClient
import com.example.people.data.repository.DefaultUserRepository
import com.example.people.domain.connectivity.ConnectivityObserver
import com.example.people.domain.repository.UserRepository
import com.example.people.lifecycle.AppLifecycleManager
import com.example.people.presentation.UsersViewModel
import org.koin.dsl.module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal val appModule = module {
    // The Compose lifecycle ViewModelStore owns the instance; Koin supplies its dependencies.
    factory { UsersViewModel(repository = get(), deleteProcessor = get(), connectivity = get<ConnectivityObserver>().isOnline) }
    single { createGoRestHttpClient(get()) }
    single<UsersRemoteDataSource> { GoRestApi(get(), writesEnabled = GoRestConfig.token.isNotBlank()) }
    single<UserCache> { get<PeopleDatabase>().userDao() }
    single<UserRepository> { DefaultUserRepository(remote = get(), cache = get()) }
    // Shared scope for background tasks / processor
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single {
        DeleteTransactionProcessor(
            database = get(), remote = get(), scope = get(),
            writesEnabled = GoRestConfig.token.isNotBlank(), refreshRepository = get(),
        )
    }
    single<DeleteProcessor> { get<DeleteTransactionProcessor>() }
    single {
        AppLifecycleManager(
            processor = get(),
            database = get(),
            httpClient = get(),
            scope = get(),
        )
    }
}

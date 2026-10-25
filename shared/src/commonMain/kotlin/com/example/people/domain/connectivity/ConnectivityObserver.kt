package com.example.people.domain.connectivity

import kotlinx.coroutines.flow.Flow

/** Reports whether the device currently has an internet-capable route. */
interface ConnectivityObserver {
    val isOnline: Flow<Boolean>
}

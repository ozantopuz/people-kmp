package com.example.people.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.people.domain.connectivity.ConnectivityObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val isOnline: Flow<Boolean> = callbackFlow {
        fun online(network: Network?): Boolean = network?.let {
            manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(online(network)) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            override fun onLost(network: Network) { trySend(online(manager.activeNetwork)) }
        }
        trySend(online(manager.activeNetwork))
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

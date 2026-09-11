package com.example.simplemediadownloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isMetered: Boolean,
) {
    fun isAllowed(wifiOnly: Boolean): Boolean {
        if (!isConnected) return false
        if (!wifiOnly) return true
        return isWifi || !isMetered
    }

    companion object {
        val DISCONNECTED = NetworkStatus(isConnected = false, isWifi = false, isMetered = true)
    }
}

interface NetworkConnectivityManager {
    val status: StateFlow<NetworkStatus>
    fun isNetworkAllowed(wifiOnly: Boolean): Boolean = status.value.isAllowed(wifiOnly)
    fun startObserving() {}
    fun stopObserving() {}
}

class DefaultNetworkConnectivityManager(
    private val context: Context,
) : NetworkConnectivityManager {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _status = MutableStateFlow(getCurrentStatus())
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun isNetworkAllowed(wifiOnly: Boolean): Boolean =
        status.value.isAllowed(wifiOnly)

    @Synchronized
    override fun startObserving() {
        if (networkCallback != null || connectivityManager == null) return
        _status.value = getCurrentStatus()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _status.value = getCurrentStatus()
            }

            override fun onLost(network: Network) {
                _status.value = getCurrentStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                _status.value = getCurrentStatus()
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            } catch (ignored: Exception) {}
        }
    }

    @Synchronized
    override fun stopObserving() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (ignored: Exception) {}
            networkCallback = null
        }
    }

    private fun getCurrentStatus(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus.DISCONNECTED
        val activeNetwork = cm.activeNetwork ?: return NetworkStatus.DISCONNECTED
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkStatus.DISCONNECTED

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return NetworkStatus(
            isConnected = hasInternet,
            isWifi = isWifi,
            isMetered = isMetered,
        )
    }
}

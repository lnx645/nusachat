package id.nusachat.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Status koneksi internet (best-effort, untuk flush antre offline).
 * Daftarkan sekali dari Application/VM, lepas saat tak dipakai.
 */
class NetworkMonitor(context: Context) {
    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            _online.value = isOnlineNow()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        _online.value = isOnlineNow()
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun isOnlineNow(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

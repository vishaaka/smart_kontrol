package com.example.kapiac

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WifiProvisioner(
    private val context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    suspend fun connectToDeviceAp(ssid: String, password: String): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(
                IllegalStateException("Android 10 altinda AP baglantisini uygulama icinden otomatik yapmak desteklenmiyor")
            )
        }

        release()

        return suspendCancellableCoroutine { continuation ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    if (continuation.isActive) {
                        continuation.resume(Result.success(Unit))
                    }
                }

                override fun onUnavailable() {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException("Cihaz AP baglantisi reddedildi veya kurulamadi")))
                    }
                }

                override fun onLost(network: Network) {
                    if (activeNetwork == network) {
                        connectivityManager.bindProcessToNetwork(null)
                        activeNetwork = null
                    }
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)

            continuation.invokeOnCancellation {
                release()
            }
        }
    }

    fun release() {
        connectivityManager.bindProcessToNetwork(null)
        activeNetwork = null
        activeNetworkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        activeNetworkCallback = null
    }

    companion object {
        fun requiredRuntimePermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}

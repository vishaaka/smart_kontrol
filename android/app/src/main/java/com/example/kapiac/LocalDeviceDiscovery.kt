package com.example.kapiac

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress

data class DiscoveryResult(
    val baseUrl: String,
    val status: DeviceStatus,
)

object LocalDeviceDiscovery {
    private const val DISCOVERY_CONNECT_TIMEOUT_MS = 450
    private const val DISCOVERY_READ_TIMEOUT_MS = 450
    private const val DISCOVERY_CONCURRENCY = 24

    suspend fun discover(context: Context, config: DeviceConfig): Result<DiscoveryResult> {
        return withContext(Dispatchers.IO) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipAddress == 0) {
                return@withContext Result.failure(
                    IllegalStateException("Telefon yerel Wi-Fi agina bagli degil")
                )
            }

            val hosts = buildCandidateHosts(ipAddress, config.baseUrl)
            if (hosts.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Yerel ag araligi olusturulamadi")
                )
            }

            coroutineScope {
                val semaphore = Semaphore(DISCOVERY_CONCURRENCY)
                val tasks = hosts.map { baseUrl ->
                    async {
                        semaphore.withPermit {
                            val result = DoorApi.fetchStatus(
                                config = config.copy(baseUrl = baseUrl),
                                connectTimeoutMs = DISCOVERY_CONNECT_TIMEOUT_MS,
                                readTimeoutMs = DISCOVERY_READ_TIMEOUT_MS,
                            )
                            if (result.ok && result.deviceStatus != null) {
                                DiscoveryResult(baseUrl, result.deviceStatus)
                            } else {
                                null
                            }
                        }
                    }
                }

                val found = tasks.awaitAll().firstOrNull { it != null }
                if (found != null) {
                    Result.success(found)
                } else {
                    Result.failure(
                        IllegalStateException("Cihaz yerel agda bulunamadi")
                    )
                }
            }
        }
    }

    private fun buildCandidateHosts(ipAddress: Int, baseUrl: String): List<String> {
        val currentIp = intToInetAddress(ipAddress)?.hostAddress.orEmpty()
        val localPrefix = currentIp.substringBeforeLast('.', "")
        if (localPrefix.isBlank()) {
            return emptyList()
        }

        val hosts = LinkedHashSet<String>()
        val savedHost = extractHost(baseUrl)
        if (savedHost.isNotBlank()) {
            hosts.add("http://$savedHost")
        }

        hosts.add("http://$localPrefix.1")
        hosts.add("http://$localPrefix.2")

        for (octet in 3..254) {
            val candidate = "$localPrefix.$octet"
            if (candidate != currentIp) {
                hosts.add("http://$candidate")
            }
        }

        hosts.add("http://192.168.4.1")
        return hosts.toList()
    }

    private fun extractHost(baseUrl: String): String {
        return baseUrl
            .trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
    }

    private fun intToInetAddress(ipAddress: Int): InetAddress? {
        val bytes = byteArrayOf(
            (ipAddress and 0xff).toByte(),
            (ipAddress shr 8 and 0xff).toByte(),
            (ipAddress shr 16 and 0xff).toByte(),
            (ipAddress shr 24 and 0xff).toByte(),
        )
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }
}

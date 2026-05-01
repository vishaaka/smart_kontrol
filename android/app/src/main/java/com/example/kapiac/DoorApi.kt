package com.example.kapiac

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

data class RelayRuntimeStatus(
    val channel: Int,
    val label: String,
    val mode: RelayMode,
    val pulseMs: Int,
    val state: Boolean,
    val recommendedAction: String,
)

data class DeviceStatus(
    val deviceName: String,
    val mode: String,
    val ip: String,
    val primaryRelay: Int,
    val channels: List<RelayRuntimeStatus>,
) {
    fun toDeviceConfig(baseUrl: String, token: String, relay2Visible: Boolean): DeviceConfig {
        val relay1Status = channels.firstOrNull { it.channel == 1 }
        val relay2Status = channels.firstOrNull { it.channel == 2 }
        return DeviceConfig(
            baseUrl = baseUrl,
            token = token,
            deviceName = deviceName,
            primaryRelay = primaryRelay.coerceIn(1, 2),
            relay2Visible = relay2Visible,
            relay1 = RelayConfig(
                label = relay1Status?.label ?: "Kapi Ac",
                mode = relay1Status?.mode ?: RelayMode.MOMENTARY,
                pulseMs = relay1Status?.pulseMs ?: 500,
            ),
            relay2 = RelayConfig(
                label = relay2Status?.label ?: "Yedek Role",
                mode = relay2Status?.mode ?: RelayMode.MOMENTARY,
                pulseMs = relay2Status?.pulseMs ?: 500,
            ),
        )
    }
}

data class DoorApiResult(
    val ok: Boolean,
    val message: String,
    val deviceStatus: DeviceStatus? = null,
)

object DoorApi {
    private const val DEFAULT_BOOTSTRAP_PASSWORD = "KapiAc123"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    private const val DEFAULT_READ_TIMEOUT_MS = 5_000

    fun performRelayAction(
        config: DeviceConfig,
        channel: Int,
        authTokenOverride: String? = null,
    ): DoorApiResult {
        val relayConfig = config.relayConfig(channel)
        val action = if (relayConfig.mode == RelayMode.LATCH) "toggle" else "trigger"
        return sendRelayAction(config, channel, action, authTokenOverride)
    }

    fun sendRelayAction(
        config: DeviceConfig,
        channel: Int,
        action: String,
        authTokenOverride: String? = null,
    ): DoorApiResult {
        val url = "${config.baseUrl.trimEnd('/')}/api/channel?channel=$channel&action=${encode(action)}"
        val connection = createConnection(url, "POST", authTokenOverride ?: config.token)
        return runCatching {
            connection.connect()
            val responseCode = connection.responseCode
            val body = readBody(connection, responseCode < 400)
            parseApiResult(responseCode, body, "Komut gonderildi")
        }.getOrElse {
            DoorApiResult(false, it.message ?: "Baglanti hatasi")
        }.also {
            connection.disconnect()
        }
    }

    fun fetchStatus(
        config: DeviceConfig,
        authTokenOverride: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): DoorApiResult {
        val authToken = authTokenOverride ?: config.token
        val tokenQuery = if (authToken.isNotBlank()) "?token=${encode(authToken)}" else ""
        val connection = createConnection(
            rawUrl = "${config.baseUrl.trimEnd('/')}/api/status$tokenQuery",
            method = "GET",
            token = authToken,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        return runCatching {
            connection.connect()
            val responseCode = connection.responseCode
            val body = readBody(connection, responseCode < 400)
            if (responseCode !in 200..299) {
                return@runCatching parseApiResult(responseCode, body, "Durum alinamadi")
            }

            val status = parseStatus(JSONObject(body))
            DoorApiResult(
                ok = true,
                message = "Bagli: ${status.mode} / ${status.ip}",
                deviceStatus = status,
            )
        }.getOrElse {
            DoorApiResult(false, it.message ?: "Durum alinamadi")
        }.also {
            connection.disconnect()
        }
    }

    fun saveRemoteConfig(config: DeviceConfig, authTokenOverride: String? = null): DoorApiResult {
        val connection = createConnection(
            "${config.baseUrl.trimEnd('/')}/api/config",
            "POST",
            authTokenOverride ?: config.token,
        )
        val body = buildFormBody(
            "deviceName" to config.deviceName,
            "token" to config.token,
            "primaryChannel" to config.primaryRelay.toString(),
            "ch1Label" to config.relay1.label,
            "ch1Mode" to config.relay1.mode.wireValue,
            "ch1PulseMs" to config.relay1.pulseMs.toString(),
            "ch2Label" to config.relay2.label,
            "ch2Mode" to config.relay2.mode.wireValue,
            "ch2PulseMs" to config.relay2.pulseMs.toString(),
        )

        return runCatching {
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val responseCode = connection.responseCode
            val responseBody = readBody(connection, responseCode < 400)
            if (responseCode !in 200..299) {
                return@runCatching parseApiResult(responseCode, responseBody, "Ayarlar kaydedilemedi")
            }

            val json = JSONObject(responseBody)
            val status = if (json.has("status")) parseStatus(json.getJSONObject("status")) else null
            DoorApiResult(
                ok = json.optBoolean("ok", true),
                message = json.optString("message", "Ayarlar kaydedildi"),
                deviceStatus = status,
            )
        }.getOrElse {
            DoorApiResult(false, it.message ?: "Ayarlar kaydedilemedi")
        }.also {
            connection.disconnect()
        }
    }

    fun provisionOverAp(
        config: DeviceConfig,
        homeWifiSsid: String,
        homeWifiPassword: String,
    ): DoorApiResult {
        val connection = createConnection("${config.baseUrl.trimEnd('/')}/api/provision", "POST", "")
        val body = buildFormBody(
            "bootstrapPassword" to DEFAULT_BOOTSTRAP_PASSWORD,
            "ssid" to homeWifiSsid,
            "password" to homeWifiPassword,
            "deviceName" to config.deviceName,
            "newToken" to config.token,
            "primaryChannel" to config.primaryRelay.toString(),
            "ch1Label" to config.relay1.label,
            "ch1Mode" to config.relay1.mode.wireValue,
            "ch1PulseMs" to config.relay1.pulseMs.toString(),
            "ch2Label" to config.relay2.label,
            "ch2Mode" to config.relay2.mode.wireValue,
            "ch2PulseMs" to config.relay2.pulseMs.toString(),
        )

        return runCatching {
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val responseCode = connection.responseCode
            val responseBody = readBody(connection, responseCode < 400)
            if (responseCode !in 200..299) {
                return@runCatching parseApiResult(responseCode, responseBody, "Kurulum yazilamadi")
            }

            val json = JSONObject(responseBody)
            val status = if (json.has("status")) parseStatus(json.getJSONObject("status")) else null
            DoorApiResult(
                ok = json.optBoolean("ok", true),
                message = json.optString("message", "Kurulum tamamlandi"),
                deviceStatus = status,
            )
        }.getOrElse {
            DoorApiResult(false, it.message ?: "Kurulum yazilamadi")
        }.also {
            connection.disconnect()
        }
    }

    private fun parseStatus(json: JSONObject): DeviceStatus {
        val channelArray = json.optJSONArray("channels") ?: JSONArray()
        val channels = buildList {
            for (index in 0 until channelArray.length()) {
                val item = channelArray.getJSONObject(index)
                add(
                    RelayRuntimeStatus(
                        channel = item.optInt("id", index + 1),
                        label = item.optString("label", "Role ${index + 1}"),
                        mode = RelayMode.fromWireValue(item.optString("mode")),
                        pulseMs = item.optInt("pulseMs", 500).coerceIn(200, 3000),
                        state = item.optBoolean("state", false),
                        recommendedAction = item.optString("recommendedAction", "trigger"),
                    )
                )
            }
        }

        return DeviceStatus(
            deviceName = json.optString("deviceName", "Kapi Ac"),
            mode = json.optString("mode", "bilinmiyor"),
            ip = json.optString("ip", "-"),
            primaryRelay = json.optInt("primaryChannel", 1).coerceIn(1, 2),
            channels = channels,
        )
    }

    private fun createConnection(
        rawUrl: String,
        method: String,
        token: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): HttpURLConnection {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.doInput = true
        if (token.isNotBlank()) {
            connection.setRequestProperty("X-Api-Token", token)
        }
        return connection
    }

    private fun readBody(connection: HttpURLConnection, success: Boolean): String {
        val stream = if (success) connection.inputStream else connection.errorStream
        if (stream == null) {
            return ""
        }

        return BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readText()
        }
    }

    private fun parseApiResult(responseCode: Int, body: String, fallbackMessage: String): DoorApiResult {
        if (responseCode !in 200..299) {
            return DoorApiResult(false, "HTTP $responseCode")
        }

        return runCatching {
            val json = JSONObject(body)
            DoorApiResult(
                ok = json.optBoolean("ok", true),
                message = json.optString("message", fallbackMessage),
            )
        }.getOrElse {
            DoorApiResult(true, fallbackMessage)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }
}

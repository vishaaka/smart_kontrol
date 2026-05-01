package com.example.kapiac

import android.content.Context

enum class RelayMode(val wireValue: String) {
    MOMENTARY("momentary"),
    LATCH("latch");

    companion object {
        fun fromWireValue(value: String?): RelayMode {
            return if (value.equals("latch", ignoreCase = true)) LATCH else MOMENTARY
        }
    }
}

data class RelayConfig(
    val label: String,
    val mode: RelayMode,
    val pulseMs: Int,
)

data class DeviceConfig(
    val baseUrl: String,
    val token: String,
    val deviceName: String,
    val primaryRelay: Int,
    val relay2Visible: Boolean,
    val relay1: RelayConfig,
    val relay2: RelayConfig,
) {
    fun relayConfig(channel: Int): RelayConfig {
        return if (channel == 2) relay2 else relay1
    }
}

data class ProvisioningPrefs(
    val deviceApSsid: String,
    val homeWifiSsid: String,
    val homeWifiPassword: String,
)

object DeviceConfigStore {
    private const val PREFS_NAME = "door_device_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PRIMARY_RELAY = "primary_relay"
    private const val KEY_RELAY2_VISIBLE = "relay2_visible"
    private const val KEY_RELAY1_LABEL = "relay1_label"
    private const val KEY_RELAY2_LABEL = "relay2_label"
    private const val KEY_RELAY1_MODE = "relay1_mode"
    private const val KEY_RELAY2_MODE = "relay2_mode"
    private const val KEY_RELAY1_PULSE = "relay1_pulse"
    private const val KEY_RELAY2_PULSE = "relay2_pulse"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_DEVICE_AP_SSID = "device_ap_ssid"
    private const val KEY_HOME_WIFI_SSID = "home_wifi_ssid"
    private const val KEY_HOME_WIFI_PASSWORD = "home_wifi_password"

    fun load(context: Context): DeviceConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DeviceConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "http://192.168.4.1").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            deviceName = prefs.getString(KEY_DEVICE_NAME, "Kapi Ac").orEmpty(),
            primaryRelay = prefs.getInt(KEY_PRIMARY_RELAY, 1).coerceIn(1, 2),
            relay2Visible = prefs.getBoolean(KEY_RELAY2_VISIBLE, false),
            relay1 = RelayConfig(
                label = prefs.getString(KEY_RELAY1_LABEL, "Kapi Ac").orEmpty(),
                mode = RelayMode.fromWireValue(prefs.getString(KEY_RELAY1_MODE, RelayMode.MOMENTARY.wireValue)),
                pulseMs = prefs.getInt(KEY_RELAY1_PULSE, 500).coerceIn(200, 3000),
            ),
            relay2 = RelayConfig(
                label = prefs.getString(KEY_RELAY2_LABEL, "Yedek Role").orEmpty(),
                mode = RelayMode.fromWireValue(prefs.getString(KEY_RELAY2_MODE, RelayMode.MOMENTARY.wireValue)),
                pulseMs = prefs.getInt(KEY_RELAY2_PULSE, 500).coerceIn(200, 3000),
            ),
        )
    }

    fun save(context: Context, config: DeviceConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_DEVICE_NAME, config.deviceName.trim())
            .putInt(KEY_PRIMARY_RELAY, config.primaryRelay.coerceIn(1, 2))
            .putBoolean(KEY_RELAY2_VISIBLE, config.relay2Visible)
            .putString(KEY_RELAY1_LABEL, config.relay1.label.trim())
            .putString(KEY_RELAY2_LABEL, config.relay2.label.trim())
            .putString(KEY_RELAY1_MODE, config.relay1.mode.wireValue)
            .putString(KEY_RELAY2_MODE, config.relay2.mode.wireValue)
            .putInt(KEY_RELAY1_PULSE, config.relay1.pulseMs.coerceIn(200, 3000))
            .putInt(KEY_RELAY2_PULSE, config.relay2.pulseMs.coerceIn(200, 3000))
            .apply()
    }

    fun saveLastStatus(context: Context, message: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, message)
            .apply()
    }

    fun loadLastStatus(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATUS, "Hazir")
            .orEmpty()
    }

    fun loadProvisioningPrefs(context: Context): ProvisioningPrefs {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ProvisioningPrefs(
            deviceApSsid = prefs.getString(KEY_DEVICE_AP_SSID, "KapiAc-XXXXXX").orEmpty(),
            homeWifiSsid = prefs.getString(KEY_HOME_WIFI_SSID, "").orEmpty(),
            homeWifiPassword = prefs.getString(KEY_HOME_WIFI_PASSWORD, "").orEmpty(),
        )
    }

    fun saveProvisioningPrefs(context: Context, prefs: ProvisioningPrefs) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_AP_SSID, prefs.deviceApSsid.trim())
            .putString(KEY_HOME_WIFI_SSID, prefs.homeWifiSsid.trim())
            .putString(KEY_HOME_WIFI_PASSWORD, prefs.homeWifiPassword)
            .apply()
    }
}

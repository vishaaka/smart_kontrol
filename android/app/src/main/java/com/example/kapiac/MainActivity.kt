package com.example.kapiac

import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.kapiac.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var deviceTitleText: TextView
    private lateinit var deviceAddressText: TextView
    private lateinit var primaryControlButton: Button
    private lateinit var secondaryControlButton: Button
    private lateinit var primaryCaptionText: TextView
    private lateinit var secondaryCaptionText: TextView
    private lateinit var secondaryGroup: View
    private lateinit var networkSubtitleText: TextView
    private lateinit var networkStateText: TextView
    private lateinit var statusText: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var wifiProvisioner: WifiProvisioner
    private lateinit var wifiManager: WifiManager

    private var currentConfig: DeviceConfig? = null
    private var currentProvisioningPrefs = ProvisioningPrefs("", "", "")
    private var onPermissionsGranted: (() -> Unit)? = null
    private var activeProvisioningViews: ProvisioningViews? = null

    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            val callback = onPermissionsGranted
            onPermissionsGranted = null
            if (allGranted) {
                callback?.invoke()
            } else {
                updateStatus("Wi-Fi listesi icin izin gerekli")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiProvisioner = WifiProvisioner(this)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        bindMainViews()
        currentConfig = DeviceConfigStore.load(this)
        currentProvisioningPrefs = DeviceConfigStore.loadProvisioningPrefs(this)

        primaryControlButton.setOnClickListener {
            val channel = (primaryControlButton.tag as? Int) ?: currentDeviceConfig().primaryRelay
            triggerRelay(channel)
        }

        secondaryControlButton.setOnClickListener {
            val channel = (secondaryControlButton.tag as? Int) ?: 2
            triggerRelay(channel)
        }

        settingsButton.setOnClickListener {
            showSettingsMenu()
        }
        closeButton.setOnClickListener {
            finishAffinity()
        }

        renderMainScreen()
        updateStatus(DeviceConfigStore.loadLastStatus(this), persist = false)
    }

    override fun onResume() {
        super.onResume()
        syncProvisioningPrefsWithCurrentNetwork()
        activeProvisioningViews?.let { views ->
            populateProvisioningInputs(views, preserveHomeWifiText = true)
        }
    }

    private fun bindMainViews() {
        deviceTitleText = findViewById(R.id.deviceTitleText)
        deviceAddressText = findViewById(R.id.deviceAddressText)
        primaryControlButton = findViewById(R.id.primaryControlButton)
        secondaryControlButton = findViewById(R.id.secondaryControlButton)
        primaryCaptionText = findViewById(R.id.primaryCaptionText)
        secondaryCaptionText = findViewById(R.id.secondaryCaptionText)
        secondaryGroup = findViewById(R.id.secondaryGroup)
        networkSubtitleText = findViewById(R.id.networkSubtitleText)
        networkStateText = findViewById(R.id.networkStateText)
        statusText = findViewById(R.id.statusText)
        settingsButton = findViewById(R.id.settingsButton)
        closeButton = findViewById(R.id.closeButton)
    }

    private fun renderMainScreen() {
        val config = currentDeviceConfig()
        deviceTitleText.text = getString(R.string.dashboard_title)
        deviceAddressText.text = config.baseUrl.ifBlank { "Yerel adres henuz bulunmadi" }
        networkSubtitleText.text =
            if (config.baseUrl.isBlank() || config.baseUrl == "http://192.168.4.1") {
                getString(R.string.network_waiting_setup)
            } else {
                getString(R.string.network_health_subtitle)
            }
        networkStateText.text =
            if (config.baseUrl.isBlank() || config.baseUrl == "http://192.168.4.1") {
                getString(R.string.network_setup_state)
            } else {
                getString(R.string.network_active)
            }

        if (config.relay2Visible) {
            applyControlButton(primaryControlButton, 1, config.relay1.label, R.drawable.control_button_primary, dual = true)
            applyControlButton(secondaryControlButton, 2, config.relay2.label, R.drawable.control_button_secondary, dual = true)
            primaryCaptionText.text = getString(R.string.primary_default_caption)
            secondaryCaptionText.text = getString(R.string.secondary_default_caption)
            secondaryGroup.visibility = View.VISIBLE
        } else {
            val primaryChannel = config.primaryRelay.coerceIn(1, 2)
            val relay = config.relayConfig(primaryChannel)
            applyControlButton(
                primaryControlButton,
                primaryChannel,
                relay.label,
                if (primaryChannel == 1) R.drawable.control_button_primary else R.drawable.control_button_secondary,
                dual = false,
            )
            primaryCaptionText.text =
                if (primaryChannel == 1) getString(R.string.primary_default_caption)
                else getString(R.string.secondary_default_caption)
            secondaryGroup.visibility = View.GONE
        }

        WidgetUpdater.updateAll(this)
    }

    private fun applyControlButton(
        button: Button,
        channel: Int,
        label: String,
        backgroundRes: Int,
        dual: Boolean,
    ) {
        val size = calculateControlButtonSize(dual)
        button.tag = channel
        button.text = label.ifBlank { if (channel == 1) "Kapi Ac" else "Yedek Role" }
        button.setBackgroundResource(backgroundRes)
        val colorRes = if (backgroundRes == R.drawable.control_button_primary) {
            android.R.color.white
        } else {
            R.color.control_button_text
        }
        button.setTextColor(ContextCompat.getColor(this, colorRes))
        button.layoutParams = button.layoutParams.apply {
            width = size
            height = size
        }
        button.visibility = View.VISIBLE
    }

    private fun calculateControlButtonSize(dual: Boolean): Int {
        val widthPx = resources.displayMetrics.widthPixels
        val ratio = if (dual) 0.28f else 0.46f
        return (widthPx * ratio).toInt()
    }

    private fun showSettingsMenu() {
        val popupMenu = PopupMenu(this, settingsButton)
        popupMenu.menu.add(0, MENU_SETTINGS, 0, "Kontrol ayarlari")
        popupMenu.menu.add(0, MENU_PROVISION, 1, "Ilk kurulum")
        popupMenu.menu.add(0, MENU_DISCOVER, 2, "Yerel agda cihazi bul")
        popupMenu.menu.add(0, MENU_REFRESH_STATUS, 3, "Durumu yenile")

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SETTINGS -> showControlSettingsDialog()
                MENU_PROVISION -> showProvisioningDialog()
                MENU_DISCOVER -> discoverDeviceOnLocalNetwork()
                MENU_REFRESH_STATUS -> refreshStatusFromDevice()
            }
            true
        }

        popupMenu.show()
    }

    private fun showControlSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_control_settings, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val config = currentDeviceConfig()
        val deviceNameInput = view.findViewById<EditText>(R.id.settingsDeviceNameInput)
        val baseUrlInput = view.findViewById<EditText>(R.id.settingsBaseUrlInput)
        val tokenInput = view.findViewById<EditText>(R.id.settingsTokenInput)
        val primaryRelaySpinner = view.findViewById<Spinner>(R.id.settingsPrimaryRelaySpinner)
        val relay2VisibleSwitch = view.findViewById<SwitchCompat>(R.id.settingsRelay2VisibleSwitch)
        val relay1LabelInput = view.findViewById<EditText>(R.id.settingsRelay1LabelInput)
        val relay2LabelInput = view.findViewById<EditText>(R.id.settingsRelay2LabelInput)
        val relay1ModeSpinner = view.findViewById<Spinner>(R.id.settingsRelay1ModeSpinner)
        val relay2ModeSpinner = view.findViewById<Spinner>(R.id.settingsRelay2ModeSpinner)
        val relay1PulseInput = view.findViewById<EditText>(R.id.settingsRelay1PulseInput)
        val relay2PulseInput = view.findViewById<EditText>(R.id.settingsRelay2PulseInput)

        deviceNameInput.setText(config.deviceName)
        baseUrlInput.setText(config.baseUrl)
        tokenInput.setText(config.token)
        relay2VisibleSwitch.isChecked = config.relay2Visible
        relay1LabelInput.setText(config.relay1.label)
        relay2LabelInput.setText(config.relay2.label)
        relay1PulseInput.setText(config.relay1.pulseMs.toString())
        relay2PulseInput.setText(config.relay2.pulseMs.toString())

        val primaryChoices = resources.getStringArray(R.array.primary_channel_values)
        val primaryAdapter = ArrayAdapter(this, R.layout.item_spinner_blue, primaryChoices).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown_blue)
        }
        primaryRelaySpinner.adapter = primaryAdapter
        primaryRelaySpinner.setSelection(if (config.primaryRelay == 2) 1 else 0)

        val modeChoices = resources.getStringArray(R.array.relay_mode_values)
        val modeAdapter = ArrayAdapter(this, R.layout.item_spinner_blue, modeChoices).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown_blue)
        }
        relay1ModeSpinner.adapter = modeAdapter
        relay2ModeSpinner.adapter = modeAdapter
        relay1ModeSpinner.setSelection(if (config.relay1.mode == RelayMode.LATCH) 1 else 0)
        relay2ModeSpinner.setSelection(if (config.relay2.mode == RelayMode.LATCH) 1 else 0)

        view.findViewById<Button>(R.id.settingsSaveLocalButton).setOnClickListener {
            currentConfig = readSettingsConfig(
                deviceNameInput = deviceNameInput,
                baseUrlInput = baseUrlInput,
                tokenInput = tokenInput,
                primaryRelaySpinner = primaryRelaySpinner,
                relay2VisibleSwitch = relay2VisibleSwitch,
                relay1LabelInput = relay1LabelInput,
                relay2LabelInput = relay2LabelInput,
                relay1ModeSpinner = relay1ModeSpinner,
                relay2ModeSpinner = relay2ModeSpinner,
                relay1PulseInput = relay1PulseInput,
                relay2PulseInput = relay2PulseInput,
            )
            persistCurrentConfig()
            renderMainScreen()
            updateStatus("Yerel ayarlar kaydedildi")
        }

        view.findViewById<Button>(R.id.settingsPushConfigButton).setOnClickListener {
            val updatedConfig = readSettingsConfig(
                deviceNameInput = deviceNameInput,
                baseUrlInput = baseUrlInput,
                tokenInput = tokenInput,
                primaryRelaySpinner = primaryRelaySpinner,
                relay2VisibleSwitch = relay2VisibleSwitch,
                relay1LabelInput = relay1LabelInput,
                relay2LabelInput = relay2LabelInput,
                relay1ModeSpinner = relay1ModeSpinner,
                relay2ModeSpinner = relay2ModeSpinner,
                relay1PulseInput = relay1PulseInput,
                relay2PulseInput = relay2PulseInput,
            )
            currentConfig = updatedConfig
            persistCurrentConfig()
            renderMainScreen()
            runRequest("Ayarlar cihaza gonderiliyor...", updatedConfig) {
                DoorApi.saveRemoteConfig(updatedConfig, currentAuthToken(updatedConfig))
            }
        }

        view.findViewById<Button>(R.id.settingsCloseButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun readSettingsConfig(
        deviceNameInput: EditText,
        baseUrlInput: EditText,
        tokenInput: EditText,
        primaryRelaySpinner: Spinner,
        relay2VisibleSwitch: SwitchCompat,
        relay1LabelInput: EditText,
        relay2LabelInput: EditText,
        relay1ModeSpinner: Spinner,
        relay2ModeSpinner: Spinner,
        relay1PulseInput: EditText,
        relay2PulseInput: EditText,
    ): DeviceConfig {
        return DeviceConfig(
            baseUrl = baseUrlInput.text.toString().trim(),
            token = tokenInput.text.toString().trim(),
            deviceName = deviceNameInput.text.toString().trim().ifBlank { "Kapi Ac" },
            primaryRelay = if (primaryRelaySpinner.selectedItemPosition == 1) 2 else 1,
            relay2Visible = relay2VisibleSwitch.isChecked,
            relay1 = RelayConfig(
                label = relay1LabelInput.text.toString().trim().ifBlank { "Kapi Ac" },
                mode = if (relay1ModeSpinner.selectedItemPosition == 1) RelayMode.LATCH else RelayMode.MOMENTARY,
                pulseMs = relay1PulseInput.text.toString().toIntOrNull()?.coerceIn(200, 3000) ?: 500,
            ),
            relay2 = RelayConfig(
                label = relay2LabelInput.text.toString().trim().ifBlank { "Yedek Role" },
                mode = if (relay2ModeSpinner.selectedItemPosition == 1) RelayMode.LATCH else RelayMode.MOMENTARY,
                pulseMs = relay2PulseInput.text.toString().toIntOrNull()?.coerceIn(200, 3000) ?: 500,
            ),
        )
    }

    private fun showProvisioningDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_provisioning, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val deviceApSsidInput = view.findViewById<EditText>(R.id.provisionDeviceApSsidInput)
        val homeWifiSsidInput = view.findViewById<AutoCompleteTextView>(R.id.provisionHomeWifiSsidInput)
        val homeWifiPasswordInput = view.findViewById<EditText>(R.id.provisionHomeWifiPasswordInput)
        val provisioningViews = ProvisioningViews(
            dialog = dialog,
            deviceApSsidInput = deviceApSsidInput,
            homeWifiSsidInput = homeWifiSsidInput,
            homeWifiPasswordInput = homeWifiPasswordInput,
        )

        populateProvisioningInputs(provisioningViews, preserveHomeWifiText = false)

        val wifiAdapter = ArrayAdapter<String>(this, R.layout.item_autocomplete_dropdown_blue, mutableListOf())
        homeWifiSsidInput.setAdapter(wifiAdapter)
        homeWifiSsidInput.setOnClickListener {
            refreshHomeWifiChoices(homeWifiSsidInput, wifiAdapter, silent = false, showPicker = true)
        }

        refreshHomeWifiChoices(homeWifiSsidInput, wifiAdapter, silent = true, showPicker = false)
        activeProvisioningViews = provisioningViews

        view.findViewById<Button>(R.id.provisionRefreshWifiListButton).setOnClickListener {
            refreshHomeWifiChoices(homeWifiSsidInput, wifiAdapter, silent = false, showPicker = true)
        }

        view.findViewById<Button>(R.id.provisionOpenWifiSettingsButton).setOnClickListener {
            currentProvisioningPrefs = ProvisioningPrefs(
                deviceApSsid = deviceApSsidInput.text.toString().trim(),
                homeWifiSsid = homeWifiSsidInput.text.toString().trim(),
                homeWifiPassword = homeWifiPasswordInput.text.toString(),
            )
            DeviceConfigStore.saveProvisioningPrefs(this, currentProvisioningPrefs)
            openWifiPickerForDeviceAp(currentProvisioningPrefs.deviceApSsid)
        }

        view.findViewById<Button>(R.id.provisionSendButton).setOnClickListener {
            val prefs = ProvisioningPrefs(
                deviceApSsid = deviceApSsidInput.text.toString().trim(),
                homeWifiSsid = homeWifiSsidInput.text.toString().trim(),
                homeWifiPassword = homeWifiPasswordInput.text.toString(),
            )
            if (prefs.homeWifiSsid.isBlank()) {
                updateStatus("Ev Wi-Fi adini secin")
                return@setOnClickListener
            }

            currentProvisioningPrefs = prefs
            DeviceConfigStore.saveProvisioningPrefs(this, prefs)

            val apConfig = currentDeviceConfig().copy(baseUrl = "http://192.168.4.1")
            updateStatus("Wi-Fi bilgileri cihaza yaziliyor...", persist = false)

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    DoorApi.provisionOverAp(
                        config = apConfig,
                        homeWifiSsid = prefs.homeWifiSsid,
                        homeWifiPassword = prefs.homeWifiPassword,
                    )
                }
                val message = if (result.ok) {
                    wifiProvisioner.release()
                    "Kurulum gonderildi. Telefonu tekrar ev Wi-Fi'na baglayip menuden 'Yerel agda cihazi bul' secin"
                } else {
                    "Hata: ${result.message}"
                }
                updateStatus(message)
            }
        }

        view.findViewById<Button>(R.id.provisionCloseButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (activeProvisioningViews?.dialog === dialog) {
                activeProvisioningViews = null
            }
        }
        dialog.show()
    }

    private fun openWifiPickerForDeviceAp(deviceApSsid: String) {
        currentConnectedSsid()
            ?.takeUnless(::isDeviceApSsid)
            ?.let { connectedHomeWifi ->
                currentProvisioningPrefs = currentProvisioningPrefs.copy(homeWifiSsid = connectedHomeWifi)
                DeviceConfigStore.saveProvisioningPrefs(this, currentProvisioningPrefs)
            }
        val targetSsid = deviceApSsid.ifBlank { "KapiAc-XXXXXX" }
        updateStatus("$targetSsid agini Wi-Fi ekranindan secin")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure { updateStatus("Wi-Fi ekrani acilamadi: ${it.message}") }
    }

    private fun triggerRelay(channel: Int) {
        val config = currentDeviceConfig()
        val label = config.relayConfig(channel).label
        runRequest("$label komutu gonderiliyor...", config) {
            DoorApi.performRelayAction(config, channel, currentAuthToken(config))
        }
    }

    private fun refreshStatusFromDevice() {
        val config = currentDeviceConfig()
        runRequest("Durum aliniyor...", config) {
            DoorApi.fetchStatus(config, currentAuthToken(config))
        }
    }

    private fun runRequest(
        pendingMessage: String,
        requestConfig: DeviceConfig = currentDeviceConfig(),
        block: suspend () -> DoorApiResult,
    ) {
        updateStatus(pendingMessage, persist = false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                block()
            }

            result.deviceStatus?.let { status ->
                currentConfig = status.toDeviceConfig(
                    baseUrl = requestConfig.baseUrl,
                    token = requestConfig.token,
                    relay2Visible = currentDeviceConfig().relay2Visible,
                )
                persistCurrentConfig()
                renderMainScreen()
            }

            val message = if (result.ok) result.message else "Hata: ${result.message}"
            updateStatus(message)
        }
    }

    private fun discoverDeviceOnLocalNetwork() {
        persistCurrentConfig()
        updateStatus("Yerel ag taraniyor...", persist = false)
        val config = currentDeviceConfig()

        lifecycleScope.launch {
            val discovery = withContext(Dispatchers.IO) {
                LocalDeviceDiscovery.discover(this@MainActivity, config)
            }

            discovery
                .onSuccess { found ->
                    currentConfig = found.status.toDeviceConfig(
                        baseUrl = found.baseUrl,
                        token = config.token,
                        relay2Visible = config.relay2Visible,
                    )
                    persistCurrentConfig()
                    renderMainScreen()
                    updateStatus("Cihaz bulundu: ${found.baseUrl}")
                }
                .onFailure { error ->
                    updateStatus("Hata: ${error.message ?: "Cihaz bulunamadi"}")
                }
        }
    }

    private fun updateStatus(message: String, persist: Boolean = true) {
        statusText.text = message
        if (persist) {
            DeviceConfigStore.saveLastStatus(this, message)
            WidgetUpdater.updateAll(this)
        }
    }

    private fun persistCurrentConfig() {
        DeviceConfigStore.save(this, currentDeviceConfig())
        WidgetUpdater.updateAll(this)
    }

    private fun currentDeviceConfig(): DeviceConfig {
        return currentConfig ?: DeviceConfigStore.load(this).also { currentConfig = it }
    }

    private fun currentAuthToken(config: DeviceConfig): String {
        return config.token.trim()
    }

    private fun ensureWifiPermissions(onGranted: () -> Unit) {
        val required = WifiProvisioner.requiredRuntimePermissions()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
            return
        }

        onPermissionsGranted = onGranted
        wifiPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshHomeWifiChoices(
        input: AutoCompleteTextView,
        adapter: ArrayAdapter<String>,
        silent: Boolean,
        showPicker: Boolean,
    ) {
        val required = WifiProvisioner.requiredRuntimePermissions()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty() && silent) {
            currentConnectedSsid()?.let { connected ->
                val seedChoices = if (isDeviceApSsid(connected)) {
                    listOfNotNull(currentProvisioningPrefs.homeWifiSsid.takeIf { it.isNotBlank() })
                } else {
                    listOf(connected)
                }
                adapter.clear()
                adapter.addAll(seedChoices)
                adapter.notifyDataSetChanged()
                if (input.text.isNullOrBlank()) {
                    seedChoices.firstOrNull()?.let { input.setText(it, false) }
                }
                if (showPicker && seedChoices.isNotEmpty()) {
                    showHomeWifiPicker(input, seedChoices)
                } else if (showPicker) {
                    updateStatus("Wi-Fi listesi icin konum izni ve konum servisi gerekli")
                }
            }
            return
        }

        ensureWifiPermissions {
            if (!isLocationServiceEnabled()) {
                updateStatus("Wi-Fi listesi icin telefon konumu acik olmali")
                return@ensureWifiPermissions
            }
            val ssids = buildWifiChoiceList()
            adapter.clear()
            adapter.addAll(ssids)
            adapter.notifyDataSetChanged()

            val preferredHomeWifi = preferredHomeWifiSsid()
            if (preferredHomeWifi != null && ssids.contains(preferredHomeWifi)) {
                input.setText(preferredHomeWifi, false)
            } else if (input.text.isNullOrBlank() && ssids.isNotEmpty()) {
                input.setText(ssids.first(), false)
            }

            if (!silent) {
                if (showPicker && ssids.isNotEmpty()) {
                    showHomeWifiPicker(input, ssids)
                }
                updateStatus(
                    if (ssids.isNotEmpty()) {
                        "Wi-Fi listesi guncellendi"
                    } else {
                        "Wi-Fi bulunamadi. Bagli ag ve izinleri kontrol edin"
                    }
                )
            }
        }
    }

    private fun buildWifiChoiceList(): List<String> {
        val results = linkedSetOf<String>()
        preferredHomeWifiSsid()?.let { results.add(it) }
        runCatching { wifiManager.startScan() }
        runCatching {
            wifiManager.scanResults
                .mapNotNull { scan ->
                    sanitizeSsid(scan.SSID)
                }
                .filterNot(::isDeviceApSsid)
                .filter { it.isNotBlank() }
                .sorted()
                .forEach(results::add)
        }
        return results.toList()
    }

    private fun currentConnectedSsid(): String? {
        return runCatching {
            sanitizeSsid(wifiManager.connectionInfo?.ssid)
        }.getOrNull()
    }

    private fun sanitizeSsid(raw: String?): String? {
        return raw
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    private fun syncProvisioningPrefsWithCurrentNetwork() {
        val connected = currentConnectedSsid() ?: return
        currentProvisioningPrefs = if (isDeviceApSsid(connected)) {
            currentProvisioningPrefs.copy(deviceApSsid = connected)
        } else {
            currentProvisioningPrefs.copy(homeWifiSsid = connected)
        }
        DeviceConfigStore.saveProvisioningPrefs(this, currentProvisioningPrefs)
    }

    private fun populateProvisioningInputs(
        views: ProvisioningViews,
        preserveHomeWifiText: Boolean,
    ) {
        syncProvisioningPrefsWithCurrentNetwork()
        val deviceApSsid = preferredDeviceApSsid()
        val homeWifiSsid = preferredHomeWifiSsid().orEmpty()
        views.deviceApSsidInput.setText(deviceApSsid)
        if (!preserveHomeWifiText || views.homeWifiSsidInput.text.isNullOrBlank()) {
            views.homeWifiSsidInput.setText(homeWifiSsid, false)
        }
        if (views.homeWifiPasswordInput.text.isNullOrEmpty()) {
            views.homeWifiPasswordInput.setText(currentProvisioningPrefs.homeWifiPassword)
        }
    }

    private fun preferredDeviceApSsid(): String {
        val connected = currentConnectedSsid()
        return when {
            connected != null && isDeviceApSsid(connected) -> connected
            currentProvisioningPrefs.deviceApSsid.isNotBlank() -> currentProvisioningPrefs.deviceApSsid
            else -> "KapiAc-XXXXXX"
        }
    }

    private fun preferredHomeWifiSsid(): String? {
        val connected = currentConnectedSsid()
        return when {
            connected != null && !isDeviceApSsid(connected) -> connected
            currentProvisioningPrefs.homeWifiSsid.isNotBlank() -> currentProvisioningPrefs.homeWifiSsid
            else -> null
        }
    }

    private fun isDeviceApSsid(ssid: String): Boolean {
        return ssid.startsWith("KapiAc-", ignoreCase = true)
    }

    private fun showHomeWifiPicker(
        input: AutoCompleteTextView,
        choices: List<String>,
    ) {
        if (choices.isEmpty()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ev Wi-Fi sec")
            .setItems(choices.toTypedArray()) { _, which ->
                val selectedSsid = choices[which]
                input.setText(selectedSsid, false)
                currentProvisioningPrefs = currentProvisioningPrefs.copy(homeWifiSsid = selectedSsid)
                DeviceConfigStore.saveProvisioningPrefs(this, currentProvisioningPrefs)
            }
            .show()
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    override fun onDestroy() {
        wifiProvisioner.release()
        super.onDestroy()
    }

    companion object {
        private const val MENU_SETTINGS = 100
        private const val MENU_PROVISION = 101
        private const val MENU_DISCOVER = 102
        private const val MENU_REFRESH_STATUS = 103
    }

    private data class ProvisioningViews(
        val dialog: AlertDialog,
        val deviceApSsidInput: EditText,
        val homeWifiSsidInput: AutoCompleteTextView,
        val homeWifiPasswordInput: EditText,
    )
}

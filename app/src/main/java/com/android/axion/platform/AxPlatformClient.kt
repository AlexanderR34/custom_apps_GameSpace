package com.android.axion.platform

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkPolicyManager
import android.net.TetheringManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log

class AxPlatformClient private constructor() {

    open class Listener {
        open fun onFeatureChanged(feature: String, active: Boolean) {}
        open fun onStateChanged(key: String, state: Bundle) {}
    }

    private val listeners = mutableListOf<Listener>()
    private var context: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var torchEnabled = false
    private var isScreenRecording = false
    private var isInitialized = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchEnabled = enabled
            notifyFeature(FEATURE_FLASHLIGHT, enabled)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    notifyFeature(FEATURE_WIFI, isFeatureActive(FEATURE_WIFI))
                }
                WifiManager.WIFI_AP_STATE_CHANGED_ACTION -> {
                    notifyFeature(FEATURE_HOTSPOT, isFeatureActive(FEATURE_HOTSPOT))
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    notifyFeature(FEATURE_BLUETOOTH, isFeatureActive(FEATURE_BLUETOOTH))
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    notifyFeature(FEATURE_ZEN, isFeatureActive(FEATURE_ZEN))
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    notifyFeature(FEATURE_AIRPLANE_MODE, isFeatureActive(FEATURE_AIRPLANE_MODE))
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    notifyFeature(FEATURE_BATTERY_SAVER, isFeatureActive(FEATURE_BATTERY_SAVER))
                }
                LocationManager.MODE_CHANGED_ACTION -> {
                    notifyFeature(FEATURE_LOCATION, isFeatureActive(FEATURE_LOCATION))
                }
                UiModeManager.ACTION_ENTER_CAR_MODE,
                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    notifyFeature(FEATURE_DARK_MODE, isFeatureActive(FEATURE_DARK_MODE))
                }
                "com.android.systemui.screenrecord.UPDATE_STATE" -> {
                    isScreenRecording = intent.getBooleanExtra("extra_state", false)
                    notifyFeature(FEATURE_SCREEN_RECORD, isScreenRecording)
                }
                TetheringManager.ACTION_TETHER_STATE_CHANGED -> {
                    notifyFeature(FEATURE_HOTSPOT, isFeatureActive(FEATURE_HOTSPOT))
                    notifyFeature(FEATURE_USB_TETHER, isFeatureActive(FEATURE_USB_TETHER))
                }
            }
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        this.context = context.applicationContext
        val appCtx = this.context ?: return

        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                addAction("com.android.systemui.screenrecord.UPDATE_STATE")
                addAction(TetheringManager.ACTION_TETHER_STATE_CHANGED)
            }
            appCtx.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering broadcast receiver", e)
        }

        try {
            val cameraManager = appCtx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering torch callback", e)
        }

        try {
            val settingsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    notifyFeature(FEATURE_ROTATION, isFeatureActive(FEATURE_ROTATION))
                    notifyFeature(FEATURE_DARK_MODE, isFeatureActive(FEATURE_DARK_MODE))
                    notifyFeature(FEATURE_NIGHT_LIGHT, isFeatureActive(FEATURE_NIGHT_LIGHT))
                    notifyFeature(FEATURE_AOD, isFeatureActive(FEATURE_AOD))
                    notifyFeature(FEATURE_COLOR_INVERSION, isFeatureActive(FEATURE_COLOR_INVERSION))
                    notifyFeature(FEATURE_COLOR_CORRECTION, isFeatureActive(FEATURE_COLOR_CORRECTION))
                    notifyFeature(FEATURE_REDUCE_BRIGHTNESS, isFeatureActive(FEATURE_REDUCE_BRIGHTNESS))
                    notifyFeature(FEATURE_ONE_HANDED_MODE, isFeatureActive(FEATURE_ONE_HANDED_MODE))
                    notifyFeature(FEATURE_HEADS_UP, isFeatureActive(FEATURE_HEADS_UP))
                    notifyFeature(FEATURE_READING_MODE, isFeatureActive(FEATURE_READING_MODE))
                    notifyFeature(FEATURE_CAFFEINE, isFeatureActive(FEATURE_CAFFEINE))
                    notifyFeature(FEATURE_BATTERY_SAVER, isFeatureActive(FEATURE_BATTERY_SAVER))
                }
            }

            val cr = appCtx.contentResolver
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.UI_NIGHT_MODE),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NIGHT_DISPLAY_ACTIVATED),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("reduce_bright_colors_activated"),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("one_handed_mode_enabled"),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                false,
                settingsObserver
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE),
                false,
                settingsObserver
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering content observers", e)
        }

        isInitialized = true
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyFeature(feature: String, active: Boolean) {
        handler.post {
            val bundle = Bundle().apply { putBoolean("active", active) }
            listeners.toList().forEach {
                it.onFeatureChanged(feature, active)
                it.onStateChanged(feature, bundle)
            }
        }
    }

    fun getState(feature: String): Bundle {
        val active = isFeatureActive(feature)
        return Bundle().apply {
            putBoolean("active", active)
        }
    }

    private fun getFlashCameraId(cm: CameraManager): String? {
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun isFeatureActive(feature: String): Boolean {
        val ctx = context ?: return false
        val cr = ctx.contentResolver
        return try {
            when (feature) {
                FEATURE_WIFI -> {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wm?.isWifiEnabled == true
                }
                FEATURE_BLUETOOTH -> {
                    val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    (bm?.adapter ?: BluetoothAdapter.getDefaultAdapter())?.isEnabled == true
                }
                FEATURE_ZEN -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                }
                FEATURE_ROTATION -> {
                    Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                }
                FEATURE_MOBILE_DATA -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.isDataEnabled == true
                }
                FEATURE_AIRPLANE_MODE -> {
                    Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                }
                FEATURE_FLASHLIGHT -> {
                    torchEnabled
                }
                FEATURE_DARK_MODE -> {
                    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                }
                FEATURE_LOCATION -> {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm?.isLocationEnabled == true
                }
                FEATURE_BATTERY_SAVER -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.isPowerSaveMode == true || Settings.Global.getInt(cr, Settings.Global.LOW_POWER_MODE, 0) == 1
                }
                FEATURE_HOTSPOT -> {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wm?.isWifiApEnabled == true
                }
                FEATURE_NFC -> {
                    NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
                }
                FEATURE_NIGHT_LIGHT -> {
                    Settings.Secure.getInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0) == 1
                }
                FEATURE_AOD -> {
                    Settings.Secure.getInt(cr, Settings.Secure.DOZE_ALWAYS_ON, 0) == 1
                }
                FEATURE_DATA_SAVER -> {
                    NetworkPolicyManager.from(ctx).restrictBackground
                }
                FEATURE_COLOR_INVERSION -> {
                    Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0) == 1
                }
                FEATURE_COLOR_CORRECTION -> {
                    Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0) == 1
                }
                FEATURE_REDUCE_BRIGHTNESS -> {
                    Settings.Secure.getInt(cr, "reduce_bright_colors_activated", 0) == 1
                }
                FEATURE_ONE_HANDED_MODE -> {
                    Settings.Secure.getInt(cr, "one_handed_mode_enabled", 0) == 1
                }
                FEATURE_HEADS_UP -> {
                    Settings.Global.getInt(cr, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1
                }
                FEATURE_AUTO_SYNC -> {
                    ContentResolver.getMasterSyncAutomatically()
                }
                FEATURE_CAMERA_PRIVACY -> {
                    val spm = ctx.getSystemService(SensorPrivacyManager::class.java)
                    spm?.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA) == true
                }
                FEATURE_MIC_PRIVACY -> {
                    val spm = ctx.getSystemService(SensorPrivacyManager::class.java)
                    spm?.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.MICROPHONE) == true
                }
                FEATURE_USB_TETHER -> {
                    val tm = ctx.getSystemService(TetheringManager::class.java)
                    tm?.tetheredIfaces?.any { it.startsWith("rndis") || it.startsWith("usb") } == true
                }
                FEATURE_READING_MODE -> {
                    Settings.Secure.getInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0) == 1
                }
                FEATURE_CAFFEINE -> {
                    Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
                }
                FEATURE_SCREEN_RECORD -> {
                    isScreenRecording
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking state for $feature", e)
            false
        }
    }

    fun toggle(feature: String) {
        val ctx = context ?: return
        val cr = ctx.contentResolver
        try {
            when (feature) {
                FEATURE_WIFI -> {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wm?.let {
                        val target = !it.isWifiEnabled
                        it.isWifiEnabled = target
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_BLUETOOTH -> {
                    val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val ba = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                    ba?.let {
                        val target = !it.isEnabled
                        if (it.isEnabled) it.disable() else it.enable()
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_ZEN -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.let {
                        val isZen = isFeatureActive(FEATURE_ZEN)
                        val target = if (isZen) {
                            NotificationManager.INTERRUPTION_FILTER_ALL
                        } else {
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        }
                        it.setInterruptionFilter(target)
                        notifyFeature(feature, !isZen)
                    }
                }
                FEATURE_ROTATION -> {
                    val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_MOBILE_DATA -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.let {
                        val target = !it.isDataEnabled
                        it.setDataEnabled(target)
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_AIRPLANE_MODE -> {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val target = !isFeatureActive(FEATURE_AIRPLANE_MODE)
                    try {
                        cm?.setAirplaneMode(target)
                    } catch (e: Exception) {
                        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, if (target) 1 else 0)
                        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                            putExtra("state", target)
                        }
                        ctx.sendBroadcast(intent)
                    }
                    notifyFeature(feature, target)
                }
                FEATURE_FLASHLIGHT -> {
                    val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val id = cameraManager?.let { getFlashCameraId(it) }
                    if (id != null && cameraManager != null) {
                        val target = !torchEnabled
                        cameraManager.setTorchMode(id, target)
                        torchEnabled = target
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_DARK_MODE -> {
                    val uiModeManager = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                    uiModeManager?.let {
                        val target = !isFeatureActive(FEATURE_DARK_MODE)
                        it.setNightModeActivated(target)
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_LOCATION -> {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm?.let {
                        val target = !it.isLocationEnabled
                        it.setLocationEnabledForUser(target, UserHandle.CURRENT)
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_BATTERY_SAVER -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val target = !isFeatureActive(FEATURE_BATTERY_SAVER)
                    try {
                        pm?.setPowerSaveModeEnabled(target)
                    } catch (e: Exception) {
                        Settings.Global.putInt(cr, Settings.Global.LOW_POWER_MODE, if (target) 1 else 0)
                    }
                    notifyFeature(feature, target)
                }
                FEATURE_HOTSPOT -> {
                    val tm = ctx.getSystemService(TetheringManager::class.java)
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val isApActive = wm?.isWifiApEnabled == true
                    val target = !isApActive
                    if (isApActive) {
                        tm?.stopTethering(TetheringManager.TETHERING_WIFI)
                    } else {
                        tm?.startTethering(
                            TetheringManager.TETHERING_WIFI,
                            { it.run() },
                            object : TetheringManager.StartTetheringCallback {}
                        )
                    }
                    notifyFeature(feature, target)
                }
                FEATURE_NFC -> {
                    val nfc = NfcAdapter.getDefaultAdapter(ctx)
                    nfc?.let {
                        val target = !it.isEnabled
                        if (it.isEnabled) it.disable() else it.enable()
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_NIGHT_LIGHT -> {
                    val cur = Settings.Secure.getInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_AOD -> {
                    val cur = Settings.Secure.getInt(cr, Settings.Secure.DOZE_ALWAYS_ON, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, Settings.Secure.DOZE_ALWAYS_ON, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_DATA_SAVER -> {
                    val npm = NetworkPolicyManager.from(ctx)
                    val target = !npm.restrictBackground
                    npm.restrictBackground = target
                    notifyFeature(feature, target)
                }
                FEATURE_COLOR_INVERSION -> {
                    val cur = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_COLOR_CORRECTION -> {
                    val cur = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_REDUCE_BRIGHTNESS -> {
                    val cur = Settings.Secure.getInt(cr, "reduce_bright_colors_activated", 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, "reduce_bright_colors_activated", target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_ONE_HANDED_MODE -> {
                    val cur = Settings.Secure.getInt(cr, "one_handed_mode_enabled", 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, "one_handed_mode_enabled", target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_HEADS_UP -> {
                    val cur = Settings.Global.getInt(cr, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1)
                    val target = if (cur == 1) 0 else 1
                    Settings.Global.putInt(cr, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_AUTO_SYNC -> {
                    val target = !ContentResolver.getMasterSyncAutomatically()
                    ContentResolver.setMasterSyncAutomatically(target)
                    notifyFeature(feature, target)
                }
                FEATURE_CAMERA_PRIVACY -> {
                    val spm = ctx.getSystemService(SensorPrivacyManager::class.java)
                    spm?.let {
                        val target = !it.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA)
                        it.setSensorPrivacy(SensorPrivacyManager.Sources.QS_TILE, SensorPrivacyManager.Sensors.CAMERA, target)
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_MIC_PRIVACY -> {
                    val spm = ctx.getSystemService(SensorPrivacyManager::class.java)
                    spm?.let {
                        val target = !it.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.MICROPHONE)
                        it.setSensorPrivacy(SensorPrivacyManager.Sources.QS_TILE, SensorPrivacyManager.Sensors.MICROPHONE, target)
                        notifyFeature(feature, target)
                    }
                }
                FEATURE_USB_TETHER -> {
                    val tm = ctx.getSystemService(TetheringManager::class.java)
                    val isActive = isFeatureActive(FEATURE_USB_TETHER)
                    tm?.setUsbTethering(!isActive)
                    notifyFeature(feature, !isActive)
                }
                FEATURE_READING_MODE -> {
                    val cur = Settings.Secure.getInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(cr, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_CAFFEINE -> {
                    val cur = Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
                    val target = if (cur != 0) 0 else (BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS)
                    Settings.Global.putInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, target)
                    notifyFeature(feature, target != 0)
                }
                FEATURE_VPN -> {
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                }
                FEATURE_CAST -> {
                    val intent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                }
                FEATURE_SCREEN_RECORD -> {
                    val intent = Intent().apply {
                        component = ComponentName("com.android.systemui", "com.android.systemui.screenrecord.RecordingService")
                        action = if (isScreenRecording) {
                            "com.android.systemui.screenrecord.STOP"
                        } else {
                            "com.android.systemui.screenrecord.START"
                        }
                        if (!isScreenRecording) {
                            putExtra("extra_resultCode", -1)
                            putExtra("extra_useAudio", 0)
                            putExtra("extra_showTaps", false)
                        }
                    }
                    try {
                        if (isScreenRecording) {
                            ctx.startService(intent)
                        } else {
                            ctx.startForegroundService(intent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error toggling screen record", e)
                    }
                }
                else -> {
                    Log.w(TAG, "Unhandled toggle feature: $feature")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling $feature", e)
        }
    }

    companion object {
        private const val TAG = "AxPlatformClient"

        @Volatile
        private var instance: AxPlatformClient? = null

        @JvmStatic
        fun getInstance(): AxPlatformClient {
            return instance ?: synchronized(this) {
                instance ?: AxPlatformClient().also { instance = it }
            }
        }

        @JvmStatic
        fun getLabel(state: Bundle): String? {
            return state.getString("label")
        }

        const val FEATURE_WIFI = "wifi"
        const val FEATURE_BLUETOOTH = "bluetooth"
        const val FEATURE_ZEN = "zen"
        const val FEATURE_ROTATION = "rotation"
        const val FEATURE_MOBILE_DATA = "mobile_data"
        const val FEATURE_AIRPLANE_MODE = "airplane_mode"
        const val FEATURE_FLASHLIGHT = "flashlight"
        const val FEATURE_DARK_MODE = "dark_mode"
        const val FEATURE_LOCATION = "location"
        const val FEATURE_BATTERY_SAVER = "battery_saver"
        const val FEATURE_HOTSPOT = "hotspot"
        const val FEATURE_NFC = "nfc"
        const val FEATURE_NIGHT_LIGHT = "night_light"
        const val FEATURE_AOD = "aod"
        const val FEATURE_DATA_SAVER = "data_saver"
        const val FEATURE_COLOR_INVERSION = "color_inversion"
        const val FEATURE_COLOR_CORRECTION = "color_correction"
        const val FEATURE_REDUCE_BRIGHTNESS = "reduce_brightness"
        const val FEATURE_ONE_HANDED_MODE = "one_handed_mode"
        const val FEATURE_HEADS_UP = "heads_up"
        const val FEATURE_AUTO_SYNC = "auto_sync"
        const val FEATURE_CAMERA_PRIVACY = "camera_privacy"
        const val FEATURE_MIC_PRIVACY = "mic_privacy"
        const val FEATURE_USB_TETHER = "usb_tether"
        const val FEATURE_READING_MODE = "reading_mode"
        const val FEATURE_CAFFEINE = "caffeine"
        const val FEATURE_VPN = "vpn"
        const val FEATURE_CAST = "cast"
        const val FEATURE_SCREEN_RECORD = "screen_record"
        const val FEATURE_SCREENSHOT = "screenshot"
    }
}

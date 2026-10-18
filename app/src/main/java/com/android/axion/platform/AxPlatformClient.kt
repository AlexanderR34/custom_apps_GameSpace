package com.android.axion.platform

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
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
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(LocationManager.MODE_CHANGED_ACTION)
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
            val rotationObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    notifyFeature(FEATURE_ROTATION, isFeatureActive(FEATURE_ROTATION))
                }
            }
            appCtx.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                rotationObserver
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering rotation observer", e)
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

    fun isFeatureActive(feature: String): Boolean {
        val ctx = context ?: return false
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
                    Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                }
                FEATURE_MOBILE_DATA -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.isDataEnabled == true
                }
                FEATURE_AIRPLANE_MODE -> {
                    Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                }
                FEATURE_FLASHLIGHT -> {
                    torchEnabled
                }
                FEATURE_DARK_MODE -> {
                    val uiModeManager = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                    (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                FEATURE_LOCATION -> {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm?.isLocationEnabled == true
                }
                FEATURE_BATTERY_SAVER -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.isPowerSaveMode == true
                }
                FEATURE_HOTSPOT -> {
                    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wm?.isWifiApEnabled == true
                }
                FEATURE_NFC -> {
                    NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
                }
                FEATURE_NIGHT_LIGHT -> {
                    Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0) == 1
                }
                FEATURE_AOD -> {
                    Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.DOZE_ALWAYS_ON, 0) == 1
                }
                FEATURE_COLOR_INVERSION -> {
                    Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0) == 1
                }
                FEATURE_HEADS_UP -> {
                    Settings.Global.getInt(ctx.contentResolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1
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
                    val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, target)
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
                    val isAir = isFeatureActive(FEATURE_AIRPLANE_MODE)
                    val target = !isAir
                    cm?.setAirplaneMode(target)
                    notifyFeature(feature, target)
                }
                FEATURE_FLASHLIGHT -> {
                    val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val id = cameraManager?.cameraIdList?.firstOrNull()
                    if (id != null) {
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
                    pm?.let {
                        val target = !it.isPowerSaveMode
                        it.setPowerSaveModeEnabled(target)
                        notifyFeature(feature, target)
                    }
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
                    val cur = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_AOD -> {
                    val cur = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.DOZE_ALWAYS_ON, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.DOZE_ALWAYS_ON, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_COLOR_INVERSION -> {
                    val cur = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0)
                    val target = if (cur == 1) 0 else 1
                    Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, target)
                    notifyFeature(feature, target == 1)
                }
                FEATURE_HEADS_UP -> {
                    val cur = Settings.Global.getInt(ctx.contentResolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1)
                    val target = if (cur == 1) 0 else 1
                    Settings.Global.putInt(ctx.contentResolver, Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, target)
                    notifyFeature(feature, target == 1)
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
        const val FEATURE_WORK_PROFILE = "work_profile"
        const val FEATURE_USB_TETHER = "usb_tether"
        const val FEATURE_DREAM = "dream"
        const val FEATURE_READING_MODE = "reading_mode"
        const val FEATURE_POWER_SHARE = "power_share"
        const val FEATURE_CAFFEINE = "caffeine"
        const val FEATURE_VPN = "vpn"
        const val FEATURE_CAST = "cast"
        const val FEATURE_PROFILES = "profiles"
        const val FEATURE_SMART_PIXELS = "smart_pixels"
        const val FEATURE_SCREEN_RECORD = "screen_record"
        const val FEATURE_SCREENSHOT = "screenshot"
    }
}

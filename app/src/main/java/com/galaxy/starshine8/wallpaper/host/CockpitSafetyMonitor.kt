package com.galaxy.starshine8.wallpaper.host

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.galaxy.starshine8.wallpaper.core.contract.CockpitEnvironmentState
import com.galaxy.starshine8.wallpaper.core.contract.DynamicSceneRenderer
import com.galaxy.starshine8.wallpaper.core.render.StarshineGlSceneRenderer
import com.galaxy.starshine8.wallpaper.core.sensor.CockpitAudioPulseProvider
import kotlinx.coroutines.CoroutineScope

/**
 * 行车安全、夜间防眩光、STR 深度休眠唤醒自愈与车载三级温控守护器（CockpitSafetyMonitor）。
 * P0 修复：修正 Intent.ACTION_CONFIGURATION_CHANGED 标准 SDK 常量名，并新增 restartAudioPulse() 供权限授予后热切换。
 */
class CockpitSafetyMonitor(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val renderer: DynamicSceneRenderer,
    private val onThermalRenderModeChanged: ((shouldUseStaticDirtyMode: Boolean) -> Unit)? = null
) {

    companion object {
        const val DAY_LUMINANCE_FACTOR = 1.0f
        const val NIGHT_LUMINANCE_FACTOR = 0.65f // 夜间压暗 35%
        private const val DIMMING_TRANSITION_DURATION_MS = 800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dimmingAnimator: ValueAnimator? = null

    @Volatile
    private var isNightModeActive: Boolean = false

    @Volatile
    private var currentDimmingFactor: Float = DAY_LUMINANCE_FACTOR

    @Volatile
    private var isVehicleParked: Boolean = true

    @Volatile
    private var latestSpectrumBins: FloatArray = FloatArray(32) { 0.12f }

    @Volatile
    private var isAudioProcedural: Boolean = true

    @Volatile
    var currentThermalTier: Int = 0
        private set

    private val audioPulseProvider = CockpitAudioPulseProvider(
        context = context,
        externalScope = coroutineScope
    ) { bins, isFallback ->
        latestSpectrumBins = bins
        isAudioProcedural = isFallback
        pushStateToRenderer()
    }

    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
        applyThermalStatus(status)
    }

    private val cockpitBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CONFIGURATION_CHANGED,
                UiModeManager.ACTION_ENTER_CAR_MODE,
                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    evaluateNightModeAndAnimate()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isVehicleParked = true
                    pushStateToRenderer()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    evaluateNightModeAndAnimate()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    evaluateNightModeImmediate()
                    (renderer as? StarshineGlSceneRenderer)?.notifyStrWakeUpSelfCheck()
                    pushStateToRenderer()
                }
            }
        }
    }

    private var isReceiverRegistered = false
    private var isThermalRegistered = false

    fun startMonitoring() {
        evaluateNightModeImmediate()
        checkChargingParkedState()
        registerThermalListenerSafely()

        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
            ContextCompat.registerReceiver(context, cockpitBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        }
        audioPulseProvider.start()
        pushStateToRenderer()
    }

    fun restartAudioPulse() {
        audioPulseProvider.start()
    }

    fun stopMonitoring() {
        audioPulseProvider.stop()
        unregisterThermalListenerSafely()
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(cockpitBroadcastReceiver) }
            isReceiverRegistered = false
        }
        mainHandler.post {
            dimmingAnimator?.cancel()
            dimmingAnimator = null
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        val nightMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val targetNight = (nightMask == Configuration.UI_MODE_NIGHT_YES)
        transitionToNightMode(targetNight)
    }

    fun evaluateNightModeAndAnimate() {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val targetNight = (uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES) ||
            (nightMask == Configuration.UI_MODE_NIGHT_YES)
        transitionToNightMode(targetNight)
    }

    fun getCurrentStateSnapshot(): CockpitEnvironmentState {
        return CockpitEnvironmentState(
            isNightMode = isNightModeActive,
            nightDimmingFactor = currentDimmingFactor,
            audioSpectrumBins = latestSpectrumBins.copyOf(),
            isAudioProceduralFallback = isAudioProcedural,
            isParked = isVehicleParked
        )
    }

    private fun registerThermalListenerSafely() {
        if (isThermalRegistered) return
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            applyThermalStatus(powerManager.currentThermalStatus)
            powerManager.addThermalStatusListener(context.mainExecutor, thermalStatusListener)
            isThermalRegistered = true
        }
    }

    private fun unregisterThermalListenerSafely() {
        if (!isThermalRegistered) return
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.removeThermalStatusListener(thermalStatusListener)
        }
        isThermalRegistered = false
    }

    private fun applyThermalStatus(status: Int) {
        val newTier = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> 0
            PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 2
        }
        currentThermalTier = newTier
        (renderer as? StarshineGlSceneRenderer)?.setThermalStatusTier(newTier)
        onThermalRenderModeChanged?.invoke(newTier >= 2)
    }

    private fun evaluateNightModeImmediate() {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isNightModeActive = (uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES) ||
            (nightMask == Configuration.UI_MODE_NIGHT_YES)
        currentDimmingFactor = if (isNightModeActive) NIGHT_LUMINANCE_FACTOR else DAY_LUMINANCE_FACTOR
    }

    private fun checkChargingParkedState() {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (isCharging) {
            isVehicleParked = true
        }
    }

    private fun transitionToNightMode(nightMode: Boolean) {
        if (isNightModeActive == nightMode && dimmingAnimator?.isRunning != true) {
            return
        }
        isNightModeActive = nightMode
        val startFactor = currentDimmingFactor
        val targetFactor = if (nightMode) NIGHT_LUMINANCE_FACTOR else DAY_LUMINANCE_FACTOR

        mainHandler.post {
            dimmingAnimator?.cancel()
            dimmingAnimator = ValueAnimator.ofFloat(startFactor, targetFactor).apply {
                duration = DIMMING_TRANSITION_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    currentDimmingFactor = anim.animatedValue as Float
                    pushStateToRenderer()
                }
                start()
            }
        }
    }

    private fun pushStateToRenderer() {
        renderer.updateCockpitState(
            CockpitEnvironmentState(
                isNightMode = isNightModeActive,
                nightDimmingFactor = currentDimmingFactor,
                audioSpectrumBins = latestSpectrumBins,
                isAudioProceduralFallback = isAudioProcedural,
                isParked = isVehicleParked
            )
        )
    }
}


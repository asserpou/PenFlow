package com.penflow.tablet.model

import android.content.Context

enum class AspectRatioMode(val displayName: String, val ratio: Float) {
    FULL_SCREEN("Full Screen (Stretch to PC)", 0.0f),
    RATIO_16_9("16:9 Standard Widescreen", 16f / 9f),
    RATIO_16_10("16:10 Modern Laptop / Mac", 16f / 10f),
    RATIO_4_3("4:3 Legacy / iPad ratio", 4f / 3f),
    RATIO_21_9("21:9 Ultra-Wide", 21f / 9f)
}

enum class PressureCurve(val displayName: String, val gamma: Float) {
    LINEAR("Linear (1:1 Native)", 1.0f),
    SOFT("Soft (Easier Max Pressure)", 0.7f),
    FIRM("Firm (Fine Line Control)", 1.4f),
    ULTRA_SENSITIVE("Ultra-Sensitive (Feather Touch)", 0.5f),
    HARD("Heavy Press Required", 1.8f)
}

enum class OsuAreaPreset(val displayName: String, val widthMm: Float, val heightMm: Float) {
    STANDARD("Standard (85 x 48 mm)", 85.0f, 47.8f),
    WRIST("Wrist (70 x 39 mm)", 70.0f, 39.4f),
    SMALL("Small (55 x 31 mm)", 55.0f, 31.0f),
    MICRO("Micro (40 x 22 mm)", 40.0f, 22.5f),
    LARGE("Large (110 x 62 mm)", 110.0f, 61.9f),
    FULL("Full Tablet (Max)", -1f, -1f),
    CUSTOM("Custom Area Size", -1f, -1f)
}

data class TabletAreaSettings(
    val widthMm: Float = 85.0f,
    val heightMm: Float = 47.8f,
    val offsetXPercent: Float = 0.5f,   // 0.0 (left) .. 0.5 (center) .. 1.0 (right)
    val offsetYPercent: Float = 0.5f,   // 0.0 (top) .. 0.5 (center) .. 1.0 (bottom)
    val preset: OsuAreaPreset = OsuAreaPreset.STANDARD,
    val lockAspectRatio: Boolean = true,
    val isFullScreen: Boolean = false
)

enum class PollingRatePreset(val displayName: String, val targetHz: Int) {
    NATIVE("Native Digitizer Rate (120 - 240 Hz)", 0),
    HZ_500("500 Hz (High Performance Interpolated)", 500),
    HZ_1000("1000 Hz (Extreme Low-Latency Stream)", 1000)
}

data class TabletSettings(
    val aspectRatio: AspectRatioMode = AspectRatioMode.FULL_SCREEN,
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    val tabletArea: TabletAreaSettings = TabletAreaSettings(),
    val pollingRate: PollingRatePreset = PollingRatePreset.NATIVE,
    val strictPalmRejection: Boolean = false,
    val invertStylusButton: Boolean = false,
    val showLocalInkTrail: Boolean = false,
    val showAreaGuides: Boolean = true,
    val keepScreenOn: Boolean = true
) {
    fun applyPressureCurve(rawPressure: Float): Float {
        if (rawPressure <= 0f) return 0f
        if (rawPressure >= 1f) return 1f
        return Math.pow(rawPressure.toDouble(), pressureCurve.gamma.toDouble()).toFloat()
            .coerceIn(0f, 1f)
    }
}

object SettingsRepository {
    private const val PREFS_NAME = "penflow_tablet_settings"

    fun load(context: Context): TabletSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val dm = context.resources.displayMetrics
        val xdpi = if (dm.xdpi > 30f) dm.xdpi else dm.densityDpi.toFloat()
        val ydpi = if (dm.ydpi > 30f) dm.ydpi else dm.densityDpi.toFloat()
        val maxWm = (dm.widthPixels / (xdpi / 25.4f))
        val maxHm = (dm.heightPixels / (ydpi / 25.4f))

        val savedWidthMm = prefs.getFloat("area_widthMm", -1f)
        val savedHeightMm = prefs.getFloat("area_heightMm", -1f)

        // Fallback or migration from older percent-based saves
        val finalWidthMm = if (savedWidthMm > 0f) {
            savedWidthMm
        } else {
            val oldPct = prefs.getFloat("area_widthPercent", -1f)
            if (oldPct > 0f) (oldPct * maxWm).coerceIn(20f, maxWm) else 85.0f
        }

        val finalHeightMm = if (savedHeightMm > 0f) {
            savedHeightMm
        } else {
            val oldPct = prefs.getFloat("area_heightPercent", -1f)
            if (oldPct > 0f) (oldPct * maxHm).coerceIn(15f, maxHm) else 47.8f
        }

        val area = TabletAreaSettings(
            widthMm = finalWidthMm,
            heightMm = finalHeightMm,
            offsetXPercent = prefs.getFloat("area_offsetXPercent", 0.5f),
            offsetYPercent = prefs.getFloat("area_offsetYPercent", 0.5f),
            preset = try {
                val presetName = prefs.getString("area_preset", OsuAreaPreset.STANDARD.name) ?: OsuAreaPreset.STANDARD.name
                OsuAreaPreset.valueOf(presetName)
            } catch (_: Exception) {
                OsuAreaPreset.STANDARD
            },
            lockAspectRatio = prefs.getBoolean("area_lockAspectRatio", true),
            isFullScreen = prefs.getBoolean("area_isFullScreen", false)
        )
        return TabletSettings(
            aspectRatio = try {
                AspectRatioMode.valueOf(prefs.getString("aspectRatio", AspectRatioMode.FULL_SCREEN.name) ?: AspectRatioMode.FULL_SCREEN.name)
            } catch (_: Exception) {
                AspectRatioMode.FULL_SCREEN
            },
            pressureCurve = try {
                PressureCurve.valueOf(prefs.getString("pressureCurve", PressureCurve.LINEAR.name) ?: PressureCurve.LINEAR.name)
            } catch (_: Exception) {
                PressureCurve.LINEAR
            },
            tabletArea = area,
            pollingRate = try {
                PollingRatePreset.valueOf(prefs.getString("pollingRate", PollingRatePreset.NATIVE.name) ?: PollingRatePreset.NATIVE.name)
            } catch (_: Exception) {
                PollingRatePreset.NATIVE
            },
            strictPalmRejection = prefs.getBoolean("strictPalmRejection_v3", false),
            invertStylusButton = prefs.getBoolean("invertStylusButton", false),
            showLocalInkTrail = prefs.getBoolean("showPenCursorOnTablet", false),
            showAreaGuides = prefs.getBoolean("showAreaGuides", true),
            keepScreenOn = prefs.getBoolean("keepScreenOn", true)
        )
    }

    fun save(context: Context, settings: TabletSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("area_widthMm", settings.tabletArea.widthMm)
            .putFloat("area_heightMm", settings.tabletArea.heightMm)
            .putFloat("area_offsetXPercent", settings.tabletArea.offsetXPercent)
            .putFloat("area_offsetYPercent", settings.tabletArea.offsetYPercent)
            .putString("area_preset", settings.tabletArea.preset.name)
            .putBoolean("area_lockAspectRatio", settings.tabletArea.lockAspectRatio)
            .putBoolean("area_isFullScreen", settings.tabletArea.isFullScreen)
            .putString("aspectRatio", settings.aspectRatio.name)
            .putString("pressureCurve", settings.pressureCurve.name)
            .putString("pollingRate", settings.pollingRate.name)
            .putBoolean("strictPalmRejection_v3", settings.strictPalmRejection)
            .putBoolean("invertStylusButton", settings.invertStylusButton)
            .putBoolean("showPenCursorOnTablet", settings.showLocalInkTrail)
            .putBoolean("showAreaGuides", settings.showAreaGuides)
            .putBoolean("keepScreenOn", settings.keepScreenOn)
            .apply()
    }
}

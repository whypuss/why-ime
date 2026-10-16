package com.wai.ime.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 震動與觸覺反饋管理工具類
 * 支援從設置讀取震動強度（0-100%），並為按鍵、候選字點擊等提供細膩震感
 */
object VibrationHelper {

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    /**
     * 觸發震動反饋
     * @param view 可選的觸控 View，用於觸發系統級 HapticFeedback
     * @param settings 用戶設定庫
     * @param isCandidateSelect 是否為選中候選詞（選詞時震動感可稍作區分，清脆有力）
     */
    fun vibrate(context: Context, view: View? = null, settings: SettingsRepository, isCandidateSelect: Boolean = false) {
        if (!settings.keyVibration) return
        val strength = settings.vibrationStrength // 0 .. 100

        if (strength <= 0) return

        init(context)

        // 1. 調用系統原生觸覺反饋（部分機型如 OPPO/ColorOS 配合線性馬達效果極佳）
        view?.performHapticFeedback(
            if (isCandidateSelect) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.KEYBOARD_TAP
        )

        // 2. 自定義時長與振幅震動
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 將 1..100 映射到振幅 1..255
                val amplitude = ((strength / 100f) * 255).toInt().coerceIn(1, 255)
                // 震動時長微調：普通按鍵 15ms ~ 35ms，選詞 20ms ~ 45ms
                val durationMs = if (isCandidateSelect) {
                    (15L + (strength * 0.25f).toLong()).coerceIn(15L, 45L)
                } else {
                    (10L + (strength * 0.20f).toLong()).coerceIn(10L, 35L)
                }

                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val durationMs = (10L + (strength * 0.25f).toLong()).coerceIn(10L, 40L)
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}

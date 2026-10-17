package com.wai.ime.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 歪輸入法用戶設定儲存庫
 */
class SettingsRepository private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wai_ime_prefs", Context.MODE_PRIVATE)

    // 鍵盤界面設置
    var keyVibration: Boolean
        get() = prefs.getBoolean("key_vibration", true)
        set(value) = prefs.edit().putBoolean("key_vibration", value).apply()

    /**
     * 震動強度百分比 (0 - 100)，預設 70%，提供充沛有力的觸覺反饋
     */
    var vibrationStrength: Int
        get() = prefs.getInt("vibration_strength", 70)
        set(value) = prefs.edit().putInt("vibration_strength", value.coerceIn(0, 100)).apply()

    var keyAnimation: Boolean
        get() = prefs.getBoolean("key_animation", true)
        set(value) = prefs.edit().putBoolean("key_animation", value).apply()

    var candidateFontSize: Int
        get() = prefs.getInt("candidate_font_size", 18) // 15, 18, 22
        set(value) = prefs.edit().putInt("candidate_font_size", value).apply()

    var keyboardHeightDp: Int
        get() = prefs.getInt("keyboard_height_dp", 270) // 240 (矮), 270 (標準/適配行距+1mm), 295 (大屏/高鍵盤)
        set(value) = prefs.edit().putInt("keyboard_height_dp", value).apply()

    /**
     * 底部切換輸入法/收起鍵盤導航欄顯示模式：
     * "auto": 自動偵測（當系統導航欄已有切換按鍵時自動隱藏，避免重複）
     * "hide": 始終隱藏（最大化鍵盤面積，推薦）
     * "show": 始終顯示
     */
    var bottomNavBarMode: String
        get() = prefs.getString("bottom_nav_bar_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("bottom_nav_bar_mode", value).apply()

    // 語言與學習目標 (目前支援日語 "ja")
    var targetLanguage: String
        get() = prefs.getString("target_language", "ja") ?: "ja"
        set(value) = prefs.edit().putString("target_language", value).apply()

    // 常規與輸入設置
    var traditionalVariant: String
        get() = prefs.getString("traditional_variant", "off") ?: "off" // off, taiwan, hongkong, standard
        set(value) = prefs.edit().putString("traditional_variant", value).apply()

    var shuangpinScheme: String
        get() = prefs.getString("shuangpin_scheme", "off") ?: "off" // off, xiaohe, ziranma, microsoft, sogou, abc, xiaolang, shoudao, jiajia
        set(value) = prefs.edit().putString("shuangpin_scheme", value).apply()

    var learningEnabled: Boolean
        get() = prefs.getBoolean("learning_enabled", true)
        set(value) = prefs.edit().putBoolean("learning_enabled", value).apply()

    // 翻譯直接上屏模式（打開時直接輸出外語翻譯）
    var outputTranslationMode: Boolean
        get() = prefs.getBoolean("output_translation_mode", false)
        set(value) = prefs.edit().putBoolean("output_translation_mode", value).apply()

    // 模糊拼音設置
    fun isFuzzyEnabled(rule: String): Boolean {
        return prefs.getBoolean("fuzzy_$rule", false)
    }

    fun setFuzzyEnabled(rule: String, enabled: Boolean) {
        prefs.edit().putBoolean("fuzzy_$rule", enabled).apply()
    }

    /**
     * 把所有設置同步應用到 Rust Engine 實例
     */
    fun applyToEngine(context: Context? = null) {
        val rules = listOf("z_zh", "c_ch", "s_sh", "n_l", "f_h", "l_r", "an_ang", "en_eng", "in_ing")
        for (r in rules) {
            WaiNativeEngine.setFuzzyRule(r, isFuzzyEnabled(r))
        }
        WaiNativeEngine.setShuangpin(shuangpinScheme)
        WaiNativeEngine.setTraditional(traditionalVariant)
        WaiNativeEngine.setLearning(learningEnabled)
        if (context != null) {
            WaiNativeEngine.setTargetLanguage(context, targetLanguage)
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}

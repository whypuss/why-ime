package com.wai.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 鍵盤頂部功能欄目（Toolbar）：
 * 包含：鍵盤切換、語言切換、繁體簡體切換、表情(Emoji)、設置快捷入口
 */
class KeyboardToolbarView(context: Context) : LinearLayout(context) {

    var onSwitchImeClick: (() -> Unit)? = null
    var onLanguageClick: (() -> Unit)? = null
    var onOutputTranslationToggleClick: (() -> Unit)? = null
    var onTraditionalToggleClick: (() -> Unit)? = null
    var onEmojiClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    private val langBtn: TextView
    private val outputTransBtn: TextView
    private val tradBtn: TextView
    private val emojiBtn: TextView

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.parseColor("#E5E7EB"))
        setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
        gravity = Gravity.CENTER_VERTICAL

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. 輸出外語翻譯開關（打開時輸出翻譯語言，關閉時輸出中文）
        outputTransBtn = createToolbarButton("🎯 輸出: 中文") {
            onOutputTranslationToggleClick?.invoke()
        }
        container.addView(outputTransBtn)

        // 2. 鍵盤切換
        val switchImeBtn = createToolbarButton("🌐 換鍵盤") {
            onSwitchImeClick?.invoke()
        }
        container.addView(switchImeBtn)

        // 3. 語言切換 (日 / 英 / 西 / 德 / 越)
        langBtn = createToolbarButton("🗣️ 語言: 日語") {
            onLanguageClick?.invoke()
        }
        container.addView(langBtn)

        // 4. 繁體/簡體一鍵切換
        tradBtn = createToolbarButton("文 簡") {
            onTraditionalToggleClick?.invoke()
        }
        container.addView(tradBtn)

        // 5. 表情
        emojiBtn = createToolbarButton("😀 表情") {
            onEmojiClick?.invoke()
        }
        container.addView(emojiBtn)

        // 6. 更多設定
        val settingsBtn = createToolbarButton("⚙️ 設置") {
            onSettingsClick?.invoke()
        }
        container.addView(settingsBtn)

        scrollView.addView(container)
        addView(scrollView)
    }

    fun updateLanguageLabel(langCode: String) {
        val name = when (langCode.lowercase()) {
            "ja" -> "日語"
            "en" -> "英語"
            "es" -> "西語"
            "de" -> "德語"
            "vi" -> "越語"
            "ko" -> "韓語"
            "pt" -> "葡語"
            else -> "日語"
        }
        langBtn.text = "🗣️ 語言: $name"
    }

    fun updateOutputTranslationLabel(isTranslateMode: Boolean, langCode: String) {
        val name = when (langCode.lowercase()) {
            "ja" -> "日語"
            "en" -> "英語"
            "es" -> "西語"
            "de" -> "德語"
            "vi" -> "越語"
            "ko" -> "韓語"
            "pt" -> "葡語"
            else -> "外語"
        }
        if (isTranslateMode) {
            outputTransBtn.text = "🎯 輸出: $name (譯)"
            outputTransBtn.setTextColor(Color.parseColor("#007AFF"))
        } else {
            outputTransBtn.text = "🎯 輸出: 中文"
            outputTransBtn.setTextColor(Color.parseColor("#3C3C43"))
        }
    }

    fun updateTraditionalLabel(variant: String) {
        val isTrad = variant != "off"
        tradBtn.text = if (isTrad) "文 繁體" else "文 簡體"
        tradBtn.setTextColor(if (isTrad) Color.parseColor("#007AFF") else Color.parseColor("#3C3C43"))
    }

    fun setEmojiActive(active: Boolean) {
        emojiBtn.setTextColor(if (active) Color.parseColor("#007AFF") else Color.parseColor("#3C3C43"))
    }

    private fun createToolbarButton(title: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3C3C43"))
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpToPx(14).toFloat()
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(3), dpToPx(2), dpToPx(3), dpToPx(2))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

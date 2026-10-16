package com.wai.ime.service

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.wai.ime.core.Candidate
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.WaiNativeEngine
import com.wai.ime.ui.CandidateBarView
import com.wai.ime.ui.KeyboardToolbarView
import com.wai.ime.ui.KeyboardView
import com.wai.ime.ui.MainActivity

/**
 * 歪輸入法核心服務 (InputMethodService)
 */
class WaiInputMethodService : InputMethodService() {

    private lateinit var rootView: LinearLayout
    private lateinit var toolbarView: KeyboardToolbarView
    private lateinit var candidateBarView: CandidateBarView
    private lateinit var keyboardView: KeyboardView
    private lateinit var settings: SettingsRepository

    private var currentCandidates: List<Candidate> = emptyList()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(applicationContext)
        // 異步在後台初始化 Rust 辭典，避免阻塞啟動主線程
        Thread {
            val ok = WaiNativeEngine.initialize(applicationContext, settings.targetLanguage)
            if (ok) {
                settings.applyToEngine(applicationContext)
            }
        }.start()
    }

    override fun onCreateInputView(): View {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. 頂部工具欄 (功能欄目：語言切換、繁簡切換、表情、設置)
        toolbarView = KeyboardToolbarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onLanguageClick = {
                showLanguageSwitchDialog()
            }
            onOutputTranslationToggleClick = {
                toggleOutputTranslationMode()
            }
            onTraditionalToggleClick = {
                toggleTraditionalMode()
            }
            onEmojiClick = {
                keyboardView.toggleEmojiMode()
                toolbarView.setEmojiActive(keyboardView.currentMode == com.wai.ime.ui.KeyboardMode.EMOJI)
            }
            onSettingsClick = {
                val intent = Intent(this@WaiInputMethodService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
        rootView.addView(toolbarView)

        // 2. 雙語候選欄 (中文 + 外語釋義/假名)
        candidateBarView = CandidateBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            candidateFontSize = settings.candidateFontSize.toFloat()
            onCandidateClickListener = { index ->
                commitCandidateByIndex(index)
            }
        }
        rootView.addView(candidateBarView)

        // 3. 底部鍵盤區域 (含逗號、句號、中英切換、以及底欄地球圖標切換輸入法)
        val heightInPx = (settings.keyboardHeightDp * resources.displayMetrics.density).toInt()
        keyboardView = KeyboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightInPx
            )
            onKeyClickListener = { key ->
                handleKeyInput(key)
            }
            onBackspaceListener = {
                handleBackspace()
            }
            onSpaceListener = {
                handleSpace()
            }
            onEnterListener = {
                handleEnter()
            }
            onSwitchImeListener = {
                handleSwitchIme()
            }
            onSwitchImeLongClickListener = {
                showInputMethodPicker()
            }
            onHideKeyboardListener = {
                requestHideSelf(0)
            }
            onEnglishModeToggleListener = { isEnglish ->
                if (isEnglish) {
                    resetComposing()
                }
            }
        }
        rootView.addView(keyboardView)

        return rootView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // 解決 vivo 等部分國產手機/定製系統因硬體鍵盤狀態誤判或暗中攔截導致鍵盤無法彈出的 bug
        return true
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 每次彈出鍵盤時，刷新最新設置與頂部欄狀態
        settings.applyToEngine(applicationContext)
        toolbarView.updateLanguageLabel(settings.targetLanguage)
        toolbarView.updateOutputTranslationLabel(settings.outputTranslationMode, settings.targetLanguage)
        toolbarView.updateTraditionalLabel(settings.traditionalVariant)
        toolbarView.setEmojiActive(keyboardView.currentMode == com.wai.ime.ui.KeyboardMode.EMOJI)

        candidateBarView.candidateFontSize = settings.candidateFontSize.toFloat()
        val heightInPx = (settings.keyboardHeightDp * resources.displayMetrics.density).toInt()
        keyboardView.layoutParams = (keyboardView.layoutParams as LinearLayout.LayoutParams).apply {
            height = heightInPx
        }
        resetComposing()
        keyboardView.renderKeyboard()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing()
    }

    override fun onDestroy() {
        super.onDestroy()
        WaiNativeEngine.destroy()
    }

    private fun handleKeyInput(key: String) {
        // 純英文模式下，直接上屏英文字符，不走中文拼音邏輯
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.commitText(key, 1)
            return
        }

        // 如果是單個英文字母，且非雙拼方案禁用全拼，則進入拼音/雙拼處理
        if (key.length == 1 && key[0].lowercaseChar() in 'a'..'z') {
            val state = WaiNativeEngine.feedChar(key[0].lowercaseChar())
            currentCandidates = state.candidates
            currentInputConnection?.setComposingText(state.preedit, 1)
            candidateBarView.updateState(state.preedit, currentCandidates)
        } else {
            // 數字、標點符號（如逗號、句號）、表情或非拼音鍵
            if (currentCandidates.isNotEmpty()) {
                commitCandidateByIndex(0)
            }
            currentInputConnection?.commitText(key, 1)
        }
    }

    private fun handleBackspace() {
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }

        val rawPreedit = WaiNativeEngine.getRawPreedit()
        if (rawPreedit.isNotEmpty()) {
            val state = WaiNativeEngine.backspace()
            currentCandidates = state.candidates
            if (state.preedit.isNotEmpty()) {
                currentInputConnection?.setComposingText(state.preedit, 1)
            } else {
                currentInputConnection?.commitText("", 1)
            }
            candidateBarView.updateState(state.preedit, currentCandidates)
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleSpace() {
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.commitText(" ", 1)
            return
        }

        if (currentCandidates.isNotEmpty()) {
            // 空格選中第一個候選
            commitCandidateByIndex(0)
        } else {
            val raw = WaiNativeEngine.getRawPreedit()
            if (raw.isNotEmpty()) {
                currentInputConnection?.commitText(raw, 1)
                resetComposing()
            } else {
                currentInputConnection?.commitText(" ", 1)
            }
        }
    }

    private fun handleEnter() {
        if (keyboardView.isEnglishMode) {
            sendKeyChar('\n')
            return
        }

        val raw = WaiNativeEngine.getRawPreedit()
        if (raw.isNotEmpty()) {
            // 回車將當前拼音原樣上屏
            currentInputConnection?.commitText(raw, 1)
            resetComposing()
        } else {
            sendKeyChar('\n')
        }
    }

    /**
     * 切換至系統中其他可用的輸入法
     */
    private fun handleSwitchIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9.0+ 支援無縫切換到上一個/下一個輸入法
            if (!switchToNextInputMethod(false)) {
                imm.showInputMethodPicker()
            }
        } else {
            imm.showInputMethodPicker()
        }
    }

    /**
     * 長按地球圖標：直接彈出系統輸入法選擇列表（方便用戶手動選擇具體輸入法）
     */
    private fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showInputMethodPicker()
    }

    /**
     * 一鍵切換是否直接輸出外語翻譯
     */
    private fun toggleOutputTranslationMode() {
        val nextMode = !settings.outputTranslationMode
        settings.outputTranslationMode = nextMode
        toolbarView.updateOutputTranslationLabel(nextMode, settings.targetLanguage)
    }

    /**
     * 一鍵切換繁體/簡體
     */
    private fun toggleTraditionalMode() {
        val nextVariant = if (settings.traditionalVariant == "off") "taiwan" else "off"
        settings.traditionalVariant = nextVariant
        settings.applyToEngine(applicationContext)
        toolbarView.updateTraditionalLabel(nextVariant)

        // 如果當前有正在輸入的拼音，刷新一次候選以即時看到簡繁變化
        val raw = WaiNativeEngine.getRawPreedit()
        if (raw.isNotEmpty()) {
            val state = WaiNativeEngine.feedChar(raw.last()) // 重新觸發刷新
            currentCandidates = state.candidates
            candidateBarView.updateState(state.preedit, currentCandidates)
        }
    }

    /**
     * 頂部欄一鍵切換學習目標語言對照（日語、英語、西語、德語、越語、韓語、葡語）
     */
    private fun showLanguageSwitchDialog() {
        val langs = arrayOf(
            "ja" to "日語 (日本語)",
            "en" to "英語 (English)",
            "es" to "西班牙語 (Español)",
            "de" to "德語 (Deutsch)",
            "vi" to "越南語 (Tiếng Việt)",
            "ko" to "韓語 (한국어)",
            "pt" to "葡萄牙語 (Português)"
        )
        val names = langs.map { it.second }.toTypedArray()
        val codes = langs.map { it.first }.toTypedArray()
        val currentIdx = codes.indexOf(settings.targetLanguage).coerceAtLeast(0)

        val dialog = AlertDialog.Builder(this)
            .setTitle("切換對照學習語言")
            .setSingleChoiceItems(names, currentIdx) { d, which ->
                val newLang = codes[which]
                settings.targetLanguage = newLang
                Thread {
                    WaiNativeEngine.setTargetLanguage(applicationContext, newLang)
                }.start()
                toolbarView.updateLanguageLabel(newLang)
                toolbarView.updateOutputTranslationLabel(settings.outputTranslationMode, newLang)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.window?.let { window ->
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            window.setType(type)
        }

        try {
            dialog.show()
        } catch (_: Exception) {
            // 若無浮動視窗權限，輪轉切換到下一個語言
            val nextIdx = (currentIdx + 1) % codes.size
            val nextLang = codes[nextIdx]
            settings.targetLanguage = nextLang
            Thread {
                WaiNativeEngine.setTargetLanguage(applicationContext, nextLang)
            }.start()
            toolbarView.updateLanguageLabel(nextLang)
            toolbarView.updateOutputTranslationLabel(settings.outputTranslationMode, nextLang)
        }
    }

    private fun commitCandidateByIndex(index: Int) {
        val text = if (settings.outputTranslationMode) {
            val trans = WaiNativeEngine.commitTranslation(index)
            if (trans.isNotEmpty()) trans else WaiNativeEngine.commitCandidate(index)
        } else {
            WaiNativeEngine.commitCandidate(index)
        }
        if (text.isNotEmpty()) {
            currentInputConnection?.commitText(text, 1)
        }
        resetComposing()
    }

    private fun resetComposing() {
        WaiNativeEngine.clear()
        currentCandidates = emptyList()
        candidateBarView.updateState("", emptyList())
        currentInputConnection?.finishComposingText()
    }
}

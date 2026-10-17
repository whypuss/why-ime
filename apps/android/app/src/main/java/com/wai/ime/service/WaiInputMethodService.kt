package com.wai.ime.service

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.wai.ime.core.Candidate
import com.wai.ime.core.CandidateResult
import com.wai.ime.core.CommitResult
import com.wai.ime.core.EngineDispatcher
import com.wai.ime.core.EngineDispatcherListener
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.VibrationHelper
import com.wai.ime.core.WaiNativeEngine
import com.wai.ime.ui.CandidateBarView
import com.wai.ime.ui.KeyboardToolbarView
import com.wai.ime.ui.KeyboardView
import com.wai.ime.ui.MainActivity

/**
 * 歪輸入法核心服務 (InputMethodService)
 * 實現 P0 架構解耦：主線程即時反饋拼音，Worker 單執行緒嚴格保序執行與查詢合併
 */
class WaiInputMethodService : InputMethodService(), EngineDispatcherListener {

    private lateinit var rootView: LinearLayout
    private lateinit var headerContainer: FrameLayout
    private lateinit var toolbarView: KeyboardToolbarView
    private lateinit var candidateBarView: CandidateBarView
    private lateinit var keyboardView: KeyboardView
    private lateinit var settings: SettingsRepository
    private lateinit var dispatcher: EngineDispatcher

    private var currentCandidates: List<Candidate> = emptyList()
    private var localPreedit: String = ""

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(applicationContext)
        dispatcher = EngineDispatcher(this)
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

        // 頂部固定高度容器 (60dp)：
        // 未輸入文字時顯示功能按鈕欄 (Toolbar)；
        // 開始輸入文字時，文字候選欄 (CandidateBar，含拼音行 + 雙語候選字與外語假名/釋義) 自動覆蓋/隱藏功能欄；
        // 嚴格保證 60dp 充裕高度，讓中文字詞與外語翻譯/假名對照完整顯示不被遮擋裁剪，
        // 且高度始終 100% 絕對固定，徹底解決高度動態變形導致宿主 App 介面被頂動的問題！
        val headerHeightPx = (60 * resources.displayMetrics.density).toInt()
        headerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx
            )
        }

        // 1. 頂部工具欄 (功能欄目：語言切換、繁簡切換、表情、設置)
        toolbarView = KeyboardToolbarView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
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
        headerContainer.addView(toolbarView)

        // 2. 雙語候選欄 (中文 + 外語釋義/假名)
        candidateBarView = CandidateBarView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            candidateFontSize = settings.candidateFontSize.toFloat()
            onCandidateClickListener = { index ->
                commitCandidateByIndex(index)
            }
        }
        headerContainer.addView(candidateBarView)

        rootView.addView(headerContainer)

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
            onKeyLongPressReplaceListener = { originalKey, replacementKey ->
                handleKeyLongPressReplace(originalKey, replacementKey)
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
        keyboardView.shouldShowBottomIcons = resolveShouldShowBottomIcons()
        resetComposing()
        // 關鍵防閃爍修復：只有全新彈出鍵盤時才完整重構鍵盤；如果是輸入過程中的 restarting 回調，絕對不可重建！
        if (!restarting) {
            keyboardView.renderKeyboard()
        }
    }

    /**
     * 智能判斷是否需要顯示底部欄中的地球與鍵盤圖標：
     * 1. 底部欄目（BottomNavBar）本身的高度與人體工學留白始終保留，防止按鍵被壓到最底邊；
     * 2. 圖標顯示判斷：
     *    - "hide": 隱藏圖標（保持留白空欄）
     *    - "show": 顯示圖標
     *    - "auto": 當檢測到系統導航欄已有原生地球/切換按鈕時，自動隱藏圖標；無原生支持時才顯示圖標。
     */
    private fun resolveShouldShowBottomIcons(): Boolean {
        return when (settings.bottomNavBarMode) {
            "hide" -> false
            "show" -> true
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    !shouldOfferSwitchingToNextInputMethod()
                } else {
                    false
                }
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposing()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::dispatcher.isInitialized) {
            dispatcher.destroy()
        }
        WaiNativeEngine.destroy()
    }

    override fun onCandidatesUpdated(result: CandidateResult) {
        // 嚴格版本與 session 隔離：舊 session 或過期請求直接丟棄
        if (!::dispatcher.isInitialized || result.sessionId != dispatcher.currentSessionId) {
            return
        }
        if (result.requestId < dispatcher.latestDeliveredRequestId) {
            return
        }
        dispatcher.latestDeliveredRequestId = result.requestId

        if (localPreedit.isEmpty()) {
            currentCandidates = emptyList()
            if (::candidateBarView.isInitialized) {
                candidateBarView.updateState("", emptyList())
            }
            updateHeaderMode(false)
        } else {
            currentCandidates = result.candidates
            if (::candidateBarView.isInitialized) {
                candidateBarView.updateState(result.preedit.ifEmpty { localPreedit }, currentCandidates)
            }
            updateHeaderMode(true)
        }
    }

    override fun onCommitResult(result: CommitResult) {
        if (result.sessionId != dispatcher.currentSessionId) {
            return
        }
        if (result.success && result.committedText.isNotEmpty()) {
            currentInputConnection?.commitText(result.committedText, 1)
            localPreedit = result.remainingPreedit
            if (localPreedit.isEmpty()) {
                currentCandidates = emptyList()
                if (::candidateBarView.isInitialized) {
                    candidateBarView.updateState("", emptyList())
                }
                updateHeaderMode(false)
                currentInputConnection?.finishComposingText()
            } else {
                updateHeaderMode(true)
                currentInputConnection?.setComposingText(localPreedit, 1)
            }
        }
    }

    private fun handleKeyInput(key: String) {
        // 純英文模式下，直接上屏英文字符，不走中文拼音邏輯
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.commitText(key, 1)
            return
        }

        // 單個英文字母，進入拼音/雙拼處理
        if (key.length == 1 && key[0].lowercaseChar() in 'a'..'z') {
            val t0 = System.nanoTime()
            val ch = key[0].lowercaseChar()

            // 1. 本地立即更新拼音狀態（T0 -> T1，< 0.1ms）
            localPreedit += ch
            updateHeaderMode(true)
            val t1 = System.nanoTime()

            // 候選條頂部預編輯區立即刷新顯示拼音（0ms 零延遲反饋給用戶）
            if (::candidateBarView.isInitialized) {
                candidateBarView.updatePreeditOnly(localPreedit)
            }

            // 2. 主線程調用 setComposingText 刷新輸入框（T1 -> T2）
            currentInputConnection?.setComposingText(localPreedit, 1)
            val t2 = System.nanoTime()

            // 3. 異步送入單執行緒 Worker 佇列，嚴格保序執行，自動合併過期查詢
            dispatcher.enqueuePush(ch)
            val t3 = System.nanoTime()

            val localMs = (t1 - t0) / 1_000_000.0
            val ipcMs = (t2 - t1) / 1_000_000.0
            val enqueueMs = (t3 - t2) / 1_000_000.0
            val mainTotalMs = (t3 - t0) / 1_000_000.0

            Log.i(
                "WAI_PERF",
                "[MAIN_THREAD] key='$ch' | localPreedit=${String.format("%.2f", localMs)}ms, " +
                "IPC(setComposing)=${String.format("%.2f", ipcMs)}ms, " +
                "enqueue=${String.format("%.2f", enqueueMs)}ms | MainTotal=${String.format("%.2f", mainTotalMs)}ms"
            )
        } else {
            // 數字、標點符號（如逗號、句號）、表情或非拼音鍵
            if (localPreedit.isNotEmpty() && currentCandidates.isNotEmpty()) {
                commitCandidateByIndex(0)
            }
            currentInputConnection?.commitText(key, 1)
        }
    }

    /**
     * 當按鍵被長按超過 350ms 時觸發，將剛才 ACTION_DOWN 輸入的字母替換為右上角副字符 (數字/標點)
     */
    private fun handleKeyLongPressReplace(originalKey: String, replacementKey: String) {
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            currentInputConnection?.commitText(replacementKey, 1)
            return
        }

        if (originalKey.length == 1 && originalKey[0].lowercaseChar() in 'a'..'z') {
            if (localPreedit.endsWith(originalKey.lowercase())) {
                localPreedit = localPreedit.dropLast(1)
                dispatcher.enqueueBackspace()
                if (localPreedit.isNotEmpty()) {
                    currentInputConnection?.setComposingText(localPreedit, 1)
                    if (::candidateBarView.isInitialized) {
                        candidateBarView.updatePreeditOnly(localPreedit)
                    }
                    updateHeaderMode(true)
                } else {
                    currentInputConnection?.commitText("", 1)
                    currentCandidates = emptyList()
                    if (::candidateBarView.isInitialized) {
                        candidateBarView.updateState("", emptyList())
                    }
                    updateHeaderMode(false)
                }
            }
            currentInputConnection?.commitText(replacementKey, 1)
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
            currentInputConnection?.commitText(replacementKey, 1)
        }
    }

    private fun handleBackspace() {
        if (keyboardView.isEnglishMode) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            return
        }

        if (localPreedit.isNotEmpty()) {
            localPreedit = localPreedit.dropLast(1)
            if (localPreedit.isNotEmpty()) {
                currentInputConnection?.setComposingText(localPreedit, 1)
                updateHeaderMode(true)
            } else {
                currentInputConnection?.commitText("", 1)
                currentCandidates = emptyList()
                candidateBarView.updateState("", emptyList())
                updateHeaderMode(false)
            }
            dispatcher.enqueueBackspace()
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
            if (localPreedit.isNotEmpty()) {
                // 無候選時空格上屏原始拼音
                currentInputConnection?.commitText(localPreedit, 1)
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

        if (localPreedit.isNotEmpty()) {
            // 回車將當前拼音原樣上屏
            currentInputConnection?.commitText(localPreedit, 1)
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

        // 如果當前有正在輸入的拼音，刷新候選
        if (localPreedit.isNotEmpty()) {
            val chars = localPreedit.toCharArray()
            dispatcher.newSession()
            for (c in chars) {
                dispatcher.enqueuePush(c)
            }
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

    /**
     * 統一控制頂部固定容器 (60dp) 顯示模式：
     * - 當正在輸入拼音或有候選字詞時：候選字欄顯示，覆蓋/隱藏功能按鈕欄；
     * - 當未輸入文字（空閒狀態）時：恢復顯示功能按鈕欄 (Toolbar)。
     * 確保整個輸入法視圖高度 100% 絕對固定，徹底根除宿主 App 介面被頂動抖動！
     */
    private fun updateHeaderMode(isComposing: Boolean) {
        if (!::toolbarView.isInitialized || !::candidateBarView.isInitialized) return
        if (isComposing) {
            if (candidateBarView.visibility != View.VISIBLE) {
                candidateBarView.visibility = View.VISIBLE
            }
            if (toolbarView.visibility != View.GONE) {
                toolbarView.visibility = View.GONE
            }
        } else {
            if (toolbarView.visibility != View.VISIBLE) {
                toolbarView.visibility = View.VISIBLE
            }
            if (candidateBarView.visibility != View.GONE) {
                candidateBarView.visibility = View.GONE
            }
        }
    }

    private fun commitCandidateByIndex(index: Int) {
        if (index in currentCandidates.indices) {
            // 空格或按鍵選中候選詞時，同樣觸發選詞震動反饋
            VibrationHelper.vibrate(this, candidateBarView, settings, isCandidateSelect = true)
            val cand = currentCandidates[index]
            dispatcher.enqueueCommit(index, cand.text, settings.outputTranslationMode)
        } else if (localPreedit.isNotEmpty()) {
            currentInputConnection?.commitText(localPreedit, 1)
            resetComposing()
        }
    }

    private fun resetComposing() {
        localPreedit = ""
        currentCandidates = emptyList()
        if (::candidateBarView.isInitialized) {
            candidateBarView.updateState("", emptyList())
        }
        updateHeaderMode(false)
        currentInputConnection?.finishComposingText()
        if (::dispatcher.isInitialized) {
            dispatcher.enqueueClear()
        }
    }
}

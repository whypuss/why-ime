package com.wai.ime.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.wai.ime.core.Candidate
import com.wai.ime.core.WaiNativeEngine
import com.wai.ime.ui.CandidateBarView
import com.wai.ime.ui.KeyboardView

/**
 * 歪輸入法核心服務 (InputMethodService)
 */
class WaiInputMethodService : InputMethodService() {

    private lateinit var rootView: LinearLayout
    private lateinit var candidateBarView: CandidateBarView
    private lateinit var keyboardView: KeyboardView

    private var currentCandidates: List<Candidate> = emptyList()

    override fun onCreate() {
        super.onCreate()
        // 異步在後台初始化 Rust 辭典，避免阻塞啟動主線程
        Thread {
            WaiNativeEngine.initialize(applicationContext, "ja")
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

        // 1. 頂部雙語候選欄 (中文 + 日語釋義/假名)
        candidateBarView = CandidateBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onCandidateClickListener = { index ->
                commitCandidateByIndex(index)
            }
        }
        rootView.addView(candidateBarView)

        // 2. 底部鍵盤區域
        keyboardView = KeyboardView(this).apply {
            val heightInPx = (250 * resources.displayMetrics.density).toInt()
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
        }
        rootView.addView(keyboardView)

        return rootView
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        if (key.length == 1 && key[0].lowercaseChar() in 'a'..'z') {
            val state = WaiNativeEngine.feedChar(key[0].lowercaseChar())
            currentCandidates = state.candidates
            currentInputConnection?.setComposingText(state.preedit, 1)
            candidateBarView.updateState(state.preedit, currentCandidates)
        } else {
            // 數字、標點符號或非拼音鍵
            if (currentCandidates.isNotEmpty()) {
                commitCandidateByIndex(0)
            }
            currentInputConnection?.commitText(key, 1)
        }
    }

    private fun handleBackspace() {
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

    private fun commitCandidateByIndex(index: Int) {
        val text = WaiNativeEngine.commitCandidate(index)
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

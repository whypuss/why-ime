package com.wai.ime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wai.ime.core.SettingsRepository

enum class KeyboardMode {
    TEXT,
    NUMERIC,
    SYMBOL,
    EMOJI
}

/**
 * 鍵盤視圖：對齊百度輸入法尺寸與鍵位佈局（針對 OPPO Find N2 等摺疊屏與大屏深度優化）：
 * 1. 26 鍵雙層鍵位（字母居中，右上角精緻數字/標點角標，長按直出）；
 * 2. 字母行按比例權重居中，與頂部 10 鍵等寬對齊，防止誤觸；
 * 3. 底部功能行對齊主流鍵位：[符] [123] [,] [ 空格 ] [。] [中/英] [ 确定 ]；
 * 4. 長按退格鍵持續高速連刪（每秒 20 字）；
 * 5. 支援極速 ACTION_DOWN 低延遲觸發、微縮放動畫與震動觸覺反饋。
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKeyClickListener: ((String) -> Unit)? = null
    var onBackspaceListener: (() -> Unit)? = null
    var onSpaceListener: (() -> Unit)? = null
    var onEnterListener: (() -> Unit)? = null
    var onSwitchImeListener: (() -> Unit)? = null
    var onEnglishModeToggleListener: ((Boolean) -> Unit)? = null

    var currentMode = KeyboardMode.TEXT
        private set

    /** 是否為純英文輸入模式（中英切換） */
    var isEnglishMode: Boolean = false
        private set

    private var isShifted = false
    private val settings = SettingsRepository.getInstance(context)

    private val handler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#D4D7DE"))
        setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(5))
        renderKeyboard()
    }

    private fun triggerHaptic() {
        if (!settings.keyVibration) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        } catch (_: Exception) {}
    }

    fun switchMode(mode: KeyboardMode) {
        currentMode = mode
        renderKeyboard()
    }

    fun toggleEmojiMode() {
        currentMode = if (currentMode == KeyboardMode.EMOJI) KeyboardMode.TEXT else KeyboardMode.EMOJI
        renderKeyboard()
    }

    fun toggleEnglishMode() {
        isEnglishMode = !isEnglishMode
        onEnglishModeToggleListener?.invoke(isEnglishMode)
        renderKeyboard()
    }

    fun renderKeyboard() {
        removeAllViews()
        when (currentMode) {
            KeyboardMode.TEXT -> renderTextKeyboard()
            KeyboardMode.NUMERIC -> renderNumericKeyboard()
            KeyboardMode.SYMBOL -> renderSymbolKeyboard()
            KeyboardMode.EMOJI -> renderEmojiKeyboard()
        }
    }

    // ================= 1. 拼音/字母 鍵盤 (百度雙層按鍵 + 長按副符號) =================
    private fun renderTextKeyboard() {
        // 第一行：字母 + 數字 1~0 (10 鍵)
        val row1 = listOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )
        // 第二行：字母 + 常用標點 (9 鍵，兩端 0.5f 留白等寬居中)
        val row2 = listOf(
            "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "%",
            "h" to "\"", "j" to "'", "k" to "*", "l" to "?"
        )
        // 第三行：字母 + 括號標點 (Shift + 7 鍵 + 退格鍵)
        val row3 = listOf(
            "z" to "(", "x" to ")", "c" to "-", "v" to "_",
            "b" to ":", "n" to ";", "m" to "/"
        )

        addView(createCompositeTextRow1(row1))
        addView(createCompositeTextRow2(row2))
        addView(createTextRow3(row3))
        addView(createBottomRow())
    }

    private fun createCompositeTextRow1(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val keyView = createCompositeKeyView(mainLabel, hint).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setKeyTouchWithLongPress(
                    onTap = { onKeyClickListener?.invoke(mainLabel) },
                    onLongPress = { onKeyClickListener?.invoke(hint) }
                )
            }
            row.addView(keyView)
        }
        return row
    }

    private fun createCompositeTextRow2(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        // 左側半鍵寬度佔位 Spacer (0.5f weight)，確保 A~L 鍵寬度與 Q~P 嚴格一致
        val leftSpacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f)
        }
        row.addView(leftSpacer)

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val keyView = createCompositeKeyView(mainLabel, hint).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setKeyTouchWithLongPress(
                    onTap = { onKeyClickListener?.invoke(mainLabel) },
                    onLongPress = { onKeyClickListener?.invoke(hint) }
                )
            }
            row.addView(keyView)
        }

        // 右側半鍵寬度佔位 Spacer (0.5f weight)
        val rightSpacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f)
        }
        row.addView(rightSpacer)

        return row
    }

    private fun createTextRow3(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        // Shift 鍵 (weight 1.45f)
        val shiftLabel = if (isShifted) "⇪" else "⇧"
        val shiftBtn = createSpecialButton(shiftLabel, weight = 1.45f).apply {
            if (isShifted) {
                background = createKeyBackground(Color.parseColor("#FFFFFF"), Color.parseColor("#B4B7BF"), cornerDp = 7)
                setTextColor(Color.parseColor("#007AFF"))
            }
            setFastTouchListener {
                isShifted = !isShifted
                renderKeyboard()
            }
        }
        row.addView(shiftBtn)

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val keyView = createCompositeKeyView(mainLabel, hint).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setKeyTouchWithLongPress(
                    onTap = { onKeyClickListener?.invoke(mainLabel) },
                    onLongPress = { onKeyClickListener?.invoke(hint) }
                )
            }
            row.addView(keyView)
        }

        // 退格鍵 (weight 1.45f，長按高速連刪)
        val delBtn = createSpecialButton("⌫", weight = 1.45f).apply {
            setRepeatBackspaceTouchListener()
        }
        row.addView(delBtn)

        return row
    }

    // ================= 2. 數字鍵盤 =================
    private fun renderNumericKeyboard() {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row2 = listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”")
        val row3 = listOf("。", "，", "、", "？", "！", ".", "…")

        addView(createKeyRowSimple(row1))
        addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        // 切換到符號鍵盤
        val symBtn = createSpecialButton("#+=", weight = 1.45f).apply {
            setFastTouchListener {
                currentMode = KeyboardMode.SYMBOL
                renderKeyboard()
            }
        }
        row3Layout.addView(symBtn)

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row3Layout.addView(btn)
        }

        // 退格鍵（長按連刪）
        val delBtn = createSpecialButton("⌫", weight = 1.45f).apply {
            setRepeatBackspaceTouchListener()
        }
        row3Layout.addView(delBtn)
        addView(row3Layout)

        addView(createBottomRow())
    }

    // ================= 3. 符號鍵盤 =================
    private fun renderSymbolKeyboard() {
        val row1 = listOf("【", "】", "{", "}", "#", "%", "^", "*", "+", "=")
        val row2 = listOf("_", "\\", "|", "~", "<", ">", "$", "€", "￡", "•")
        val row3 = listOf("‘", "’", "《", "》", "·", "—", ":")

        addView(createKeyRowSimple(row1))
        addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        // 返回 123
        val numBtn = createSpecialButton("123", weight = 1.45f).apply {
            setFastTouchListener {
                currentMode = KeyboardMode.NUMERIC
                renderKeyboard()
            }
        }
        row3Layout.addView(numBtn)

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row3Layout.addView(btn)
        }

        val delBtn = createSpecialButton("⌫", weight = 1.45f).apply {
            setRepeatBackspaceTouchListener()
        }
        row3Layout.addView(delBtn)
        addView(row3Layout)

        addView(createBottomRow())
    }

    // ================= 4. 表情 (Emoji) 鍵盤 =================
    private fun renderEmojiKeyboard() {
        val row1 = listOf("😀", "😂", "🤣", "😍", "🥰", "😎", "🥳", "🤔")
        val row2 = listOf("😭", "🥺", "😡", "😱", "👍", "👏", "🙏", "🎉")
        val row3 = listOf("❤️", "✨", "🔥", "💯", "🌸", "🍱", "🍙", "✈️")

        addView(createKeyRowSimple(row1))
        addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row3Layout.addView(btn)
        }

        val delBtn = createSpecialButton("⌫", weight = 1.2f).apply {
            setRepeatBackspaceTouchListener()
        }
        row3Layout.addView(delBtn)
        addView(row3Layout)

        addView(createBottomRow())
    }

    private fun createKeyRowSimple(keys: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        for (k in keys) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row.addView(btn)
        }
        return row
    }

    // ================= 底部功能行 (對齊百度輸入法: [符] [123] [,] [空格] [。] [中/英] [确定]) =================
    private fun createBottomRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()

        // 1. 符號鍵 (符) -> 切換到符號鍵盤
        val symBtn = createSpecialButton("符", weight = 1.05f).apply {
            if (currentMode == KeyboardMode.SYMBOL) {
                setTextColor(Color.parseColor("#007AFF"))
            }
            setFastTouchListener {
                currentMode = if (currentMode == KeyboardMode.SYMBOL) KeyboardMode.TEXT else KeyboardMode.SYMBOL
                renderKeyboard()
            }
        }
        row.addView(symBtn)

        // 2. 數字鍵 (123) -> 切換到數字鍵盤
        val numLabel = if (currentMode == KeyboardMode.NUMERIC) "中文" else "123"
        val numBtn = createSpecialButton(numLabel, weight = 1.05f).apply {
            if (currentMode == KeyboardMode.NUMERIC) {
                setTextColor(Color.parseColor("#007AFF"))
            }
            setFastTouchListener {
                currentMode = if (currentMode == KeyboardMode.NUMERIC) KeyboardMode.TEXT else KeyboardMode.NUMERIC
                renderKeyboard()
            }
        }
        row.addView(numBtn)

        // 3. 逗號鍵 (，/ ,) 附帶長按上標 '
        val commaChar = if (isEnglishMode) "," else "，"
        val commaKey = createCompositeKeyView(commaChar, "'").apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(marginH, 0, marginH, 0)
            }
            setKeyTouchWithLongPress(
                onTap = { onKeyClickListener?.invoke(commaChar) },
                onLongPress = { onKeyClickListener?.invoke("'") }
            )
        }
        row.addView(commaKey)

        // 4. 空格鍵 (空格 / 歪輸入法 / English)
        val spaceLabel = if (isEnglishMode) "English" else "空格"
        val spaceBtn = Button(context).apply {
            text = spaceLabel
            textSize = 13.5f
            setTextColor(Color.parseColor("#8E8E93"))
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E2E4E9"), cornerDp = 7)
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 3.4f).apply {
                setMargins(marginH, 0, marginH, 0)
            }
            setFastTouchListener { onSpaceListener?.invoke() }
        }
        row.addView(spaceBtn)

        // 5. 句號鍵 (。/ .) 附帶長按上標 ?
        val periodChar = if (isEnglishMode) "." else "。"
        val periodKey = createCompositeKeyView(periodChar, "?").apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(marginH, 0, marginH, 0)
            }
            setKeyTouchWithLongPress(
                onTap = { onKeyClickListener?.invoke(periodChar) },
                onLongPress = { onKeyClickListener?.invoke("?") }
            )
        }
        row.addView(periodKey)

        // 6. 中英切換鍵 (中/英)
        val langLabel = if (isEnglishMode) "英" else "中"
        val langBtn = createSpecialButton(langLabel, weight = 1.1f).apply {
            if (isEnglishMode) {
                setTextColor(Color.parseColor("#007AFF"))
            }
            setFastTouchListener {
                toggleEnglishMode()
            }
        }
        row.addView(langBtn)

        // 7. 确定 / 回車鍵 (高對比度藍色按鈕)
        val enterBtn = Button(context).apply {
            text = "确定"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createKeyBackground(Color.parseColor("#007AFF"), Color.parseColor("#005EC4"), cornerDp = 7)
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f).apply {
                setMargins(marginH, 0, marginH, 0)
            }
            setFastTouchListener { onEnterListener?.invoke() }
        }
        row.addView(enterBtn)

        return row
    }

    // ================= 複合按鍵視圖 (字母居中 + 右上角精緻副標 數字/標點) =================
    private fun createCompositeKeyView(mainChar: String, hintChar: String): View {
        return FrameLayout(context).apply {
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E2E4E9"), cornerDp = 7)
            isClickable = true
            isFocusable = true

            // 頂部小副標 (數字或標點) - 位於右上角
            val hintView = TextView(context).apply {
                text = hintChar
                textSize = 9.5f
                setTextColor(Color.parseColor("#92949B"))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dpToPx(2), dpToPx(3), 0)
                }
            }
            addView(hintView)

            // 主字符 - 居中微調，清晰大方
            val mainView = TextView(context).apply {
                text = mainChar
                textSize = 18f
                setTextColor(Color.parseColor("#1C1C1E"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins(0, dpToPx(3), 0, 0)
                }
            }
            addView(mainView)
        }
    }

    /**
     * 支援短按上屏主字符、長按上屏副標字符 (數字/符號) 的觸控監聽
     */
    private fun View.setKeyTouchWithLongPress(onTap: () -> Unit, onLongPress: () -> Unit) {
        var isLongPressed = false
        val longPressRunnable = Runnable {
            isLongPressed = true
            triggerHaptic()
            if (settings.keyAnimation) {
                scaleX = 1.05f
                scaleY = 1.05f
            }
            onLongPress()
        }

        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    v.isPressed = true
                    if (settings.keyAnimation) {
                        v.scaleX = 0.94f
                        v.scaleY = 0.94f
                    }
                    handler.postDelayed(longPressRunnable, 350) // 350ms 判定為長按
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handler.removeCallbacks(longPressRunnable)
                    if (settings.keyAnimation) {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    }
                    if (!isLongPressed) {
                        triggerHaptic()
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(longPressRunnable)
                    if (settings.keyAnimation) {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 退格鍵（Backspace ⌫）連續長按自動連刪監聽：
     * 按下立即刪除一次；長按 350ms 後，以 50ms 間隔持續高速刪除，鬆手即止。
     */
    private fun View.setRepeatBackspaceTouchListener() {
        var repeatRunnable: Runnable? = null
        repeatRunnable = Runnable {
            triggerHaptic()
            onBackspaceListener?.invoke()
            handler.postDelayed(repeatRunnable!!, 50) // 連刪速度：每秒 20 字
        }

        val startRepeatRunnable = Runnable {
            handler.post(repeatRunnable!!)
        }

        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (settings.keyAnimation) {
                        v.scaleX = 0.94f
                        v.scaleY = 0.94f
                    }
                    triggerHaptic()
                    onBackspaceListener?.invoke() // 立即刪除一次
                    handler.postDelayed(startRepeatRunnable, 350) // 350ms 後進入連刪
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(startRepeatRunnable)
                    repeatRunnable?.let { handler.removeCallbacks(it) }
                    if (settings.keyAnimation) {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun View.setFastTouchListener(onTrigger: () -> Unit) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (settings.keyAnimation) {
                        v.scaleX = 0.94f
                        v.scaleY = 0.94f
                    }
                    triggerHaptic()
                    onTrigger()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (settings.keyAnimation) {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createKeyButton(label: String): Button {
        return Button(context).apply {
            text = label
            textSize = if (label.length > 1 && label.codePointCount(0, label.length) > 1) 16f else 19f
            setTextColor(Color.parseColor("#1C1C1E"))
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E2E4E9"), cornerDp = 7)
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
        }
    }

    private fun createSpecialButton(label: String, weight: Float): Button {
        val marginH = (2.5f * context.resources.displayMetrics.density).toInt()
        return Button(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2C2C2E"))
            background = createKeyBackground(Color.parseColor("#C3C6CE"), Color.parseColor("#B0B3BC"), cornerDp = 7)
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(marginH, 0, marginH, 0)
            }
        }
    }

    private fun createKeyBackground(normalColor: Int, pressedColor: Int, cornerDp: Int = 7): RippleDrawable {
        val normalDrawable = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dpToPx(cornerDp).toFloat()
        }
        val pressedDrawable = GradientDrawable().apply {
            setColor(pressedColor)
            cornerRadius = dpToPx(cornerDp).toFloat()
        }

        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(), normalDrawable)
        }

        val colorStateList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(pressedColor, normalColor)
        )

        return RippleDrawable(colorStateList, stateList, pressedDrawable)
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

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
 * 鍵盤視圖：支援跟手觸控響應、按鍵微縮放動效、震動觸覺反饋、
 * 字母上標數字/符號（長按快速輸入）、長按退格鍵持續連續刪除、
 * 拼音與英文切換（中英切換）、獨立逗號鍵（，）、句號鍵（。）、
 * 數字鍵盤、符號鍵盤、表情(Emoji)鍵盤、以及一鍵切換系統其他輸入法 (Globe 🌐)
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
        setBackgroundColor(Color.parseColor("#D1D3D9"))
        setPadding(dpToPx(4), dpToPx(5), dpToPx(4), dpToPx(8))
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

    // ================= 1. 拼音/字母 鍵盤 (百度/標準雙層按鍵 + 長按副符號) =================
    private fun renderTextKeyboard() {
        // 第一行：字母 + 數字 1~0
        val row1 = listOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )
        // 第二行：字母 + 常用標點
        val row2 = listOf(
            "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "%",
            "h" to "\"", "j" to "'", "k" to "*", "l" to "?"
        )
        // 第三行：字母 + 括號標點
        val row3 = listOf(
            "z" to "(", "x" to ")", "c" to "-", "v" to "_",
            "b" to ":", "n" to ";", "m" to "/"
        )

        addView(createCompositeTextRow(row1))
        addView(createCompositeTextRow(row2, sidePaddingDp = 14))
        addView(createTextRow3(row3))
        addView(createBottomRow())
    }

    private fun createCompositeTextRow(keysWithHint: List<Pair<String, String>>, sidePaddingDp: Int = 0): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dpToPx(sidePaddingDp), dpToPx(3), dpToPx(sidePaddingDp), dpToPx(3))
        }

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val keyView = createCompositeKeyView(mainLabel, hint).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setKeyTouchWithLongPress(
                    onTap = {
                        onKeyClickListener?.invoke(mainLabel)
                    },
                    onLongPress = {
                        onKeyClickListener?.invoke(hint)
                    }
                )
            }
            row.addView(keyView)
        }
        return row
    }

    private fun createTextRow3(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        // Shift 鍵
        val shiftLabel = if (isShifted) "⇪" else "⇧"
        val shiftBtn = createSpecialButton(shiftLabel, weight = 1.3f).apply {
            if (isShifted) {
                background = createKeyBackground(Color.parseColor("#FFFFFF"), Color.parseColor("#B4B7BF"))
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
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setKeyTouchWithLongPress(
                    onTap = {
                        onKeyClickListener?.invoke(mainLabel)
                    },
                    onLongPress = {
                        onKeyClickListener?.invoke(hint)
                    }
                )
            }
            row.addView(keyView)
        }

        // 退格鍵（支援長按持續連續快速刪除）
        val delBtn = createSpecialButton("⌫", weight = 1.3f).apply {
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
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        // 切換到符號鍵盤
        val symBtn = createSpecialButton("#+=", weight = 1.3f).apply {
            setFastTouchListener {
                currentMode = KeyboardMode.SYMBOL
                renderKeyboard()
            }
        }
        row3Layout.addView(symBtn)

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row3Layout.addView(btn)
        }

        // 退格鍵（長按連刪）
        val delBtn = createSpecialButton("⌫", weight = 1.3f).apply {
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
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        // 返回 123
        val numBtn = createSpecialButton("123", weight = 1.3f).apply {
            setFastTouchListener {
                currentMode = KeyboardMode.NUMERIC
                renderKeyboard()
            }
        }
        row3Layout.addView(numBtn)

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row3Layout.addView(btn)
        }

        val delBtn = createSpecialButton("⌫", weight = 1.3f).apply {
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
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        for (k in row3) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
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
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        for (k in keys) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setFastTouchListener { onKeyClickListener?.invoke(k) }
            }
            row.addView(btn)
        }
        return row
    }

    // ================= 底部功能行 (中英切換/數字切換/逗號/空格/句號/回車) =================
    private fun createBottomRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dpToPx(3), 0, dpToPx(3))
        }

        // 1. 數字/拼音切換鍵 (123)
        val modeLabel = if (currentMode == KeyboardMode.TEXT) "123" else "拼音"
        val modeBtn = createSpecialButton(modeLabel, weight = 1.1f).apply {
            setFastTouchListener {
                currentMode = if (currentMode == KeyboardMode.TEXT) KeyboardMode.NUMERIC else KeyboardMode.TEXT
                renderKeyboard()
            }
        }
        row.addView(modeBtn)

        // 2. 中英切換鍵 (中 / 英)
        val langModeLabel = if (isEnglishMode) "英" else "中"
        val langModeBtn = createSpecialButton(langModeLabel, weight = 1.0f).apply {
            if (isEnglishMode) {
                setTextColor(Color.parseColor("#007AFF"))
            }
            setFastTouchListener {
                toggleEnglishMode()
            }
        }
        row.addView(langModeBtn)

        // 3. 逗號鍵 (，)
        val commaChar = if (isEnglishMode) "," else "，"
        val commaBtn = createKeyButton(commaChar).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setFastTouchListener { onKeyClickListener?.invoke(commaChar) }
        }
        row.addView(commaBtn)

        // 4. 空格鍵
        val spaceLabel = if (isEnglishMode) "English" else "歪輸入法"
        val spaceBtn = Button(context).apply {
            text = spaceLabel
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E5E5EA"))
            isAllCaps = false
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 3.0f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setFastTouchListener { onSpaceListener?.invoke() }
        }
        row.addView(spaceBtn)

        // 5. 句號鍵 (。)
        val periodChar = if (isEnglishMode) "." else "。"
        val periodBtn = createKeyButton(periodChar).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setFastTouchListener { onKeyClickListener?.invoke(periodChar) }
        }
        row.addView(periodBtn)

        // 6. 回車/換行鍵 (↵)
        val enterBtn = Button(context).apply {
            text = "↵"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = createKeyBackground(Color.parseColor("#007AFF"), Color.parseColor("#0056B3"))
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setFastTouchListener { onEnterListener?.invoke() }
        }
        row.addView(enterBtn)

        return row
    }

    // ================= 複合按鍵視圖 (字母 + 頂部小數字/標點) =================
    private fun createCompositeKeyView(mainChar: String, hintChar: String): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E2E4E9"))
            isClickable = true
            isFocusable = true
            setPadding(0, dpToPx(2), 0, dpToPx(2))

            // 頂部小副標 (數字或標點)
            val hintView = TextView(context).apply {
                text = hintChar
                textSize = 9.5f
                setTextColor(Color.parseColor("#8E8E93"))
                gravity = Gravity.CENTER
            }
            addView(hintView)

            // 主字母
            val mainView = TextView(context).apply {
                text = mainChar
                textSize = 18f
                setTextColor(Color.parseColor("#1C1C1E"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
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
            background = createKeyBackground(Color.WHITE, Color.parseColor("#E2E4E9"))
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
        }
    }

    private fun createSpecialButton(label: String, weight: Float): Button {
        return Button(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#2C2C2E"))
            background = createKeyBackground(Color.parseColor("#B4B7BF"), Color.parseColor("#9E9FA4"))
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
        }
    }

    private fun createKeyBackground(normalColor: Int, pressedColor: Int): RippleDrawable {
        val normalDrawable = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dpToPx(6).toFloat()
        }
        val pressedDrawable = GradientDrawable().apply {
            setColor(pressedColor)
            cornerRadius = dpToPx(6).toFloat()
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

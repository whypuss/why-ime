package com.wai.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.VibrationHelper

enum class KeyboardMode {
    TEXT,
    NUMERIC,
    SYMBOL,
    EMOJI
}

/**
 * 鍵盤視圖：對齊百度輸入法尺寸與鍵位佈局（針對 OPPO Find N2 摺疊屏展開狀態深度優化）：
 * 1. 全域零死角靈敏觸控（Zero Dead-Zone）：按鍵觸控區域無縫鋪滿整個網格，相鄰按鍵與行間隙 100% 響應，徹底根除漏觸/靈敏度不足；
 * 2. 瞬態 70ms 按鍵變灰反饋（一瞬間變灰即恢復）：按下瞬間亮起，70ms 極速回彈白底，杜絕按鍵黏灰拖遝感；
 * 3. 舒適長按 550ms + 10dp 防滑動誤觸機制：長按出符號時長由 350ms 延長至標準 550ms，手指滑動立即取消，絕不干擾正常拼音/字母連打；
 * 4. 26 鍵雙層鍵位（字母居中，右上角精緻數字/標點角標）；
 * 5. 底部主功能行：[符] [123] [,] [ 空格 ] [。] [中/英] [ 确定 ]；
 * 6. 下方新增獨立切換輸入法底欄（地球圖標長按選鍵盤 + 收起鍵盤圖標）；
 * 7. 長按退格鍵持續高速連刪；
 * 8. ACTION_DOWN 零延遲出字 + 觸覺震動反饋。
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKeyClickListener: ((String) -> Unit)? = null
    var onKeyLongPressReplaceListener: ((String, String) -> Unit)? = null
    var onBackspaceListener: (() -> Unit)? = null
    var onSpaceListener: (() -> Unit)? = null
    var onEnterListener: (() -> Unit)? = null
    var onSwitchImeListener: (() -> Unit)? = null
    var onSwitchImeLongClickListener: (() -> Unit)? = null
    var onHideKeyboardListener: (() -> Unit)? = null
    var onEnglishModeToggleListener: ((Boolean) -> Unit)? = null

    /** 是否需要顯示底部欄中的地球與鍵盤圖標（根據系統導航模式與用戶設定智能判斷，底欄本身始終保留留白） */
    var shouldShowBottomIcons: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                renderKeyboard()
            }
        }

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
        setBackgroundColor(Color.parseColor("#E7E8EC"))
        updateKeyboardPadding()
        renderKeyboard()
    }

    /**
     * 動態計算鍵盤左右與上下內邊距：
     * 若屏幕寬度 > 520dp（如 OPPO Find N2 展開內屏 ~600-700dp），
     * 兩側自適應留白縮小按鍵寬度，防誤觸。
     */
    private fun updateKeyboardPadding() {
        val displayMetrics = context.resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        val deltaSideDp = mmToDp(1.5f).toInt()
        val sidePaddingDp = if (widthDp >= 520f) 36 + deltaSideDp else 4 + deltaSideDp
        setPadding(dpToPx(sidePaddingDp), dpToPx(2), dpToPx(sidePaddingDp), dpToPx(2))
    }

    private fun triggerHaptic() {
        VibrationHelper.vibrate(context, this, settings, isCandidateSelect = false)
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
        updateKeyboardPadding()

        val keysContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }

        when (currentMode) {
            KeyboardMode.TEXT -> renderTextKeyboard(keysContainer)
            KeyboardMode.NUMERIC -> renderNumericKeyboard(keysContainer)
            KeyboardMode.SYMBOL -> renderSymbolKeyboard(keysContainer)
            KeyboardMode.EMOJI -> renderEmojiKeyboard(keysContainer)
        }

        addView(keysContainer)

        // 底部獨立留白欄（保留舒適人體工學下沉間隙）：
        // 若系統導航欄已有原生地球/鍵盤圖標，僅隱藏裡面的圖標按鈕，始終保留該行的高度與留白
        addView(createBottomNavBar())
    }

    // ================= 1. 拼音/字母 鍵盤 (雙層按鍵 + 舒適長按) =================
    private fun renderTextKeyboard(container: LinearLayout) {
        val row1 = listOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )
        val row2 = listOf(
            "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "%",
            "h" to "\"", "j" to "'", "k" to "*", "l" to "?"
        )
        val row3 = listOf(
            "z" to "(", "x" to ")", "c" to "-", "v" to "_",
            "b" to ":", "n" to ";", "m" to "/"
        )

        container.addView(createCompositeTextRow1(row1))
        container.addView(createCompositeTextRow2(row2))
        container.addView(createTextRow3(row3))
        container.addView(createBottomFunctionRow())
    }

    private fun createCompositeTextRow1(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0) // 零死角觸控：行內邊距為 0
        }

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val (touchCell, visualCard) = createCompositeKeyView(mainLabel, hint)
            touchCell.setKeyTouchWithLongPress(
                visualCard = visualCard,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                onTap = { onKeyClickListener?.invoke(mainLabel) },
                onLongPressReplace = { onKeyLongPressReplaceListener?.invoke(mainLabel, hint) }
            )
            row.addView(touchCell)
        }
        return row
    }

    private fun createCompositeTextRow2(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        // 左右兩端各 0.5f 留白，確保 A~L 鍵寬度與 Q~P 完全一致且整體居中
        // 邊界靈敏度優化：點擊左留白直接轉發給 A 鍵，點擊右留白轉發給 L 鍵，邊界盲打絕不丟鍵！
        val leftSpacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f)
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val keyA = if (isShifted) "A" else "a"
                    triggerHaptic()
                    onKeyClickListener?.invoke(keyA)
                    true
                } else false
            }
        }
        row.addView(leftSpacer)

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val (touchCell, visualCard) = createCompositeKeyView(mainLabel, hint)
            touchCell.setKeyTouchWithLongPress(
                visualCard = visualCard,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                onTap = { onKeyClickListener?.invoke(mainLabel) },
                onLongPressReplace = { onKeyLongPressReplaceListener?.invoke(mainLabel, hint) }
            )
            row.addView(touchCell)
        }

        val rightSpacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f)
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val keyL = if (isShifted) "L" else "l"
                    triggerHaptic()
                    onKeyClickListener?.invoke(keyL)
                    true
                } else false
            }
        }
        row.addView(rightSpacer)

        return row
    }

    private fun createTextRow3(keysWithHint: List<Pair<String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        // Shift 鍵
        val shiftLabel = if (isShifted) "⇪" else "⇧"
        val shiftNormalColor = if (isShifted) Color.parseColor("#FFFFFF") else Color.parseColor("#C3C6CE")
        val shiftTextColor = if (isShifted) Color.parseColor("#007AFF") else Color.parseColor("#2C2C2E")
        val (shiftCell, shiftCard) = createSimpleKeyView(
            label = shiftLabel,
            weight = 1.4f,
            normalColor = shiftNormalColor,
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = shiftTextColor,
            textSizeSp = 16f,
            isBold = true
        )
        shiftCell.setFastTouchListener(shiftCard, shiftNormalColor, Color.parseColor("#AEB2BC")) {
            isShifted = !isShifted
            renderKeyboard()
        }
        row.addView(shiftCell)

        for ((primary, hint) in keysWithHint) {
            val mainLabel = if (isShifted) primary.uppercase() else primary.lowercase()
            val (touchCell, visualCard) = createCompositeKeyView(mainLabel, hint)
            touchCell.setKeyTouchWithLongPress(
                visualCard = visualCard,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                onTap = { onKeyClickListener?.invoke(mainLabel) },
                onLongPressReplace = { onKeyLongPressReplaceListener?.invoke(mainLabel, hint) }
            )
            row.addView(touchCell)
        }

        // 退格鍵 (⌫，長按持續高速連刪)
        val (delCell, delCard) = createSimpleKeyView(
            label = "⌫",
            weight = 1.4f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 16f,
            isBold = true
        )
        delCell.setRepeatBackspaceTouchListener(delCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC"))
        row.addView(delCell)

        return row
    }

    // ================= 2. 數字鍵盤 =================
    private fun renderNumericKeyboard(container: LinearLayout) {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row2 = listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”")
        val row3 = listOf("。", "，", "、", "？", "！", ".", "…")

        container.addView(createKeyRowSimple(row1))
        container.addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        val (symCell, symCard) = createSimpleKeyView(
            label = "#+=",
            weight = 1.4f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 15f,
            isBold = true
        )
        symCell.setFastTouchListener(symCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC")) {
            currentMode = KeyboardMode.SYMBOL
            renderKeyboard()
        }
        row3Layout.addView(symCell)

        for (k in row3) {
            val (touchCell, visualCard) = createSimpleKeyView(
                label = k,
                weight = 1f,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                textColor = Color.parseColor("#1C1C1E"),
                textSizeSp = 19f
            )
            touchCell.setFastTouchListener(visualCard, Color.WHITE, Color.parseColor("#D5D8E0")) {
                onKeyClickListener?.invoke(k)
            }
            row3Layout.addView(touchCell)
        }

        val (delCell, delCard) = createSimpleKeyView(
            label = "⌫",
            weight = 1.4f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 16f,
            isBold = true
        )
        delCell.setRepeatBackspaceTouchListener(delCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC"))
        row3Layout.addView(delCell)
        container.addView(row3Layout)

        container.addView(createBottomFunctionRow())
    }

    // ================= 3. 符號鍵盤 =================
    private fun renderSymbolKeyboard(container: LinearLayout) {
        val row1 = listOf("【", "】", "{", "}", "#", "%", "^", "*", "+", "=")
        val row2 = listOf("_", "\\", "|", "~", "<", ">", "$", "€", "￡", "•")
        val row3 = listOf("‘", "’", "《", "》", "·", "—", ":")

        container.addView(createKeyRowSimple(row1))
        container.addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        val (numCell, numCard) = createSimpleKeyView(
            label = "123",
            weight = 1.4f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 15f,
            isBold = true
        )
        numCell.setFastTouchListener(numCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC")) {
            currentMode = KeyboardMode.NUMERIC
            renderKeyboard()
        }
        row3Layout.addView(numCell)

        for (k in row3) {
            val (touchCell, visualCard) = createSimpleKeyView(
                label = k,
                weight = 1f,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                textColor = Color.parseColor("#1C1C1E"),
                textSizeSp = 19f
            )
            touchCell.setFastTouchListener(visualCard, Color.WHITE, Color.parseColor("#D5D8E0")) {
                onKeyClickListener?.invoke(k)
            }
            row3Layout.addView(touchCell)
        }

        val (delCell, delCard) = createSimpleKeyView(
            label = "⌫",
            weight = 1.4f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 16f,
            isBold = true
        )
        delCell.setRepeatBackspaceTouchListener(delCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC"))
        row3Layout.addView(delCell)
        container.addView(row3Layout)

        container.addView(createBottomFunctionRow())
    }

    // ================= 4. 表情 (Emoji) 鍵盤 =================
    private fun renderEmojiKeyboard(container: LinearLayout) {
        val row1 = listOf("😀", "😂", "🤣", "😍", "🥰", "😎", "🥳", "🤔")
        val row2 = listOf("😭", "🥺", "😡", "😱", "👍", "👏", "🙏", "🎉")
        val row3 = listOf("❤️", "✨", "🔥", "💯", "🌸", "🍱", "🍙", "✈️")

        container.addView(createKeyRowSimple(row1))
        container.addView(createKeyRowSimple(row2))

        val row3Layout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        for (k in row3) {
            val (touchCell, visualCard) = createSimpleKeyView(
                label = k,
                weight = 1f,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                textColor = Color.parseColor("#1C1C1E"),
                textSizeSp = 19f
            )
            touchCell.setFastTouchListener(visualCard, Color.WHITE, Color.parseColor("#D5D8E0")) {
                onKeyClickListener?.invoke(k)
            }
            row3Layout.addView(touchCell)
        }

        val (delCell, delCard) = createSimpleKeyView(
            label = "⌫",
            weight = 1.2f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = Color.parseColor("#2C2C2E"),
            textSizeSp = 16f,
            isBold = true
        )
        delCell.setRepeatBackspaceTouchListener(delCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC"))
        row3Layout.addView(delCell)
        container.addView(row3Layout)

        container.addView(createBottomFunctionRow())
    }

    private fun createKeyRowSimple(keys: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        for (k in keys) {
            val (touchCell, visualCard) = createSimpleKeyView(
                label = k,
                weight = 1f,
                normalColor = Color.WHITE,
                pressedColor = Color.parseColor("#D5D8E0"),
                textColor = Color.parseColor("#1C1C1E"),
                textSizeSp = if (k.length > 1 && k.codePointCount(0, k.length) > 1) 16f else 19f
            )
            touchCell.setFastTouchListener(visualCard, Color.WHITE, Color.parseColor("#D5D8E0")) {
                onKeyClickListener?.invoke(k)
            }
            row.addView(touchCell)
        }
        return row
    }

    // ================= 底部功能行: [符] [123] [,] [空格] [。] [中/英] [确定] =================
    private fun createBottomFunctionRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 0, 0, 0)
        }

        // 1. 符號鍵 (符)
        val symColor = if (currentMode == KeyboardMode.SYMBOL) Color.parseColor("#007AFF") else Color.parseColor("#2C2C2E")
        val (symCell, symCard) = createSimpleKeyView(
            label = "符",
            weight = 1.05f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = symColor,
            textSizeSp = 15f,
            isBold = true
        )
        symCell.setFastTouchListener(symCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC")) {
            currentMode = if (currentMode == KeyboardMode.SYMBOL) KeyboardMode.TEXT else KeyboardMode.SYMBOL
            renderKeyboard()
        }
        row.addView(symCell)

        // 2. 數字鍵 (123)
        val numLabel = if (currentMode == KeyboardMode.NUMERIC) "中文" else "123"
        val numColor = if (currentMode == KeyboardMode.NUMERIC) Color.parseColor("#007AFF") else Color.parseColor("#2C2C2E")
        val (numCell, numCard) = createSimpleKeyView(
            label = numLabel,
            weight = 1.05f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = numColor,
            textSizeSp = 15f,
            isBold = true
        )
        numCell.setFastTouchListener(numCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC")) {
            currentMode = if (currentMode == KeyboardMode.NUMERIC) KeyboardMode.TEXT else KeyboardMode.NUMERIC
            renderKeyboard()
        }
        row.addView(numCell)

        // 3. 逗號鍵 (，/ ,) 附帶長按直出 '
        val commaChar = if (isEnglishMode) "," else "，"
        val (commaCell, commaCard) = createCompositeKeyView(commaChar, "'", weight = 1.0f)
        commaCell.setKeyTouchWithLongPress(
            visualCard = commaCard,
            normalColor = Color.WHITE,
            pressedColor = Color.parseColor("#D5D8E0"),
            onTap = { onKeyClickListener?.invoke(commaChar) },
            onLongPressReplace = { onKeyLongPressReplaceListener?.invoke(commaChar, "'") }
        )
        row.addView(commaCell)

        // 4. 空格鍵 (空格 / English)
        val spaceLabel = if (isEnglishMode) "English" else "空格"
        val (spaceCell, spaceCard) = createSimpleKeyView(
            label = spaceLabel,
            weight = 3.4f,
            normalColor = Color.WHITE,
            pressedColor = Color.parseColor("#D5D8E0"),
            textColor = Color.parseColor("#8E8E93"),
            textSizeSp = 13.5f
        )
        spaceCell.setFastTouchListener(spaceCard, Color.WHITE, Color.parseColor("#D5D8E0")) {
            onSpaceListener?.invoke()
        }
        row.addView(spaceCell)

        // 5. 句號鍵 (。/ .) 附帶長按直出 ?
        val periodChar = if (isEnglishMode) "." else "。"
        val (periodCell, periodCard) = createCompositeKeyView(periodChar, "?", weight = 1.0f)
        periodCell.setKeyTouchWithLongPress(
            visualCard = periodCard,
            normalColor = Color.WHITE,
            pressedColor = Color.parseColor("#D5D8E0"),
            onTap = { onKeyClickListener?.invoke(periodChar) },
            onLongPressReplace = { onKeyLongPressReplaceListener?.invoke(periodChar, "?") }
        )
        row.addView(periodCell)

        // 6. 中英切換鍵 (中/英)
        val langLabel = if (isEnglishMode) "英" else "中"
        val langColor = if (isEnglishMode) Color.parseColor("#007AFF") else Color.parseColor("#2C2C2E")
        val (langCell, langCard) = createSimpleKeyView(
            label = langLabel,
            weight = 1.1f,
            normalColor = Color.parseColor("#C3C6CE"),
            pressedColor = Color.parseColor("#AEB2BC"),
            textColor = langColor,
            textSizeSp = 15f,
            isBold = true
        )
        langCell.setFastTouchListener(langCard, Color.parseColor("#C3C6CE"), Color.parseColor("#AEB2BC")) {
            toggleEnglishMode()
        }
        row.addView(langCell)

        // 7. 确定鍵 (高對比度藍色按鈕)
        val (enterCell, enterCard) = createSimpleKeyView(
            label = "确定",
            weight = 1.4f,
            normalColor = Color.parseColor("#007AFF"),
            pressedColor = Color.parseColor("#005EC4"),
            textColor = Color.WHITE,
            textSizeSp = 15f,
            isBold = true
        )
        enterCell.setFastTouchListener(enterCard, Color.parseColor("#007AFF"), Color.parseColor("#005EC4")) {
            onEnterListener?.invoke()
        }
        row.addView(enterCell)

        return row
    }

    // ================= 底部新增一欄：切換鍵盤地球圖標與收起鍵盤 =================
    private fun createBottomNavBar(): View {
        val navBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                setMargins(0, dpToPx(2), 0, 0)
            }
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 若系統導航欄已有原生地球和鍵盤圖標，僅隱藏裡面的圖標，但保留該欄目的高度與位置空間
        if (!shouldShowBottomIcons) {
            return navBar
        }

        // 1. 地球圖標（切換輸入法）：短按切換，長按彈出系統選擇輸入法菜單
        val globeBtn = FrameLayout(context).apply {
            layoutParams = LayoutParams(dpToPx(56), LayoutParams.MATCH_PARENT)
            val iconView = TextView(context).apply {
                text = "🌐"
                textSize = 17f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(iconView)

            var isLongPressed = false
            val longPressRunnable = Runnable {
                isLongPressed = true
                triggerHaptic()
                onSwitchImeLongClickListener?.invoke()
            }

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isLongPressed = false
                        v.isPressed = true
                        handler.postDelayed(longPressRunnable, 450)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        handler.removeCallbacks(longPressRunnable)
                        if (!isLongPressed) {
                            triggerHaptic()
                            onSwitchImeListener?.invoke()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(longPressRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
        navBar.addView(globeBtn)

        // 中間留白 Spacer
        val spacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        navBar.addView(spacer)

        // 2. 右側收起鍵盤圖標（點擊收起鍵盤）
        val hideBtn = FrameLayout(context).apply {
            layoutParams = LayoutParams(dpToPx(56), LayoutParams.MATCH_PARENT)
            val iconView = TextView(context).apply {
                text = "⌨️"
                textSize = 17f
                setTextColor(Color.parseColor("#4A4C52"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(iconView)

            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    triggerHaptic()
                    onHideKeyboardListener?.invoke()
                    true
                } else false
            }
        }
        navBar.addView(hideBtn)

        return navBar
    }

    // ================= 核心佈局架構：零死角觸控單元 (TouchCell + VisualCard) =================
    /**
     * 建立具有 100% 無死角觸控響應的按鍵單元：
     * @return touchCell: 外層觸控容器，邊距為 0，與相鄰按鍵和上下行無縫咬合，觸控面積 100% 覆蓋；
     *         visualCard: 內層視覺卡片，帶有真實邊距與圓角，提供完美舒適的視覺間隙。
     */
    private fun createKeyCell(
        weight: Float,
        normalColor: Int,
        cornerDp: Int = 6,
        buildVisual: (FrameLayout) -> Unit
    ): Pair<FrameLayout, FrameLayout> {
        val touchCell = FrameLayout(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(0, 0, 0, 0) // 零死角：外層觸控區域鋪滿整個單元格
            }
            isClickable = true
            isFocusable = true
        }

        val marginH = keyMarginHorizontal
        val marginV = (rowPaddingVertical / 2).coerceAtLeast(dpToPx(1))

        val visualCard = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(marginH, marginV, marginH, marginV)
            }
            background = createKeyShapeDrawable(normalColor, cornerDp)
        }

        buildVisual(visualCard)
        touchCell.addView(visualCard)

        return Pair(touchCell, visualCard)
    }

    /**
     * 複合按鍵視圖 (字母居中 + 右上角精緻副標 數字/標點)
     */
    private fun createCompositeKeyView(
        mainChar: String,
        hintChar: String,
        weight: Float = 1f
    ): Pair<FrameLayout, FrameLayout> {
        return createKeyCell(weight, Color.WHITE, cornerDp = 6) { card ->
            // 頂部副標 (數字或標點) - 位於右上角
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
            card.addView(hintView)

            // 主字符 - 居中清晰
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
            card.addView(mainView)
        }
    }

    /**
     * 單一文字/符號按鍵視圖 (空格、退格、Shift、符號、表情等)
     */
    private fun createSimpleKeyView(
        label: String,
        weight: Float = 1f,
        normalColor: Int = Color.WHITE,
        pressedColor: Int = Color.parseColor("#D5D8E0"),
        textColor: Int = Color.parseColor("#1C1C1E"),
        textSizeSp: Float = 18f,
        isBold: Boolean = false,
        cornerDp: Int = 6
    ): Pair<FrameLayout, FrameLayout> {
        return createKeyCell(weight, normalColor, cornerDp) { card ->
            val labelView = TextView(context).apply {
                text = label
                textSize = textSizeSp
                setTextColor(textColor)
                if (isBold) typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            card.addView(labelView)
        }
    }

    /**
     * 支援「瞬態 70ms 變灰」+「550ms 舒適長按防滑動」觸控監聽：
     * 1. 瞬態視覺反饋：按下瞬間立即變灰 + 微縮放，70ms 後自動恢復白底（一瞬間變灰即恢復，絕不停留拖遝）；
     * 2. 0ms 零延遲出字：ACTION_DOWN 立即震動並出字上屏；
     * 3. 舒適長按 550ms：延長長按閾值至 550ms，手指滑動超過 10dp 自動取消，徹底消除快打時誤出數字符號的干擾！
     */
    private fun View.setKeyTouchWithLongPress(
        visualCard: View,
        normalColor: Int,
        pressedColor: Int,
        onTap: () -> Unit,
        onLongPressReplace: () -> Unit
    ) {
        var isLongPressed = false
        var downX = 0f
        var downY = 0f
        val touchSlop = dpToPx(10)

        val flashResetRunnable = Runnable {
            visualCard.background = createKeyShapeDrawable(normalColor, 6)
            if (settings.keyAnimation) {
                visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
            }
        }

        val longPressRunnable = Runnable {
            isLongPressed = true
            triggerHaptic()
            if (settings.keyAnimation) {
                visualCard.animate().scaleX(1.06f).scaleY(1.06f).setDuration(60).start()
            }
            onLongPressReplace()
            handler.postDelayed({
                visualCard.background = createKeyShapeDrawable(normalColor, 6)
                if (settings.keyAnimation) {
                    visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
                }
            }, 100)
        }

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    downX = event.x
                    downY = event.y

                    // 1. 瞬態按鍵變灰反饋
                    visualCard.background = createKeyShapeDrawable(pressedColor, 6)
                    if (settings.keyAnimation) {
                        visualCard.scaleX = 0.94f
                        visualCard.scaleY = 0.94f
                    }

                    // 2. 70ms 後自動恢復白底（一瞬間變灰即恢復）
                    handler.removeCallbacks(flashResetRunnable)
                    handler.postDelayed(flashResetRunnable, 70)

                    // 3. 0ms 即刻震動 + 出字
                    triggerHaptic()
                    onTap()

                    // 4. 長按 550ms 排程（由 350ms 延長至舒適標準 550ms）
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 若手指滑動超過 10dp，判定為快速打字滑行，立即取消長按定時器，絕不誤出符號！
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isLongPressed) {
                        visualCard.background = createKeyShapeDrawable(normalColor, 6)
                        if (settings.keyAnimation) {
                            visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 單次點擊快捷鍵觸控監聽（70ms 極速變灰瞬態反饋）
     */
    private fun View.setFastTouchListener(
        visualCard: View,
        normalColor: Int,
        pressedColor: Int,
        onTrigger: () -> Unit
    ) {
        val flashResetRunnable = Runnable {
            visualCard.background = createKeyShapeDrawable(normalColor, 6)
            if (settings.keyAnimation) {
                visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
            }
        }

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    visualCard.background = createKeyShapeDrawable(pressedColor, 6)
                    if (settings.keyAnimation) {
                        visualCard.scaleX = 0.94f
                        visualCard.scaleY = 0.94f
                    }
                    handler.removeCallbacks(flashResetRunnable)
                    handler.postDelayed(flashResetRunnable, 70)

                    triggerHaptic()
                    onTrigger()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    visualCard.background = createKeyShapeDrawable(normalColor, 6)
                    if (settings.keyAnimation) {
                        visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 退格鍵（Backspace ⌫）連續長按自動連刪監聽（帶 70ms 瞬態變灰反饋）
     */
    private fun View.setRepeatBackspaceTouchListener(
        visualCard: View,
        normalColor: Int,
        pressedColor: Int
    ) {
        var repeatRunnable: Runnable? = null
        repeatRunnable = Runnable {
            triggerHaptic()
            onBackspaceListener?.invoke()
            handler.postDelayed(repeatRunnable!!, 50)
        }

        val startRepeatRunnable = Runnable {
            handler.post(repeatRunnable!!)
        }

        val flashResetRunnable = Runnable {
            visualCard.background = createKeyShapeDrawable(normalColor, 6)
            if (settings.keyAnimation) {
                visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
            }
        }

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    visualCard.background = createKeyShapeDrawable(pressedColor, 6)
                    if (settings.keyAnimation) {
                        visualCard.scaleX = 0.94f
                        visualCard.scaleY = 0.94f
                    }
                    handler.removeCallbacks(flashResetRunnable)
                    handler.postDelayed(flashResetRunnable, 70)

                    triggerHaptic()
                    onBackspaceListener?.invoke()
                    handler.postDelayed(startRepeatRunnable, 500)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startRepeatRunnable)
                    repeatRunnable?.let { handler.removeCallbacks(it) }
                    visualCard.background = createKeyShapeDrawable(normalColor, 6)
                    if (settings.keyAnimation) {
                        visualCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(40).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 按鍵間距（水平外邊距）：
     * 兩鍵之間淨間距保持精準的 +0.3mm (~1.9dp) 防誤觸間隙。
     */
    private val keyMarginHorizontal: Int
        get() = ((2.2f + mmToDp(0.15f)) * context.resources.displayMetrics.density).toInt()

    /**
     * 鍵盤行距（垂直上下間距）：
     * 行距增加 1 毫米（1mm ≈ 6.3dp），相鄰行按鍵之間的垂直間隙為 10.3dp，保持開闊大氣。
     */
    private val rowPaddingVertical: Int
        get() = ((2.0f + mmToDp(0.5f)) * context.resources.displayMetrics.density).toInt()

    private fun createKeyShapeDrawable(color: Int, cornerDp: Int = 6): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(cornerDp).toFloat()
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun mmToDp(mm: Float): Float {
        return mm * (160f / 25.4f)
    }
}

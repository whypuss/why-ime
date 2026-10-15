package com.wai.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * 簡潔美觀的 26 鍵全鍵盤視圖
 */
class KeyboardView(context: Context) : LinearLayout(context) {

    var onKeyClickListener: ((String) -> Unit)? = null
    var onBackspaceListener: (() -> Unit)? = null
    var onSpaceListener: (() -> Unit)? = null
    var onEnterListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#D1D3D9"))
        setPadding(6, 12, 6, 16)

        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

        addView(createKeyRow(row1))
        addView(createKeyRow(row2, sidePaddingDp = 16))
        addView(createSpecialRow3(row3))
        addView(createBottomRow())
    }

    private fun createKeyRow(keys: List<String>, sidePaddingDp: Int = 0): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dpToPx(sidePaddingDp), 4, dpToPx(sidePaddingDp), 4)
        }

        for (k in keys) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setOnClickListener { onKeyClickListener?.invoke(k) }
            }
            row.addView(btn)
        }
        return row
    }

    private fun createSpecialRow3(keys: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 4, 0, 4)
        }

        // 左側 Shift (暫作裝飾/大寫切換預留)
        val shiftBtn = createSpecialButton("⇧", weight = 1.3f).apply {
            setOnClickListener { /* 預留大小寫切換 */ }
        }
        row.addView(shiftBtn)

        // 中間 7 鍵
        for (k in keys) {
            val btn = createKeyButton(k).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setOnClickListener { onKeyClickListener?.invoke(k) }
            }
            row.addView(btn)
        }

        // 右側退格
        val delBtn = createSpecialButton("⌫", weight = 1.3f).apply {
            setOnClickListener { onBackspaceListener?.invoke() }
        }
        row.addView(delBtn)

        return row
    }

    private fun createBottomRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 4, 0, 4)
        }

        val numBtn = createSpecialButton("123", weight = 1.2f)
        row.addView(numBtn)

        val commaBtn = createKeyButton(",").apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setOnClickListener { onKeyClickListener?.invoke(",") }
        }
        row.addView(commaBtn)

        // 空格鍵
        val spaceBtn = Button(context).apply {
            text = "歪"
            textSize = 15f
            setTextColor(Color.parseColor("#8E8E93"))
            background = createKeyBackground(Color.WHITE)
            isAllCaps = false
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 4f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setOnClickListener { onSpaceListener?.invoke() }
        }
        row.addView(spaceBtn)

        val periodBtn = createKeyButton(".").apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setOnClickListener { onKeyClickListener?.invoke(".") }
        }
        row.addView(periodBtn)

        // 回車鍵
        val enterBtn = Button(context).apply {
            text = "↵"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = createKeyBackground(Color.parseColor("#007AFF"))
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.5f).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            setOnClickListener { onEnterListener?.invoke() }
        }
        row.addView(enterBtn)

        return row
    }

    private fun createKeyButton(label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 18f
            setTextColor(Color.parseColor("#1C1C1E"))
            background = createKeyBackground(Color.WHITE)
            isAllCaps = false
            stateListAnimator = null
        }
    }

    private fun createSpecialButton(label: String, weight: Float): Button {
        return Button(context).apply {
            text = label
            textSize = 16f
            setTextColor(Color.parseColor("#3A3A3C"))
            background = createKeyBackground(Color.parseColor("#B4B7BF"))
            isAllCaps = false
            stateListAnimator = null
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
        }
    }

    private fun createKeyBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(5).toFloat()
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

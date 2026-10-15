package com.wai.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.wai.ime.core.Candidate

/**
 * 候選詞條視圖（展示拼音、候選字、以及即時對照的日語假名和釋義）
 */
class CandidateBarView(context: Context) : LinearLayout(context) {

    private val preeditView: TextView
    private val scrollView: HorizontalScrollView
    private val candidateContainer: LinearLayout

    var onCandidateClickListener: ((Int) -> Unit)? = null
    var candidateFontSize: Float = 18f
        set(value) {
            field = value
            refreshCurrentViews()
        }

    private var lastPreedit: String = ""
    private var lastCandidates: List<Candidate> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F7"))
        setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))

        // 頂部拼音顯示
        preeditView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#007AFF"))
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
        }
        addView(preeditView)

        // 水平滾動候選區
        scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        candidateContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scrollView.addView(
            candidateContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    /**
     * 更新候選條狀態
     */
    fun updateState(preedit: String, candidates: List<Candidate>) {
        lastPreedit = preedit
        lastCandidates = candidates

        if (preedit.isNotBlank()) {
            preeditView.text = preedit
            preeditView.visibility = View.VISIBLE
        } else {
            preeditView.visibility = View.GONE
        }

        candidateContainer.removeAllViews()

        if (candidates.isEmpty()) {
            return
        }

        candidates.forEachIndexed { index, candidate ->
            val itemView = createCandidateItemView(index, candidate)
            candidateContainer.addView(itemView)
        }
        scrollView.scrollTo(0, 0)
    }

    private fun refreshCurrentViews() {
        updateState(lastPreedit, lastCandidates)
    }

    private fun createCandidateItemView(index: Int, candidate: Candidate): View {
        val itemLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onCandidateClickListener?.invoke(index)
            }
        }

        // 1. 中文候選字 (第一行)
        val chineseText = TextView(context).apply {
            text = "${index + 1}. ${candidate.text}"
            textSize = candidateFontSize
            setTextColor(Color.parseColor("#1C1C1E"))
            typeface = Typeface.DEFAULT_BOLD
        }
        itemLayout.addView(chineseText)

        // 2. 日語釋義 + 假名 (第二行，青簡核心特點)
        val japaneseLabel = candidate.formatJapaneseLabel()
        if (japaneseLabel != null) {
            val japaneseText = TextView(context).apply {
                text = japaneseLabel
                textSize = (candidateFontSize * 0.65f).coerceAtLeast(10f)
                setTextColor(Color.parseColor("#8E8E93"))
                maxLines = 1
            }
            itemLayout.addView(japaneseText)
        }

        return itemLayout
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

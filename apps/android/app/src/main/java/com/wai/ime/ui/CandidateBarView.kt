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
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.VibrationHelper

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

    private val settings = SettingsRepository.getInstance(context)

    private var lastPreedit: String = ""
    private var lastCandidates: List<Candidate> = emptyList()

    // 預分配可重複使用的候選 View 緩存池，消除輸入時頻繁 new View 的 GC 抖動
    private val viewPool = ArrayList<CandidateItemViewHolder>()

    private class CandidateItemViewHolder(
        val root: LinearLayout,
        val chineseText: TextView,
        val foreignText: TextView
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#EEF0F3"))
        setPadding(dpToPx(6), dpToPx(1), dpToPx(6), dpToPx(1))

        // 頂部拼音顯示（固定高度 18dp，杜絕任何高度抖動）
        preeditView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#007AFF"))
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            visibility = View.INVISIBLE
            setPadding(dpToPx(8), dpToPx(1), dpToPx(8), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(18))
        }
        addView(preeditView)

        // 水平滾動候選區 (以 weight=1f 填滿固定容器的剩餘高度)
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
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /**
     * 主線程即時刷新拼音顯示（0ms 零延遲反饋，不等待後台候選詞查詢）
     */
    fun updatePreeditOnly(preedit: String) {
        lastPreedit = preedit
        if (preedit.isNotBlank()) {
            if (preeditView.visibility != View.VISIBLE) {
                preeditView.visibility = View.VISIBLE
            }
            if (preeditView.text != preedit) {
                preeditView.text = preedit
            }
        } else {
            preeditView.text = ""
            preeditView.visibility = View.INVISIBLE
        }
    }

    /**
     * 更新候選條狀態（採用 ViewHolder 複用，零 GC 延遲極速刷新）
     */
    fun updateState(preedit: String, candidates: List<Candidate>) {
        lastPreedit = preedit
        lastCandidates = candidates

        if (preedit.isNotBlank()) {
            preeditView.text = preedit
            preeditView.visibility = View.VISIBLE
        } else {
            preeditView.text = ""
            preeditView.visibility = View.INVISIBLE
        }

        val count = candidates.size

        // 確保 View 池足夠
        while (viewPool.size < count) {
            viewPool.add(createViewHolder())
        }

        // 把當前所需數量的 View 加入 container
        if (candidateContainer.childCount != count) {
            candidateContainer.removeAllViews()
            for (i in 0 until count) {
                candidateContainer.addView(viewPool[i].root)
            }
        }

        for (i in 0 until count) {
            val candidate = candidates[i]
            val vh = viewPool[i]

            vh.chineseText.text = "${i + 1}. ${candidate.text}"
            vh.chineseText.textSize = candidateFontSize

            val foreignLabel = candidate.formatJapaneseLabel()
            if (foreignLabel != null) {
                vh.foreignText.text = foreignLabel
                vh.foreignText.textSize = (candidateFontSize * 0.65f).coerceAtLeast(10f)
                vh.foreignText.visibility = View.VISIBLE
            } else {
                vh.foreignText.visibility = View.GONE
            }

            vh.root.setOnClickListener {
                VibrationHelper.vibrate(context, vh.root, settings, isCandidateSelect = true)
                onCandidateClickListener?.invoke(i)
            }
        }

        if (preedit.isEmpty() || candidates.isEmpty()) {
            scrollView.scrollTo(0, 0)
        }
    }

    private fun refreshCurrentViews() {
        updateState(lastPreedit, lastCandidates)
    }

    private fun createViewHolder(): CandidateItemViewHolder {
        val itemLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(12), dpToPx(1), dpToPx(12), dpToPx(1))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val chineseText = TextView(context).apply {
            textSize = candidateFontSize
            setTextColor(Color.parseColor("#1C1C1E"))
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        itemLayout.addView(chineseText)

        val foreignText = TextView(context).apply {
            textSize = (candidateFontSize * 0.62f).coerceAtLeast(10f)
            setTextColor(Color.parseColor("#636366"))
            maxLines = 1
            includeFontPadding = false
            setPadding(0, dpToPx(1), 0, 0)
        }
        itemLayout.addView(foreignText)

        return CandidateItemViewHolder(itemLayout, chineseText, foreignText)
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

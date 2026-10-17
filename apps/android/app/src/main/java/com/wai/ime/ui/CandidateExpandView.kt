package com.wai.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wai.ime.core.Candidate
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.VibrationHelper

/**
 * 展開式全量候選字列表/網格視圖（點擊候選欄右側箭頭後展開）
 * 覆蓋於鍵盤區域，4 列網格佈局展示所有候選字詞及其外語對照翻譯
 */
class CandidateExpandView(context: Context) : LinearLayout(context) {

    private val recyclerView: RecyclerView
    private val adapter: CandidateGridAdapter
    private val settings = SettingsRepository.getInstance(context)

    var onCandidateClickListener: ((Int) -> Unit)? = null
    var candidateFontSize: Float = 18f
        set(value) {
            field = value
            adapter.notifyDataSetChanged()
        }

    private var candidates: List<Candidate> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F6F8"))

        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(context, 4)
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(8))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        adapter = CandidateGridAdapter()
        recyclerView.adapter = adapter
        addView(recyclerView)
    }

    fun setCandidates(newCandidates: List<Candidate>) {
        candidates = newCandidates
        adapter.notifyDataSetChanged()
        recyclerView.scrollToPosition(0)
    }

    private inner class CandidateGridAdapter : RecyclerView.Adapter<CandidateViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
            val itemView = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dpToPx(56)
                ).apply {
                    setMargins(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))
                }
                setBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }

            val chineseText = TextView(context).apply {
                textSize = candidateFontSize
                setTextColor(Color.parseColor("#1C1C1E"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }
            itemView.addView(chineseText)

            val foreignText = TextView(context).apply {
                textSize = (candidateFontSize * 0.58f).coerceAtLeast(10f)
                setTextColor(Color.parseColor("#636366"))
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setPadding(0, dpToPx(2), 0, 0)
            }
            itemView.addView(foreignText)

            return CandidateViewHolder(itemView, chineseText, foreignText)
        }

        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            val cand = candidates[position]
            holder.chineseText.text = cand.text
            holder.chineseText.textSize = candidateFontSize

            val foreignLabel = cand.formatJapaneseLabel()
            if (foreignLabel != null) {
                holder.foreignText.text = foreignLabel
                holder.foreignText.textSize = (candidateFontSize * 0.58f).coerceAtLeast(10f)
                holder.foreignText.visibility = View.VISIBLE
            } else {
                holder.foreignText.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                VibrationHelper.vibrate(context, holder.itemView, settings, isCandidateSelect = true)
                onCandidateClickListener?.invoke(position)
            }
        }

        override fun getItemCount(): Int = candidates.size
    }

    private class CandidateViewHolder(
        itemView: View,
        val chineseText: TextView,
        val foreignText: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

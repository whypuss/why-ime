package com.wai.ime.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 候選詞模型（包含中文與外語釋義/假名/讀音）
 */
data class Candidate(
    val text: String,
    val hasTranslation: Boolean,
    val translationText: String?,
    val reading: String?,
    val pos: String?
) {
    /**
     * 格式化外語對照標籤文字：例 "開発する [かいはつする]" 或 "develop (v.)"
     */
    fun formatJapaneseLabel(): String? {
        if (!hasTranslation || translationText == null) return null
        val posSuffix = if (!pos.isNullOrBlank()) " ($pos)" else ""
        return if (!reading.isNullOrBlank() && reading != translationText) {
            "$translationText [$reading]$posSuffix"
        } else {
            "$translationText$posSuffix"
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): Candidate {
            return Candidate(
                text = obj.optString("text", ""),
                hasTranslation = obj.optBoolean("has_translation", false),
                translationText = obj.optString("translation_text").takeIf { it.isNotEmpty() },
                reading = obj.optString("reading").takeIf { it.isNotEmpty() },
                pos = obj.optString("pos").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * 引擎返回的狀態
 */
data class EngineState(
    val preedit: String,
    val candidates: List<Candidate>,
    val isComposing: Boolean
) {
    companion object {
        fun fromJson(jsonStr: String): EngineState {
            if (jsonStr.isBlank()) {
                return EngineState("", emptyList(), false)
            }
            return try {
                val obj = JSONObject(jsonStr)
                val preedit = obj.optString("preedit", "")
                val isComposing = obj.optBoolean("is_composing", false)
                val candidatesJson = obj.optJSONArray("candidates") ?: JSONArray()
                val count = candidatesJson.length()
                val candidates = ArrayList<Candidate>(count)
                for (i in 0 until count) {
                    candidates.add(Candidate.fromJson(candidatesJson.getJSONObject(i)))
                }
                EngineState(preedit, candidates, isComposing)
            } catch (e: Exception) {
                EngineState("", emptyList(), false)
            }
        }
    }
}

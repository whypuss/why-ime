package com.wai.ime.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 候選詞模型（包含中文與日語釋義/假名）
 */
data class Candidate(
    val text: String,
    val syllables: List<String>,
    val hasTranslation: Boolean,
    val targetLang: String?,
    val translationText: String?,
    val reading: String?,
    val pos: String?,
    val fresh: Boolean
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
            val syllablesJson = obj.optJSONArray("syllables") ?: JSONArray()
            val syllables = mutableListOf<String>()
            for (i in 0 until syllablesJson.length()) {
                syllables.add(syllablesJson.getString(i))
            }

            return Candidate(
                text = obj.optString("text", ""),
                syllables = syllables,
                hasTranslation = obj.optBoolean("has_translation", false),
                targetLang = obj.optString("target_lang").takeIf { it.isNotEmpty() },
                translationText = obj.optString("translation_text").takeIf { it.isNotEmpty() },
                reading = obj.optString("reading").takeIf { it.isNotEmpty() },
                pos = obj.optString("pos").takeIf { it.isNotEmpty() },
                fresh = obj.optBoolean("fresh", false)
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
                val candidates = mutableListOf<Candidate>()
                for (i in 0 until candidatesJson.length()) {
                    candidates.add(Candidate.fromJson(candidatesJson.getJSONObject(i)))
                }
                EngineState(preedit, candidates, isComposing)
            } catch (e: Exception) {
                EngineState("", emptyList(), false)
            }
        }
    }
}

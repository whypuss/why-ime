package com.wai.ime.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 歪輸入法 JNI 底層引擎單例管理
 */
object WaiNativeEngine {
    private const val TAG = "WaiNativeEngine"
    private var nativeHandle: Long = 0L
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("wai_android")
            Log.i(TAG, "libwai_android.so 加載成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libwai_android.so 加載失敗: ${e.message}")
        }
    }

    @Synchronized
    fun initialize(context: Context, langCode: String = "ja"): Boolean {
        if (isInitialized && nativeHandle != 0L) {
            return true
        }

        try {
            val dictFile = copyAssetToFile(context, "dict.tsv", "dict.tsv")
            val glossaryFile = copyAssetToFile(context, "glossary-ja.tsv", "glossary-ja.tsv")

            Log.i(TAG, "加載詞典檔案: ${dictFile.absolutePath}, 釋義: ${glossaryFile.absolutePath}")

            nativeHandle = nativeCreate(
                dictPath = dictFile.absolutePath,
                glossaryPath = glossaryFile.absolutePath,
                langCode = langCode
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "原生引擎初始化結果: isInitialized=$isInitialized, ptr=$nativeHandle")
            return isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "初始化原生引擎拋出異常", e)
            return false
        }
    }

    fun feedChar(ch: Char): EngineState {
        if (!isInitialized || nativeHandle == 0L) return EngineState("", emptyList(), false)
        val json = nativeFeedChar(nativeHandle, ch.code)
        return EngineState.fromJson(json)
    }

    fun backspace(): EngineState {
        if (!isInitialized || nativeHandle == 0L) return EngineState("", emptyList(), false)
        val json = nativeBackspace(nativeHandle)
        return EngineState.fromJson(json)
    }

    fun clear() {
        if (!isInitialized || nativeHandle == 0L) return
        nativeClear(nativeHandle)
    }

    fun commitCandidate(index: Int): String {
        if (!isInitialized || nativeHandle == 0L) return ""
        return nativeCommitCandidate(nativeHandle, index)
    }

    fun getRawPreedit(): String {
        if (!isInitialized || nativeHandle == 0L) return ""
        return nativeGetRawPreedit(nativeHandle)
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            isInitialized = false
        }
    }

    private fun copyAssetToFile(context: Context, assetName: String, fileName: String): File {
        val file = File(context.filesDir, fileName)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file
    }

    // ================= Native 外部聲明 =================
    private external fun nativeCreate(dictPath: String, glossaryPath: String, langCode: String): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeFeedChar(ptr: Long, ch: Int): String
    private external fun nativeBackspace(ptr: Long): String
    private external fun nativeClear(ptr: Long)
    private external fun nativeCommitCandidate(ptr: Long, index: Int): String
    private external fun nativeGetRawPreedit(ptr: Long): String
}

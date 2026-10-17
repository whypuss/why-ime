package com.wai.ime.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 引擎操作事件（嚴格保序）
 */
sealed interface EngineAction {
    data class Push(val ch: Char, val sessionId: Long, val requestId: Long) : EngineAction
    data class Backspace(val sessionId: Long, val requestId: Long) : EngineAction
    data class Clear(val sessionId: Long) : EngineAction
    data class QueryEnglish(val prefix: String, val sessionId: Long, val requestId: Long) : EngineAction
    data class CommitCandidate(
        val index: Int,
        val expectedText: String,
        val isTranslation: Boolean,
        val sessionId: Long,
        val requestId: Long
    ) : EngineAction
}

/**
 * 候選詞查詢結果（帶版本號與 session 隔離）
 */
data class CandidateResult(
    val sessionId: Long,
    val requestId: Long,
    val preedit: String,
    val candidates: List<Candidate>
)

/**
 * 候選詞上屏提交結果
 */
data class CommitResult(
    val sessionId: Long,
    val committedText: String,
    val remainingPreedit: String,
    val success: Boolean
)

/**
 * 回調監聽器（均在 Main Looper 主線程回調）
 */
interface EngineDispatcherListener {
    fun onCandidatesUpdated(result: CandidateResult)
    fun onCommitResult(result: CommitResult)
}

/**
 * P0 核心排程器：
 * 1. 專用單執行緒 Worker (HandlerThread) 保證底層狀態修改絕對順序
 * 2. 嚴格保序：Push、Backspace、Clear、Commit 依序執行，絕不丟失任何狀態轉移
 * 3. 查詢合併 (Query Coalescing)：未開始執行的舊查詢可被合併，並設有有界超時上限（MAX_QUERY_DELAY_MS），防止持續輸入時候選詞飢餓
 * 4. 三重版本隔離：sessionId、requestId 與候選詞 text 嚴格匹配，避免過期或索引偏移覆蓋
 */
class EngineDispatcher(private val listener: EngineDispatcherListener) {

    companion object {
        private const val TAG = "EngineDispatcher"
        // 快速連續輸入時的查詢合併有界延遲上限（毫秒）
        // 若連續輸入超過此時間，即使佇列中仍有按鍵，也強制觸發一次最新狀態查詢，確保候選欄持續刷新
        const val MAX_QUERY_DELAY_MS = 50L
    }

    private val workerThread = HandlerThread("WaiEngineWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionCounter = AtomicLong(1)
    private val requestCounter = AtomicLong(1)

    @Volatile
    var currentSessionId: Long = 1
        private set

    @Volatile
    var latestDeliveredRequestId: Long = 0

    // 統計數據：總按鍵數、被合併的查詢數
    val totalKeyCount = AtomicLong(0)
    val coalescedQueryCount = AtomicLong(0)
    val executedQueryCount = AtomicLong(0)

    private val actionQueue = ArrayDeque<EngineAction>()
    private var isDrainScheduled = false
    private var lastQueryUptime: Long = 0L

    /**
     * 開始新的輸入 session（例如鍵盤重新彈出、清空或上屏完成後）
     */
    fun newSession(): Long {
        val newId = sessionCounter.incrementAndGet()
        currentSessionId = newId
        latestDeliveredRequestId = 0
        enqueue(EngineAction.Clear(newId))
        return newId
    }

    fun enqueuePush(ch: Char): Long {
        val sId = currentSessionId
        val rId = requestCounter.incrementAndGet()
        totalKeyCount.incrementAndGet()
        enqueue(EngineAction.Push(ch, sId, rId))
        return rId
    }

    fun enqueueBackspace(): Long {
        val sId = currentSessionId
        val rId = requestCounter.incrementAndGet()
        totalKeyCount.incrementAndGet()
        enqueue(EngineAction.Backspace(sId, rId))
        return rId
    }

    fun enqueueClear(): Long {
        val sId = sessionCounter.incrementAndGet()
        currentSessionId = sId
        latestDeliveredRequestId = 0
        enqueue(EngineAction.Clear(sId))
        return sId
    }

    fun enqueueCommit(index: Int, expectedText: String, isTranslation: Boolean): Long {
        val sId = currentSessionId
        val rId = requestCounter.incrementAndGet()
        enqueue(EngineAction.CommitCandidate(index, expectedText, isTranslation, sId, rId))
        return rId
    }

    fun enqueueQueryEnglish(prefix: String): Long {
        val sId = currentSessionId
        val rId = requestCounter.incrementAndGet()
        enqueue(EngineAction.QueryEnglish(prefix, sId, rId))
        return rId
    }

    private fun enqueue(action: EngineAction) {
        synchronized(actionQueue) {
            actionQueue.addLast(action)
            if (!isDrainScheduled) {
                isDrainScheduled = true
                workerHandler.post { drainQueue() }
            }
        }
    }

    /**
     * 在單執行緒 Worker 中依序消耗佇列
     */
    private fun drainQueue() {
        var hasTextMutated = false
        var lastSessionId = 0L
        var lastRequestId = 0L

        while (true) {
            val (action, nextIsTextMutating) = synchronized(actionQueue) {
                if (actionQueue.isEmpty()) {
                    isDrainScheduled = false
                    null to false
                } else {
                    val act = actionQueue.removeFirst()
                    val nextMut = actionQueue.any { it is EngineAction.Push || it is EngineAction.Backspace }
                    act to nextMut
                }
            }

            if (action == null) break

            when (action) {
                is EngineAction.Push -> {
                    if (action.sessionId == currentSessionId) {
                        WaiNativeEngine.pushChar(action.ch)
                        hasTextMutated = true
                        lastSessionId = action.sessionId
                        lastRequestId = action.requestId
                    }
                }
                is EngineAction.Backspace -> {
                    if (action.sessionId == currentSessionId) {
                        WaiNativeEngine.backspaceFast()
                        hasTextMutated = true
                        lastSessionId = action.sessionId
                        lastRequestId = action.requestId
                    }
                }
                is EngineAction.Clear -> {
                    WaiNativeEngine.clearFast()
                    hasTextMutated = false
                    lastQueryUptime = 0L
                    mainHandler.post {
                        listener.onCandidatesUpdated(CandidateResult(action.sessionId, 0L, "", emptyList()))
                    }
                }
                is EngineAction.QueryEnglish -> {
                    if (action.sessionId == currentSessionId) {
                        hasTextMutated = false
                        val state = WaiNativeEngine.queryEnglish(action.prefix, 15)
                        executedQueryCount.incrementAndGet()
                        val res = CandidateResult(action.sessionId, action.requestId, state.preedit, state.candidates)
                        mainHandler.post {
                            listener.onCandidatesUpdated(res)
                        }
                    }
                }
                is EngineAction.CommitCandidate -> {
                    if (action.sessionId == currentSessionId) {
                        // 如果在提交之前有積壓的拼音輸入，先執行一次最新查詢以同步 Rust 候選狀態
                        if (hasTextMutated) {
                            WaiNativeEngine.queryCandidates()
                            hasTextMutated = false
                            lastQueryUptime = SystemClock.uptimeMillis()
                        }

                        val committed = if (action.isTranslation) {
                            WaiNativeEngine.commitTranslationVerified(action.index, action.expectedText)
                        } else {
                            WaiNativeEngine.commitCandidateVerified(action.index, action.expectedText)
                        }
                        val remainingPreedit = WaiNativeEngine.getRawPreedit()
                        val success = committed.isNotEmpty()

                        mainHandler.post {
                            listener.onCommitResult(
                                CommitResult(action.sessionId, committed, remainingPreedit, success)
                            )
                        }

                        if (success && remainingPreedit.isNotEmpty()) {
                            // 局部提交（如選中「開發」，仍剩「者」）：對剩餘拼音發起後續查詢
                            val state = WaiNativeEngine.queryCandidates()
                            mainHandler.post {
                                listener.onCandidatesUpdated(
                                    CandidateResult(action.sessionId, action.requestId, state.preedit, state.candidates)
                                )
                            }
                        } else if (!success) {
                            Log.w(TAG, "候選詞提交驗證失敗（版本或文字不匹配）: index=${action.index}, expected=${action.expectedText}")
                            // 提交失敗時，重新查詢最新候選以糾正 UI
                            val state = WaiNativeEngine.queryCandidates()
                            mainHandler.post {
                                listener.onCandidatesUpdated(
                                    CandidateResult(action.sessionId, action.requestId, state.preedit, state.candidates)
                                )
                            }
                        }
                    }
                }
            }

            // 判斷是否在此時執行 query()
            if (hasTextMutated && lastSessionId == currentSessionId) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - lastQueryUptime
                val shouldQuery = !nextIsTextMutating || (elapsed >= MAX_QUERY_DELAY_MS)

                if (shouldQuery) {
                    val t0 = System.nanoTime()
                    val state = WaiNativeEngine.queryCandidates()
                    val tQueryMs = (System.nanoTime() - t0) / 1_000_000.0
                    lastQueryUptime = now
                    hasTextMutated = false
                    executedQueryCount.incrementAndGet()

                    val res = CandidateResult(lastSessionId, lastRequestId, state.preedit, state.candidates)
                    mainHandler.post {
                        listener.onCandidatesUpdated(res)
                    }
                } else {
                    coalescedQueryCount.incrementAndGet()
                }
            }
        }
    }

    fun destroy() {
        workerThread.quitSafely()
    }
}

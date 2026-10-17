package com.wai.ime.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wai.ime.R
import com.wai.ime.core.SettingsRepository
import com.wai.ime.core.VibrationHelper
import com.wai.ime.core.WaiNativeEngine

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        // 啟動基準量測測試（同時對比同步基準與 P0 解耦排程器）
        Thread {
            WaiNativeEngine.initialize(applicationContext, "ja")
            Thread.sleep(800)
            runBenchmarkSuite()
            Thread.sleep(500)
            runP0DecoupledBenchmark()
        }.start()

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F2F2F7"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(36))
        }
        scrollView.addView(root)

        // 頂部標題
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        root.addView(titleView)

        val descView = TextView(this).apply {
            text = getString(R.string.welcome_hint)
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dpToPx(4), 0, dpToPx(16))
        }
        root.addView(descView)

        // 1. 啟用向導卡片
        root.addView(createActivationCard())

        // 2. 即時測試輸入框卡片
        root.addView(createTestInputCard())

        // 3. 常規設置（簡繁體、模糊拼音、記憶學習）
        root.addView(createSectionHeader("常規設置 (輸入與詞庫)"))
        root.addView(createGeneralSettingsCard())

        // 4. 鍵盤界面設置（字體大小、高度、震動、動效）
        root.addView(createSectionHeader("鍵盤界面設置"))
        root.addView(createKeyboardInterfaceCard())

        // 5. 高級設置（雙拼方案）
        root.addView(createSectionHeader("高級設置 (雙拼方案)"))
        root.addView(createAdvancedSettingsCard())

        // 6. 關於歪輸入法
        root.addView(createSectionHeader("關於"))
        root.addView(createAboutCard())

        setContentView(scrollView)
    }

    private fun createActivationCard(): View {
        val card = createCardLayout()

        val cardTitle = TextView(this).apply {
            text = "輸入法啟用狀態"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1E"))
            setPadding(0, 0, 0, dpToPx(12))
        }
        card.addView(cardTitle)

        val btnEnable = createActionButton("1. 在系統中啟用歪輸入法") {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        }
        card.addView(btnEnable)

        val btnSelect = createActionButton("2. 選擇歪輸入法為默認鍵盤") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        card.addView(btnSelect)

        return card
    }

    private fun createTestInputCard(): View {
        val card = createCardLayout()

        val cardTitle = TextView(this).apply {
            text = "輸入法測試區"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1E"))
            setPadding(0, 0, 0, dpToPx(8))
        }
        card.addView(cardTitle)

        val hintView = TextView(this).apply {
            text = "點擊下方輸入框彈出歪輸入法，嘗試輸入拼音（如：kaifa、riben、xuexi）"
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 0, 0, dpToPx(10))
        }
        card.addView(hintView)

        val editText = EditText(this).apply {
            setHint("點此測試歪輸入法...")
            textSize = 16f
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F7"))
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#E5E5EA"))
            }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
            }
        }
        card.addView(editText)

        return card
    }

    private fun createGeneralSettingsCard(): View {
        val card = createCardLayout()

        // 語言對照設定
        val langLabels = arrayOf(
            "日語 (日本語)",
            "英語 (English)",
            "西班牙語 (Español)",
            "德語 (Deutsch)",
            "越南語 (Tiếng Việt)",
            "韓語 (한국어)",
            "葡萄牙語 (Português)"
        )
        val langCodes = arrayOf("ja", "en", "es", "de", "vi", "ko", "pt")
        val currentLangIndex = langCodes.indexOf(settings.targetLanguage).coerceAtLeast(0)

        card.addView(createClickableRow("對照學習語言", langLabels[currentLangIndex]) { summaryView ->
            val checked = langCodes.indexOf(settings.targetLanguage).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("選擇候選詞對照學習語言")
                .setSingleChoiceItems(langLabels, checked) { dialog, which ->
                    val newLang = langCodes[which]
                    settings.targetLanguage = newLang
                    summaryView.text = langLabels[which]
                    Thread {
                        WaiNativeEngine.setTargetLanguage(applicationContext, newLang)
                    }.start()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        addDivider(card)

        // 簡繁切換
        val traditionalLabels = arrayOf("簡體中文", "繁體（台灣字形）", "繁體（香港字形）", "繁體（通用字形）")
        val traditionalValues = arrayOf("off", "taiwan", "hongkong", "standard")
        val currentTradIndex = traditionalValues.indexOf(settings.traditionalVariant).coerceAtLeast(0)

        card.addView(createClickableRow("簡繁中文轉換", traditionalLabels[currentTradIndex]) { summaryView ->
            val checked = traditionalValues.indexOf(settings.traditionalVariant).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("選擇簡繁中文轉換模式")
                .setSingleChoiceItems(traditionalLabels, checked) { dialog, which ->
                    settings.traditionalVariant = traditionalValues[which]
                    summaryView.text = traditionalLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        addDivider(card)

        // 模糊拼音詳細設定
        card.addView(createClickableRow("模糊拼音設置", "設定 z/zh, c/ch, an/ang 等 9 種模糊音") {
            showFuzzySettingsDialog()
        })

        addDivider(card)

        // 記憶學習
        card.addView(createSwitchRow("詞頻記憶學習", "根據個人輸入習慣動態調整候選詞頻", settings.learningEnabled) { isChecked ->
            settings.learningEnabled = isChecked
        })

        return card
    }

    private fun createKeyboardInterfaceCard(): View {
        val card = createCardLayout()

        // 按鍵與選詞震動開關
        card.addView(createSwitchRow("按鍵與選詞震動反饋", "敲擊按鍵及選中候選詞時提供即時震動跟手反饋", settings.keyVibration) { isChecked ->
            settings.keyVibration = isChecked
            if (isChecked) {
                VibrationHelper.vibrate(this, null, settings, isCandidateSelect = false)
            }
        })

        addDivider(card)

        // 震動強度滑桿調節 (0 - 100%)
        card.addView(createSeekBarRow("震動強度調節", settings.vibrationStrength) { strength ->
            settings.vibrationStrength = strength
            // 滑動時即時觸發一次該強度的震動體驗
            VibrationHelper.vibrate(this, null, settings, isCandidateSelect = false)
        })

        addDivider(card)

        // 按鍵點擊動效
        card.addView(createSwitchRow("按鍵點擊動畫", "點擊按鍵時縮放微動效", settings.keyAnimation) { isChecked ->
            settings.keyAnimation = isChecked
        })

        addDivider(card)

        // 候選字體大小
        val fontLabels = arrayOf("標準 (15sp)", "大字 (18sp)", "超大 (22sp)")
        val fontSizes = arrayOf(15, 18, 22)
        val currentFontIndex = fontSizes.indexOf(settings.candidateFontSize).let { if (it >= 0) it else 1 }

        card.addView(createClickableRow("候選字體大小", fontLabels[currentFontIndex]) { summaryView ->
            val checked = fontSizes.indexOf(settings.candidateFontSize).let { if (it >= 0) it else 1 }
            AlertDialog.Builder(this)
                .setTitle("選擇候選字體大小")
                .setSingleChoiceItems(fontLabels, checked) { dialog, which ->
                    settings.candidateFontSize = fontSizes[which]
                    summaryView.text = fontLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        addDivider(card)

        // 鍵盤高度
        val heightLabels = arrayOf("矮鍵盤 (250dp)", "標準/適配行距與抬升 (280dp)", "高鍵盤 (305dp)")
        val heights = arrayOf(250, 280, 305)
        val currentHeightIndex = heights.indexOf(settings.keyboardHeightDp).let { if (it >= 0) it else 1 }

        card.addView(createClickableRow("鍵盤高度調節", heightLabels[currentHeightIndex]) { summaryView ->
            val checked = heights.indexOf(settings.keyboardHeightDp).let { if (it >= 0) it else 1 }
            AlertDialog.Builder(this)
                .setTitle("選擇鍵盤高度")
                .setSingleChoiceItems(heightLabels, checked) { dialog, which ->
                    settings.keyboardHeightDp = heights[which]
                    summaryView.text = heightLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        addDivider(card)

        // 鍵盤往上抬升/底部間距調節
        val spacingLabels = arrayOf("緊湊 (44dp)", "標準防遮擋抬升 (64dp)", "大抬升 (78dp)", "超大抬升 (92dp)")
        val spacings = arrayOf(44, 64, 78, 92)
        val currentSpacingIndex = spacings.indexOf(settings.bottomSpacingDp).let { if (it >= 0) it else 1 }

        card.addView(createClickableRow("鍵盤底部抬升間距", spacingLabels[currentSpacingIndex]) { summaryView ->
            val checked = spacings.indexOf(settings.bottomSpacingDp).let { if (it >= 0) it else 1 }
            AlertDialog.Builder(this)
                .setTitle("選擇鍵盤底部抬升間距")
                .setSingleChoiceItems(spacingLabels, checked) { dialog, which ->
                    settings.bottomSpacingDp = spacings[which]
                    summaryView.text = spacingLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        addDivider(card)

        // 底部導航欄圖標（地球與收起鍵盤）顯示設置
        val navModeLabels = arrayOf("自動隱藏圖標（系統已有時）", "始終隱藏圖標（僅保留底欄空間）", "始終顯示圖標")
        val navModeValues = arrayOf("auto", "hide", "show")
        val currentNavIndex = navModeValues.indexOf(settings.bottomNavBarMode).let { if (it >= 0) it else 0 }

        card.addView(createClickableRow("底部切換與收起圖標", navModeLabels[currentNavIndex]) { summaryView ->
            val checked = navModeValues.indexOf(settings.bottomNavBarMode).let { if (it >= 0) it else 0 }
            AlertDialog.Builder(this)
                .setTitle("底部切換與收起圖標顯示")
                .setSingleChoiceItems(navModeLabels, checked) { dialog, which ->
                    settings.bottomNavBarMode = navModeValues[which]
                    summaryView.text = navModeLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        return card
    }

    private fun createAdvancedSettingsCard(): View {
        val card = createCardLayout()

        val schemeLabels = arrayOf(
            "全拼 (關閉雙拼)",
            "小鶴雙拼",
            "自然碼雙拼",
            "微軟雙拼",
            "搜狗雙拼",
            "智能 ABC 雙拼",
            "小浪雙拼",
            "首道雙拼",
            "拼音加加雙拼"
        )
        val schemeValues = arrayOf(
            "off",
            "xiaohe",
            "ziranma",
            "microsoft",
            "sogou",
            "abc",
            "xiaolang",
            "shoudao",
            "jiajia"
        )
        val currentIndex = schemeValues.indexOf(settings.shuangpinScheme).coerceAtLeast(0)

        card.addView(createClickableRow("雙拼輸入方案", schemeLabels[currentIndex]) { summaryView ->
            val checked = schemeValues.indexOf(settings.shuangpinScheme).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("選擇雙拼輸入方案")
                .setSingleChoiceItems(schemeLabels, checked) { dialog, which ->
                    settings.shuangpinScheme = schemeValues[which]
                    summaryView.text = schemeLabels[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        return card
    }

    private fun createAboutCard(): View {
        val card = createCardLayout()

        val infoTitle = TextView(this).apply {
            text = "歪輸入法 (Why IME)"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        card.addView(infoTitle)

        val infoDesc = TextView(this).apply {
            text = "版本：v0.1.18-alpha\n特點：\n1. 雙語學習輸入：打中文即時本地離線對照日文假名、英/西/德/越/韓/葡多語釋義；\n2. 零網絡依賴：完全離線運行，保護個人隱私；\n3. 0ms 跟手觸覺反饋：支援觸控瞬態變灰、無死角防誤觸、無級震動強度調節；\n4. 針對折疊屏與全面屏手勢智能優化。"
            textSize = 13f
            setTextColor(Color.parseColor("#3C3C43"))
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }
        card.addView(infoDesc)

        return card
    }

    private fun showFuzzySettingsDialog() {
        val rules = arrayOf(
            "z_zh" to "z ↔ zh",
            "c_ch" to "c ↔ ch",
            "s_sh" to "s ↔ sh",
            "n_l" to "n ↔ l",
            "f_h" to "f ↔ h",
            "l_r" to "l ↔ r",
            "an_ang" to "an ↔ ang",
            "en_eng" to "en ↔ eng",
            "in_ing" to "in ↔ ing"
        )
        val names = rules.map { it.second }.toTypedArray()
        val checkedItems = BooleanArray(rules.size) { i ->
            settings.isFuzzyEnabled(rules[i].first)
        }

        AlertDialog.Builder(this)
            .setTitle("模糊拼音規則開關")
            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                settings.setFuzzyEnabled(rules[which].first, isChecked)
            }
            .setPositiveButton("完成", null)
            .show()
    }

    // ================= UI 輔助方法 =================

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpToPx(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(14))
            }
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6E6E73"))
            setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(6))
        }
    }

    private fun createActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#007AFF"))
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F7"))
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(4), 0, dpToPx(6))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createSwitchRow(
        title: String,
        subtitle: String,
        initialValue: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        textLayout.addView(titleView)

        val subView = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        textLayout.addView(subView)

        val switch = Switch(this).apply {
            isChecked = initialValue
            setOnCheckedChangeListener { _, isChecked ->
                onCheckedChange(isChecked)
            }
        }

        row.addView(textLayout)
        row.addView(switch)
        return row
    }

    private fun createSeekBarRow(
        title: String,
        initialProgress: Int,
        onProgressChange: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(titleView)

        val valueView = TextView(this).apply {
            text = "$initialProgress%"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#007AFF"))
        }
        headerLayout.addView(valueView)
        container.addView(headerLayout)

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = initialProgress
            setPadding(0, dpToPx(8), 0, dpToPx(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueView.text = "$progress%"
                    if (fromUser) {
                        onProgressChange(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(seekBar)

        return container
    }

    private fun createClickableRow(
        title: String,
        currentSummary: String,
        onClick: (summaryView: TextView) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            isClickable = true
            isFocusable = true
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(titleView)

        val summaryView = TextView(this).apply {
            text = currentSummary
            textSize = 14f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
        }
        row.addView(summaryView)

        val arrowView = TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(Color.parseColor("#C7C7CC"))
        }
        row.addView(arrowView)

        row.setOnClickListener {
            onClick(summaryView)
        }

        return row
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#E5E5EA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            )
        }
        container.addView(divider)
    }

    private fun runBenchmarkSuite() {
        val testWords = listOf("kaifa", "riben", "xuexi", "macauprice")
        android.util.Log.i("WAI_BENCH", "==================== [基準性能測試開始] ====================")

        // 暖機
        WaiNativeEngine.clear()
        for (c in "test") { WaiNativeEngine.feedCharTimed(c) }
        WaiNativeEngine.clear()

        val latencies = mutableListOf<Double>()
        for (word in testWords) {
            WaiNativeEngine.clear()
            android.util.Log.i("WAI_BENCH", "--- 測試詞: $word ---")
            for (ch in word) {
                val tStart = System.nanoTime()
                val res = WaiNativeEngine.feedCharTimed(ch)
                val tEnd = System.nanoTime()
                val totalMs = (tEnd - tStart) / 1_000_000.0
                val jniMs = (res.tJniReturnedNanos - tStart) / 1_000_000.0
                val jsonMs = (res.tParseDoneNanos - res.tJniReturnedNanos) / 1_000_000.0
                latencies.add(totalMs)
                android.util.Log.i(
                    "WAI_BENCH",
                    "  按鍵 '$ch' -> 總耗時=${String.format("%.2f", totalMs)}ms (JNI+Rust=${String.format("%.2f", jniMs)}ms, JSON解析=${String.format("%.2f", jsonMs)}ms, 候選數=${res.state.candidates.size})"
                )
            }
        }
        latencies.sort()
        val p50 = latencies[latencies.size * 50 / 100]
        val p95 = latencies[latencies.size * 95 / 100]
        val p99 = latencies[latencies.size * 99 / 100]
        android.util.Log.i("WAI_BENCH", "==================== [基準統計] ====================")
        android.util.Log.i("WAI_BENCH", "總樣本數: ${latencies.size}")
        android.util.Log.i("WAI_BENCH", "P50: ${String.format("%.2f", p50)}ms | P95: ${String.format("%.2f", p95)}ms | P99: ${String.format("%.2f", p99)}ms")
        android.util.Log.i("WAI_BENCH", "==================== [基準性能測試結束] ====================")
    }

    private fun runP0DecoupledBenchmark() {
        val testWords = listOf("kaifa", "riben", "xuexi", "macauprice")
        android.util.Log.i("WAI_BENCH_P0", "==================== [P0 解耦排程器測試開始] ====================")

        val mainLatencies = mutableListOf<Double>()
        var lastCandidates = listOf<com.wai.ime.core.Candidate>()
        var lastCommitResult: com.wai.ime.core.CommitResult? = null

        val dispatcher = com.wai.ime.core.EngineDispatcher(object : com.wai.ime.core.EngineDispatcherListener {
            override fun onCandidatesUpdated(result: com.wai.ime.core.CandidateResult) {
                lastCandidates = result.candidates
            }

            override fun onCommitResult(result: com.wai.ime.core.CommitResult) {
                lastCommitResult = result
            }
        })

        Thread {
            // 階段 1：常規連打測試（模擬 25ms 擊鍵間隔）
            for (word in testWords) {
                dispatcher.newSession()
                android.util.Log.i("WAI_BENCH_P0", "--- 常規連打測試詞: $word ---")
                for (ch in word) {
                    val t0 = System.nanoTime()
                    dispatcher.enqueuePush(ch)
                    val t1 = System.nanoTime()
                    mainLatencies.add((t1 - t0) / 1_000_000.0)
                    Thread.sleep(25)
                }
                Thread.sleep(120)
                android.util.Log.i(
                    "WAI_BENCH_P0",
                    "詞 $word 最終候選數: ${lastCandidates.size}, 首候選=${lastCandidates.firstOrNull()?.text}"
                )
            }

            // 階段 2：極速連打壓力測試（模擬 5ms 超高頻擊鍵，測試過期查詢合併效果）
            android.util.Log.i("WAI_BENCH_P0", "--- 極速壓力測試 (5ms 極限連打長句) ---")
            val stressSentence = "kaifazhexuexiriben"
            dispatcher.newSession()
            for (ch in stressSentence) {
                val t0 = System.nanoTime()
                dispatcher.enqueuePush(ch)
                val t1 = System.nanoTime()
                mainLatencies.add((t1 - t0) / 1_000_000.0)
                Thread.sleep(5) // 5ms 極限高頻
            }
            Thread.sleep(150)
            android.util.Log.i(
                "WAI_BENCH_P0",
                "長句連打完成，最終候選數: ${lastCandidates.size}, 首候選=${lastCandidates.firstOrNull()?.text}"
            )

            // 階段 3：驗證候選版本隔離與提交校驗
            android.util.Log.i("WAI_BENCH_P0", "--- 候選詞提交與版本隔離測試 ---")
            // 3.1 正常提交正確候選
            val cand0 = lastCandidates.firstOrNull()
            if (cand0 != null) {
                dispatcher.enqueueCommit(0, cand0.text, false)
                Thread.sleep(80)
                android.util.Log.i(
                    "WAI_BENCH_P0",
                    "提交有效候選 '${cand0.text}': 成功=${lastCommitResult?.success}, 上屏='${lastCommitResult?.committedText}', 剩餘='${lastCommitResult?.remainingPreedit}'"
                )
            }

            // 3.2 提交過期/篡改的候選詞（版本不匹配防禦）
            dispatcher.newSession()
            for (ch in "kaifa") { dispatcher.enqueuePush(ch) }
            Thread.sleep(80)
            // 刻意傳入錯誤的 expectedText 模擬版本偏移或按鍵競爭
            dispatcher.enqueueCommit(0, "不存在的詞彙_WRONG", false)
            Thread.sleep(80)
            android.util.Log.i(
                "WAI_BENCH_P0",
                "提交不匹配候選防禦結果: 成功=${lastCommitResult?.success} (預期應為 false 拒絕上屏)"
            )

            mainLatencies.sort()
            val p50 = mainLatencies[mainLatencies.size * 50 / 100]
            val p95 = mainLatencies[mainLatencies.size * 95 / 100]
            val p99 = mainLatencies[mainLatencies.size * 99 / 100]

            val totalKeys = dispatcher.totalKeyCount.get()
            val executed = dispatcher.executedQueryCount.get()
            val coalesced = dispatcher.coalescedQueryCount.get()
            val coalescedRate = if (totalKeys > 0) (coalesced.toDouble() / totalKeys.toDouble()) * 100 else 0.0

            android.util.Log.i("WAI_BENCH_P0", "==================== [P0 解耦排程統計] ====================")
            android.util.Log.i("WAI_BENCH_P0", "總輸入按鍵數: $totalKeys")
            android.util.Log.i("WAI_BENCH_P0", "實際執行查詢數: $executed")
            android.util.Log.i(
                "WAI_BENCH_P0",
                "合併/跳過的過期查詢數: $coalesced (${String.format("%.1f", coalescedRate)}%)"
            )
            android.util.Log.i(
                "WAI_BENCH_P0",
                "主線程按鍵延遲 P50: ${String.format("%.3f", p50)}ms | P95: ${String.format("%.3f", p95)}ms | P99: ${String.format("%.3f", p99)}ms"
            )
            android.util.Log.i("WAI_BENCH_P0", "==================== [P0 解耦排程器測試結束] ====================")
            dispatcher.destroy()
        }.start()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

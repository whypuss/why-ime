# 歪輸入法 (Why IME)

> **打中文，學外語。**  
> 基於 [青簡 (Qingjian)](https://github.com/qingjian-team/qingjian) 高性能 Rust 核心移植的 Android 雙語學習輸入法。

[![GitHub Release](https://img.shields.io/github/v/release/whypuss/why-ime)](https://github.com/whypuss/why-ime/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

---

## 📱 核心特色

- **打中文即時顯示日語對照**：
  在打中文拼音時，候選條第一行顯示中文詞，第二行即時浮現**日語釋義與假名注音（Furigana）**。
  - 輸入 `kaifa` → `1. 開發` 下方展示 `開発する [かいはつする] (v.)`。
  - 讓你每次日常打字聊天，都在潛移默化中復習日語單詞與漢字讀法。
- **純本地離線辭庫**：
  隨包自帶 23.9 萬完整中日離線辭庫，絕不聯網上傳打字內容，無延遲、零隱私洩漏風險。
- **極致跟手觸覺與按鍵動效**：
  - 採用低延遲 `ACTION_DOWN` 快速響應機制，擺脫普通按鍵抬手延遲，輸入體驗行雲流水。
  - 按鍵微縮放動效 + 實時震動觸覺反饋（可在設定中開關）。
- **完整鍵盤模式支援**：
  - **拼音/英文鍵盤**：支援大小寫切換、空格上屏、回車鍵原樣上屏。
  - **數字鍵盤 (123)**：常用數字與符號。
  - **符號鍵盤 (#+=)**：括號、引號、數學與貨幣標點符號。
  - **表情鍵盤 (😀)**：常用 Emoji 表情一鍵選入。
  - **切換輸入法 (🌐)**：一鍵無縫輪換至系統中其他輸入法。
- **豐富完備的輸入法設置（參考主流手機輸入法）**：
  - **簡繁轉換**：簡體中文、繁體（台灣字形）、繁體（香港字形）、繁體（通用字形）。
  - **模糊拼音**：支援 9 種獨立規則自由配置（`z ↔ zh`、`c ↔ ch`、`s ↔ sh`、`n ↔ l`、`f ↔ h`、`l ↔ r`、`an ↔ ang`、`en ↔ eng`、`in ↔ ing`）。
  - **雙拼輸入**：支援小鶴雙拼、自然碼、微軟雙拼、搜狗雙拼、智能ABC、小浪雙拼、首道雙拼、拼音加加等。
  - **鍵盤界面自訂**：候選字體大小調節（標準/大字/超大）、鍵盤高度調節（矮/標準/高）、震動開關、點擊動效開關。
  - **詞頻記憶學習**：本地動態學習記錄，越用越懂你的常用詞。
  - **應用內即時測試區**：改完設置即可在設置頁內直接打字驗證。

---

## 📥 下載與安裝

你可以在 GitHub Releases 直接下載最新 APK 安裝包：

👉 **[點此下載 why-ime-v0.1.3-alpha.apk](https://github.com/whypuss/why-ime/releases/download/v0.1.3-alpha/why-ime-v0.1.3-alpha.apk)**

### 啟用步驟：
1. 下載並安裝 APK。
2. 打開「歪輸入法」App，點擊 **「1. 在系統中啟用歪輸入法」**，在系統鍵盤管理中將其開啟。
3. 點擊 **「2. 選擇歪輸入法為默認鍵盤」**，切換為歪輸入法。
4. 開始打字，體驗中文候選詞旁邊即時浮現的日語對照！

---

## 🛠️ 開發與建置

### 架構概覽
- `crates/qingjian-core`：拼音解析、候選生成、整句切分、模糊音、雙拼與簡繁轉換核心。
- `crates/qingjian-translate`：多語言對照辭庫引擎（中日、中英等）。
- `crates/wai-android`：Rust ↔ Android JNI 橋接層，封裝 Engine 為 C-ABI 動態庫（`libwai_android.so`）。
- `apps/android`：Android 原生輸入法殼（Kotlin、InputMethodService、自繪候選欄、軟鍵盤、以及設置中心）。

### 一鍵本機建置 (macOS / Linux)

需要環境：
- Rust 1.80+ (`rustup target add aarch64-linux-android x86_64-linux-android`)
- `cargo-ndk`
- Android SDK & NDK r25+
- OpenJDK 17

執行一鍵建置：
```bash
# 編譯 Rust JNI 本地庫並打包 APK
bash apps/android/build.sh
```

產生的 APK 位於：
`apps/android/app/build/outputs/apk/debug/app-debug.apk`

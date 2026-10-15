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
- **Rust JNI 極致效能**：
  核心拼音切分、模糊音、雙拼與注音由 `qingjian-core` 提供，查詞與日語標注全部在本地微秒級完成。
- **Android 標準輸入法服務**：
  基於 Android 標準 `InputMethodService` 構建，支援全局任意 App 呼出與流暢輸入。

---

## 📥 下載與安裝

你可以在 GitHub Releases 直接下載最新 APK 安裝包：

👉 **[點此下載 why-ime-v0.1.0-alpha.apk](https://github.com/whypuss/why-ime/releases/download/v0.1.0-alpha/why-ime-v0.1.0-alpha.apk)**

### 啟用步驟：
1. 下載並安裝 APK。
2. 打開「歪輸入法」App，點擊 **「1. 在系統設定中啟用歪輸入法」**，在系統鍵盤管理中將其開啟。
3. 點擊 **「2. 切換為歪輸入法」** 或在打字時長按空格/輸入法切換鍵切換。
4. 開始打字，體驗中文候選詞旁邊即時浮現的日語對照！

---

## 🛠️ 開發與建置

### 架構概覽
- `crates/qingjian-core`：拼音解析、候選生成、整句切分核心。
- `crates/qingjian-translate`：多語言對照辭庫引擎（中日、中英等）。
- `crates/wai-android`：Rust ↔ Android JNI 橋接層，封裝 Engine 為 C-ABI 動態庫（`libwai_android.so`）。
- `apps/android`：Android 原生輸入法殼（Kotlin、InputMethodService、自繪候選欄與軟鍵盤）。

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
編譯產物將生成在：`apps/android/app/build/outputs/apk/debug/why-ime-v0.1.0-alpha.apk`。

---

## 📄 開源許可

本項目依據 [GPL-3.0-or-later](LICENSE) 許可證開源。  
感謝 [青簡團隊 (qingjian-team)](https://github.com/qingjian-team/qingjian) 的優秀開源內核。

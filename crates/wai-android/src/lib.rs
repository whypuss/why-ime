//! 歪輸入法（Wai IME）Android JNI 橋接層。
//!
//! 把 Rust Core（qingjian-core + qingjian-translate + qingjian-dictionary）
//! 封裝為提供給 Android Kotlin/Java InputMethodService 調用的高性能本地庫。

use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};

use qingjian_core::shuangpin::Scheme;
use qingjian_core::traditional::TraditionalVariant;
use qingjian_core::{Candidate, Engine, Language, Query};
use qingjian_dictionary::Dictionary;
use qingjian_translate::Glossary;
use serde::Serialize;

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __android_log_write(prio: std::os::raw::c_int, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) -> std::os::raw::c_int;
}

#[cfg(not(target_os = "android"))]
unsafe fn __android_log_write(_prio: std::os::raw::c_int, _tag: *const std::os::raw::c_char, _text: *const std::os::raw::c_char) -> std::os::raw::c_int {
    0
}

unsafe fn android_log_write(prio: std::os::raw::c_int, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) {
    unsafe {
        __android_log_write(prio, tag, text);
    }
}

/// 傳遞給 Android 端的候選詞資訊（包含中文與外語釋義/假名）。
#[derive(Debug, Clone, Serialize)]
pub struct CandidateDto {
    /// 候選中文詞（例如："開發"）
    pub text: String,
    /// 是否有翻譯
    pub has_translation: bool,
    /// 第一條日語/外語釋義（例如："開発する"）
    pub translation_text: Option<String>,
    /// 假名/讀音（例如："かいはつする"）
    pub reading: Option<String>,
    /// 詞性（例如："v."）
    pub pos: Option<String>,
}

impl From<&Candidate> for CandidateDto {
    fn from(c: &Candidate) -> Self {
        let (has_translation, translation_text, reading, pos) =
            if let Some(ref trans) = c.translation {
                let first_sense = trans.senses().first();
                (
                    true,
                    first_sense.map(|s| s.text.clone()),
                    first_sense.and_then(|s| s.reading.clone()),
                    first_sense.and_then(|s| s.part_of_speech.map(|p| p.abbreviation().to_string())),
                )
            } else {
                (false, None, None, None)
            };

        CandidateDto {
            text: c.text.clone(),
            has_translation,
            translation_text,
            reading,
            pos,
        }
    }
}

/// 目前引擎返回給 UI 的完整上下文狀態。
#[derive(Debug, Clone, Serialize)]
pub struct EngineStateDto {
    /// 當前輸入的拼音/組詞字符（例如："kaifa"）
    pub preedit: String,
    /// 候選詞列表
    pub candidates: Vec<CandidateDto>,
    /// 是否處於組詞狀態
    pub is_composing: bool,
}

pub struct WaiEngineHandle {
    pub engine: Engine,
    pub last_query: Option<Query>,
}

impl WaiEngineHandle {
    pub fn new(dict_path: &str, glossary_path: &str, target_lang: Language) -> Result<Self, String> {
        let dictionary = Dictionary::from_path(dict_path)
            .map_err(|e| format!("加載詞庫失敗: {e}"))?;

        let mut engine = Engine::new(dictionary);

        if !glossary_path.is_empty() {
            match Glossary::from_path(target_lang, glossary_path) {
                Ok(glossary) => {
                    engine = engine.with_translator(Box::new(glossary));
                }
                Err(e) => {
                    tracing::warn!("加載釋義表失敗: {e}，將不帶翻譯運行");
                }
            }
        }

        Ok(Self {
            engine,
            last_query: None,
        })
    }

    pub fn input_char(&mut self, ch: char) -> EngineStateDto {
        self.engine.push(ch);
        self.update_query()
    }

    pub fn backspace(&mut self) -> EngineStateDto {
        self.engine.backspace();
        self.update_query()
    }

    pub fn clear(&mut self) -> EngineStateDto {
        self.engine.clear();
        self.last_query = None;
        EngineStateDto {
            preedit: String::new(),
            candidates: Vec::new(),
            is_composing: false,
        }
    }

    pub fn push_char(&mut self, ch: char) -> String {
        self.engine.push(ch);
        self.engine.raw_preedit().text
    }

    pub fn backspace_fast(&mut self) -> String {
        self.engine.backspace();
        self.engine.raw_preedit().text
    }

    pub fn clear_fast(&mut self) {
        self.engine.clear();
        self.last_query = None;
    }

    pub fn query_candidates(&mut self) -> EngineStateDto {
        self.update_query()
    }

    pub fn commit_candidate_verified(&mut self, index: usize, expected_text: &str) -> Option<String> {
        let candidate = self.last_query.as_ref().and_then(|q| {
            q.candidates.items.get(index).cloned()
        })?;

        if !expected_text.is_empty() && candidate.text != expected_text {
            return None;
        }

        let committed = self.engine.commit(&candidate);
        self.last_query = None;
        Some(committed)
    }

    pub fn commit_translation_verified(&mut self, index: usize, expected_text: &str) -> Option<String> {
        let candidate = self.last_query.as_ref().and_then(|q| {
            q.candidates.items.get(index).cloned()
        })?;

        if !expected_text.is_empty() && candidate.text != expected_text {
            return None;
        }

        let committed = self.engine.commit_translation(&candidate, 0)
            .unwrap_or_else(|| self.engine.commit(&candidate));
        self.last_query = None;
        Some(committed)
    }

    pub fn commit_candidate(&mut self, index: usize) -> Option<String> {
        let candidate = self.last_query.as_ref().and_then(|q| {
            q.candidates.items.get(index).cloned()
        })?;

        let committed = self.engine.commit(&candidate);
        self.last_query = None;
        Some(committed)
    }

    pub fn commit_translation(&mut self, index: usize) -> Option<String> {
        let candidate = self.last_query.as_ref().and_then(|q| {
            q.candidates.items.get(index).cloned()
        })?;

        // 優先嘗試上屏第 1 條釋義（sense 0），若無翻譯則退回中文本身
        let committed = self.engine.commit_translation(&candidate, 0)
            .unwrap_or_else(|| self.engine.commit(&candidate));
        self.last_query = None;
        Some(committed)
    }

    pub fn get_raw_preedit(&self) -> String {
        self.engine.raw_preedit().text
    }

    pub fn set_fuzzy_rule(&mut self, name: &str, on: bool) {
        let mut rules = self.engine.fuzzy();
        rules.set(name, on);
        self.engine.set_fuzzy(rules);
    }

    pub fn set_shuangpin_scheme(&mut self, scheme_str: &str) {
        if scheme_str.is_empty() || scheme_str == "off" {
            self.engine.set_shuangpin(None);
        } else if let Ok(scheme) = scheme_str.parse::<Scheme>() {
            self.engine.set_shuangpin(Some(scheme));
        }
    }

    pub fn set_traditional(&mut self, variant_str: &str) {
        let variant = match variant_str {
            "taiwan" | "tw" => TraditionalVariant::Taiwan,
            "hongkong" | "hk" => TraditionalVariant::Hongkong,
            "standard" | "t" => TraditionalVariant::Standard,
            _ => TraditionalVariant::Off,
        };
        self.engine.set_traditional(variant);
    }

    pub fn set_learning(&mut self, enabled: bool) {
        self.engine.set_learning(enabled);
    }

    pub fn set_target_language(&mut self, glossary_path: &str, lang_code: &str) {
        let target_lang = lang_code.parse::<Language>().unwrap_or(Language::Japanese);
        if !glossary_path.is_empty() {
            // 在後台加載，避免短暫卡頓
            let path = glossary_path.to_string();
            match Glossary::from_path(target_lang, &path) {
                Ok(glossary) => {
                    self.engine.set_translator(Box::new(glossary));
                }
                Err(e) => {
                    tracing::warn!("切換釋義表失敗: {e}");
                }
            }
        }
    }

    fn update_query(&mut self) -> EngineStateDto {
        let preedit = self.engine.raw_preedit().text;
        if preedit.is_empty() {
            self.last_query = None;
            return EngineStateDto {
                preedit,
                candidates: Vec::new(),
                is_composing: false,
            };
        }

        match self.engine.query() {
            Ok(mut query) => {
                // 核心優化：只有當前輸入構成完整音節或完整詞（「輸入一個字或者一個詞」）時才進行翻譯
                // 若輸入為殘缺字母或中間聲母（如 k、kaif、zh、s…），不進行翻譯標註，以保證輸入極致流暢跟手
                let is_complete = query.tail.is_empty()
                    && query.segmentations.first().map_or(false, |s| {
                        !s.last_is_partial() && s.incomplete_count() == 0
                    });

                // 移動端候選欄最多展示前 25 個候選，截斷避免對 500 個候選全量查詢與巨大 JSON 傳輸
                query.candidates.items.truncate(25);

                if is_complete {
                    let _ = self.engine.annotate(&mut query.candidates);
                }

                let candidates = query
                    .candidates
                    .items
                    .iter()
                    .map(CandidateDto::from)
                    .collect();

                self.last_query = Some(query);

                EngineStateDto {
                    preedit,
                    candidates,
                    is_composing: true,
                }
            }
            Err(_) => {
                self.last_query = None;
                EngineStateDto {
                    preedit,
                    candidates: Vec::new(),
                    is_composing: true,
                }
            }
        }
    }
}

// ======================== JNI 導出函式 ========================

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    dict_path: JString,
    glossary_path: JString,
    lang_code: JString,
) -> jlong {
    let dict_path: String = match env.get_string(&dict_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let glossary_path: String = match env.get_string(&glossary_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let lang_code: String = match env.get_string(&lang_code) {
        Ok(s) => s.into(),
        Err(_) => "ja".to_string(),
    };

    let target_lang = lang_code.parse::<Language>().unwrap_or(Language::Japanese);

    match WaiEngineHandle::new(&dict_path, &glossary_path, target_lang) {
        Ok(handle) => Box::into_raw(Box::new(Mutex::new(handle))) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ptr as *mut Mutex<WaiEngineHandle>);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeFeedChar(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    ch: jint,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    if let Some(c) = char::from_u32(ch as u32) {
        let t_start = std::time::Instant::now();
        handle.engine.push(c);
        let t_push = t_start.elapsed();

        let t_query_start = std::time::Instant::now();
        let preedit = handle.engine.raw_preedit().text;
        let (candidates_dtos, t_query, t_annot) = if preedit.is_empty() {
            handle.last_query = None;
            (Vec::new(), std::time::Duration::ZERO, std::time::Duration::ZERO)
        } else {
            match handle.engine.query() {
                Ok(mut query) => {
                    let t_q = t_query_start.elapsed();
                    let is_complete = query.tail.is_empty()
                        && query.segmentations.first().map_or(false, |s| {
                            !s.last_is_partial() && s.incomplete_count() == 0
                        });
                    query.candidates.items.truncate(25);
                    let t_annot_start = std::time::Instant::now();
                    if is_complete {
                        let _ = handle.engine.annotate(&mut query.candidates);
                    }
                    let t_a = t_annot_start.elapsed();
                    let dtos: Vec<CandidateDto> = query.candidates.items.iter().map(CandidateDto::from).collect();
                    handle.last_query = Some(query);
                    (dtos, t_q, t_a)
                }
                Err(_) => {
                    handle.last_query = None;
                    (Vec::new(), t_query_start.elapsed(), std::time::Duration::ZERO)
                }
            }
        };

        let t_json_start = std::time::Instant::now();
        let state = EngineStateDto {
            preedit,
            candidates: candidates_dtos,
            is_composing: !handle.engine.raw_preedit().text.is_empty(),
        };
        let json = serde_json::to_string(&state).unwrap_or_default();
        let t_json = t_json_start.elapsed();

        // 輸出 Rust 底層基準數據到 logcat
        let log_msg = format!(
            "[RUST_BENCH] char='{}' | push={:.2}ms, query={:.2}ms, annot={:.2}ms, json={:.2}ms | Total={:.2}ms",
            c,
            t_push.as_secs_f64() * 1000.0,
            t_query.as_secs_f64() * 1000.0,
            t_annot.as_secs_f64() * 1000.0,
            t_json.as_secs_f64() * 1000.0,
            t_start.elapsed().as_secs_f64() * 1000.0,
        );
        let tag = std::ffi::CString::new("WaiNativeEngine").unwrap();
        let msg = std::ffi::CString::new(log_msg).unwrap();
        unsafe {
            android_log_write(3, tag.as_ptr(), msg.as_ptr());
        }

        return env.new_string(json).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw();
    }

    env.new_string("").unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeBackspace(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let state = handle.backspace();
    let json = serde_json::to_string(&state).unwrap_or_default();
    env.new_string(json).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeClear(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.clear();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeCommitCandidate(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    index: jint,
) -> jstring {
    if ptr == 0 || index < 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let committed = handle.commit_candidate(index as usize).unwrap_or_default();
    env.new_string(committed).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeCommitTranslation(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    index: jint,
) -> jstring {
    if ptr == 0 || index < 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let committed = handle.commit_translation(index as usize).unwrap_or_default();
    env.new_string(committed).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativePushChar(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    ch: jint,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let raw = if let Some(c) = char::from_u32(ch as u32) {
        handle.push_char(c)
    } else {
        handle.get_raw_preedit()
    };
    env.new_string(raw).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeBackspaceFast(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let raw = handle.backspace_fast();
    env.new_string(raw).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeClearFast(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.clear_fast();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeQuery(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let state = handle.query_candidates();
    let json = serde_json::to_string(&state).unwrap_or_default();
    env.new_string(json).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeCommitCandidateVerified(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    index: jint,
    expected_text: JString,
) -> jstring {
    if ptr == 0 || index < 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let expected: String = match env.get_string(&expected_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let committed = handle.commit_candidate_verified(index as usize, &expected).unwrap_or_default();
    env.new_string(committed).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeCommitTranslationVerified(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    index: jint,
    expected_text: JString,
) -> jstring {
    if ptr == 0 || index < 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let expected: String = match env.get_string(&expected_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let mut handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let committed = handle.commit_translation_verified(index as usize, &expected).unwrap_or_default();
    env.new_string(committed).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeGetRawPreedit(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    if ptr == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    let handle = match handle_mutex.lock() {
        Ok(h) => h,
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let raw = handle.get_raw_preedit();
    env.new_string(raw).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeSetFuzzyRule(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    rule_name: JString,
    enabled: jboolean,
) {
    if ptr == 0 {
        return;
    }
    let rule_name: String = match env.get_string(&rule_name) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.set_fuzzy_rule(&rule_name, enabled != 0);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeSetShuangpin(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    scheme: JString,
) {
    if ptr == 0 {
        return;
    }
    let scheme: String = match env.get_string(&scheme) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.set_shuangpin_scheme(&scheme);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeSetTraditional(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    variant: JString,
) {
    if ptr == 0 {
        return;
    }
    let variant: String = match env.get_string(&variant) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.set_traditional(&variant);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeSetLearning(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    enabled: jboolean,
) {
    if ptr == 0 {
        return;
    }
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.set_learning(enabled != 0);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wai_ime_core_WaiNativeEngine_nativeSetTargetLanguage(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    glossary_path: JString,
    lang_code: JString,
) {
    if ptr == 0 {
        return;
    }
    let glossary_path: String = match env.get_string(&glossary_path) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let lang_code: String = match env.get_string(&lang_code) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let handle_mutex = unsafe { &*(ptr as *mut Mutex<WaiEngineHandle>) };
    if let Ok(mut handle) = handle_mutex.lock() {
        handle.set_target_language(&glossary_path, &lang_code);
    }
}

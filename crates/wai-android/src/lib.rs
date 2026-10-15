//! 歪輸入法（Wai IME）Android JNI 橋接層。
//!
//! 把 Rust Core（qingjian-core + qingjian-translate + qingjian-dictionary）
//! 封裝為提供給 Android Kotlin/Java InputMethodService 調用的高性能本地庫。

use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};

use qingjian_core::{Candidate, Engine, Language, Query};
use qingjian_dictionary::Dictionary;
use qingjian_translate::Glossary;
use serde::Serialize;

/// 傳遞給 Android 端的候選詞資訊（包含中文與日語釋義/假名）。
#[derive(Debug, Clone, Serialize)]
pub struct CandidateDto {
    /// 候選中文詞（例如："開發"）
    pub text: String,
    /// 拼音音節（例如：["kai", "fa"]）
    pub syllables: Vec<String>,
    /// 是否有翻譯
    pub has_translation: bool,
    /// 目標語言（例如："ja"）
    pub target_lang: Option<String>,
    /// 第一條日語/外語釋義（例如："開発する"）
    pub translation_text: Option<String>,
    /// 假名讀音（例如："かいはつする"）
    pub reading: Option<String>,
    /// 詞性（例如："v."）
    pub pos: Option<String>,
    /// 生詞標記
    pub fresh: bool,
}

impl From<&Candidate> for CandidateDto {
    fn from(c: &Candidate) -> Self {
        let (has_translation, target_lang, translation_text, reading, pos, fresh) =
            if let Some(ref trans) = c.translation {
                let first_sense = trans.senses().first();
                (
                    true,
                    Some(trans.language.code().to_string()),
                    first_sense.map(|s| s.text.clone()),
                    first_sense.and_then(|s| s.reading.clone()),
                    first_sense.and_then(|s| s.part_of_speech.map(|p| p.abbreviation().to_string())),
                    first_sense.map_or(false, |s| s.fresh),
                )
            } else {
                (false, None, None, None, None, false)
            };

        CandidateDto {
            text: c.text.clone(),
            syllables: c.syllables.clone(),
            has_translation,
            target_lang,
            translation_text,
            reading,
            pos,
            fresh,
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

    pub fn commit_candidate(&mut self, index: usize) -> Option<String> {
        let candidate = self.last_query.as_ref().and_then(|q| {
            q.candidates.items.get(index).cloned()
        })?;

        let committed = self.engine.commit(&candidate);
        self.last_query = None;
        Some(committed)
    }

    pub fn get_raw_preedit(&self) -> String {
        self.engine.raw_preedit().text
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
                let _ = self.engine.annotate(&mut query.candidates);
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
        let state = handle.input_char(c);
        let json = serde_json::to_string(&state).unwrap_or_default();
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

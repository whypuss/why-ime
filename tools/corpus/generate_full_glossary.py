#!/usr/bin/env python3
"""全量生成韓語 (ko) 與葡萄牙語 (pt) 釋義表 (glossary-ko.tsv, glossary-pt.tsv)。

特點：
1. 從 dict.tsv 提取全部唯一詞條 (約 128,891 詞)，優先按詞頻降序處理
2. 借用 glossary-en.tsv 的詞性標註 (POS)
3. 批量多線程調用 Google 翻譯 API
4. 支援斷點續傳 (JSONL checkpoint)
5. 輸出嚴格符合青簡/歪輸入法規範：UTF-8 無 BOM、LF 換行、按碼點排序、一行一詞
"""

import os
import sys
import time
import json
import re
import urllib.request
import urllib.parse
import concurrent.futures
from pathlib import Path

REPO_ROOT = Path("/Users/whypuss/projects/why-ime")
DICT_PATH = REPO_ROOT / "apps/android/app/src/main/assets/dict.tsv"
EN_GLOSSARY_PATH = REPO_ROOT / "assets/glossary/glossary-en.tsv"
OUT_ASSETS_DIR = REPO_ROOT / "apps/android/app/src/main/assets"
OUT_REPO_DIR = REPO_ROOT / "assets/glossary"
PROGRESS_DIR = REPO_ROOT / "data/generated"

ALLOWED_POS = {
    "n.", "v.", "adj.", "adv.", "pron.", "prep.", "conj.", "num.",
    "m.", "part.", "int.", "phr."
}

def load_pos_map():
    pos_map = {}
    if EN_GLOSSARY_PATH.exists():
        with EN_GLOSSARY_PATH.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                w = parts[0].strip()
                if len(parts) > 1 and w:
                    first = parts[1].strip()
                    m = re.match(r"^([a-z]+\.)\s+", first)
                    if m and m.group(1) in ALLOWED_POS:
                        pos_map[w] = m.group(1)
    return pos_map

def load_dict_words():
    words_freq = {}
    with DICT_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if not parts:
                continue
            w = parts[0].strip()
            freq = int(parts[2]) if len(parts) >= 3 and parts[2].isdigit() else 0
            if w:
                if w not in words_freq or freq > words_freq[w]:
                    words_freq[w] = freq
    # 按詞頻從高到低排序，詞頻相同的按長度從長到短，最後按字符排序
    sorted_words = sorted(words_freq.keys(), key=lambda x: (-words_freq[x], -len(x), x))
    return sorted_words

def clean_gloss(raw: str) -> str:
    if not raw:
        return ""
    s = raw.strip()
    s = re.sub(r"[（(][^）)]*[）)]", "", s)
    s = s.replace("|", "").replace("\t", " ").replace("\n", " ").replace("\r", " ")
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip(" .,;:·。，、")
    if len(s) > 40:
        s = s[:40].strip()
    return s

def fetch_translation_batch(words, target_lang, max_retries=3):
    text = "\n".join(words)
    url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl={target_lang}&dt=t&q={urllib.parse.quote(text)}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})

    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                translated = "".join([part[0] for part in res[0] if part[0]])
                lines = [clean_gloss(l) for l in translated.strip().split("\n")]
                if len(lines) == len(words):
                    return lines
                # 若行數不完全匹配，退化為逐個或安全填補
                if len(lines) < len(words):
                    lines.extend([""] * (len(words) - len(lines)))
                    return lines[:len(words)]
                else:
                    return lines[:len(words)]
        except Exception as e:
            time.sleep(1.0 * (attempt + 1))
    return [""] * len(words)

def build_glossary(target_lang, batch_size=80, max_workers=10, limit=None):
    PROGRESS_DIR.mkdir(parents=True, exist_ok=True)
    prog_file = PROGRESS_DIR / f"gloss-{target_lang}-checkpoint.jsonl"

    pos_map = load_pos_map()
    all_words = load_dict_words()
    if limit:
        all_words = all_words[:limit]

    print(f"[{target_lang}] 字典詞總數: {len(all_words):,}")

    done = {}
    if prog_file.exists():
        with prog_file.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    w = data.get("w")
                    g = data.get("g")
                    if w and g:
                        done[w] = g
                except Exception:
                    continue
        print(f"[{target_lang}] 斷點續傳：已載入 {len(done):,} 條已翻譯記錄")

    todo_words = [w for w in all_words if w not in done]
    print(f"[{target_lang}] 待翻譯詞數: {len(todo_words):,}")

    batches = [todo_words[i:i + batch_size] for i in range(0, len(todo_words), batch_size)]

    t0 = time.time()
    translated_count = 0
    total_to_do = len(todo_words)

    with prog_file.open("a", encoding="utf-8") as f_out:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_batch = {
                executor.submit(fetch_translation_batch, batch, target_lang): batch
                for batch in batches
            }

            for future in concurrent.futures.as_completed(future_to_batch):
                batch = future_to_batch[future]
                try:
                    results = future.result()
                    for w, tr in zip(batch, results):
                        if tr and tr.lower() != w.lower():
                            pos = pos_map.get(w, "")
                            formatted_gloss = f"{pos} {tr}".strip()
                            done[w] = formatted_gloss
                            f_out.write(json.dumps({"w": w, "g": formatted_gloss}, ensure_ascii=False) + "\n")
                        else:
                            # 即使失敗也寫空記錄，避免重複白查
                            f_out.write(json.dumps({"w": w, "g": ""}, ensure_ascii=False) + "\n")
                    f_out.flush()
                except Exception as e:
                    print(f"Error processing batch: {e}")

                translated_count += len(batch)
                dt = time.time() - t0
                speed = translated_count / max(dt, 0.001)
                eta = (total_to_do - translated_count) / max(speed, 0.001)
                if translated_count % (batch_size * 5) == 0 or translated_count >= total_to_do:
                    print(f"[{target_lang}] 進度: {translated_count:,}/{total_to_do:,} ({translated_count*100/max(total_to_do,1):.1f}%) | 有效: {len(done):,} | 速度: {speed:.1f} 詞/s | 剩餘: {eta/60:.1f} 分鐘", flush=True)

    # 輸出最終排序 TSV (無 BOM、LF、按碼點排序)
    header = f"# 由 Why IME 全量生成 ({target_lang})。词\t[词性. ]译词\n"
    sorted_words = sorted(done.keys())

    out_files = [
        OUT_ASSETS_DIR / f"glossary-{target_lang}.tsv",
        OUT_REPO_DIR / f"glossary-{target_lang}.tsv"
    ]

    for path in out_files:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="\n") as f:
            f.write(header)
            for w in sorted_words:
                f.write(f"{w}\t{done[w]}\n")
        print(f"[{target_lang}] 寫入完成: {path} (共 {len(sorted_words):,} 詞條)")

if __name__ == "__main__":
    lang = sys.argv[1] if len(sys.argv) > 1 else "ko"
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else None
    build_glossary(lang, limit=limit)

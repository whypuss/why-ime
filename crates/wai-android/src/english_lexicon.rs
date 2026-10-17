use std::fs::File;
use std::path::Path;
use memmap2::Mmap;
use serde::Serialize;

const MAGIC: &[u8; 4] = b"WAIE";
const HEADER_SIZE: usize = 20;
const ENTRY_SIZE: usize = 48;

#[derive(Debug, Clone, Serialize)]
pub struct EnglishCandidateDto {
    pub word: String,
    pub freq: u32,
    pub zh: String,
    pub ja: String,
    pub reading: String,
    pub pos: String,
}

pub struct EnglishLexicon {
    mmap: Mmap,
    count: usize,
    arena_offset: usize,
    arena_len: usize,
}

impl EnglishLexicon {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, String> {
        let file = File::open(path).map_err(|e| format!("打开英文词典失敗: {e}"))?;
        let mmap = unsafe { Mmap::map(&file).map_err(|e| format!("Mmap 失敗: {e}"))? };

        if mmap.len() < HEADER_SIZE {
            return Err("文件太小，不是有效 en_lexicon.bin".into());
        }

        if &mmap[0..4] != MAGIC {
            return Err("魔數不匹配".into());
        }

        let version = u32::from_le_bytes(mmap[4..8].try_into().unwrap());
        if version != 1 {
            return Err(format!("不支持的字典版本: {version}"));
        }

        let count = u32::from_le_bytes(mmap[8..12].try_into().unwrap()) as usize;
        let arena_offset = u32::from_le_bytes(mmap[12..16].try_into().unwrap()) as usize;
        let arena_len = u32::from_le_bytes(mmap[16..20].try_into().unwrap()) as usize;

        if mmap.len() < arena_offset + arena_len || arena_offset < HEADER_SIZE + count * ENTRY_SIZE {
            return Err("文件長度結構損壞".into());
        }

        Ok(Self {
            mmap,
            count,
            arena_offset,
            arena_len,
        })
    }

    #[inline]
    fn get_code_str(&self, idx: usize) -> &str {
        let base = HEADER_SIZE + idx * ENTRY_SIZE;
        let c_off = u32::from_le_bytes(self.mmap[base..base + 4].try_into().unwrap()) as usize;
        let c_len_freq = u32::from_le_bytes(self.mmap[base + 4..base + 8].try_into().unwrap());
        let c_len = (c_len_freq & 0xFFFF) as usize;
        let abs_start = self.arena_offset + c_off;
        let slice = &self.mmap[abs_start..abs_start + c_len];
        std::str::from_utf8(slice).unwrap_or("")
    }

    pub fn get_candidate(&self, idx: usize) -> EnglishCandidateDto {
        let base = HEADER_SIZE + idx * ENTRY_SIZE;
        let c_len_freq = u32::from_le_bytes(self.mmap[base + 4..base + 8].try_into().unwrap());
        let freq = (c_len_freq >> 16) & 0xFFFF;

        let w_off = u32::from_le_bytes(self.mmap[base + 8..base + 12].try_into().unwrap()) as usize;
        let w_len = (u32::from_le_bytes(self.mmap[base + 12..base + 16].try_into().unwrap()) & 0xFFFF) as usize;

        let zh_off = u32::from_le_bytes(self.mmap[base + 16..base + 20].try_into().unwrap()) as usize;
        let zh_len = (u32::from_le_bytes(self.mmap[base + 20..base + 24].try_into().unwrap()) & 0xFFFF) as usize;

        let ja_off = u32::from_le_bytes(self.mmap[base + 24..base + 28].try_into().unwrap()) as usize;
        let ja_len = (u32::from_le_bytes(self.mmap[base + 28..base + 32].try_into().unwrap()) & 0xFFFF) as usize;

        let r_off = u32::from_le_bytes(self.mmap[base + 32..base + 36].try_into().unwrap()) as usize;
        let r_len = (u32::from_le_bytes(self.mmap[base + 36..base + 40].try_into().unwrap()) & 0xFFFF) as usize;

        let p_off = u32::from_le_bytes(self.mmap[base + 40..base + 44].try_into().unwrap()) as usize;
        let p_len = (u32::from_le_bytes(self.mmap[base + 44..base + 48].try_into().unwrap()) & 0xFFFF) as usize;

        let arena = &self.mmap[self.arena_offset..self.arena_offset + self.arena_len];

        let word = std::str::from_utf8(&arena[w_off..w_off + w_len]).unwrap_or("").to_string();
        let zh = if zh_len > 0 { std::str::from_utf8(&arena[zh_off..zh_off + zh_len]).unwrap_or("").to_string() } else { String::new() };
        let ja = if ja_len > 0 { std::str::from_utf8(&arena[ja_off..ja_off + ja_len]).unwrap_or("").to_string() } else { String::new() };
        let reading = if r_len > 0 { std::str::from_utf8(&arena[r_off..r_off + r_len]).unwrap_or("").to_string() } else { String::new() };
        let pos = if p_len > 0 { std::str::from_utf8(&arena[p_off..p_off + p_len]).unwrap_or("").to_string() } else { String::new() };

        EnglishCandidateDto {
            word,
            freq,
            zh,
            ja,
            reading,
            pos,
        }
    }

    /// 二分搜尋前綴並依詞頻排序，最多取 limit 條結果
    pub fn query_prefix(&self, prefix: &str, limit: usize) -> Vec<EnglishCandidateDto> {
        let prefix_lower = prefix.to_ascii_lowercase();
        if prefix_lower.is_empty() || limit == 0 {
            return Vec::new();
        }

        // 二分定位第一個 >= prefix_lower 的下標
        let mut low = 0;
        let mut high = self.count.saturating_sub(1);
        let mut start_idx = self.count;

        while low <= high {
            let mid = low + (high - low) / 2;
            let code = self.get_code_str(mid);
            if code >= prefix_lower.as_str() {
                start_idx = mid;
                if mid == 0 { break; }
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        let mut hits = Vec::new();
        let scan_limit = (start_idx + 200).min(self.count);

        for idx in start_idx..scan_limit {
            let code = self.get_code_str(idx);
            if !code.starts_with(&prefix_lower) {
                break;
            }
            hits.push(self.get_candidate(idx));
        }

        // 排序規則：
        // 1. 恰好完全匹配排第一（例如打 devel 精確匹配 devel，打 run 精確匹配 run）
        // 2. 詞頻高的排在前面
        hits.sort_by(|a, b| {
            let a_exact = a.word.eq_ignore_ascii_case(&prefix_lower);
            let b_exact = b.word.eq_ignore_ascii_case(&prefix_lower);
            b_exact.cmp(&a_exact).then_with(|| b.freq.cmp(&a.freq))
        });

        hits.truncate(limit);
        hits
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_open_and_query() {
        let path = "../../assets/lexicon/en_lexicon.bin";
        if std::path::Path::new(path).exists() {
            let lex = EnglishLexicon::open(path).expect("open lexicon");
            let hits = lex.query_prefix("devel", 5);
            assert!(!hits.is_empty());
            println!("Hits for 'devel': {:?}", hits);
            let hits_run = lex.query_prefix("run", 5);
            assert_eq!(hits_run[0].word.to_ascii_lowercase(), "run");
        }
    }
}


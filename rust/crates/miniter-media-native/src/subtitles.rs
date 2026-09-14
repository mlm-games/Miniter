//! Lenient subtitle-cue parsing shared by every export path.
//!
//! Single home for SRT block parsing (previously copied in three places)
//! and the ASS `Dialogue:` splitter. Supported input:
//!
//! - BOM stripped, CRLF/CR normalized, blank-line block split.
//! - Optional numeric sequence line; `-->` line found by scan, with VTT
//!   cue settings after the end timestamp ignored.
//! - Timestamps `hh:mm:ss,ms`, `hh:mm:ss.ms`, `mm:ss.ms`, bare `ss.ms`.
//! - ASS/SSA via `reassarus-core`, with the `Dialogue:` splitter as
//!   fallback when the script fails to parse.

use crate::export_shared::strip_ass_override_tags;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedSubtitleCue {
    pub start_us: i64,
    pub end_us: i64,
    pub text: String,
}

/// Parse SRT (or VTT, which degrades gracefully) content into cues.
pub fn parse_srt_content(content: &str) -> Vec<ParsedSubtitleCue> {
    let normalized = content
        .strip_prefix('\u{FEFF}')
        .unwrap_or(content)
        .replace("\r\n", "\n")
        .replace('\r', "\n");

    let mut cues = Vec::new();
    for block in normalized.split("\n\n") {
        let lines: Vec<&str> = block.lines().collect();
        if lines.iter().all(|l| l.trim().is_empty()) {
            continue;
        }
        let mut cursor = 0usize;
        if !lines.is_empty() && lines[cursor].trim().chars().all(|c| c.is_ascii_digit()) {
            cursor += 1;
        }
        let mut timing: Option<(i64, i64)> = None;
        while cursor < lines.len() {
            let line = lines[cursor].trim();
            if line.contains("-->") {
                let mut parts = line.split("-->");
                let start = parts.next().map(str::trim).unwrap_or("");
                let end = parts
                    .next()
                    .map(|e| e.split_whitespace().next().unwrap_or("").trim())
                    .unwrap_or("");
                match (parse_timestamp_us(start), parse_timestamp_us(end)) {
                    (Some(s), Some(e)) => {
                        timing = Some((s, e));
                        cursor += 1;
                        break;
                    }
                    _ => cursor += 1,
                }
            } else {
                cursor += 1;
            }
        }
        let Some((start_us, end_us)) = timing else {
            continue;
        };
        if end_us <= start_us || cursor >= lines.len() {
            continue;
        }
        let text = lines[cursor..].join("\n").trim().to_string();
        if text.is_empty() {
            continue;
        }
        cues.push(ParsedSubtitleCue {
            start_us,
            end_us,
            text,
        });
    }

    cues.sort_by_key(|c| c.start_us);
    cues
}

/// Parse a single SRT/VTT timestamp into microseconds.
/// Accepts `hh:mm:ss,ms`, `hh:mm:ss.ms`, `mm:ss.ms`, and bare seconds.
pub fn parse_timestamp_us(value: &str) -> Option<i64> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    let (time_part, frac_part) = match trimmed.split_once([',', '.']) {
        Some((t, f)) => (t, f.trim()),
        None => {
            return parse_hms_to_us(trimmed, None);
        }
    };
    if frac_part.is_empty() || !frac_part.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let mut ms_str = frac_part.to_string();
    while ms_str.len() < 3 {
        ms_str.push('0');
    }
    ms_str.truncate(3);
    let ms: i64 = ms_str.parse().ok()?;
    parse_hms_to_us(time_part, Some(ms))
}

fn parse_hms_to_us(time_part: &str, ms: Option<i64>) -> Option<i64> {
    let fields: Vec<&str> = time_part.split(':').collect();
    let (h, m, s) = match fields.as_slice() {
        [s] => (0i64, 0i64, s.parse::<i64>().ok()?),
        [m, s] => (0i64, m.parse::<i64>().ok()?, s.parse::<i64>().ok()?),
        [h, m, s] => (
            h.parse::<i64>().ok()?,
            m.parse::<i64>().ok()?,
            s.parse::<i64>().ok()?,
        ),
        _ => return None,
    };
    if h < 0 || !(0..60).contains(&m) || !(0..60).contains(&s) {
        return None;
    }
    let ms = ms.unwrap_or(0);
    Some(((h * 3600 + m * 60 + s) * 1_000 + ms) * 1_000)
}

/// Parse ASS/SSA content into plain-text cues. Tries `reassarus-core`
/// first, then the `Dialogue:` splitter below, so a malformed header
/// never drops every cue. Same path on every target.
pub fn parse_ass_content(content: &str, preserve_styles: bool) -> Vec<ParsedSubtitleCue> {
    match parse_ass_with_reassarus(content, preserve_styles) {
        Some(cues) if !cues.is_empty() => return cues,
        _ => {}
    }
    parse_ass_dialogue_lines(content)
}

fn parse_ass_with_reassarus(
    content: &str,
    preserve_styles: bool,
) -> Option<Vec<ParsedSubtitleCue>> {
    let script = reassarus_core::Script::parse(content).ok()?;
    let mut cues = Vec::new();
    for section in script.sections() {
        let reassarus_core::Section::Events(events) = section else {
            continue;
        };
        for event in events {
            if !event.is_dialogue() {
                continue;
            }
            let start_cs = event.start_time_cs().ok()?;
            let end_cs = event.end_time_cs().ok()?;
            if end_cs <= start_cs {
                continue;
            }
            let mut text = event
                .text
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ");
            if !preserve_styles {
                text = strip_ass_override_tags(&text);
            }
            let text = text.trim().to_string();
            if text.is_empty() {
                continue;
            }
            cues.push(ParsedSubtitleCue {
                start_us: start_cs as i64 * 10_000,
                end_us: end_cs as i64 * 10_000,
                text,
            });
        }
    }
    cues.sort_by_key(|c| c.start_us);
    Some(cues)
}

/// `Dialogue:` line parser, used when the full ASS parse rejects the file.
fn parse_ass_dialogue_lines(content: &str) -> Vec<ParsedSubtitleCue> {
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");
    let mut cues = Vec::new();
    for line in normalized.lines() {
        let trimmed = line.trim();
        let Some(payload) = trimmed.strip_prefix("Dialogue:") else {
            continue;
        };
        let fields: Vec<&str> = payload.splitn(10, ',').collect();
        if fields.len() < 10 {
            continue;
        }
        let (Some(start_us), Some(end_us)) = (
            parse_ass_timestamp_us(fields[1].trim()),
            parse_ass_timestamp_us(fields[2].trim()),
        ) else {
            continue;
        };
        if end_us <= start_us {
            continue;
        }
        let text = normalize_ass_text(fields[9]).trim().to_string();
        if text.is_empty() {
            continue;
        }
        cues.push(ParsedSubtitleCue {
            start_us,
            end_us,
            text,
        });
    }
    cues.sort_by_key(|c| c.start_us);
    cues
}

fn normalize_ass_text(input: &str) -> String {
    let text = input
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ");
    strip_ass_override_tags(&text)
}

/// ASS timestamps are `h:mm:ss.cc` (centiseconds, 1-2+ digits accepted).
fn parse_ass_timestamp_us(value: &str) -> Option<i64> {
    let mut hms = value.trim().split(':');
    let h: i64 = hms.next()?.parse().ok()?;
    let m: i64 = hms.next()?.parse().ok()?;
    let sec_frac = hms.next()?;
    if hms.next().is_some() || h < 0 {
        return None;
    }
    let mut sf = sec_frac.split('.');
    let s: i64 = sf.next()?.parse().ok()?;
    let cs_str = sf.next()?;
    if sf.next().is_some() || cs_str.is_empty() {
        return None;
    }
    if !cs_str.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let cs: i64 = if cs_str.len() == 1 {
        cs_str.parse::<i64>().ok()?.saturating_mul(10)
    } else {
        cs_str.get(0..2)?.parse::<i64>().ok()?
    };
    if !(0..60).contains(&m) || !(0..60).contains(&s) {
        return None;
    }
    Some((h * 3600 + m * 60 + s) * 1_000_000 + cs * 10_000)
}

/// Find the cue text active at `timestamp_us` (first match wins).
pub fn cue_text_at(cues: &[ParsedSubtitleCue], timestamp_us: i64) -> Option<String> {
    cues.iter().find_map(|cue| {
        if timestamp_us >= cue.start_us && timestamp_us < cue.end_us {
            Some(cue.text.clone())
        } else {
            None
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn srt_basic_cues_parse() {
        let content =
            "1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n";
        let cues = parse_srt_content(content);
        assert_eq!(cues.len(), 2);
        assert_eq!(cues[0].start_us, 1_000_000);
        assert_eq!(cues[0].end_us, 2_500_000);
        assert_eq!(cues[0].text, "Hello");
    }

    #[test]
    fn srt_tolerates_bom_crlf_and_missing_sequence() {
        let content = "\u{FEFF}WEBVTT-ish\r\n\r\n00:00.000 --> 00:01.500 align:center\nHi\r\n\r\n";
        let cues = parse_srt_content(content);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].text, "Hi");
        assert_eq!(cues[0].end_us, 1_500_000);
    }

    #[test]
    fn srt_cue_identifier_line_is_skipped() {
        let content = "cue-1\n00:00:01.000 --> 00:00:02.000\nText\n";
        let cues = parse_srt_content(content);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].text, "Text");
    }

    #[test]
    fn srt_multiline_text_preserved() {
        let content = "1\n00:00:01,000 --> 00:00:02,000\nLine one\nLine two\n";
        let cues = parse_srt_content(content);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].text, "Line one\nLine two");
    }

    #[test]
    fn srt_rejects_bad_timing_and_empty_text() {
        let content = "1\nnot a timing line\nText\n\n2\n00:00:03,000 --> 00:00:02,000\nBackwards\n\n3\n00:00:05,000 --> 00:00:06,000\n\n";
        assert!(parse_srt_content(content).is_empty());
    }

    #[test]
    fn timestamp_formats() {
        assert_eq!(parse_timestamp_us("00:00:01,000"), Some(1_000_000));
        assert_eq!(parse_timestamp_us("00:00:01.5"), Some(1_500_000));
        assert_eq!(parse_timestamp_us("01:02.250"), Some(62_250_000));
        assert_eq!(parse_timestamp_us("1:02:03,4567"), Some(3_723_456_000));
        assert_eq!(parse_timestamp_us("00:00:01"), Some(1_000_000));
        assert_eq!(parse_timestamp_us(""), None);
        assert_eq!(parse_timestamp_us("nope"), None);
        assert_eq!(parse_timestamp_us("00:77:01,000"), None);
    }

    #[test]
    fn ass_dialogue_lines_parse_with_style_strip() {
        let content =
            "[Events]\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\b1}Hi\\Nthere\n";
        let cues = parse_ass_dialogue_lines(content);
        assert_eq!(cues.len(), 1);
        assert_eq!(cues[0].start_us, 1_000_000);
        assert_eq!(cues[0].end_us, 2_500_000);
        assert_eq!(cues[0].text, "Hi\nthere");
    }

    #[test]
    fn ass_timestamp_centiseconds() {
        assert_eq!(parse_ass_timestamp_us("0:00:01.00"), Some(1_000_000));
        assert_eq!(parse_ass_timestamp_us("0:00:01.5"), Some(1_500_000));
        assert_eq!(parse_ass_timestamp_us("1:02:03.45"), Some(3_723_450_000));
        assert_eq!(parse_ass_timestamp_us("bad"), None);
    }

    #[test]
    fn cue_text_at_first_match() {
        let cues = vec![
            ParsedSubtitleCue {
                start_us: 0,
                end_us: 1_000_000,
                text: "A".into(),
            },
            ParsedSubtitleCue {
                start_us: 2_000_000,
                end_us: 3_000_000,
                text: "B".into(),
            },
        ];
        assert_eq!(cue_text_at(&cues, 500_000).as_deref(), Some("A"));
        assert_eq!(cue_text_at(&cues, 1_500_000), None);
        assert_eq!(cue_text_at(&cues, 2_500_000).as_deref(), Some("B"));
    }
}

use base64::Engine as _;
use js_sys::{Array, Uint8Array};
use miniter_domain::project::Project;
use miniter_media_native::frame::RgbaFrame;
use miniter_usecases::commands::EditCommand;
use miniter_usecases::reducer::{self, EditorState};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Cursor;
use std::sync::{LazyLock, Mutex};
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{Blob, BlobPropertyBag, HtmlAnchorElement, Url};

use crate::{EXPORT_CANCELLED, EXPORT_PROGRESS};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WasmExportPayload {
    ok: bool,
    bytes_base64: String,
    file_name: String,
    mime_type: String,
}

fn run_wasm_export(
    project_json: String,
    output_path: String,
) -> Result<WasmExportPayload, JsValue> {
    EXPORT_CANCELLED.store(false, std::sync::atomic::Ordering::SeqCst);
    EXPORT_PROGRESS.store(0, std::sync::atomic::Ordering::SeqCst);

    let mut project = Project::from_json(&project_json)
        .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))?;
    project.export_profile.output_path = output_path.clone();

    let files_map: HashMap<String, Vec<u8>> = REGISTERED_FILES
        .lock()
        .map_err(|_| JsValue::from_str("Lock poisoned"))?
        .iter()
        .map(|(path, file)| (path.clone(), file.bytes.clone()))
        .collect();

    let artifact = crate::wasm_export::export_project_to_bytes(
        &project,
        &output_path,
        &files_map,
        || EXPORT_CANCELLED.load(std::sync::atomic::Ordering::Relaxed),
        |pct| EXPORT_PROGRESS.store(pct, std::sync::atomic::Ordering::SeqCst),
    )
    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;

    Ok(WasmExportPayload {
        ok: true,
        bytes_base64: base64::engine::general_purpose::STANDARD.encode(artifact.bytes),
        file_name: artifact.file_name,
        mime_type: artifact.mime_type,
    })
}

fn payload_to_blob_url(payload: &WasmExportPayload) -> Result<String, JsValue> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&payload.bytes_base64)
        .map_err(|e| JsValue::from_str(&format!("Base64 decode failed: {e}")))?;

    let data = Uint8Array::from(bytes.as_slice());
    let parts = Array::new();
    parts.push(&data);

    let bag = BlobPropertyBag::new();
    bag.set_type(&payload.mime_type);
    let blob =
        Blob::new_with_u8_array_sequence_and_options(&parts, &bag).map_err(|e| JsValue::from(e))?;

    Url::create_object_url_with_blob(&blob).map_err(|e| JsValue::from(e))
}

fn trigger_download(blob_url: &str, file_name: &str) -> Result<(), JsValue> {
    let window = web_sys::window().ok_or_else(|| JsValue::from_str("Missing window"))?;
    let document = window
        .document()
        .ok_or_else(|| JsValue::from_str("Missing document"))?;

    let anchor = document
        .create_element("a")
        .map_err(|e| JsValue::from(e))?
        .dyn_into::<HtmlAnchorElement>()
        .map_err(|_| JsValue::from_str("Failed to create anchor element"))?;

    anchor.set_href(blob_url);
    anchor.set_download(file_name);

    let body = document
        .body()
        .ok_or_else(|| JsValue::from_str("Missing document body"))?;

    body.append_child(&anchor).map_err(|e| JsValue::from(e))?;
    anchor.click();
    let _ = body.remove_child(&anchor);

    Ok(())
}

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}

#[wasm_bindgen]
pub struct WasmEditorHandle {
    inner: Mutex<EditorState>,
}

#[derive(Clone)]
struct RegisteredFile {
    bytes: Vec<u8>,
    extension_hint: Option<String>,
}

static REGISTERED_FILES: LazyLock<Mutex<HashMap<String, RegisteredFile>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn get_registered_file(path: &str) -> Option<RegisteredFile> {
    REGISTERED_FILES
        .lock()
        .ok()
        .and_then(|files| files.get(path).cloned())
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WasmVideoProbeResult {
    duration_us: i64,
    width: u32,
    height: u32,
    frame_rate: f64,
    video_codec: String,
    has_audio: bool,
    audio_sample_rate: u32,
    audio_channels: u32,
    video_bitrate: u32,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WasmFrameData {
    width: u32,
    height: u32,
    rgba_base64: String,
    pts_us: i64,
}

#[wasm_bindgen]
impl WasmEditorHandle {
    #[wasm_bindgen(constructor)]
    pub fn new(name: String) -> WasmEditorHandle {
        WasmEditorHandle {
            inner: Mutex::new(EditorState::new(Project::new(name))),
        }
    }

    #[wasm_bindgen(js_name = fromJson)]
    pub fn from_json(json: String) -> Result<WasmEditorHandle, JsValue> {
        let project = Project::from_json(&json)
            .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))?;

        Ok(WasmEditorHandle {
            inner: Mutex::new(EditorState::new(project)),
        })
    }

    #[wasm_bindgen(js_name = toJson)]
    pub fn to_json(&self) -> Result<String, JsValue> {
        let state = self
            .inner
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;

        state
            .project
            .to_json()
            .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
    }

    pub fn dispatch(&self, command_json: String) -> Result<bool, JsValue> {
        let cmd: EditCommand = serde_json::from_str(&command_json)
            .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))?;

        let mut state = self
            .inner
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;

        reducer::dispatch(&mut state, cmd)
            .map_err(|e| JsValue::from_str(&format!("Apply error: {e}")))?;

        Ok(true)
    }

    pub fn undo(&self) -> Result<bool, JsValue> {
        let mut state = self
            .inner
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;

        reducer::undo(&mut state).map_err(|e| JsValue::from_str(&format!("Apply error: {e}")))?;

        Ok(state.history.can_undo())
    }

    pub fn redo(&self) -> Result<bool, JsValue> {
        let mut state = self
            .inner
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;

        reducer::redo(&mut state).map_err(|e| JsValue::from_str(&format!("Apply error: {e}")))?;

        Ok(state.history.can_redo())
    }

    #[wasm_bindgen(js_name = canUndo)]
    pub fn can_undo(&self) -> bool {
        self.inner
            .lock()
            .map(|s| s.history.can_undo())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = canRedo)]
    pub fn can_redo(&self) -> bool {
        self.inner
            .lock()
            .map(|s| s.history.can_redo())
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = playheadUs)]
    pub fn playhead_us(&self) -> f64 {
        self.inner
            .lock()
            .map(|s| s.playhead.as_micros() as f64)
            .unwrap_or(0.0)
    }

    #[wasm_bindgen(js_name = setPlayheadUs)]
    pub fn set_playhead_us(&self, us: f64) {
        if let Ok(mut state) = self.inner.lock() {
            state.playhead = miniter_domain::time::Timestamp::from_micros(us as i64);
        }
    }

    #[wasm_bindgen(js_name = renderPlanAtPlayhead)]
    pub fn render_plan_at_playhead(&self, width: u32, height: u32) -> Result<String, JsValue> {
        let state = self
            .inner
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;

        let plan = miniter_render_plan::render_graph::plan_frame(
            &state.project.timeline,
            state.playhead,
            width,
            height,
            state.project.export_profile.subtitle_mode,
        );

        Ok(format!("{plan:#?}"))
    }

    #[wasm_bindgen(js_name = durationUs)]
    pub fn duration_us(&self) -> f64 {
        self.inner
            .lock()
            .map(|s| s.project.timeline.duration_end().as_micros() as f64)
            .unwrap_or(0.0)
    }
}

#[wasm_bindgen(js_name = probeAudio)]
pub fn probe_audio(path: String) -> Result<String, JsValue> {
    let meta = if let Some(file) = get_registered_file(&path) {
        miniter_audio::probe::probe_audio_bytes(&file.bytes, file.extension_hint.as_deref())
    } else {
        miniter_audio::probe::probe_audio(std::path::Path::new(&path))
    }
    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;

    let value = serde_json::json!({
        "codec": meta.codec,
        "sampleRate": meta.sample_rate,
        "channels": meta.channels,
        "durationUs": meta.duration_us,
    });

    serde_json::to_string(&value)
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

#[wasm_bindgen(js_name = extractWaveform)]
pub fn extract_waveform(path: String, buckets: u32) -> Result<String, JsValue> {
    let data = if let Some(file) = get_registered_file(&path) {
        miniter_audio::waveform::extract_waveform_bytes(
            &file.bytes,
            buckets as usize,
            file.extension_hint.as_deref(),
        )
    } else {
        miniter_audio::waveform::extract_waveform(std::path::Path::new(&path), buckets as usize)
    }
    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;

    let json: Vec<[f32; 2]> = data.peaks.iter().map(|(lo, hi)| [*lo, *hi]).collect();

    serde_json::to_string(&json)
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

#[wasm_bindgen(js_name = probeVideo)]
pub fn probe_video(path: String) -> Result<String, JsValue> {
    let info = if let Some(file) = get_registered_file(&path) {
        miniter_media_native::probe::probe_media_bytes(&file.bytes, file.extension_hint.as_deref())
    } else {
        miniter_media_native::probe::probe_media(std::path::Path::new(&path))
    }
    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;

    let vs = info.video_streams.first();
    let aus = info.audio_streams.first();

    let result = WasmVideoProbeResult {
        duration_us: info.duration_us.unwrap_or(0),
        width: vs.map(|v| v.width).unwrap_or(0),
        height: vs.map(|v| v.height).unwrap_or(0),
        frame_rate: vs.map(|v| v.frame_rate).unwrap_or(30.0),
        video_codec: vs.map(|v| v.codec.clone()).unwrap_or_default(),
        has_audio: !info.audio_streams.is_empty(),
        audio_sample_rate: aus.map(|a| a.sample_rate).unwrap_or(0),
        audio_channels: aus.map(|a| a.channels).unwrap_or(0),
        video_bitrate: vs.map(|v| v.bitrate).unwrap_or(0),
    };

    serde_json::to_string(&result)
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

fn encode_frame(frame: RgbaFrame) -> WasmFrameData {
    WasmFrameData {
        width: frame.width,
        height: frame.height,
        rgba_base64: base64::engine::general_purpose::STANDARD.encode(frame.data),
        pts_us: frame.pts_us,
    }
}

#[wasm_bindgen(js_name = registerFile)]
pub fn register_file(path: String, bytes_base64: String, extension: Option<String>) -> bool {
    let decoded = match base64::engine::general_purpose::STANDARD.decode(bytes_base64) {
        Ok(bytes) => bytes,
        Err(_) => return false,
    };

    if let Ok(mut files) = REGISTERED_FILES.lock() {
        files.insert(
            path,
            RegisteredFile {
                bytes: decoded,
                extension_hint: extension
                    .map(|e| e.trim().trim_start_matches('.').to_lowercase())
                    .filter(|e| !e.is_empty()),
            },
        );
        return true;
    }

    false
}

#[wasm_bindgen(js_name = extractThumbnail)]
pub fn extract_thumbnail(path: String, target_us: f64) -> Result<String, JsValue> {
    let frame = if let Some(file) = get_registered_file(&path) {
        let size = file.bytes.len() as u64;
        let reader = Cursor::new(file.bytes);
        let mut session =
            miniter_media_native::decoder::VideoDecodeSession::from_reader(reader, size)
                .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;
        let mut last_frame: Option<RgbaFrame> = None;

        loop {
            match session
                .next_frame()
                .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?
            {
                Some(frame) => {
                    if frame.pts_us >= target_us as i64 {
                        break frame;
                    }
                    last_frame = Some(frame);
                }
                None => {
                    break last_frame
                        .ok_or_else(|| JsValue::from_str("Media error: No video stream"))?;
                }
            }
        }
    } else {
        miniter_media_native::thumbnailer::extract_thumbnail(
            std::path::Path::new(&path),
            target_us as i64,
        )
        .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?
    };

    serde_json::to_string(&encode_frame(frame))
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

#[wasm_bindgen(js_name = extractThumbnails)]
pub fn extract_thumbnails(path: String, count: u32, duration_us: f64) -> Result<String, JsValue> {
    let frames = if let Some(file) = get_registered_file(&path) {
        if count == 0 || duration_us <= 0.0 {
            Vec::new()
        } else {
            let size = file.bytes.len() as u64;
            let reader = Cursor::new(file.bytes);
            let mut session =
                miniter_media_native::decoder::VideoDecodeSession::from_reader(reader, size)
                    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;

            let duration = duration_us as i64;
            let interval_us = duration / count as i64;
            let targets: Vec<i64> = (0..count as i64).map(|idx| idx * interval_us).collect();
            let mut results = Vec::with_capacity(count as usize);
            let mut target_idx = 0usize;
            let mut last_frame: Option<RgbaFrame> = None;

            loop {
                if target_idx >= targets.len() {
                    break;
                }

                match session
                    .next_frame()
                    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?
                {
                    Some(frame) => {
                        while target_idx < targets.len() && frame.pts_us >= targets[target_idx] {
                            results.push(frame.clone());
                            target_idx += 1;
                        }
                        last_frame = Some(frame);
                    }
                    None => {
                        if let Some(frame) = last_frame {
                            while results.len() < count as usize {
                                results.push(frame.clone());
                            }
                        }
                        break;
                    }
                }
            }

            results
        }
    } else {
        miniter_media_native::thumbnailer::extract_thumbnails(
            std::path::Path::new(&path),
            count as usize,
            duration_us as i64,
        )
        .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?
    };

    let mapped: Vec<WasmFrameData> = frames.into_iter().map(encode_frame).collect();

    serde_json::to_string(&mapped)
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

#[wasm_bindgen(js_name = exportProjectJson)]
pub fn export_project_json(project_json: String, output_path: String) -> Result<bool, JsValue> {
    let payload = run_wasm_export(project_json, output_path)?;
    let url = payload_to_blob_url(&payload)?;

    let download_result = trigger_download(&url, &payload.file_name);
    let revoke_result = Url::revoke_object_url(&url).map_err(|e| JsValue::from(e));

    download_result?;
    revoke_result?;
    Ok(true)
}

#[wasm_bindgen(js_name = exportProgress)]
pub fn export_progress() -> u32 {
    EXPORT_PROGRESS.load(std::sync::atomic::Ordering::SeqCst)
}

#[wasm_bindgen(js_name = cancelExport)]
pub fn cancel_export() {
    EXPORT_CANCELLED.store(true, std::sync::atomic::Ordering::SeqCst);
}

#[wasm_bindgen(js_name = exportProjectBlob)]
pub fn export_project_blob(project_json: String, output_path: String) -> Result<String, JsValue> {
    let payload = run_wasm_export(project_json, output_path)?;
    serde_json::to_string(&payload)
        .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
}

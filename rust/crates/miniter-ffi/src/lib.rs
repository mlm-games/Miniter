use miniter_domain::project::Project;
use miniter_usecases::commands::EditCommand;
use miniter_usecases::reducer::{self, EditorState};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

uniffi::setup_scaffolding!();

static EXPORT_CANCELLED: AtomicBool = AtomicBool::new(false);
static EXPORT_PROGRESS: AtomicU32 = AtomicU32::new(0);

struct EditorStateHandle(Mutex<EditorState>);

impl EditorStateHandle {
    fn new(name: String) -> Self {
        Self(Mutex::new(EditorState::new(Project::new(name))))
    }

    fn from_json(json: &str) -> Result<Self, String> {
        let project = Project::from_json(json).map_err(|e| e.to_string())?;
        Ok(Self(Mutex::new(EditorState::new(project))))
    }

    fn to_json(&self) -> Result<String, String> {
        self.0
            .lock()
            .map_err(|_| "Lock poisoned".to_string())
            .and_then(|state| state.project.to_json().map_err(|e| e.to_string()))
    }

    fn dispatch(&self, command_json: &str) -> Result<bool, String> {
        let cmd: EditCommand = serde_json::from_str(command_json).map_err(|e| e.to_string())?;
        let mut state = self.0.lock().map_err(|_| "Lock poisoned".to_string())?;
        reducer::dispatch(&mut state, cmd).map_err(|e| e.to_string())?;
        Ok(true)
    }

    fn undo(&self) -> Result<bool, String> {
        let mut state = self.0.lock().map_err(|_| "Lock poisoned".to_string())?;
        reducer::undo(&mut state).map_err(|e| e.to_string())?;
        Ok(state.history.can_undo())
    }

    fn redo(&self) -> Result<bool, String> {
        let mut state = self.0.lock().map_err(|_| "Lock poisoned".to_string())?;
        reducer::redo(&mut state).map_err(|e| e.to_string())?;
        Ok(state.history.can_redo())
    }

    fn can_undo(&self) -> bool {
        self.0.lock().map(|s| s.history.can_undo()).unwrap_or(false)
    }

    fn can_redo(&self) -> bool {
        self.0.lock().map(|s| s.history.can_redo()).unwrap_or(false)
    }

    fn playhead_us(&self) -> i64 {
        self.0.lock().map(|s| s.playhead.as_micros()).unwrap_or(0)
    }

    fn set_playhead_us(&self, us: i64) {
        if let Ok(mut s) = self.0.lock() {
            s.playhead = miniter_domain::time::Timestamp::from_micros(us);
        }
    }

    fn render_plan_at_playhead(&self, width: u32, height: u32) -> Result<String, String> {
        let state = self.0.lock().map_err(|_| "Lock poisoned".to_string())?;
        let plan = miniter_render_plan::render_graph::plan_frame(
            &state.project.timeline,
            state.playhead,
            width,
            height,
            state.project.export_profile.subtitle_mode,
        );
        serde_json::to_string(&plan).map_err(|e| e.to_string())
    }

    fn duration_us(&self) -> i64 {
        self.0
            .lock()
            .map(|s| s.project.timeline.duration_end().as_micros())
            .unwrap_or(0)
    }
}

#[cfg(not(target_arch = "wasm32"))]
mod native_ffi {
    use super::*;

    #[derive(uniffi::Object)]
    pub struct EditorHandle(EditorStateHandle);

    #[uniffi::export]
    impl EditorHandle {
        #[uniffi::constructor]
        pub fn new(name: String) -> Self {
            Self(EditorStateHandle::new(name))
        }

        #[uniffi::constructor]
        pub fn from_json(json: String) -> Result<Self, MiniterError> {
            EditorStateHandle::from_json(&json)
                .map(Self)
                .map_err(|e| MiniterError::Parse { detail: e })
        }

        pub fn to_json(&self) -> Result<String, MiniterError> {
            self.0
                .to_json()
                .map_err(|e| MiniterError::Serialize { detail: e })
        }

        pub fn dispatch(&self, command_json: String) -> Result<bool, MiniterError> {
            self.0
                .dispatch(&command_json)
                .map_err(|e| MiniterError::Parse { detail: e })
        }

        pub fn undo(&self) -> Result<bool, MiniterError> {
            self.0.undo().map_err(|e| MiniterError::Apply { detail: e })
        }

        pub fn redo(&self) -> Result<bool, MiniterError> {
            self.0.redo().map_err(|e| MiniterError::Apply { detail: e })
        }

        pub fn can_undo(&self) -> bool {
            self.0.can_undo()
        }

        pub fn can_redo(&self) -> bool {
            self.0.can_redo()
        }

        pub fn playhead_us(&self) -> i64 {
            self.0.playhead_us()
        }

        pub fn set_playhead_us(&self, us: i64) {
            self.0.set_playhead_us(us)
        }

        pub fn render_plan_at_playhead(
            &self,
            width: u32,
            height: u32,
        ) -> Result<String, MiniterError> {
            self.0
                .render_plan_at_playhead(width, height)
                .map_err(|e| MiniterError::Serialize { detail: e })
        }

        pub fn duration_us(&self) -> i64 {
            self.0.duration_us()
        }
    }

    #[derive(uniffi::Record)]
    pub struct VideoProbeResult {
        pub duration_us: i64,
        pub width: u32,
        pub height: u32,
        pub frame_rate: f64,
        pub video_codec: String,
        pub has_audio: bool,
        pub audio_sample_rate: u32,
        pub audio_channels: u32,
        pub video_bitrate: u32,
    }

    #[derive(uniffi::Record)]
    pub struct FrameData {
        pub width: u32,
        pub height: u32,
        pub rgba: Vec<u8>,
        pub pts_us: i64,
    }

    #[uniffi::export]
    pub fn probe_audio(path: String) -> Result<String, MiniterError> {
        let meta = miniter_audio::probe::probe_audio(std::path::Path::new(&path)).map_err(|e| {
            MiniterError::Media {
                detail: e.to_string(),
            }
        })?;
        Ok(format!("{meta:?}"))
    }

    #[uniffi::export]
    pub fn extract_waveform(path: String, buckets: u32) -> Result<String, MiniterError> {
        let data = miniter_audio::waveform::extract_waveform(
            std::path::Path::new(&path),
            buckets as usize,
        )
        .map_err(|e| MiniterError::Media {
            detail: e.to_string(),
        })?;
        let json: Vec<[f32; 2]> = data.peaks.iter().map(|(lo, hi)| [*lo, *hi]).collect();
        serde_json::to_string(&json).map_err(|e| MiniterError::Serialize {
            detail: e.to_string(),
        })
    }

    #[uniffi::export]
    pub fn probe_video(path: String) -> Result<VideoProbeResult, MiniterError> {
        let info =
            miniter_media_native::probe::probe_media(std::path::Path::new(&path)).map_err(|e| {
                MiniterError::Media {
                    detail: e.to_string(),
                }
            })?;

        let vs = info.video_streams.first();
        let aus = info.audio_streams.first();

        Ok(VideoProbeResult {
            duration_us: info.duration_us.unwrap_or(0),
            width: vs.map(|v| v.width).unwrap_or(0),
            height: vs.map(|v| v.height).unwrap_or(0),
            frame_rate: vs.map(|v| v.frame_rate).unwrap_or(30.0),
            video_codec: vs.map(|v| v.codec.clone()).unwrap_or_default(),
            has_audio: !info.audio_streams.is_empty(),
            audio_sample_rate: aus.map(|a| a.sample_rate).unwrap_or(0),
            audio_channels: aus.map(|a| a.channels).unwrap_or(0),
            video_bitrate: vs.map(|v| v.bitrate).unwrap_or(0),
        })
    }

    #[uniffi::export]
    pub fn extract_thumbnail(path: String, target_us: i64) -> Result<FrameData, MiniterError> {
        let frame = miniter_media_native::thumbnailer::extract_thumbnail(
            std::path::Path::new(&path),
            target_us,
        )
        .map_err(|e| MiniterError::Media {
            detail: e.to_string(),
        })?;

        Ok(FrameData {
            width: frame.width,
            height: frame.height,
            rgba: frame.data,
            pts_us: frame.pts_us,
        })
    }

    #[uniffi::export]
    pub fn extract_thumbnails(
        path: String,
        count: u32,
        duration_us: i64,
    ) -> Result<Vec<FrameData>, MiniterError> {
        let frames = miniter_media_native::thumbnailer::extract_thumbnails(
            std::path::Path::new(&path),
            count as usize,
            duration_us,
        )
        .map_err(|e| MiniterError::Media {
            detail: e.to_string(),
        })?;

        Ok(frames
            .into_iter()
            .map(|f| FrameData {
                width: f.width,
                height: f.height,
                rgba: f.data,
                pts_us: f.pts_us,
            })
            .collect())
    }

    #[uniffi::export]
    pub fn export_project_json(
        project_json: String,
        output_path: String,
    ) -> Result<bool, MiniterError> {
        EXPORT_CANCELLED.store(false, Ordering::SeqCst);
        EXPORT_PROGRESS.store(0, Ordering::SeqCst);

        let mut project = Project::from_json(&project_json).map_err(|e| MiniterError::Parse {
            detail: e.to_string(),
        })?;
        project.export_profile.output_path = output_path.clone();

        match miniter_media_native::export::export_project(
            &project,
            std::path::Path::new(&output_path),
            || EXPORT_CANCELLED.load(Ordering::SeqCst),
            |pct| EXPORT_PROGRESS.store(pct, Ordering::SeqCst),
        ) {
            Ok(()) => Ok(true),
            Err(miniter_media_native::export::ExportError::Cancelled) => {
                Err(MiniterError::Cancelled)
            }
            Err(e) => Err(MiniterError::Media {
                detail: e.to_string(),
            }),
        }
    }

    #[uniffi::export]
    pub fn export_progress() -> u32 {
        EXPORT_PROGRESS.load(Ordering::SeqCst)
    }

    #[uniffi::export]
    pub fn cancel_export() {
        EXPORT_CANCELLED.store(true, Ordering::SeqCst);
    }

    #[uniffi::export]
    pub fn was_export_hardware_accelerated() -> bool {
        !miniter_media_native::export::was_hardware_fallback()
    }
}

#[cfg(target_arch = "wasm32")]
mod web_ffi {
    use super::*;
    use base64::Engine as _;
    use js_sys::{Array, Uint8Array};
    use miniter_media_native::frame::RgbaFrame;
    use serde::{Deserialize, Serialize};
    use std::cell::RefCell;
    use std::collections::HashMap;
    use std::io::Cursor;
    use std::sync::LazyLock;
    use wasm_bindgen::JsCast;
    use wasm_bindgen::prelude::*;
    use web_sys::{Blob, BlobPropertyBag, HtmlAnchorElement, Url};

    #[wasm_bindgen(start)]
    pub fn init() {
        console_error_panic_hook::set_once();
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

    #[wasm_bindgen]
    pub struct WasmEditorHandle(EditorStateHandle);

    #[wasm_bindgen]
    impl WasmEditorHandle {
        #[wasm_bindgen(constructor)]
        pub fn new(name: String) -> WasmEditorHandle {
            WasmEditorHandle(EditorStateHandle::new(name))
        }

        #[wasm_bindgen(js_name = fromJson)]
        pub fn from_json(json: String) -> Result<WasmEditorHandle, JsValue> {
            EditorStateHandle::from_json(&json)
                .map(WasmEditorHandle)
                .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))
        }

        #[wasm_bindgen(js_name = toJson)]
        pub fn to_json(&self) -> Result<String, JsValue> {
            self.0
                .to_json()
                .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
        }

        pub fn dispatch(&self, command_json: String) -> Result<bool, JsValue> {
            self.0
                .dispatch(&command_json)
                .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))
        }

        pub fn undo(&self) -> Result<bool, JsValue> {
            self.0
                .undo()
                .map_err(|e| JsValue::from_str(&format!("Apply error: {e}")))
        }

        pub fn redo(&self) -> Result<bool, JsValue> {
            self.0
                .redo()
                .map_err(|e| JsValue::from_str(&format!("Apply error: {e}")))
        }

        #[wasm_bindgen(js_name = canUndo)]
        pub fn can_undo(&self) -> bool {
            self.0.can_undo()
        }

        #[wasm_bindgen(js_name = canRedo)]
        pub fn can_redo(&self) -> bool {
            self.0.can_redo()
        }

        #[wasm_bindgen(js_name = playheadUs)]
        pub fn playhead_us(&self) -> f64 {
            self.0.playhead_us() as f64
        }

        #[wasm_bindgen(js_name = setPlayheadUs)]
        pub fn set_playhead_us(&self, us: f64) {
            self.0.set_playhead_us(us as i64)
        }

        #[wasm_bindgen(js_name = renderPlanAtPlayhead)]
        pub fn render_plan_at_playhead(&self, width: u32, height: u32) -> Result<String, JsValue> {
            self.0
                .render_plan_at_playhead(width, height)
                .map_err(|e| JsValue::from_str(&format!("Serialize error: {e}")))
        }

        #[wasm_bindgen(js_name = durationUs)]
        pub fn duration_us(&self) -> f64 {
            self.0.duration_us() as f64
        }
    }

    #[derive(Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct WasmVideoProbeResult {
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
    struct WasmFrameData {
        width: u32,
        height: u32,
        rgba_base64: String,
        pts_us: i64,
    }

    #[derive(Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct WasmExportPayload {
        ok: bool,
        bytes_base64: String,
        file_name: String,
        mime_type: String,
    }

    fn encode_frame(frame: RgbaFrame) -> WasmFrameData {
        WasmFrameData {
            width: frame.width,
            height: frame.height,
            rgba_base64: base64::engine::general_purpose::STANDARD.encode(frame.data),
            pts_us: frame.pts_us,
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

        serde_json::to_string(&serde_json::json!({
            "codec": meta.codec,
            "sampleRate": meta.sample_rate,
            "channels": meta.channels,
            "durationUs": meta.duration_us,
        }))
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
        if let Some(file) = get_registered_file(&path) {
            probe_video_bytes(&file.bytes, file.extension_hint.as_deref())
        } else {
            probe_video_path(std::path::Path::new(&path))
        }
    }

    fn probe_video_path(path: &std::path::Path) -> Result<String, JsValue> {
        let info = miniter_media_native::probe::probe_media(path)
            .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;
        Ok(serde_json::to_string(&to_wasm_probe_result(&info))
            .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))?)
    }

    fn probe_video_bytes(bytes: &[u8], ext_hint: Option<&str>) -> Result<String, JsValue> {
        let info = miniter_media_native::probe::probe_media_bytes(bytes, ext_hint)
            .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;
        Ok(serde_json::to_string(&to_wasm_probe_result(&info))
            .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))?)
    }

    fn to_wasm_probe_result(info: &miniter_media_native::MediaInfo) -> WasmVideoProbeResult {
        let vs = info.video_streams.first();
        let aus = info.audio_streams.first();
        WasmVideoProbeResult {
            duration_us: info.duration_us.unwrap_or(0),
            width: vs.map(|v| v.width).unwrap_or(0),
            height: vs.map(|v| v.height).unwrap_or(0),
            frame_rate: vs.map(|v| v.frame_rate).unwrap_or(30.0),
            video_codec: vs.map(|v| v.codec.clone()).unwrap_or_default(),
            has_audio: !info.audio_streams.is_empty(),
            audio_sample_rate: aus.map(|a| a.sample_rate).unwrap_or(0),
            audio_channels: aus.map(|a| a.channels).unwrap_or(0),
            video_bitrate: vs.map(|v| v.bitrate).unwrap_or(0),
        }
    }

    async fn yield_to_js() {
        wasm_bindgen_futures::JsFuture::from(js_sys::Promise::new(
            &mut |resolve: js_sys::Function, _reject: js_sys::Function| {
                web_sys::window()
                    .unwrap_throw()
                    .set_timeout_with_callback_and_timeout_and_arguments_0(&resolve, 0)
                    .unwrap_throw();
            },
        ))
        .await
        .unwrap_throw();
    }

    #[wasm_bindgen(js_name = extractThumbnail)]
    pub async fn extract_thumbnail(path: String, target_us: f64) -> Result<String, JsValue> {
        let frame = if let Some(file) = get_registered_file(&path) {
            let size = file.bytes.len() as u64;
            let reader = Cursor::new(file.bytes);
            let mut session =
                miniter_media_native::decoder::VideoDecodeSession::from_reader(reader, size, false)
                    .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?;
            let mut last_frame: Option<RgbaFrame> = None;
            loop {
                yield_to_js().await;
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
    pub async fn extract_thumbnails(
        path: String,
        count: u32,
        duration_us: f64,
    ) -> Result<String, JsValue> {
        let frames = if let Some(file) = get_registered_file(&path) {
            if count == 0 || duration_us <= 0.0 {
                Vec::new()
            } else {
                let size = file.bytes.len() as u64;
                let reader = Cursor::new(file.bytes);
                let mut session = miniter_media_native::decoder::VideoDecodeSession::from_reader(
                    reader, size, false,
                )
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
                    yield_to_js().await;
                    match session
                        .next_frame()
                        .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))?
                    {
                        Some(frame) => {
                            while target_idx < targets.len() && frame.pts_us >= targets[target_idx]
                            {
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

    fn run_wasm_export(
        project_json: String,
        output_path: String,
    ) -> Result<miniter_media_native::wasm_export::WasmExportArtifact, JsValue> {
        EXPORT_CANCELLED.store(false, Ordering::SeqCst);
        EXPORT_PROGRESS.store(0, Ordering::SeqCst);

        let mut project = Project::from_json(&project_json)
            .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))?;
        project.export_profile.output_path = output_path.clone();

        let files_map: HashMap<String, Vec<u8>> = REGISTERED_FILES
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?
            .iter()
            .map(|(path, file)| (path.clone(), file.bytes.clone()))
            .collect();

        miniter_media_native::wasm_export::export_project_to_bytes(
            &project,
            &output_path,
            &files_map,
            || EXPORT_CANCELLED.load(Ordering::Relaxed),
            |pct| EXPORT_PROGRESS.store(pct, Ordering::SeqCst),
        )
        .map_err(|e| JsValue::from_str(&format!("Media error: {e}")))
    }

    fn trigger_download(blob_url: &str, file_name: &str) -> Result<(), JsValue> {
        let window = web_sys::window().ok_or_else(|| JsValue::from_str("Missing window"))?;
        let document = window
            .document()
            .ok_or_else(|| JsValue::from_str("Missing document"))?;
        let body = document
            .body()
            .ok_or_else(|| JsValue::from_str("Missing body"))?;

        let a = document.create_element("a").map_err(|e| JsValue::from(e))?;
        let a = a.dyn_into::<HtmlAnchorElement>()?;
        a.set_href(blob_url);
        a.set_download(file_name);
        a.style().set_property("display", "none")?;
        body.append_child(&a)?;
        a.click();
        body.remove_child(&a)?;
        Ok(())
    }

    #[wasm_bindgen(js_name = exportProjectJson)]
    pub fn export_project_json(project_json: String, output_path: String) -> Result<bool, JsValue> {
        let artifact = run_wasm_export(project_json, output_path)?;
        let data = Uint8Array::from(artifact.bytes.as_slice());
        let parts = Array::new();
        parts.push(&data);
        let bag = BlobPropertyBag::new();
        bag.set_type(&artifact.mime_type);
        let blob = Blob::new_with_u8_array_sequence_and_options(&parts, &bag)
            .map_err(|e| JsValue::from(e))?;
        let url = Url::create_object_url_with_blob(&blob).map_err(|e| JsValue::from(e))?;
        trigger_download(&url, &artifact.file_name)?;
        Url::revoke_object_url(&url).map_err(|e| JsValue::from(e))?;
        Ok(true)
    }

    #[wasm_bindgen(js_name = exportProjectBlob)]
    pub fn export_project_blob(
        project_json: String,
        output_path: String,
    ) -> Result<String, JsValue> {
        let artifact = run_wasm_export(project_json, output_path)?;
        let payload = WasmExportPayload {
            ok: true,
            bytes_base64: base64::engine::general_purpose::STANDARD.encode(&artifact.bytes),
            file_name: artifact.file_name,
            mime_type: artifact.mime_type,
        };
        serde_json::to_string(&payload)
            .map_err(|e| JsValue::from_str(&format!("Serialization error: {e}")))
    }

    #[wasm_bindgen(js_name = exportProgress)]
    pub fn export_progress() -> u32 {
        EXPORT_PROGRESS.load(Ordering::SeqCst)
    }

    #[wasm_bindgen(js_name = cancelExport)]
    pub fn cancel_export() {
        EXPORT_CANCELLED.store(true, Ordering::SeqCst);
    }

    fn registered_file_to_blob_url(path: &str) -> Result<String, JsValue> {
        let files = REGISTERED_FILES
            .lock()
            .map_err(|_| JsValue::from_str("Lock poisoned"))?;
        let Some(file) = files.get(path) else {
            return Err(JsValue::from_str("File not registered"));
        };
        let data = Uint8Array::from(file.bytes.as_slice());
        let parts = Array::new();
        parts.push(&data);
        let mime = file
            .extension_hint
            .as_deref()
            .map(guess_mime_type_from_extension)
            .unwrap_or("application/octet-stream");
        let bag = BlobPropertyBag::new();
        bag.set_type(mime);
        let blob = Blob::new_with_u8_array_sequence_and_options(&parts, &bag)
            .map_err(|e| JsValue::from(e))?;
        Url::create_object_url_with_blob(&blob).map_err(|e| JsValue::from(e))
    }

    fn guess_mime_type_from_extension(ext: &str) -> &'static str {
        match ext
            .trim()
            .trim_start_matches('.')
            .to_ascii_lowercase()
            .as_str()
        {
            "mp4" => "video/mp4",
            "mov" => "video/quicktime",
            "webm" => "video/webm",
            "mkv" => "video/x-matroska",
            "m4v" => "video/x-m4v",
            "avi" => "video/x-msvideo",
            "mp3" => "audio/mpeg",
            "wav" => "audio/wav",
            "ogg" => "audio/ogg",
            "flac" => "audio/flac",
            "aac" => "audio/aac",
            "m4a" => "audio/mp4",
            _ => "application/octet-stream",
        }
    }

    #[wasm_bindgen(js_name = mediaBlobUrl)]
    pub fn media_blob_url(path: String) -> Result<String, JsValue> {
        registered_file_to_blob_url(&path)
    }

    #[wasm_bindgen(js_name = revokeBlobUrl)]
    pub fn revoke_blob_url(url: String) -> Result<(), JsValue> {
        Url::revoke_object_url(&url).map_err(|e| JsValue::from(e))
    }

    /// Specific codec support is probed on the Kotlin/JS side.
    #[wasm_bindgen(js_name = isWebCodecsHardwareAccelerated)]
    pub fn is_web_codecs_hardware_accelerated() -> bool {
        true
    }

    #[wasm_bindgen]
    pub struct WasmExportSession {
        inner: RefCell<Option<miniter_media_native::wasm_export::WasmExportChunker>>,
        result: RefCell<Option<miniter_media_native::wasm_export::WasmExportArtifact>>,
    }

    #[wasm_bindgen]
    impl WasmExportSession {
        #[wasm_bindgen(constructor)]
        pub fn new(
            project_json: String,
            output_path: String,
        ) -> Result<WasmExportSession, JsValue> {
            EXPORT_CANCELLED.store(false, Ordering::SeqCst);
            EXPORT_PROGRESS.store(0, Ordering::SeqCst);

            let project = Project::from_json(&project_json)
                .map_err(|e| JsValue::from_str(&format!("Parse error: {e}")))?;

            let files_map: HashMap<String, Vec<u8>> = REGISTERED_FILES
                .lock()
                .map_err(|_| JsValue::from_str("Lock poisoned"))?
                .iter()
                .map(|(path, file)| (path.clone(), file.bytes.clone()))
                .collect();

            let chunker = miniter_media_native::wasm_export::WasmExportChunker::new(
                &project,
                &output_path,
                files_map,
            )
            .map_err(|e| JsValue::from_str(&format!("Export init failed: {e}")))?;

            Ok(WasmExportSession {
                inner: RefCell::new(Some(chunker)),
                result: RefCell::new(None),
            })
        }

        /// Process one batch of frames. Returns JSON:
        /// `{"ok":true,"done":false,"progress":50000}` or
        /// `{"ok":true,"done":true,"payload":{"ok":true,"bytesBase64":"...","fileName":"...","mimeType":"..."}}` or
        /// `{"ok":false,"error":"..."}`
        #[wasm_bindgen(js_name = processChunk)]
        pub async fn process_chunk(&self) -> JsValue {
            yield_to_js().await;

            // Check cancellation set by cancel() (avoids reentrant &mut self)
            if EXPORT_CANCELLED.load(Ordering::SeqCst) {
                *self.inner.borrow_mut() = None;
                return Self::json_string(&serde_json::json!({
                    "ok": false,
                    "error": "Export cancelled",
                }));
            }

            // Return cached result if already finished
            if let Some(ref art) = *self.result.borrow() {
                let payload = WasmExportPayload {
                    ok: true,
                    bytes_base64: base64::engine::general_purpose::STANDARD.encode(&art.bytes),
                    file_name: art.file_name.clone(),
                    mime_type: art.mime_type.clone(),
                };
                return Self::json_string(&serde_json::json!({
                    "ok": true,
                    "done": true,
                    "progress": 100_000,
                    "payload": payload,
                }));
            }

            // Process 1 frame
            let mut inner = self.inner.borrow_mut();
            let Some(ref mut chunker) = *inner else {
                return Self::json_string(&serde_json::json!({
                    "ok": false,
                    "error": "No active export session",
                }));
            };

            match chunker.process_chunk(1) {
                Ok(Some(progress)) => {
                    EXPORT_PROGRESS.store(progress, Ordering::SeqCst);
                    Self::json_string(&serde_json::json!({
                        "ok": true,
                        "done": false,
                        "progress": progress,
                    }))
                }
                Ok(None) => {
                    let chunker = inner.take().unwrap_throw();
                    drop(inner);
                    EXPORT_PROGRESS.store(100_000, Ordering::SeqCst);
                    match chunker.finish() {
                        Ok(artifact) => {
                            let payload = WasmExportPayload {
                                ok: true,
                                bytes_base64: base64::engine::general_purpose::STANDARD
                                    .encode(&artifact.bytes),
                                file_name: artifact.file_name.clone(),
                                mime_type: artifact.mime_type.clone(),
                            };
                            let json = Self::json_string(&serde_json::json!({
                                "ok": true,
                                "done": true,
                                "progress": 100_000,
                                "payload": payload,
                            }));
                            *self.result.borrow_mut() = Some(artifact);
                            json
                        }
                        Err(e) => Self::json_string(&serde_json::json!({
                            "ok": false,
                            "error": e,
                        })),
                    }
                }
                Err(e) => Self::json_string(&serde_json::json!({
                    "ok": false,
                    "error": e,
                })),
            }
        }

        fn json_string(value: &serde_json::Value) -> JsValue {
            serde_json::to_string(value)
                .map(|s| JsValue::from_str(&s))
                .unwrap_or_else(|_| {
                    JsValue::from_str(r#"{"ok":false,"error":"JSON serialization failed"}"#)
                })
        }

        pub fn cancel(&self) {
            EXPORT_CANCELLED.store(true, Ordering::SeqCst);
        }

        #[wasm_bindgen(js_name = getProgress)]
        pub fn progress(&self) -> u32 {
            if self.result.borrow().is_some() {
                100_000
            } else if let Some(ref chunker) = *self.inner.borrow() {
                chunker.progress()
            } else {
                0
            }
        }

        #[wasm_bindgen(js_name = isDone)]
        pub fn is_done(&self) -> bool {
            self.result.borrow().is_some()
                || self.inner.borrow().as_ref().map_or(true, |c| c.is_done())
        }
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MiniterError {
    #[error("Parse error: {detail}")]
    Parse { detail: String },

    #[error("Serialization error: {detail}")]
    Serialize { detail: String },

    #[error("Apply error: {detail}")]
    Apply { detail: String },

    #[error("Media error: {detail}")]
    Media { detail: String },

    #[error("Export cancelled")]
    Cancelled,

    #[error("Lock poisoned")]
    LockPoisoned,
}

#[cfg(not(target_arch = "wasm32"))]
pub use native_ffi::*;

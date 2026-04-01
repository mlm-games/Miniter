use miniter_domain::project::Project;
use miniter_usecases::commands::EditCommand;
use miniter_usecases::reducer::{self, EditorState};
use std::sync::Mutex;

uniffi::setup_scaffolding!();

#[derive(uniffi::Object)]
pub struct EditorHandle {
    inner: Mutex<EditorState>,
}

#[uniffi::export]
impl EditorHandle {
    #[uniffi::constructor]
    pub fn new(name: String) -> Self {
        Self {
            inner: Mutex::new(EditorState::new(Project::new(name))),
        }
    }

    #[uniffi::constructor]
    pub fn from_json(json: String) -> Result<Self, MiniterError> {
        let project = Project::from_json(&json).map_err(|e| MiniterError::Parse {
            detail: e.to_string(),
        })?;
        Ok(Self {
            inner: Mutex::new(EditorState::new(project)),
        })
    }

    pub fn to_json(&self) -> Result<String, MiniterError> {
        let state = self.inner.lock().map_err(|_| MiniterError::LockPoisoned)?;
        state
            .project
            .to_json()
            .map_err(|e| MiniterError::Serialize {
                detail: e.to_string(),
            })
    }

    pub fn dispatch(&self, command_json: String) -> Result<bool, MiniterError> {
        let cmd: EditCommand =
            serde_json::from_str(&command_json).map_err(|e| MiniterError::Parse {
                detail: e.to_string(),
            })?;
        let mut state = self.inner.lock().map_err(|_| MiniterError::LockPoisoned)?;
        reducer::dispatch(&mut state, cmd).map_err(|e| MiniterError::Apply {
            detail: e.to_string(),
        })?;
        Ok(true)
    }

    pub fn undo(&self) -> Result<bool, MiniterError> {
        let mut state = self.inner.lock().map_err(|_| MiniterError::LockPoisoned)?;
        reducer::undo(&mut state).map_err(|e| MiniterError::Apply {
            detail: e.to_string(),
        })?;
        Ok(state.history.can_undo())
    }

    pub fn redo(&self) -> Result<bool, MiniterError> {
        let mut state = self.inner.lock().map_err(|_| MiniterError::LockPoisoned)?;
        reducer::redo(&mut state).map_err(|e| MiniterError::Apply {
            detail: e.to_string(),
        })?;
        Ok(state.history.can_redo())
    }

    pub fn can_undo(&self) -> bool {
        self.inner
            .lock()
            .map(|s| s.history.can_undo())
            .unwrap_or(false)
    }

    pub fn can_redo(&self) -> bool {
        self.inner
            .lock()
            .map(|s| s.history.can_redo())
            .unwrap_or(false)
    }

    pub fn playhead_us(&self) -> i64 {
        self.inner
            .lock()
            .map(|s| s.playhead.as_micros())
            .unwrap_or(0)
    }

    pub fn set_playhead_us(&self, us: i64) {
        if let Ok(mut s) = self.inner.lock() {
            s.playhead = miniter_domain::time::Timestamp::from_micros(us);
        }
    }

    pub fn render_plan_at_playhead(&self, width: u32, height: u32) -> Result<String, MiniterError> {
        let state = self.inner.lock().map_err(|_| MiniterError::LockPoisoned)?;
        let plan = miniter_render_plan::render_graph::plan_frame(
            &state.project.timeline,
            state.playhead,
            width,
            height,
        );
        Ok(format!("{plan:#?}"))
    }

    pub fn duration_us(&self) -> i64 {
        self.inner
            .lock()
            .map(|s| s.project.timeline.duration_end().as_micros())
            .unwrap_or(0)
    }
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
    let data =
        miniter_audio::waveform::extract_waveform(std::path::Path::new(&path), buckets as usize)
            .map_err(|e| MiniterError::Media {
                detail: e.to_string(),
            })?;
    let json: Vec<[f32; 2]> = data.peaks.iter().map(|(lo, hi)| [*lo, *hi]).collect();
    serde_json::to_string(&json).map_err(|e| MiniterError::Serialize {
        detail: e.to_string(),
    })
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

#[derive(uniffi::Record)]
pub struct FrameData {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
    pub pts_us: i64,
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

    #[error("Lock poisoned")]
    LockPoisoned,
}

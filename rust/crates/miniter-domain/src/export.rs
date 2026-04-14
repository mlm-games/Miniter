use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ExportFormat {
    Mp4,
    Mov,
    Av1Ivf,
    Av1Mp4,
}

impl ExportFormat {
    pub fn extension(self) -> &'static str {
        match self {
            Self::Mp4 => "mp4",
            Self::Av1Ivf => "ivf",
            Self::Av1Mp4 => "mp4",
            Self::Mov => "mov",
        }
    }

    pub fn mime(self) -> &'static str {
        match self {
            Self::Mp4 => "video/mp4",
            Self::Av1Ivf => "video/ivf",
            Self::Av1Mp4 => "video/mp4",
            Self::Mov => "video/quicktime",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ExportResolution {
    Source,
    Sd480,
    Hd720,
    Hd1080,
    Uhd4k,
    Custom { width: u32, height: u32 },
}

impl ExportResolution {
    pub fn dimensions(self) -> (u32, u32) {
        match self {
            Self::Source => (0, 0),
            Self::Sd480 => (854, 480),
            Self::Hd720 => (1280, 720),
            Self::Hd1080 => (1920, 1080),
            Self::Uhd4k => (3840, 2160),
            Self::Custom { width, height } => (width, height),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum SubtitleMode {
    #[default]
    Hard,
    Soft,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportProfile {
    pub format: ExportFormat,
    pub resolution: ExportResolution,
    pub fps: f64,
    pub video_bitrate_kbps: u32,
    pub audio_bitrate_kbps: u32,
    pub audio_sample_rate: u32,
    pub output_path: String,
    #[serde(default)]
    pub subtitle_mode: SubtitleMode,
}

impl Default for ExportProfile {
    fn default() -> Self {
        Self {
            format: ExportFormat::Mp4,
            resolution: ExportResolution::Source,
            fps: 30.0,
            video_bitrate_kbps: 8_000,
            audio_bitrate_kbps: 192,
            audio_sample_rate: 48_000,
            output_path: String::new(),
            subtitle_mode: SubtitleMode::Hard,
        }
    }
}

use fontdb::Database as FontDatabase;
use reassarus_renderer::{BackendType, Frame, RenderContext, Renderer};
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum SubtitleError {
    #[error("Failed to read subtitle file: {0}")]
    FileRead(#[from] std::io::Error),
    #[error("Failed to parse ASS script: {0}")]
    Parse(String),
    #[error("Failed to render subtitle: {0}")]
    Render(#[from] reassarus_renderer::utils::RenderError),
}

/// Read a user-picked subtitle font into bytes.
///
/// Returns `None` when no override is set or the file cannot be read —
/// callers fall back to default rendering. Never fails the export.
fn load_custom_font_bytes(font_path: Option<&str>) -> Option<Vec<u8>> {
    let path = font_path.filter(|p| !p.is_empty())?;
    std::fs::read(Path::new(path)).ok().filter(|b| !b.is_empty())
}

/// Build a font database with the custom font pre-registered.
///
/// An empty database makes the renderer fall back to system fonts, so a
/// missing/unreadable override degrades to previous behaviour instead of
/// failing the frame.
fn font_database_with_override(font_path: Option<&str>) -> FontDatabase {
    let mut db = FontDatabase::new();
    if let Some(bytes) = load_custom_font_bytes(font_path) {
        db.load_font_data(bytes);
    }
    db
}

pub struct SubtitleRenderer {
    renderer: Renderer,
    script_content: Option<String>,
}

impl SubtitleRenderer {
    pub fn new(width: u32, height: u32) -> Result<Self, SubtitleError> {
        Self::with_font_override(width, height, None)
    }

    /// Create a renderer with a user-picked font pre-registered.
    ///
    /// The override resolves by family name during shaping; when it is
    /// absent or unreadable the renderer falls back to system fonts.
    pub fn with_font_override(
        width: u32,
        height: u32,
        font_path: Option<&str>,
    ) -> Result<Self, SubtitleError> {
        let context = RenderContext::new(width, height);
        let renderer = Renderer::with_font_database(
            BackendType::Repose,
            context,
            font_database_with_override(font_path),
        )?;
        Ok(Self {
            renderer,
            script_content: None,
        })
    }

    pub fn render_frame(&mut self, time_cs: i64) -> Result<Frame, SubtitleError> {
        let content = self
            .script_content
            .as_ref()
            .ok_or_else(|| SubtitleError::Parse("No script loaded".to_string()))?;
        let script = reassarus_core::parser::Script::parse(content)
            .map_err(|e| SubtitleError::Parse(format!("{:?}", e)))?;
        let frame = self.renderer.render_frame(&script, time_cs as u32)?;
        Ok(frame)
    }

    pub fn load_script(&mut self, path: &Path) -> Result<(), SubtitleError> {
        let content = std::fs::read_to_string(path)?;
        self.script_content = Some(content);
        Ok(())
    }
}

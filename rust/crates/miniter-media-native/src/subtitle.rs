use ass_renderer::{BackendType, Frame, RenderContext, Renderer};
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum SubtitleError {
    #[error("Failed to read subtitle file: {0}")]
    FileRead(#[from] std::io::Error),
    #[error("Failed to parse ASS script: {0}")]
    Parse(String),
    #[error("Failed to render subtitle: {0}")]
    Render(#[from] ass_renderer::utils::RenderError),
}

pub struct SubtitleRenderer {
    renderer: Renderer,
    script_content: Option<String>,
}

impl SubtitleRenderer {
    pub fn new(width: u32, height: u32) -> Result<Self, SubtitleError> {
        let context = RenderContext::new(width, height);
        let renderer = Renderer::new(BackendType::Software, context)?;
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
        let script = ass_core::parser::Script::parse(content)
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

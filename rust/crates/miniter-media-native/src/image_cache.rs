use crate::frame::RgbaFrame;
use image::GenericImageView;
use std::collections::HashMap;
use std::path::Path;

pub struct ImageCache {
    cache: HashMap<String, RgbaFrame>,
}

impl ImageCache {
    pub fn new() -> Self {
        Self {
            cache: HashMap::new(),
        }
    }

    pub fn get_frame(&mut self, path: &Path) -> Result<RgbaFrame, String> {
        let key = path.to_string_lossy().into_owned();
        if let Some(frame) = self.cache.get(&key) {
            return Ok(frame.clone());
        }

        let img = image::open(path).map_err(|e| e.to_string())?;
        let rgba = img.to_rgba8();
        let (width, height) = rgba.dimensions();
        let data = rgba.into_raw();
        let frame = RgbaFrame {
            width,
            height,
            data,
            pts_us: 0,
        };
        self.cache.insert(key, frame.clone());
        Ok(frame)
    }

    pub fn clear(&mut self) {
        self.cache.clear();
    }
}

impl Default for ImageCache {
    fn default() -> Self {
        Self::new()
    }
}
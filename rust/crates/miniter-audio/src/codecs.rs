use std::sync::OnceLock;

use symphonia::core::codecs::registry::CodecRegistry;

pub fn get_codecs() -> &'static CodecRegistry {
    static REGISTRY: OnceLock<CodecRegistry> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut registry = CodecRegistry::new();
        symphonia::default::register_enabled_codecs(&mut registry);
        registry.register_audio_decoder::<symphonia_adapter_oporus::OpusDecoder>();
        registry
    })
}

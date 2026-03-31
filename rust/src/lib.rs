use uniffi::custom_default;

pub fn add(a: i32, b: i32) -> i32 {
    a + b
}

pub fn greet(name: String) -> String {
    format!("Hello, {}!", name)
}

#[custom_default]
pub fn get_default_number() -> i32 {
    42
}

uniffi::include_scaffolding!("miniter_ffi");

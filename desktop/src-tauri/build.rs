fn main() {
    // Only the Tauri window app needs its build step; the web binary is a plain
    // Rust program.
    #[cfg(feature = "desktop")]
    tauri_build::build();
}

//! Stand-ins for the few Tauri types the shared command functions mention, so
//! the same code builds for the browser front end (`--features web`) without
//! Tauri.

use std::ops::Deref;

/// Same shape as `tauri::State`: a borrowed handle to managed state that
/// derefs to it. The HTTP dispatcher wraps the shared state in this to call the
/// command functions directly.
pub struct State<'a, T>(pub &'a T);

impl<T> Deref for State<'_, T> {
    type Target = T;
    fn deref(&self) -> &T {
        self.0
    }
}

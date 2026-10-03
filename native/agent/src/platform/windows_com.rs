//! Thread-bound STA initialization shared by desktop Shell operations.
#![cfg(windows)]

use std::marker::PhantomData;
use std::rc::Rc;

use windows_sys::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx, CoUninitialize};

/// Neither Send nor Sync: successful initialization must be undone on this thread.
pub(super) struct Com(PhantomData<Rc<()>>);

impl Com {
    pub(super) fn init() -> Result<Self, String> {
        let reserved = std::ptr::null();
        let flags = COINIT_APARTMENTTHREADED as u32;
        // SAFETY: reserved is null; each success is owned by this thread-bound guard.
        let hr = unsafe { CoInitializeEx(reserved, flags) };
        if hr < 0 {
            return Err(format!("CoInitializeEx failed: {hr:#x}"));
        }
        Ok(Self(PhantomData))
    }
}

impl Drop for Com {
    fn drop(&mut self) {
        // SAFETY: pairs exactly one successful initialization on the same thread.
        unsafe { CoUninitialize() };
    }
}

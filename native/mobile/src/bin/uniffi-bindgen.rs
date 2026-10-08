//! `cargo run -p fluxdown_mobile --features bindgen --bin uniffi-bindgen -- generate ...`
//! 生成 Kotlin / Swift 绑定；仅在 `bindgen` feature 下构建。

fn main() {
    uniffi::uniffi_bindgen_main();
}

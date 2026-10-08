//! 文件图标包：任务列表等处「文件 → 图标」的可替换来源，与主题解耦（主题只决定单色图标的
//! 着色，图标包决定形状；彩色图标包自带配色）。
//!
//! - [`FileKind`]：文件大类（扩展名表 `assets/icon-packs/kinds.json`，Web 共用）。
//! - [`parse_icon_pack`]：`fluxdown.icon-pack` 文件格式（内置包与用户包同一格式，容错解析 + 诊断）。
//! - [`IconPackState`]：偏好 `appearance.file_icon_pack` 的投影与回退链（含系统图标环节）。
//! - [`pack_icon`] / [`IconPackAssets`]：GPUI 绘制与资源。
//!
//! TS 镜像：`web/src/lib/icon-pack/`（解析、回退链逐条对齐，共用 `tests/fixtures` 用例）。

mod kind;
mod pack;
mod registry;
mod render;

pub use kind::FileKind;
pub use pack::{
    Diagnostic, DiagnosticKind, ICON_PACK_FORMAT, ICON_PACK_SCHEMA_VERSION, IconMode, IconPack,
    IconPackParseError, IconSvg, MAX_ICONS, MAX_SVG_BYTES, PackIcon, PackMeta, ParsedPack,
    is_pack_ref, parse_icon_pack, svg_is_safe,
};
pub use registry::{
    BUILTIN_PACK_IDS, DEFAULT_DESKTOP_PACK, FALLBACK_PACK, FileIconChoice, IconPackState,
    SYSTEM_PACK_ID, active_icon_packs, apply_icon_pack_preference, builtin_pack,
};
pub use render::{IconPackAssets, pack_icon};

pub(crate) use render::ASSET_PREFIX;

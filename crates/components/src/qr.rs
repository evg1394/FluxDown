//! 二维码：文本在本地编码成 SVG 图（`gpui::Image`），不访问网络。
//!
//! 输出自带 4 模块宽、完全不透明的静区（扫码器要求），配色由调用方给定：
//! [`QrPalette::MONOCHROME`] 是最稳妥的黑白，[`QrPalette::themed`] 取主题的前景 / 表面色，
//! 并保证「深色模块在浅色底上」，明暗主题下都可扫。

use std::fmt::Write as _;

use fluxdown_ui_theme::active_theme;
use gpui::{App, Hsla, Image, ImageFormat};
use qrcodegen::{QrCode, QrCodeEcc};

/// 二维码文本字符数上限：纯数字、最低纠错级别也容纳不了超过 7089 个字符。
pub const MAX_QR_TEXT_LENGTH: usize = 7089;
/// 静区宽度（模块数）。
const QR_BORDER: i32 = 4;
/// SVG 的固有边长（像素）；显示尺寸由调用方的 `img().size(..)` 决定。
const SVG_SIZE: u32 = 240;

/// 二维码配色：`dark` 画模块，`light` 画底与静区；两者都按不透明处理。
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct QrPalette {
    pub dark: Hsla,
    pub light: Hsla,
}

impl QrPalette {
    /// 纯黑白：任何场景都最易识别。
    pub const MONOCHROME: Self = Self {
        dark: Hsla {
            h: 0.,
            s: 0.,
            l: 0.,
            a: 1.,
        },
        light: Hsla {
            h: 0.,
            s: 0.,
            l: 1.,
            a: 1.,
        },
    };

    /// 两个颜色里亮度低的画模块、高的画底，保证深码浅底。
    #[must_use]
    pub fn dark_on_light(first: Hsla, second: Hsla) -> Self {
        let (dark, light) = if first.l <= second.l {
            (first, second)
        } else {
            (second, first)
        };
        Self {
            dark: Hsla { a: 1., ..dark },
            light: Hsla { a: 1., ..light },
        }
    }

    /// 主题配色：正文色与表面色按亮度分配（亮色主题 = 深字浅底，暗色主题 = 深底色画码、浅字色作底）。
    #[must_use]
    pub fn themed(cx: &App) -> Self {
        let colors = active_theme(cx).tokens().colors;
        Self::dark_on_light(colors.foreground, colors.surface)
    }
}

/// 把文本编码成二维码图；文本为空或超出二维码容量返回 `None`（调用方回退为显示原文）。
#[must_use]
pub fn qr_image(text: &str, palette: QrPalette) -> Option<Image> {
    if text.is_empty() || text.chars().take(MAX_QR_TEXT_LENGTH + 1).count() > MAX_QR_TEXT_LENGTH {
        return None;
    }
    // 唯一的失败原因是数据超出容量（字符数上限只是粗筛，字节数 / 字符集才决定真实容量）。
    let Ok(qr) = QrCode::encode_text(text, QrCodeEcc::Medium) else {
        return None;
    };
    let svg = qr_svg(&qr, palette).ok()?;
    Some(Image::from_bytes(ImageFormat::Svg, svg.into_bytes()))
}

fn qr_svg(qr: &QrCode, palette: QrPalette) -> Result<String, std::fmt::Error> {
    let side = qr.size() + QR_BORDER * 2;
    let mut svg = String::with_capacity((qr.size() * qr.size()) as usize * 10);
    write!(
        svg,
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{SVG_SIZE}\" height=\"{SVG_SIZE}\" viewBox=\"0 0 {side} {side}\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"{}\"/><path fill=\"{}\" d=\"",
        hex(palette.light),
        hex(palette.dark),
    )?;
    for y in 0..qr.size() {
        for x in 0..qr.size() {
            if qr.get_module(x, y) {
                write!(svg, "M{},{}h1v1h-1z", x + QR_BORDER, y + QR_BORDER)?;
            }
        }
    }
    svg.push_str("\"/></svg>");
    Ok(svg)
}

/// `#rrggbb`（忽略透明度：二维码底与模块都必须不透明）。
fn hex(color: Hsla) -> String {
    let rgba = color.to_rgb();
    let channel = |value: f32| (value.clamp(0., 1.) * 255.).round() as u8;
    format!(
        "#{:02x}{:02x}{:02x}",
        channel(rgba.r),
        channel(rgba.g),
        channel(rgba.b)
    )
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use gpui::{SvgRenderer, hsla};

    use super::*;

    const LOGIN_URL: &str = "https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=regression-test&from=";

    #[test]
    fn quiet_zone_is_opaque_light_and_finder_pattern_is_dark() {
        let image = qr_image(LOGIN_URL, QrPalette::MONOCHROME).expect("login URL should encode");
        let rendered = image
            .to_image_data(SvgRenderer::new(Arc::new(())))
            .expect("QR SVG should render");
        let width = u32::from(rendered.size(0).width) as usize;
        let pixels = rendered.as_bytes(0).expect("QR pixels");
        let qr = QrCode::encode_text(LOGIN_URL, QrCodeEcc::Medium).expect("reference dimensions");
        let side = (qr.size() + QR_BORDER * 2) as usize;
        let border = QR_BORDER as usize * width / side;
        // 整圈静区都必须不透明（暗色主题下不能露出窗口底色），不只是第一行。
        for y in 0..border {
            for x in 0..width {
                let pixel = (y * width + x) * 4;
                assert_eq!(&pixels[pixel..pixel + 4], &[255, 255, 255, 255]);
            }
        }
        let finder_center = ((QR_BORDER as usize * 2 + 1) * width) / (side * 2);
        let finder = (finder_center * width + finder_center) * 4;
        assert_eq!(&pixels[finder..finder + 4], &[0, 0, 0, 255]);
    }

    #[test]
    fn dark_on_light_orders_by_lightness_and_forces_opaque() {
        let ink = hsla(0.6, 0.2, 0.15, 0.4);
        let paper = hsla(0.1, 0.1, 0.95, 1.);
        for (first, second) in [(ink, paper), (paper, ink)] {
            let palette = QrPalette::dark_on_light(first, second);
            assert!(palette.dark.l < palette.light.l);
            assert!((palette.dark.a - 1.).abs() < f32::EPSILON);
            assert!((palette.light.a - 1.).abs() < f32::EPSILON);
        }
    }

    #[test]
    fn hex_formats_rgb_and_ignores_alpha() {
        assert_eq!(hex(QrPalette::MONOCHROME.dark), "#000000");
        assert_eq!(hex(QrPalette::MONOCHROME.light), "#ffffff");
        assert_eq!(hex(hsla(0., 0., 1., 0.2)), "#ffffff");
    }

    #[test]
    fn unencodable_text_is_none() {
        assert!(qr_image("", QrPalette::MONOCHROME).is_none());
        for text in [
            "码".repeat(MAX_QR_TEXT_LENGTH + 1),
            "a".repeat(2332),
            "😀".repeat(583),
            "9".repeat(6000),
        ] {
            assert!(qr_image(&text, QrPalette::MONOCHROME).is_none());
        }
    }
}

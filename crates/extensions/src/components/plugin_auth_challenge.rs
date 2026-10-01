use std::fmt::Write as _;
use std::sync::Arc;

use gpui::{Image, ImageFormat};
use qrcodegen::{QrCode, QrCodeEcc};

const CHALLENGE_TEXT_LIMIT: usize = 512;
const MAX_CHALLENGE_DATA_URL_LEN: usize = 256 * 1024;
// Even a numeric QR at the lowest ECC cannot hold more than 7089 characters.
const MAX_QR_TEXT_LENGTH: usize = 7089;
const QR_BORDER: i32 = 4;

#[derive(Default)]
pub(super) struct AuthChallenge {
    value: Option<String>,
    kind: Option<String>,
    image: Option<Arc<Image>>,
}

impl AuthChallenge {
    pub(super) fn apply(&mut self, value: Option<String>, kind: Option<String>, pending: bool) {
        let next_value = if pending && value.is_none() {
            self.value.as_deref()
        } else {
            value.as_deref()
        };
        let next_kind = if pending && kind.is_none() {
            self.kind.as_deref()
        } else {
            kind.as_deref()
        };
        let changed = self.value.as_deref() != next_value || self.kind.as_deref() != next_kind;
        if !pending || value.is_some() {
            self.value = value;
        }
        if !pending || kind.is_some() {
            self.kind = kind;
        }
        if changed {
            self.image = self
                .value
                .as_deref()
                .and_then(|value| challenge_image(value, self.is_qrcode()).map(Arc::new));
        }
    }

    pub(super) fn clear(&mut self) {
        self.value = None;
        self.kind = None;
        self.image = None;
    }

    pub(super) fn value(&self) -> Option<&str> {
        self.value.as_deref()
    }

    pub(super) fn kind(&self) -> Option<&str> {
        self.kind.as_deref()
    }

    pub(super) fn image(&self) -> Option<&Arc<Image>> {
        self.image.as_ref()
    }

    pub(super) fn is_qrcode(&self) -> bool {
        self.kind
            .as_deref()
            .is_some_and(|kind| kind.eq_ignore_ascii_case("qrcode"))
    }
}

fn challenge_image(value: &str, qrcode: bool) -> Option<Image> {
    if value.starts_with("data:") {
        // A malformed/unsupported data image remains text, not a QR of its base64 payload.
        return decode_data_image_challenge(value);
    }
    if !qrcode
        || value.is_empty()
        || value.chars().take(MAX_QR_TEXT_LENGTH + 1).count() > MAX_QR_TEXT_LENGTH
    {
        return None;
    }
    let qr = match QrCode::encode_text(value, QrCodeEcc::Medium) {
        Ok(qr) => qr,
        Err(error) => {
            eprintln!("plugin authentication QR encoding failed: {error}");
            return None;
        }
    };
    match qr_svg(&qr) {
        Ok(bytes) => Some(Image::from_bytes(ImageFormat::Svg, bytes)),
        Err(error) => {
            eprintln!("plugin authentication QR image formatting failed: {error}");
            None
        }
    }
}

fn qr_svg(qr: &QrCode) -> Result<Vec<u8>, std::fmt::Error> {
    let side = qr.size() + QR_BORDER * 2;
    let mut svg = String::with_capacity((qr.size() * qr.size()) as usize * 10);
    write!(
        svg,
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"240\" height=\"240\" viewBox=\"0 0 {side} {side}\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\""
    )?;
    for y in 0..qr.size() {
        for x in 0..qr.size() {
            if qr.get_module(x, y) {
                write!(svg, "M{},{}h1v1h-1z", x + QR_BORDER, y + QR_BORDER)?;
            }
        }
    }
    svg.push_str("\"/></svg>");
    Ok(svg.into_bytes())
}

fn decode_data_image_challenge(value: &str) -> Option<Image> {
    if value.len() > MAX_CHALLENGE_DATA_URL_LEN {
        return None;
    }
    let rest = value.strip_prefix("data:")?;
    let (meta, payload) = rest.split_once(',')?;
    let mut segments = meta.split(';');
    let mime = segments.next()?.to_ascii_lowercase();
    if !segments.any(|segment| segment.eq_ignore_ascii_case("base64")) {
        return None;
    }
    let format = ImageFormat::from_mime_type(&mime)?;
    let bytes = base64_decode(payload)?;
    if bytes.is_empty() {
        return None;
    }
    Some(Image::from_bytes(format, bytes))
}

fn base64_decode(input: &str) -> Option<Vec<u8>> {
    fn sextet(byte: u8) -> Option<u8> {
        match byte {
            b'A'..=b'Z' => Some(byte - b'A'),
            b'a'..=b'z' => Some(byte - b'a' + 26),
            b'0'..=b'9' => Some(byte - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    }
    let cleaned: Vec<u8> = input
        .bytes()
        .filter(|byte| !byte.is_ascii_whitespace())
        .collect();
    let end = cleaned
        .iter()
        .rposition(|&byte| byte != b'=')
        .map_or(0, |index| index + 1);
    let data = &cleaned[..end];
    if data.len() % 4 == 1 {
        return None;
    }
    let mut out = Vec::with_capacity(data.len() * 3 / 4 + 3);
    for chunk in data.chunks(4) {
        let mut buf = [0u8; 4];
        for (slot, &byte) in buf.iter_mut().zip(chunk) {
            *slot = sextet(byte)?;
        }
        out.push((buf[0] << 2) | (buf[1] >> 4));
        if chunk.len() > 2 {
            out.push((buf[1] << 4) | (buf[2] >> 2));
        }
        if chunk.len() > 3 {
            out.push((buf[2] << 6) | buf[3]);
        }
    }
    Some(out)
}

pub(super) fn truncate_challenge_text(value: &str) -> String {
    if value.chars().count() <= CHALLENGE_TEXT_LIMIT {
        return value.to_string();
    }
    let mut truncated: String = value.chars().take(CHALLENGE_TEXT_LIMIT).collect();
    truncated.push('…');
    truncated
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use gpui::SvgRenderer;
    use qrcodegen::{QrCode, QrCodeEcc};

    use super::{AuthChallenge, MAX_QR_TEXT_LENGTH, QR_BORDER, challenge_image};

    const LOGIN_URL: &str = "https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=regression-test&from=";

    #[test]
    fn qr_login_has_opaque_white_quiet_zone_and_black_finder_pattern() {
        let image = challenge_image(LOGIN_URL, true).expect("login URL should encode");
        let rendered = image
            .to_image_data(SvgRenderer::new(Arc::new(())))
            .expect("QR SVG should render");
        let width = u32::from(rendered.size(0).width) as usize;
        let pixels = rendered.as_bytes(0).expect("QR pixels");
        let qr = QrCode::encode_text(LOGIN_URL, QrCodeEcc::Medium).expect("reference dimensions");
        let side = (qr.size() + QR_BORDER * 2) as usize;
        let border = QR_BORDER as usize * width / side;
        // The full quiet zone must remain opaque in dark themes, not just the SVG's first row.
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
    fn pending_poll_preserves_preview_but_terminal_response_clears_it() {
        let mut challenge = AuthChallenge::default();
        challenge.apply(Some(LOGIN_URL.into()), Some("qrcode".into()), true);
        let image = challenge.image().expect("QR image").clone();
        challenge.apply(None, None, true);
        assert_eq!(challenge.value(), Some(LOGIN_URL));
        assert!(Arc::ptr_eq(
            challenge.image().expect("retained image"),
            &image
        ));
        challenge.apply(Some(LOGIN_URL.into()), Some("qrcode".into()), true);
        assert!(Arc::ptr_eq(
            challenge.image().expect("unchanged image"),
            &image
        ));
        challenge.apply(None, None, false);
        assert!(challenge.value().is_none());
        assert!(challenge.image().is_none());
    }

    #[test]
    fn challenge_kind_change_removes_qr_without_losing_copy_text() {
        let mut challenge = AuthChallenge::default();
        challenge.apply(Some(LOGIN_URL.into()), Some("QrCoDe".into()), true);
        assert!(challenge.is_qrcode());
        challenge.apply(None, Some("text".into()), true);
        assert_eq!(challenge.value(), Some(LOGIN_URL));
        assert!(challenge.image().is_none());
    }

    #[test]
    fn oversized_qr_falls_back_to_complete_original_text() {
        for text in [
            "码".repeat(MAX_QR_TEXT_LENGTH + 1),
            "a".repeat(2332),
            "😀".repeat(583),
            "9".repeat(6000),
        ] {
            let mut challenge = AuthChallenge::default();
            challenge.apply(Some(text.clone()), Some("qrcode".into()), true);
            assert_eq!(challenge.value(), Some(text.as_str()));
            assert!(challenge.image().is_none());
        }
        assert!(challenge_image("", true).is_none());
        assert!(challenge_image(LOGIN_URL, false).is_none());
    }

    #[test]
    fn data_image_takes_precedence_and_invalid_data_is_not_encoded_as_qr() {
        let svg = "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxIiBoZWlnaHQ9IjEiPjxyZWN0IHdpZHRoPSIxIiBoZWlnaHQ9IjEiIGZpbGw9IndoaXRlIi8+PC9zdmc+";
        let image = challenge_image(svg, true).expect("data image preview");
        let rendered = image
            .to_image_data(SvgRenderer::new(Arc::new(())))
            .expect("valid data SVG");
        assert!(
            rendered
                .as_bytes(0)
                .expect("data image pixels")
                .as_chunks::<4>()
                .0
                .iter()
                .all(|pixel| *pixel == [255, 255, 255, 255])
        );
        assert!(challenge_image("data:image/png;base64,not-base64!", true).is_none());
    }
}

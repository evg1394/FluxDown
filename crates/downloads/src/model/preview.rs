use fluxdown_protocol::CreateTaskRequest;

pub(crate) fn previewable(request: &CreateTaskRequest) -> bool {
    ["http://", "https://"].iter().any(|scheme| {
        request
            .url
            .get(..scheme.len())
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case(scheme))
    }) && request
        .method
        .as_deref()
        .is_none_or(|method| method.eq_ignore_ascii_case("GET"))
        && request.body.is_none()
        && request.audio_url.as_deref().is_none_or(str::is_empty)
        && request.torrent_b64.is_none()
}

#[derive(Default)]
pub(crate) struct PreviewGate {
    generation: u64,
    active: Option<u64>,
}

impl PreviewGate {
    pub(crate) fn begin(&mut self) -> u64 {
        self.generation = self.generation.wrapping_add(1);
        self.active = Some(self.generation);
        self.generation
    }

    pub(crate) fn is_active(&self) -> bool {
        self.active.is_some()
    }

    pub(crate) fn cancel(&mut self) {
        self.active = None;
    }

    // 响应、超时、取消后的迟到响应，只允许当前请求结束一次。
    pub(crate) fn finish(&mut self, generation: u64) -> bool {
        if self.active == Some(generation) {
            self.active = None;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{PreviewGate, previewable};

    #[test]
    fn cancellation_and_restart_reject_late_responses_and_double_completion() {
        let mut gate = PreviewGate::default();
        let old = gate.begin();
        gate.cancel();
        assert!(!gate.finish(old));
        let current = gate.begin();
        assert!(!gate.finish(old));
        assert!(gate.is_active());
        assert!(gate.finish(current));
        assert!(!gate.finish(current));
        assert!(!gate.is_active());
    }

    #[test]
    fn post_torrent_and_audio_pairs_never_enter_get_only_preview() {
        let request = |value| serde_json::from_value(value).expect("request fixture");
        assert!(previewable(&request(
            serde_json::json!({"url":"HTTPS://site.example/page"})
        )));
        for value in [
            serde_json::json!({"url":"https://site.example/page","method":"POST"}),
            serde_json::json!({"url":"https://site.example/page","body":{"kind":"urlencoded","raw":"data"}}),
            serde_json::json!({"url":"https://site.example/page","audioUrl":"https://site.example/audio"}),
            serde_json::json!({"url":"https://site.example/page","torrentB64":"AA=="}),
            serde_json::json!({"url":"magnet:?xt=urn:btih:abc"}),
            serde_json::json!({"url":"ftp://site.example/file"}),
        ] {
            assert!(!previewable(&request(value)));
        }
    }
}

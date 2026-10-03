//! Profile validation and session-bound edit lifetime.

use fluxdown_protocol::AgentSessionDto;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ProfileField {
    Nickname,
    OriginId,
}

impl ProfileField {
    pub fn unchanged(self, raw: &str, session: &AgentSessionDto) -> bool {
        match self {
            Self::Nickname => raw.trim() == session.user.nickname.trim(),
            Self::OriginId => {
                origin_id(raw).is_some_and(|value| Some(value) == session.user.origin_id)
            }
        }
    }
}

pub(crate) fn can_edit_origin_id(session: &AgentSessionDto) -> bool {
    session
        .entitlements
        .0
        .get("originIdEdit")
        .and_then(serde_json::Value::as_bool)
        == Some(true)
        && !session.user.origin_id_changed
}

pub(crate) fn nickname(raw: &str) -> Option<&str> {
    let value = raw.trim();
    (1..=32).contains(&value.chars().count()).then_some(value)
}

pub(crate) fn origin_id(raw: &str) -> Option<i64> {
    let value = raw.trim();
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    value.parse::<i64>().ok().filter(|value| *value >= 10000)
}

/// Invalidation is permanent: reconnecting or switching back cannot revive an old request.
pub(crate) struct EditLifetime {
    user_id: String,
    device_id: String,
    pub field: ProfileField,
    pub closed: bool,
}

impl EditLifetime {
    pub fn new(session: &AgentSessionDto, field: ProfileField) -> Self {
        Self {
            user_id: session.user.id.clone(),
            device_id: session.device.id.clone(),
            field,
            closed: false,
        }
    }

    pub fn valid(
        &self,
        session: Option<&AgentSessionDto>,
        stale: bool,
        saving: Option<i64>,
    ) -> bool {
        if self.closed || stale {
            return false;
        }
        let Some(session) = session else { return false };
        session.user.id == self.user_id
            && session.device.id == self.device_id
            && (self.field == ProfileField::Nickname
                || can_edit_origin_id(session)
                // The shared session event can precede our successful RPC response.
                || (saving.is_some()
                    && saving == session.user.origin_id
                    && session.user.origin_id_changed
                    && session.entitlements.0.get("originIdEdit").and_then(serde_json::Value::as_bool)
                        == Some(true)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn session() -> AgentSessionDto {
        serde_json::from_value(json!({
            "user": {"id": "user", "email": "mail@example.test"},
            "entitlements": {"originIdEdit": true},
            "device": {"id": "device", "deviceId": "device", "name": "PC", "createdAt": "", "lastSeenAt": ""}
        })).expect("valid session fixture")
    }

    #[test]
    fn unchanged_values_do_not_consume_an_edit() {
        let mut session = session();
        session.user.nickname = "昵称🙂".into();
        session.user.origin_id = Some(12345);
        assert!(ProfileField::Nickname.unchanged("  昵称🙂 ", &session));
        assert!(!ProfileField::Nickname.unchanged("新昵称", &session));
        assert!(ProfileField::OriginId.unchanged(" 00012345 ", &session));
        assert!(!ProfileField::OriginId.unchanged("12346", &session));
        assert!(!ProfileField::OriginId.unchanged("invalid", &session));
        session.user.origin_id = None;
        assert!(!ProfileField::OriginId.unchanged("", &session));
        assert!(!ProfileField::OriginId.unchanged("12345", &session));
    }

    #[test]
    fn nickname_trims_and_counts_unicode_scalars() {
        assert_eq!(nickname(" \n昵称🙂\t"), Some("昵称🙂"));
        assert_eq!(nickname(" \t\n"), None);
        assert!(nickname(&"🙂".repeat(32)).is_some());
        assert!(nickname(&"🙂".repeat(33)).is_none());
        assert!(nickname(&"e\u{301}".repeat(16)).is_some());
        assert!(nickname(&"e\u{301}".repeat(17)).is_none());
    }

    #[test]
    fn origin_id_requires_decimal_i64_at_least_10000() {
        for invalid in [
            "",
            "9999",
            "-10000",
            "+10000",
            "1e4",
            "10000.0",
            "１２３４５",
            "9223372036854775808",
        ] {
            assert_eq!(origin_id(invalid), None, "{invalid}");
        }
        assert_eq!(origin_id(" 00010000 "), Some(10000));
        assert_eq!(origin_id("9223372036854775807"), Some(i64::MAX));
    }

    #[test]
    fn origin_permission_is_strict_and_allows_initial_assignment() {
        let mut session = session();
        assert!(session.user.origin_id.is_none());
        assert!(can_edit_origin_id(&session));
        for value in [json!(false), json!("true"), json!(1), json!(null)] {
            session.entitlements.0.insert("originIdEdit".into(), value);
            assert!(!can_edit_origin_id(&session));
        }
        session.entitlements.0.clear();
        assert!(!can_edit_origin_id(&session));
        session
            .entitlements
            .0
            .insert("originIdEdit".into(), json!(true));
        session.user.origin_id_changed = true;
        assert!(!can_edit_origin_id(&session));
    }

    #[test]
    fn lifetime_rejects_disconnect_switch_permission_loss_and_late_results() {
        let mut session = session();
        let mut edit = EditLifetime::new(&session, ProfileField::OriginId);
        assert!(edit.valid(Some(&session), false, None));
        assert!(!edit.valid(None, false, None));
        assert!(!edit.valid(Some(&session), true, None));
        session.user.id = "other".into();
        assert!(!edit.valid(Some(&session), false, None));
        session.user.id = "user".into();
        session.device.id = "new-device".into();
        assert!(!edit.valid(Some(&session), false, None));
        session.device.id = "device".into();
        session.user.origin_id_changed = true;
        session.user.origin_id = Some(12345);
        assert!(!edit.valid(Some(&session), false, None));
        assert!(!edit.valid(Some(&session), false, Some(54321)));
        assert!(edit.valid(Some(&session), false, Some(12345)));
        session.entitlements.0.clear();
        assert!(!edit.valid(Some(&session), false, Some(12345)));
        session
            .entitlements
            .0
            .insert("originIdEdit".into(), json!(true));
        edit.closed = true;
        assert!(!edit.valid(Some(&session), false, Some(12345)));
    }
}

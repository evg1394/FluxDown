use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

const SCHEME: &str = "fluxdown-notification";
const DIRECTORY: &str = "notification-actions";
const TTL: u64 = 24 * 60 * 60;
const MAX_RECORDS: usize = 64;
const MAX_BYTES: u64 = 64 * 1024;

pub(super) fn uri(token: &str, action: &str) -> String {
    format!("{SCHEME}://{token}/{action}")
}

fn valid_token(token: &str) -> bool {
    // Canonical lower-case RFC 4122 UUID v4 only; no URL decoding or permissive UUID parsing.
    token.len() == 36
        && token.bytes().enumerate().all(|(index, byte)| match index {
            8 | 13 | 18 | 23 => byte == b'-',
            14 => byte == b'4',
            19 => matches!(byte, b'8' | b'9' | b'a' | b'b'),
            _ => byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte),
        })
}

fn parse_uri(value: &str) -> Result<(&str, &str), String> {
    let (token, action) = value
        .strip_prefix("fluxdown-notification://")
        .and_then(|value| value.split_once('/'))
        .ok_or("invalid notification activation URI")?;
    if !valid_token(token) || !matches!(action, "open-file" | "open-folder") {
        return Err("invalid notification token or action".into());
    }
    Ok((token, action))
}

struct Activation<'a> {
    data_dir: &'a Path,
    token: &'a str,
    action: &'a str,
}

// args excludes argv[0]. Reserved flags always consume the invocation, including malformed
// forms, so a bad protocol launch can never fall through into normal download/server startup.
fn parse_args(args: &[String]) -> Option<Result<Activation<'_>, String>> {
    if !args.iter().any(|arg| arg.starts_with("--notification-")) {
        return None;
    }
    Some((|| {
        let [directory_flag, directory, action_flag, value] = args else {
            return Err(
                "expected exactly --notification-data-dir <directory> --notification-action <URI>"
                    .into(),
            );
        };
        if directory_flag != "--notification-data-dir" || action_flag != "--notification-action" {
            return Err("invalid notification activation argument order".into());
        }
        let data_dir = Path::new(directory);
        if !data_dir.is_absolute() {
            return Err("notification data directory must be absolute".into());
        }
        let (token, action) = parse_uri(value)?;
        Ok(Activation {
            data_dir,
            token,
            action,
        })
    })())
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Record {
    path: PathBuf,
    issued_at: u64,
    expires_at: u64,
    slot: u8,
}

impl Record {
    fn validate(&self, now: u64) -> Result<(), String> {
        if !self.path.is_absolute() || usize::from(self.slot) >= MAX_RECORDS {
            return Err("invalid notification action record".into());
        }
        if self.issued_at > now
            || self.expires_at <= now
            || self.issued_at.checked_add(TTL) != Some(self.expires_at)
        {
            return Err("notification action has expired or has an invalid timestamp".into());
        }
        Ok(())
    }
}

fn decode_record(bytes: &[u8], now: u64) -> Result<Record, String> {
    if bytes.len() as u64 > MAX_BYTES {
        return Err("notification action record is too large".into());
    }
    let record: Record = serde_json::from_slice(bytes)
        .map_err(|error| format!("invalid notification action record: {error}"))?;
    record.validate(now)?;
    Ok(record)
}

fn quote_argument(value: &str) -> Result<String, String> {
    // Windows filesystem paths cannot contain quotes; reject registry placeholders too.
    // Percent expansion in protocol command templates must not change a literal path.
    if value.contains(['"', '\0', '%']) {
        return Err("notification registration path contains an unsupported character".into());
    }
    let trailing_slashes = value.chars().rev().take_while(|ch| *ch == '\\').count();
    Ok(format!("\"{value}{}\"", "\\".repeat(trailing_slashes)))
}

fn command_line(executable: &str, data_dir: &str) -> Result<String, String> {
    Ok(format!(
        "{} --notification-data-dir {} --notification-action \"%1\"",
        quote_argument(executable)?,
        quote_argument(data_dir)?,
    ))
}

#[cfg(windows)]
pub(super) fn handle_activation(args: &[String]) -> Option<Result<(), String>> {
    parse_args(args).map(|parsed| {
        let activation = parsed?;
        let record = read_record(&token_path(activation.data_dir, activation.token), now()?)?;
        // Nothing from the URI becomes a filesystem path. The only path used was previously
        // emitted by this agent and is stored in the user's private data directory.
        super::super::CompletionActions {
            path: record.path,
            open_file_label: String::new(),
            open_folder_label: String::new(),
        }
        .try_activate(activation.action)
    })
}

#[cfg(any(windows, test))]
fn token_path(data_dir: &Path, token: &str) -> PathBuf {
    data_dir.join(DIRECTORY).join(format!("{token}.json"))
}

#[cfg(windows)]
fn now() -> Result<u64, String> {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|error| format!("invalid system time for notifications: {error}"))
}

#[cfg(windows)]
fn read_record(path: &Path, now: u64) -> Result<Record, String> {
    use std::io::Read;
    let metadata = std::fs::symlink_metadata(path)
        .map_err(|error| format!("notification action is unavailable: {error}"))?;
    if !metadata.is_file() || metadata.len() > MAX_BYTES {
        return Err("notification action is not a bounded regular file".into());
    }
    let file = std::fs::File::open(path)
        .map_err(|error| format!("could not read notification action: {error}"))?;
    let mut bytes = Vec::new();
    file.take(MAX_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("could not read notification action: {error}"))?;
    decode_record(&bytes, now)
}

#[cfg(windows)]
pub(super) fn register_protocol(data_dir: &Path) -> Result<(), String> {
    use winreg::{RegKey, enums::HKEY_CURRENT_USER};
    if !data_dir.is_absolute() {
        return Err("notification data directory must be absolute".into());
    }
    let executable = std::env::current_exe()
        .map_err(|error| format!("could not locate notification agent: {error}"))?;
    let executable = executable
        .to_str()
        .ok_or("notification executable is not Unicode")?;
    let directory = data_dir
        .to_str()
        .ok_or("notification data directory is not Unicode")?;
    let command = command_line(executable, directory)?;
    let register = || -> std::io::Result<()> {
        let (key, _) = RegKey::predef(HKEY_CURRENT_USER)
            .create_subkey(format!("Software\\Classes\\{SCHEME}"))?;
        let (command_key, _) = key.create_subkey("shell\\open\\command")?;
        command_key.set_value("", &command)?;
        key.set_value("", &"URL:FluxDown completion notification")?;
        key.set_value("URL Protocol", &"")
    };
    register().map_err(|error| format!("could not register notification protocol: {error}"))
}

#[cfg(windows)]
pub(super) struct Stored {
    pub(super) token: String,
    pub(super) slot: u8,
    pub(super) windows_expiry: i64,
    path: PathBuf,
    _lease: std::fs::File,
}

#[cfg(windows)]
impl Stored {
    pub(super) fn remove(&self) -> Result<(), String> {
        remove_record(&self.path)
    }
}

#[cfg(windows)]
fn remove_record(path: &Path) -> Result<(), String> {
    std::fs::remove_file(path)
        .map_err(|error| format!("could not remove notification action record: {error}"))
}

#[cfg(windows)]
pub(super) fn store(
    data_dir: &Path,
    path: &Path,
    mut remove_history: impl FnMut(u8) -> Result<(), String>,
) -> Result<Stored, String> {
    use std::io::Write;
    if !data_dir.is_absolute() || !path.is_absolute() {
        return Err("notification directories and completion paths must be absolute".into());
    }
    let directory = data_dir.join(DIRECTORY);
    std::fs::create_dir_all(&directory)
        .map_err(|error| format!("could not create notification action directory: {error}"))?;
    let lease = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(directory.join("emission.lock"))
        .map_err(|error| format!("could not open notification emission lock: {error}"))?;
    fs2::FileExt::lock_exclusive(&lease)
        .map_err(|error| format!("could not lock notification emission: {error}"))?;
    let now = now()?;
    let slots = prune(&directory, now, &mut remove_history)?;
    let slot = (0..MAX_RECORDS as u8)
        .find(|slot| !slots.contains(slot))
        .ok_or("no free notification history slot")?;
    let expires_at = now.checked_add(TTL).ok_or("notification expiry overflow")?;
    let windows_expiry = expires_at
        .checked_add(11_644_473_600)
        .and_then(|seconds| seconds.checked_mul(10_000_000))
        .and_then(|ticks| i64::try_from(ticks).ok())
        .ok_or("notification expiry is outside Windows time range")?;
    let record = Record {
        path: path.to_owned(),
        issued_at: now,
        expires_at,
        slot,
    };
    let bytes = serde_json::to_vec(&record)
        .map_err(|error| format!("could not encode notification action: {error}"))?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err("notification completion path is too large".into());
    }
    let token = uuid::Uuid::new_v4().to_string();
    let record_path = token_path(data_dir, &token);
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&record_path)
        .map_err(|error| format!("could not create notification action: {error}"))?;
    if let Err(error) = file.write_all(&bytes).and_then(|()| file.sync_all()) {
        drop(file);
        remove_record(&record_path).map_err(|cleanup_error| {
            format!("could not persist notification action: {error}; token cleanup also failed: {cleanup_error}")
        })?;
        return Err(format!("could not persist notification action: {error}"));
    }
    Ok(Stored {
        token,
        slot,
        windows_expiry,
        path: record_path,
        _lease: lease,
    })
}

#[cfg(windows)]
fn prune(
    directory: &Path,
    now: u64,
    remove_history: &mut impl FnMut(u8) -> Result<(), String>,
) -> Result<Vec<u8>, String> {
    let entries = std::fs::read_dir(directory)
        .map_err(|error| format!("could not list notification actions: {error}"))?;
    let mut retained = Vec::new();
    for entry in entries {
        let entry =
            entry.map_err(|error| format!("could not inspect notification action: {error}"))?;
        let name = entry.file_name();
        let Some(token) = name.to_str().and_then(|name| name.strip_suffix(".json")) else {
            continue;
        };
        if !valid_token(token) {
            continue;
        }
        match read_record(&entry.path(), now) {
            Ok(record) => retained.push((record.issued_at, record.slot, entry.path())),
            Err(error) => {
                // Expired/corrupt records cannot activate. Slot tags and OS expiry bound history
                // independently, even when a corrupt record no longer contains a readable slot.
                tracing::debug!(%error, "pruning unusable notification action");
                remove_record(&entry.path())?;
            }
        }
    }
    retained.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.2.cmp(&b.2)));
    let evict = retained.len().saturating_sub(MAX_RECORDS - 1);
    for (_, slot, path) in retained.drain(..evict) {
        remove_history(slot)?;
        remove_record(&path)?;
    }
    Ok(retained.into_iter().map(|(_, slot, _)| slot).collect())
}

#[cfg(test)]
mod tests {
    use super::{
        Record, TTL, command_line, decode_record, parse_args, parse_uri, uri, valid_token,
    };
    const TOKEN: &str = "d735c73f-0697-4f90-b330-c3c3a035c963";

    #[test]
    fn token_resolves_only_under_local_action_directory() {
        let directory = std::env::temp_dir();
        let value = uri(TOKEN, "open-file");
        let (token, _) = parse_uri(&value).unwrap();
        assert_eq!(
            super::token_path(&directory, token),
            directory
                .join("notification-actions")
                .join(format!("{TOKEN}.json"))
        );
    }

    #[test]
    fn strict_uri_and_uuid() {
        for action in ["open-file", "open-folder"] {
            assert_eq!(parse_uri(&uri(TOKEN, action)).unwrap(), (TOKEN, action));
        }
        for value in [
            "",
            "file:///C:/test",
            "fluxdown-notification://../open-file",
            "fluxdown-notification://d735c73f-0697-4f90-b330-c3c3a035c963/open-file?path=C:/evil",
            "fluxdown-notification://d735c73f-0697-4f90-b330-c3c3a035c963/open-file#x",
        ] {
            assert!(parse_uri(value).is_err());
        }
        for action in [
            "default",
            "open-file/",
            "OPEN-FILE",
            "open%2Dfile",
            "open-file\0",
            "open-file ",
        ] {
            assert!(parse_uri(&uri(TOKEN, action)).is_err());
        }
        for token in [
            TOKEN.to_uppercase(),
            TOKEN.replace("-", ""),
            TOKEN.replace("4f90", "1f90"),
            format!("{TOKEN}@host"),
        ] {
            assert!(!valid_token(&token));
        }
    }

    #[test]
    fn exact_args_and_reserved_flags() {
        let directory = std::env::temp_dir();
        let args = vec![
            "--notification-data-dir".into(),
            directory.to_string_lossy().into_owned(),
            "--notification-action".into(),
            uri(TOKEN, "open-folder"),
        ];
        let parsed = parse_args(&args).unwrap().unwrap();
        assert_eq!(parsed.data_dir, directory);
        assert_eq!(parsed.token, TOKEN);
        assert_eq!(parsed.action, "open-folder");
        assert!(parse_args(&["--server".into()]).is_none());
        for flags in [
            vec!["--notification-action=evil".into()],
            vec!["--notification-data-dir".into()],
            [args.clone(), vec!["--server".into()]].concat(),
        ] {
            assert!(parse_args(&flags).unwrap().is_err());
        }
        let mut relative = args.clone();
        relative[1] = "relative".into();
        assert!(parse_args(&relative).unwrap().is_err());
        let mut reordered = args;
        reordered.swap(0, 2);
        assert!(parse_args(&reordered).unwrap().is_err());
    }

    #[test]
    fn timestamp_and_path_validation() {
        let mut record = Record {
            path: std::env::temp_dir().join("a.zip"),
            issued_at: 100,
            expires_at: 100 + TTL,
            slot: 0,
        };
        assert!(record.validate(100).is_ok());
        assert!(record.validate(99).is_err());
        assert!(record.validate(100 + TTL - 1).is_ok());
        assert!(record.validate(100 + TTL).is_err());
        record.expires_at += 1;
        assert!(record.validate(100).is_err());
        record.expires_at -= 1;
        record.slot = 64;
        assert!(record.validate(100).is_err());
        record.slot = 0;
        record.path = "relative.zip".into();
        assert!(record.validate(100).is_err());
    }

    #[test]
    fn records_are_bounded_and_strict() {
        assert!(decode_record(&vec![b' '; 65_537], 100).is_err());
        assert!(decode_record(b"{}", 100).is_err());
        let record = Record {
            path: std::env::temp_dir().join("a.zip"),
            issued_at: 100,
            expires_at: 100 + TTL,
            slot: 1,
        };
        let mut value = serde_json::to_value(&record).unwrap();
        assert!(decode_record(&serde_json::to_vec(&value).unwrap(), 100).is_ok());
        value["command"] = "evil".into();
        assert!(decode_record(&serde_json::to_vec(&value).unwrap(), 100).is_err());
    }

    #[test]
    fn registry_command_quotes_paths_and_only_one_uri_placeholder() {
        let command = command_line(
            r"C:\Program Files\FluxDown\fluxdown-agent.exe",
            r"C:\Data Dir\",
        )
        .unwrap();
        assert_eq!(
            command,
            "\"C:\\Program Files\\FluxDown\\fluxdown-agent.exe\" --notification-data-dir \"C:\\Data Dir\\\\\" --notification-action \"%1\""
        );
        assert!(command_line("bad\"exe", "dir").is_err());
        assert!(command_line("exe", "%1").is_err());
    }
}

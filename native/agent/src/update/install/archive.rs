//! 更新包解压：zip / tar.gz → 目录。
//!
//! - 拒绝路径穿越（`..`、绝对路径、盘符 / 备用数据流）与一切链接 / 设备条目；官方包不含它们，
//!   出现即视为包被篡改或格式不符；
//! - 解压结果若只有一个顶层目录（`FluxDown-<ver>-linux-x64/`、`fluxdown-server-<ver>-<platform>/`）
//!   就剥掉它，返回内容根目录；Windows 便携包本身是平铺根，原样返回。

use std::ffi::OsStr;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, Read};
use std::path::{Path, PathBuf};

use super::InstallError;

/// 把 `package` 解压到 `dest`（不存在则创建，应为空）；返回内容根目录。
pub(super) fn extract(package: &Path, dest: &Path) -> Result<PathBuf, InstallError> {
    fs::create_dir_all(dest).map_err(InstallError::storage(format!(
        "create extraction directory {}",
        dest.display()
    )))?;
    let name = package
        .file_name()
        .and_then(OsStr::to_str)
        .unwrap_or_default()
        .to_ascii_lowercase();
    if name.ends_with(".zip") {
        extract_zip(package, dest)?;
    } else if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        extract_tar_gz(package, dest)?;
    } else {
        return Err(InstallError::InvalidPackage(format!(
            "unsupported archive type: {name}"
        )));
    }
    content_root(dest)
}

/// 归档内条目名 → 相对路径。`Ok(None)` 表示跳过：根条目（`./`），以及 macOS bsdtar / Finder
/// 打包时夹带的元数据（AppleDouble `._*`、`__MACOSX/`、`.DS_Store`）——它们不属于程序文件，
/// 留着还会让「只有一个顶层目录」的判定失效（macOS runner 打出的 tar.gz 就带 `._<顶层目录>`）。
fn safe_relative(name: &str) -> Result<Option<PathBuf>, InstallError> {
    let unsafe_entry = || InstallError::InvalidPackage(format!("unsafe archive entry: {name:?}"));
    if name.starts_with(['/', '\\']) {
        return Err(unsafe_entry());
    }
    let mut relative = PathBuf::new();
    let mut metadata = false;
    for part in name.split(['/', '\\']) {
        match part {
            "" | "." => {}
            ".." => return Err(unsafe_entry()),
            // 盘符（`C:`）与 NTFS 备用数据流（`file:stream`）。
            part if part.contains(':') => return Err(unsafe_entry()),
            part => {
                metadata |= part.starts_with("._") || part == "__MACOSX" || part == ".DS_Store";
                relative.push(part);
            }
        }
    }
    Ok((!metadata && !relative.as_os_str().is_empty()).then_some(relative))
}

fn create_dir(path: &Path) -> Result<(), InstallError> {
    fs::create_dir_all(path).map_err(InstallError::storage(format!(
        "create directory {}",
        path.display()
    )))
}

/// 写出一个普通文件；Unix 上保留归档里的权限位（保证属主可读写，便于之后清理）。
#[cfg_attr(not(unix), allow(unused_variables))]
fn write_file(path: &Path, reader: &mut impl Read, mode: Option<u32>) -> Result<(), InstallError> {
    if let Some(parent) = path.parent() {
        create_dir(parent)?;
    }
    let context = format!("write {}", path.display());
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(InstallError::storage(context.clone()))?;
    io::copy(reader, &mut file).map_err(InstallError::storage(context.clone()))?;
    #[cfg(unix)]
    if let Some(mode) = mode {
        use std::os::unix::fs::PermissionsExt;

        file.set_permissions(fs::Permissions::from_mode((mode & 0o777) | 0o600))
            .map_err(InstallError::storage(context))?;
    }
    Ok(())
}

fn extract_zip(package: &Path, dest: &Path) -> Result<(), InstallError> {
    let file = File::open(package)
        .map_err(InstallError::storage(format!("open {}", package.display())))?;
    let invalid = |error: zip::result::ZipError| {
        InstallError::InvalidPackage(format!("cannot read zip archive: {error}"))
    };
    let mut archive = zip::ZipArchive::new(BufReader::new(file)).map_err(invalid)?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(invalid)?;
        let Some(relative) = safe_relative(entry.name())? else {
            continue;
        };
        let target = dest.join(&relative);
        if entry.is_dir() {
            create_dir(&target)?;
        } else if entry.is_symlink() {
            return Err(InstallError::InvalidPackage(format!(
                "archive entry {} is a link",
                relative.display()
            )));
        } else {
            let mode = entry.unix_mode();
            write_file(&target, &mut entry, mode)?;
        }
    }
    Ok(())
}

fn extract_tar_gz(package: &Path, dest: &Path) -> Result<(), InstallError> {
    let file = File::open(package)
        .map_err(InstallError::storage(format!("open {}", package.display())))?;
    let invalid = |error: io::Error| {
        InstallError::InvalidPackage(format!("cannot read tar.gz archive: {error}"))
    };
    let mut archive = tar::Archive::new(flate2::read::GzDecoder::new(BufReader::new(file)));
    for entry in archive.entries().map_err(invalid)? {
        let mut entry = entry.map_err(invalid)?;
        let kind = entry.header().entry_type();
        if kind.is_pax_global_extensions() {
            continue;
        }
        let (name, relative) = {
            let path = entry.path().map_err(invalid)?;
            let name = path
                .to_str()
                .ok_or_else(|| {
                    InstallError::InvalidPackage(format!("non-UTF-8 archive entry: {path:?}"))
                })?
                .to_owned();
            let relative = safe_relative(&name)?;
            (name, relative)
        };
        let Some(relative) = relative else {
            continue;
        };
        let target = dest.join(&relative);
        if kind.is_dir() {
            create_dir(&target)?;
        } else if kind.is_file() || kind.is_contiguous() {
            let mode = entry.header().mode().ok();
            write_file(&target, &mut entry, mode)?;
        } else {
            return Err(InstallError::InvalidPackage(format!(
                "archive entry {name:?} has an unsupported type (link or special file)"
            )));
        }
    }
    Ok(())
}

/// 解压目录里只有一个子目录时返回它，否则返回解压目录本身。
fn content_root(dest: &Path) -> Result<PathBuf, InstallError> {
    let context = format!("read {}", dest.display());
    let mut entries = fs::read_dir(dest).map_err(InstallError::storage(context.clone()))?;
    let first = entries
        .next()
        .transpose()
        .map_err(InstallError::storage(context.clone()))?;
    let second = entries
        .next()
        .transpose()
        .map_err(InstallError::storage(context.clone()))?;
    if let (Some(only), None) = (first, second) {
        let is_dir = only
            .file_type()
            .map_err(InstallError::storage(context))?
            .is_dir();
        if is_dir {
            return Ok(only.path());
        }
    }
    Ok(dest.to_path_buf())
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;
    use crate::update::install::test_support::TempDir;

    fn tar_gz(entries: &[(&str, &[u8], u32)], raw_name: Option<&str>) -> io::Result<Vec<u8>> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, data, mode) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(*mode);
            header.set_entry_type(tar::EntryType::Regular);
            if let Some(raw) = raw_name {
                // `set_path` 会拒绝 `..`；直接写名字字段才能造出恶意归档。
                let gnu = header
                    .as_gnu_mut()
                    .ok_or_else(|| io::Error::other("gnu header"))?;
                gnu.name = [0; 100];
                gnu.name[..raw.len()].copy_from_slice(raw.as_bytes());
                header.set_cksum();
                builder.append(&header, *data)?;
            } else {
                builder.append_data(&mut header, name, *data)?;
            }
        }
        let tar = builder.into_inner()?;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(&tar)?;
        encoder.finish()
    }

    fn zip_archive(entries: &[(&str, &[u8])]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        let mut writer = zip::ZipWriter::new(io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated)
            .unix_permissions(0o755);
        for (name, data) in entries {
            writer.start_file(*name, options)?;
            writer.write_all(data)?;
        }
        Ok(writer.finish()?.into_inner())
    }

    #[test]
    fn tar_gz_unwraps_the_single_top_directory_and_keeps_exec_bits()
    -> Result<(), Box<dyn std::error::Error>> {
        let dir = TempDir::new("archive-tar")?;
        let package = dir.path().join("fluxdown-server-1.0.0-linux-x64.tar.gz");
        fs::write(
            &package,
            tar_gz(
                &[
                    (
                        "fluxdown-server-1.0.0-linux-x64/fluxdown-agent",
                        b"agent",
                        0o755,
                    ),
                    (
                        "fluxdown-server-1.0.0-linux-x64/fluxdownd",
                        b"daemon",
                        0o755,
                    ),
                ],
                None,
            )?,
        )?;
        let root = extract(&package, &dir.path().join("out"))?;
        assert_eq!(
            root.file_name(),
            Some(OsStr::new("fluxdown-server-1.0.0-linux-x64"))
        );
        assert_eq!(fs::read(root.join("fluxdown-agent"))?, b"agent");
        assert_eq!(fs::read(root.join("fluxdownd"))?, b"daemon");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;

            let mode = fs::metadata(root.join("fluxdown-agent"))?
                .permissions()
                .mode();
            assert_eq!(mode & 0o111, 0o111, "exec bits preserved");
        }
        Ok(())
    }

    /// macOS bsdtar 打包时夹带的 AppleDouble 条目不能破坏顶层目录展开，也不能落进程序目录。
    #[test]
    fn macos_metadata_entries_are_skipped() -> Result<(), Box<dyn std::error::Error>> {
        let dir = TempDir::new("archive-appledouble")?;
        let package = dir.path().join("fluxdown-server-1.0.0-macos-arm64.tar.gz");
        fs::write(
            &package,
            tar_gz(
                &[
                    ("._fluxdown-server-1.0.0-macos-arm64", b"meta", 0o644),
                    (
                        "fluxdown-server-1.0.0-macos-arm64/fluxdown-agent",
                        b"agent",
                        0o755,
                    ),
                    (
                        "fluxdown-server-1.0.0-macos-arm64/._fluxdown-agent",
                        b"meta",
                        0o644,
                    ),
                    (
                        "fluxdown-server-1.0.0-macos-arm64/fluxdownd",
                        b"daemon",
                        0o755,
                    ),
                ],
                None,
            )?,
        )?;
        let root = extract(&package, &dir.path().join("out"))?;
        assert_eq!(
            root.file_name(),
            Some(OsStr::new("fluxdown-server-1.0.0-macos-arm64"))
        );
        assert!(!root.join("._fluxdown-agent").exists());
        assert_eq!(fs::read(root.join("fluxdown-agent"))?, b"agent");
        Ok(())
    }

    #[test]
    fn zip_with_flat_root_is_not_unwrapped() -> Result<(), Box<dyn std::error::Error>> {
        let dir = TempDir::new("archive-zip-flat")?;
        let package = dir.path().join("FluxDown-portable.zip");
        fs::write(
            &package,
            zip_archive(&[
                ("fluxdown-agent.exe", b"agent"),
                ("portable", b""),
                ("data/readme.txt", b"hi"),
            ])?,
        )?;
        let out = dir.path().join("out");
        let root = extract(&package, &out)?;
        assert_eq!(root, out);
        assert_eq!(fs::read(root.join("data").join("readme.txt"))?, b"hi");
        Ok(())
    }

    #[test]
    fn zip_with_single_top_directory_is_unwrapped() -> Result<(), Box<dyn std::error::Error>> {
        let dir = TempDir::new("archive-zip-top")?;
        let package = dir.path().join("server.zip");
        fs::write(
            &package,
            zip_archive(&[
                (
                    "fluxdown-server-1.0.0-windows-x64/fluxdown-agent.exe",
                    b"agent",
                ),
                ("fluxdown-server-1.0.0-windows-x64/fluxdownd.exe", b"daemon"),
            ])?,
        )?;
        let root = extract(&package, &dir.path().join("out"))?;
        assert!(root.join("fluxdown-agent.exe").is_file());
        assert!(root.join("fluxdownd.exe").is_file());
        Ok(())
    }

    #[test]
    fn zip_rejects_path_traversal_and_writes_nothing_outside()
    -> Result<(), Box<dyn std::error::Error>> {
        for name in [
            "../evil.txt",
            "a/../../evil.txt",
            "..\\evil.txt",
            "/abs/evil.txt",
            "C:/evil.txt",
        ] {
            let dir = TempDir::new("archive-zip-evil")?;
            let package = dir.path().join("evil.zip");
            fs::write(
                &package,
                zip_archive(&[("ok.txt", b"ok"), (name, b"evil")])?,
            )?;
            let out = dir.path().join("out");
            let error = extract(&package, &out)
                .err()
                .ok_or_else(|| io::Error::other(format!("{name} must be rejected")))?;
            assert!(
                matches!(error, InstallError::InvalidPackage(_)),
                "{name}: {error:#}"
            );
            assert!(!dir.path().join("evil.txt").exists(), "{name}");
            assert!(!out.join("evil.txt").exists(), "{name}");
        }
        Ok(())
    }

    #[test]
    fn tar_rejects_path_traversal_links_and_unknown_types() -> Result<(), Box<dyn std::error::Error>>
    {
        let dir = TempDir::new("archive-tar-evil")?;
        let package = dir.path().join("evil.tar.gz");
        fs::write(
            &package,
            tar_gz(&[("x", b"evil", 0o644)], Some("../evil.txt"))?,
        )?;
        let out = dir.path().join("out");
        let error = extract(&package, &out)
            .err()
            .ok_or_else(|| io::Error::other("traversal must be rejected"))?;
        assert!(
            matches!(error, InstallError::InvalidPackage(_)),
            "{error:#}"
        );
        assert!(!dir.path().join("evil.txt").exists());

        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Symlink);
        header.set_size(0);
        builder.append_link(&mut header, "pkg/link", "/etc/passwd")?;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(&builder.into_inner()?)?;
        let package = dir.path().join("link.tar.gz");
        fs::write(&package, encoder.finish()?)?;
        let error = extract(&package, &dir.path().join("out2"))
            .err()
            .ok_or_else(|| io::Error::other("symlink must be rejected"))?;
        assert!(
            matches!(error, InstallError::InvalidPackage(_)),
            "{error:#}"
        );
        Ok(())
    }

    #[test]
    fn unknown_extension_and_garbage_are_invalid_packages() -> Result<(), Box<dyn std::error::Error>>
    {
        let dir = TempDir::new("archive-garbage")?;
        let rar = dir.path().join("x.rar");
        fs::write(&rar, b"x")?;
        assert!(matches!(
            extract(&rar, &dir.path().join("o1")),
            Err(InstallError::InvalidPackage(_))
        ));
        let zip = dir.path().join("x.zip");
        fs::write(&zip, b"not a zip")?;
        assert!(matches!(
            extract(&zip, &dir.path().join("o2")),
            Err(InstallError::InvalidPackage(_))
        ));
        Ok(())
    }
}

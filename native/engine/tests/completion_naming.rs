//! 完成期定名集成测试：引擎自行推断的文件名（HEAD 被拒时的 URL 占位、无扩展名）
//! 在下载完成时按实际 GET 响应的 Content-Disposition 与文件头魔数精修；用户显式给
//! 的名字永不改写。

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::sync::Arc;

use fluxdown_engine::bt_downloader::BtConfig;
use fluxdown_engine::download_manager::NewTaskSpec;
use fluxdown_engine::proxy_config::ProxyConfig;
use fluxdown_engine::{Engine, EngineConfig, NoopSelection, NoopSink};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const PNG: &[u8] = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR-test-body";

/// 每个连接读一个请求，`handler(method, path)` 给出完整原始响应。
async fn spawn_http(handler: impl Fn(&str, &str) -> Vec<u8> + Send + Sync + 'static) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handler = Arc::new(handler);
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            let handler = handler.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut tmp = [0u8; 1024];
                while !buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    let n = stream.read(&mut tmp).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    buf.extend_from_slice(&tmp[..n]);
                }
                let text = String::from_utf8_lossy(&buf);
                let mut request_line = text.lines().next().unwrap_or("").split(' ');
                let method = request_line.next().unwrap_or("").to_owned();
                let path = request_line.next().unwrap_or("").to_owned();
                if let Err(error) = stream.write_all(&handler(&method, &path)).await {
                    eprintln!("test HTTP client closed connection: {error}");
                }
                if let Err(error) = stream.shutdown().await {
                    eprintln!("test HTTP client closed connection: {error}");
                }
            });
        }
    });
    port
}

fn response(method: &str, status: &str, headers: &str, body: &[u8]) -> Vec<u8> {
    let mut out = format!(
        "HTTP/1.1 {status}\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .into_bytes();
    if method != "HEAD" {
        out.extend_from_slice(body);
    }
    out
}

/// 建一个单连接 HTTP 任务，跑到完成，返回 (DB 中的最终文件名, 磁盘内容)。
async fn download(port: u16, path: &str, file_name: &str) -> (String, Vec<u8>) {
    download_with_hint(port, path, file_name, 0).await
}

/// 同 [`download`]，`hint_file_size > 0` 时走跳过 probe 的 hint 模式。
async fn download_with_hint(
    port: u16,
    path: &str,
    file_name: &str,
    hint_file_size: i64,
) -> (String, Vec<u8>) {
    let work = std::env::temp_dir().join(format!("fluxdown-naming-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&work).await.unwrap();
    let mut engine = Engine::new(
        EngineConfig {
            max_concurrent: 1,
            speed_limit_bps: 0,
            upload_limit_bps: 0,
            default_save_dir: work.to_string_lossy().into_owned(),
            app_data_dir: work.to_string_lossy().into_owned(),
            bt_config: BtConfig::default(),
            proxy_config: ProxyConfig::default(),
            user_agent: String::new(),
            data_dir_override: Some(work.clone()),
            database_url: None,
        },
        Arc::new(NoopSink),
        Arc::new(NoopSelection),
    )
    .await
    .unwrap();
    let mut done_rx = engine.manager.take_done_rx().unwrap();
    let mut progress_rx = engine.manager.take_progress_rx().unwrap();
    tokio::spawn(async move { while progress_rx.recv().await.is_some() {} });

    let id = engine
        .manager
        .create_task(NewTaskSpec {
            url: format!("http://127.0.0.1:{port}{path}"),
            save_dir: work.to_string_lossy().into_owned(),
            file_name: file_name.to_owned(),
            segments: 1,
            hint_file_size,
            ..Default::default()
        })
        .await
        .unwrap();
    let done = tokio::time::timeout(std::time::Duration::from_secs(20), done_rx.recv())
        .await
        .unwrap()
        .unwrap();
    engine.manager.on_task_done(&done).await;
    let task = engine.db.load_task_by_id(&id).await.unwrap().unwrap();
    assert_eq!(task.status, 3, "download failed: {}", task.error_message);
    let content = tokio::fs::read(work.join(&task.file_name)).await.unwrap();
    engine.manager.shutdown().await;
    drop(engine);
    if let Err(error) = tokio::fs::remove_dir_all(&work).await
        && error.kind() != std::io::ErrorKind::NotFound
    {
        eprintln!("best-effort test directory cleanup: {error}");
    }
    (task.file_name, content)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn head_rejected_endpoint_is_named_from_get_content_disposition() {
    // Discuz/预签名 URL 形态：HEAD 405，只有 GET 带 Content-Disposition。启动时只能用
    // URL 段 `attachment.php` 占位，完成时必须换成服务器给的真名。
    let port = spawn_http(|method, _| {
        if method == "HEAD" {
            return response(method, "405 Method Not Allowed", "", b"");
        }
        response(
            method,
            "200 OK",
            "Content-Type: application/pdf\r\n\
             Content-Disposition: attachment; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf\r\n",
            b"%PDF-1.7 body",
        )
    })
    .await;
    let (name, content) = download(port, "/forum/attachment.php?aid=42", "").await;
    assert_eq!(name, "季度报告.pdf");
    assert_eq!(content, b"%PDF-1.7 body");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn extensionless_octet_stream_gets_extension_from_file_magic() {
    let port = spawn_http(|method, _| {
        response(
            method,
            "200 OK",
            "Content-Type: application/octet-stream\r\n",
            PNG,
        )
    })
    .await;
    let (name, content) = download(port, "/media/GxYzAbC", "").await;
    assert_eq!(name, "GxYzAbC.png");
    assert_eq!(content, PNG);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn explicit_name_is_never_refined() {
    let port = spawn_http(|method, _| {
        response(
            method,
            "200 OK",
            "Content-Type: image/png\r\n\
             Content-Disposition: attachment; filename=\"server.png\"\r\n",
            PNG,
        )
    })
    .await;
    let (name, _) = download(port, "/media/GxYzAbC", "keep-this").await;
    assert_eq!(name, "keep-this");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn hint_mode_without_probe_uses_first_get_content_disposition() {
    // 浏览器扩展 / 用户脚本面板：带大小提示（跳过 probe，保护一次性 URL）且名字留空。
    // 引擎看到 Content-Disposition 的唯一机会是真正下载的那条 GET。
    const BODY: &[u8] = b"PK\x03\x04 archive body";
    let port = spawn_http(|method, _| {
        if method == "HEAD" {
            return response(method, "403 Forbidden", "", b"");
        }
        response(
            method,
            "200 OK",
            "Content-Type: application/zip\r\n\
             Content-Disposition: attachment; filename=\"pack.zip\"\r\n",
            BODY,
        )
    })
    .await;
    let (name, content) = download_with_hint(port, "/dl.php?id=7", "", BODY.len() as i64).await;
    assert_eq!(name, "pack.zip");
    assert_eq!(content, BODY);
}

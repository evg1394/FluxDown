//! `fluxdown-agent` 官方客户端常驻后端。
// 发布构建由开机自启直接拉起：GUI 子系统，登录时不弹控制台窗口。
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

/// mimalloc 全局分配器：多线程 tokio 下吞吐与内存碎片均优于 musl/glibc 默认分配器。
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn main() -> fluxdown_agent::runtime::AgentResult {
    let args: Vec<String> = std::env::args().skip(1).collect();
    #[cfg(windows)]
    if let Some(result) = fluxdown_agent::notification::handle_activation(&args) {
        fluxdown_agent::logging::init_desktop();
        let result = result.map_err(|error| -> Box<dyn std::error::Error + Send + Sync> {
            std::io::Error::other(error).into()
        });
        fluxdown_agent::logging::finish(&result);
        return result;
    }
    // `--server`：headless 服务器形态（NAS / Docker）。不启动托盘、不接入桌面集成，
    // 监听地址、访问密钥、Web UI 等全部来自 `FLUXDOWN_*` 环境变量。
    if args.iter().any(|arg| arg == "--server") {
        return fluxdown_agent::server_mode::run_blocking(
            fluxdown_agent::shell::ShellHost::without_tray(
                fluxdown_protocol::TrayUnavailableReason::NotBuilt,
                false,
            ),
        );
    }
    let autostart = args
        .iter()
        .any(|arg| arg == fluxdown_agent::platform::AUTOSTART_ARG);
    fluxdown_agent::logging::init_desktop();
    let result = {
        #[cfg(feature = "desktop")]
        {
            fluxdown_agent::shell::host::run(autostart, fluxdown_agent::runtime::run_blocking)
        }
        #[cfg(not(feature = "desktop"))]
        {
            fluxdown_agent::runtime::run_blocking(fluxdown_agent::shell::ShellHost::without_tray(
                fluxdown_protocol::TrayUnavailableReason::NotBuilt,
                autostart,
            ))
        }
    };
    fluxdown_agent::logging::finish(&result);
    result
}

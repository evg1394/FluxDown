const SOURCE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../userscript/fluxdown.user.js"
));
const INSTALL_CONFIG: &str = "const INSTALL_CONFIG = { port: 17800, token: '' };";

pub(super) fn generate(port: u16, token: &str) -> Result<String, serde_json::Error> {
    let config = serde_json::to_string(&serde_json::json!({ "port": port, "token": token }))?;
    Ok(SOURCE.replacen(
        INSTALL_CONFIG,
        &format!("const INSTALL_CONFIG = {config};"),
        1,
    ))
}

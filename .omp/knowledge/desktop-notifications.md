# Desktop native notification validation

Implementation coordinates: `native/agent/src/notification/` (native delivery and
activation), `background_effects.rs` (completion batching), and
`scripts/desktop-dev/src/macos.rs` (development bundle identity).

## Desktop development runner

Run from the repository root:

```sh
cargo desktop-dev --build-only
cargo desktop-dev
```

The first command builds without launching, activating, or registering applications.
On macOS it also creates and verifies an ad-hoc-signed bundle, printing its path:
`<Cargo output>/desktop-dev/<generation>/FluxDown.app`. The second command retains
the existing activation/reuse behavior; an already-running UI is activated without
rebuilding. The runner accepts only `--build-only` and help, not arbitrary UI args.

## macOS native notification validation

1. Use a session without an existing FluxDown UI/agent/daemon if you need to test
   newly built code. Explicitly quit services yourself when appropriate: the runner
   never stops them, and an existing service is reused, including a production one.
2. Run `cargo desktop-dev --build-only` to check the full build and bundle signing.
3. Run `cargo desktop-dev`, then use the notification test action in Settings'
   diagnostics/doctor page. Allow notifications when macOS asks. Check System
   Settings → Notifications → FluxDown, and confirm the delivered notification
   shows **FluxDown**, its icon, and the expected body. Also test a download
   completion with notifications enabled. Test both action buttons (macOS may
   put them under Options) and clicking the notification body: the body and folder
   button reveal the file, while Open File uses the default application. Repeat
   from Notification Center, after closing the UI, and after quitting the agent.
   For a merged batch, actions target the final file named in the notification.
   Moving/deleting the file must make Open File fail rather than open another file;
   the folder action may still open its existing parent.

Existing Script Editor notifications are not migrated. Validate a newly completed
download, not a notification emitted by an older process.

The agent is a regular copied executable at
`FluxDown.app/Contents/Helpers/FluxDownAgent.app/Contents/MacOS/fluxdown-agent`,
with main bundle identity `com.fluxdown.app.agent`. Daemon and native messaging
relay remain adjacent. No executable symlinks are used. Both bundles have icons
and minimal plists; the helper is `LSUIElement`. No URL/document handlers are
claimed by the dev plists.

At launch (not build-only), the runner registers the exact helper path with
LaunchServices and defaults `FLUXDOWN_AGENT_BIN` to its bundled executable. Direct
spawn preserves inherited environment settings such as `FLUXDOWN_DATA_DIR` and
`FLUXDOWN_AGENT_DATA_DIR`; an explicit `FLUXDOWN_AGENT_BIN` remains authoritative
and must itself point into a valid bundle to test native notifications. This dev
launch can retain the desktop Dock icon while its agent remains alive; release
launching uses LaunchServices instead. No notification backend/event-loop changes
are made by this runner.

### Limitations

- Dev and production intentionally share bundle identities. Notification consent,
  display-name caches and LaunchServices records are not isolated by data directory.
  Ad-hoc rebuilds can change signing identity/permission behavior; do not reset the
  system notification database or production permissions just to test this runner.
- macOS consent, Focus settings and the notification backend determine actual
  delivery. Successful signing is not an end-to-end notification test.
- Each build keeps a fresh staging generation so resident agents and registered
  relay paths are not overwritten. These can be large; remove old generations only
  after their services have exited and any relay registration no longer uses them.
- Bundles are local development artifacts, not notarized distribution packages.
- Changing a data directory alone does not isolate fixed service ports. Do not
  assume this safely runs alongside production.

## Windows runtime validation

Use a freshly built Windows desktop package; compile checks do not exercise the
notification service. Verify the FluxDown name/icon, both localized buttons, body
click, Notification Center activation, and clicking after the agent has exited.
The dedicated `fluxdown-notification:` protocol launches only a bounded local
file action and must not start a download service or open a new task dialog.

Action records are bearer capabilities in the private agent data directory.
They expire after 24 hours and are bounded to 64 records; pruning runs only when
sending. Older notifications may be evicted. Do not publish those files or token
URIs. An unknown/expired token, malformed URI, extra CLI flags, or URI-supplied
path must fail without opening anything. Changing the registered executable or
data directory may invalidate older notifications.

## Bounded checks (no UI launch)

```sh
cargo check -p fluxdown-desktop-dev --bin fluxdown-desktop-dev
cargo test -p fluxdown-desktop-dev
cargo clippy -p fluxdown-desktop-dev --all-targets -- -D warnings
rustfmt --edition 2024 --check scripts/desktop-dev/src/main.rs scripts/desktop-dev/src/macos.rs
bash -n scripts/package_gpui_macos.sh
```

macOS tests check layout/plist planning and assemble a real signed fixture using
copies of `/usr/bin/true`, exercising icon generation, plist linting and deep
signature verification without launching or registering anything.

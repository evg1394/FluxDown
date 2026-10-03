// Native notifications require the agent helper bundle, never the outer UI or a
// borrowed system identity. Keep this backend optional for headless macOS builds.
#[cfg(feature = "desktop")]
pub(super) use native::{availability, initialize, show};

#[cfg(not(feature = "desktop"))]
pub(super) fn initialize() -> Result<(), String> {
    Err("native macOS notifications require the desktop feature and agent app bundle".into())
}

#[cfg(not(feature = "desktop"))]
pub(super) fn availability() -> super::NotificationAvailability {
    super::NotificationAvailability::Unavailable(
        "native macOS notifications require the desktop feature and agent app bundle".into(),
    )
}

#[cfg(not(feature = "desktop"))]
pub(super) fn show(
    _title: &str,
    _body: &str,
    _actions: Option<&super::CompletionActions>,
) -> Result<(), String> {
    initialize()
}

#[cfg(feature = "desktop")]
mod native {
    use std::cell::OnceCell;
    use std::path::{Component, Path, PathBuf};
    use std::ptr::NonNull;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::{self, SyncSender};
    use std::time::Duration;

    use block2::{DynBlock, RcBlock};
    use objc2::rc::{Retained, autoreleasepool};
    use objc2::runtime::{Bool, ProtocolObject};
    use objc2::{AnyThread, MainThreadMarker, define_class, msg_send};
    use objc2_foundation::{
        NSArray, NSBundle, NSDictionary, NSError, NSObject, NSObjectProtocol, NSSet, NSString,
        ns_string,
    };
    use objc2_user_notifications::{
        UNAuthorizationOptions, UNAuthorizationStatus, UNMutableNotificationContent,
        UNNotification, UNNotificationAction, UNNotificationActionOptions, UNNotificationCategory,
        UNNotificationCategoryOptions, UNNotificationDefaultActionIdentifier,
        UNNotificationPresentationOptions, UNNotificationRequest, UNNotificationResponse,
        UNNotificationSetting, UNNotificationSettings, UNUserNotificationCenter,
        UNUserNotificationCenterDelegate,
    };

    use super::super::{CompletionActions, NotificationAvailability};

    const BUNDLE_ID: &str = "com.fluxdown.app.agent";
    const CATEGORY: &str = "fluxdown.download-complete.v1";
    const PATH_KEY: &str = "fluxdown.completion-path.v1";
    const REQUEST_PREFIX: &str = "fluxdown.notification.";
    const CALLBACK_TIMEOUT: Duration = Duration::from_secs(5);
    const AUTHORIZATION_TIMEOUT: Duration = Duration::from_secs(60);

    // UNUserNotificationCenter.delegate is weak. Only initialize() on the main thread
    // accesses this strong reference; the main thread lives until process exit.
    // Apple's background callbacks use no delegate state or thread-local storage.
    thread_local! {
        static DELEGATE: OnceCell<Retained<NotificationDelegate>> = const { OnceCell::new() };
    }
    static INITIALIZED: AtomicBool = AtomicBool::new(false);

    define_class!(
        // SAFETY: NSObject has no subclass requirements; there are no ivars or Drop
        // implementation, and callbacks touch only their arguments/thread-safe APIs.
        #[unsafe(super = NSObject)]
        #[name = "FluxDownAgentNotificationDelegate"]
        struct NotificationDelegate;

        // SAFETY: NSObjectProtocol adds no implementation requirements.
        unsafe impl NSObjectProtocol for NotificationDelegate {}

        // SAFETY: Selectors and argument types match UNUserNotificationCenterDelegate;
        // both callbacks complete exactly once, without retaining borrowed arguments.
        unsafe impl UNUserNotificationCenterDelegate for NotificationDelegate {
            // SAFETY: The selector's object arguments and completion block match Apple's ABI;
            // the block is borrowed for this call and completed once without retaining inputs.
            #[unsafe(method(userNotificationCenter:willPresentNotification:withCompletionHandler:))]
            fn will_present(
                &self,
                _center: &UNUserNotificationCenter,
                _notification: &UNNotification,
                completion: &DynBlock<dyn Fn(UNNotificationPresentationOptions)>,
            ) {
                // Alert also supports the package's macOS 10.15 deployment target;
                // Banner/List were introduced in macOS 11.
                #[allow(deprecated)]
                let options = UNNotificationPresentationOptions::Alert
                    | UNNotificationPresentationOptions::Banner
                    | UNNotificationPresentationOptions::List;
                completion.call((options,));
            }

            // SAFETY: The response and void completion block match Apple's delegate ABI;
            // only owned Rust action data escapes, after invoking completion exactly once.
            #[unsafe(method(userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:))]
            fn did_receive(
                &self,
                _center: &UNUserNotificationCenter,
                response: &UNNotificationResponse,
                completion: &DynBlock<dyn Fn()>,
            ) {
                let activation = autoreleasepool(|_| decode_response(response));
                completion.call(());
                if let Some((actions, action)) = activation {
                    // File opening may block; never hold the system callback queue.
                    if let Err(error) = std::thread::Builder::new()
                        .name("notification-action".into())
                        .spawn(move || actions.activate(action))
                    {
                        tracing::warn!(%error, "could not start notification action");
                    }
                }
            }
        }
    );

    pub(in super::super) fn initialize() -> Result<(), String> {
        if MainThreadMarker::new().is_none() {
            return Err("notification delegate must be initialized on the main thread before the event loop".into());
        }
        autoreleasepool(|_| {
            let center = checked_center()?;
            DELEGATE.with(|slot| {
                let delegate = slot.get_or_init(|| {
                    let allocated = NotificationDelegate::alloc();
                    // SAFETY: NSObject's init is valid for this stateless subclass and
                    // returns an owned instance retained in main-thread TLS until exit.
                    unsafe { msg_send![allocated, init] }
                });
                center.setDelegate(Some(ProtocolObject::from_ref(&**delegate)));
            });
            INITIALIZED.store(true, Ordering::Release);
            Ok(())
        })
    }

    fn checked_center() -> Result<Retained<UNUserNotificationCenter>, String> {
        let bundle = NSBundle::mainBundle();
        let identifier = bundle.bundleIdentifier().map(|value| value.to_string());
        let bundle_path = PathBuf::from(bundle.bundlePath().to_string());
        let executable = bundle
            .executablePath()
            .map(|value| PathBuf::from(value.to_string()));
        let expected_executable = bundle_path.join("Contents/MacOS/fluxdown-agent");
        if identifier.as_deref() != Some(BUNDLE_ID)
            || bundle_path.extension().is_none_or(|ext| ext != "app")
            || executable.as_deref() != Some(expected_executable.as_path())
        {
            return Err(format!(
                "native notifications require the {BUNDLE_ID} agent .app bundle; bare cargo binaries are unsupported"
            ));
        }
        // currentNotificationCenter can abort for an unbundled process: all identity
        // checks must precede this call, including on the read-only diagnostic path.
        Ok(UNUserNotificationCenter::currentNotificationCenter())
    }

    fn worker_center() -> Result<Retained<UNUserNotificationCenter>, String> {
        if MainThreadMarker::new().is_some() {
            return Err("blocking notification operations must run off the main thread".into());
        }
        if !INITIALIZED.load(Ordering::Acquire) {
            return Err("native notification delegate has not been initialized".into());
        }
        checked_center()
    }

    fn deliver<T>(sender: &SyncSender<T>, value: T) {
        if let Err(error) = sender.try_send(value) {
            // Timeout is an expected possibility (especially an unanswered permission
            // prompt). Do not panic or block the framework's callback queue.
            tracing::debug!(%error, "notification callback receiver is no longer waiting");
        }
    }

    fn settings(
        center: &UNUserNotificationCenter,
    ) -> Result<
        (
            UNAuthorizationStatus,
            UNNotificationSetting,
            UNNotificationSetting,
        ),
        String,
    > {
        let (sender, receiver) = mpsc::sync_channel(1);
        let callback = RcBlock::new(move |settings: NonNull<UNNotificationSettings>| {
            // SAFETY: Apple supplies a live, nonnull settings object for this callback;
            // only scalar values escape the callback's lifetime.
            let settings = unsafe { settings.as_ref() };
            deliver(
                &sender,
                (
                    settings.authorizationStatus(),
                    settings.alertSetting(),
                    settings.notificationCenterSetting(),
                ),
            );
        });
        center.getNotificationSettingsWithCompletionHandler(&callback);
        receiver
            .recv_timeout(CALLBACK_TIMEOUT)
            .map_err(|error| format!("could not read macOS notification settings: {error}"))
    }

    pub(in super::super) fn availability() -> NotificationAvailability {
        let result = autoreleasepool(|_| worker_center().and_then(|center| settings(&center)));
        match result {
            Err(error) => NotificationAvailability::Unavailable(error),
            Ok((UNAuthorizationStatus::Denied, _, _)) => NotificationAvailability::Blocked(
                "FluxDown notifications are disabled in macOS System Settings".into(),
            ),
            Ok((UNAuthorizationStatus::NotDetermined, _, _)) => {
                NotificationAvailability::Unverifiable
            }
            Ok((status, alert, list)) if authorized(status) => {
                if alert == UNNotificationSetting::Disabled
                    && list == UNNotificationSetting::Disabled
                {
                    NotificationAvailability::Blocked(
                        "macOS banners and Notification Center delivery are disabled for FluxDown"
                            .into(),
                    )
                } else {
                    NotificationAvailability::Available(format!(
                        "UserNotifications sender {BUNDLE_ID}; banner setting {}",
                        alert.0
                    ))
                }
            }
            Ok(_) => NotificationAvailability::Unverifiable,
        }
    }

    fn authorized(status: UNAuthorizationStatus) -> bool {
        matches!(
            status,
            UNAuthorizationStatus::Authorized
                | UNAuthorizationStatus::Provisional
                | UNAuthorizationStatus::Ephemeral
        )
    }

    fn request_permission(center: &UNUserNotificationCenter) -> Result<(), String> {
        let (status, _, _) = settings(center)?;
        if authorized(status) {
            return Ok(());
        }
        if status != UNAuthorizationStatus::NotDetermined {
            return Err("macOS notification permission is denied or unsupported".into());
        }
        let (sender, receiver) = mpsc::sync_channel(1);
        let callback = RcBlock::new(move |granted: Bool, error: *mut NSError| {
            // SAFETY: The nullable NSError is provided by Apple and valid during the
            // callback. Its description is copied before returning.
            let result = match unsafe { error.as_ref() } {
                Some(error) => Err(error.localizedDescription().to_string()),
                None if granted.as_bool() => Ok(()),
                None => Err("macOS notification permission was not granted".into()),
            };
            deliver(&sender, result);
        });
        center.requestAuthorizationWithOptions_completionHandler(
            UNAuthorizationOptions::Alert,
            &callback,
        );
        receiver
            .recv_timeout(AUTHORIZATION_TIMEOUT)
            .map_err(|error| {
                format!("macOS notification permission request did not complete: {error}")
            })?
    }

    pub(in super::super) fn show(
        title: &str,
        body: &str,
        actions: Option<&CompletionActions>,
    ) -> Result<(), String> {
        autoreleasepool(|_| show_in_pool(title, body, actions))
    }

    fn show_in_pool(
        title: &str,
        body: &str,
        actions: Option<&CompletionActions>,
    ) -> Result<(), String> {
        let center = worker_center()?;
        let content = UNMutableNotificationContent::new();
        content.setTitle(&NSString::from_str(title));
        content.setBody(&NSString::from_str(body));
        if let Some(actions) = actions {
            let path = actions
                .path
                .to_str()
                .filter(|path| valid_path(path))
                .ok_or_else(|| {
                    "notification action requires an absolute UTF-8 filesystem path, not a URI"
                        .to_owned()
                })?;
            register_category(&center, actions);
            content.setCategoryIdentifier(&NSString::from_str(CATEGORY));
            let key = NSString::from_str(PATH_KEY);
            let value = NSString::from_str(path);
            let info = NSDictionary::from_slices(&[&*key], &[&*value]);
            let info = info
                .downcast::<NSDictionary>()
                .map_err(|_| "notification userInfo is not an NSDictionary".to_owned())?;
            // SAFETY: All keys and values are NSString, valid property-list objects.
            unsafe { content.setUserInfo(&info) };
        }
        // Only sending may ask for permission; initialization and diagnostics never do.
        request_permission(&center)?;
        let identifier = NSString::from_str(&format!("{REQUEST_PREFIX}{}", uuid::Uuid::new_v4()));
        let request = UNNotificationRequest::requestWithIdentifier_content_trigger(
            &identifier,
            &content,
            None,
        );
        let (sender, receiver) = mpsc::sync_channel(1);
        let callback = RcBlock::new(move |error: *mut NSError| {
            // SAFETY: Apple's optional NSError is borrowed only within this callback.
            let result = unsafe { error.as_ref() }.map_or(Ok(()), |error| {
                Err(error.localizedDescription().to_string())
            });
            deliver(&sender, result);
        });
        center.addNotificationRequest_withCompletionHandler(&request, Some(&callback));
        receiver.recv_timeout(CALLBACK_TIMEOUT).map_err(|error| {
            format!("macOS notification submission did not complete (delivery is unknown): {error}")
        })?
    }

    fn register_category(center: &UNUserNotificationCenter, actions: &CompletionActions) {
        // Foreground lets macOS relaunch the helper on action activation. Localization
        // is supplied by the facade; no translated labels are embedded in this backend.
        let open = UNNotificationAction::actionWithIdentifier_title_options(
            ns_string!("open-file"),
            &NSString::from_str(&actions.open_file_label),
            UNNotificationActionOptions::Foreground,
        );
        let reveal = UNNotificationAction::actionWithIdentifier_title_options(
            ns_string!("open-folder"),
            &NSString::from_str(&actions.open_folder_label),
            UNNotificationActionOptions::Foreground,
        );
        let category =
            UNNotificationCategory::categoryWithIdentifier_actions_intentIdentifiers_options(
                &NSString::from_str(CATEGORY),
                &NSArray::from_slice(&[&*open, &*reveal]),
                &NSArray::new(),
                UNNotificationCategoryOptions::empty(),
            );
        center.setNotificationCategories(&NSSet::from_slice(&[&*category]));
    }

    fn decode_response(
        response: &UNNotificationResponse,
    ) -> Option<(CompletionActions, &'static str)> {
        let identifier = response.actionIdentifier();
        // SAFETY: This framework NSString constant exists since macOS 10.14 and is
        // immutable for the process lifetime.
        let default_identifier = unsafe { UNNotificationDefaultActionIdentifier };
        let action = if *identifier == *default_identifier {
            "default"
        } else {
            match identifier.to_string().as_str() {
                "open-file" => "open-file",
                "open-folder" => "open-folder",
                _ => return None,
            }
        };
        let request = response.notification().request();
        let content = request.content();
        if !request.identifier().to_string().starts_with(REQUEST_PREFIX)
            || content.categoryIdentifier().to_string() != CATEGORY
        {
            return None;
        }
        // No application IPC accepts this payload: it is read only from the OS-owned
        // notification response. Runtime downcasting still rejects malformed userInfo.
        let info = content.userInfo();
        let value = info.objectForKey(&NSString::from_str(PATH_KEY))?;
        let path = value.downcast_ref::<NSString>()?.to_string();
        if !valid_path(&path) {
            tracing::warn!("rejected invalid macOS notification action path");
            return None;
        }
        Some((
            CompletionActions {
                path: PathBuf::from(path),
                // Activation uses only path; localized button titles already live in the
                // OS category. Persisting the path allows callbacks after an agent restart.
                open_file_label: String::new(),
                open_folder_label: String::new(),
            },
            action,
        ))
    }

    fn valid_path(value: &str) -> bool {
        let path = Path::new(value);
        path.is_absolute()
            && !value.starts_with("//")
            && !value.contains('\0')
            && !value.contains("://")
            && path.file_name().is_some()
            && !path.components().any(|part| part == Component::ParentDir)
    }

    #[cfg(test)]
    mod tests {
        use super::{BUNDLE_ID, authorized, checked_center, valid_path};
        use objc2_foundation::NSBundle;
        use objc2_user_notifications::UNAuthorizationStatus;

        #[test]
        fn bare_test_binary_never_opens_notification_center() {
            let identifier = NSBundle::mainBundle()
                .bundleIdentifier()
                .map(|value| value.to_string());
            if identifier.as_deref() != Some(BUNDLE_ID) {
                assert!(checked_center().is_err());
            }
        }

        #[test]
        fn action_paths_are_filesystem_only() {
            for path in ["/Users/test/Downloads/a.zip", "/tmp/a 'quoted' 文件.zip"] {
                assert!(valid_path(path), "{path}");
            }
            for path in [
                "",
                "a.zip",
                "file:///tmp/a",
                "https://example.com/a",
                "//host/share/a",
                "/",
                "/tmp/../secret",
                "/tmp/a\0b",
            ] {
                assert!(!valid_path(path), "{path}");
            }
        }

        #[test]
        fn only_known_authorized_states_allow_delivery() {
            assert!(authorized(UNAuthorizationStatus::Authorized));
            assert!(authorized(UNAuthorizationStatus::Provisional));
            assert!(!authorized(UNAuthorizationStatus::NotDetermined));
            assert!(!authorized(UNAuthorizationStatus::Denied));
            assert!(!authorized(UNAuthorizationStatus(99)));
        }
    }
}

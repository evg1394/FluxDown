//! FluxDown 本机服务与客户端共享的传输无关协议类型。
//!
//! 本 crate 只定义 wire 契约；不得依赖下载引擎、网络运行时、数据库或 UI。

pub mod agent;
pub mod capture_link;
pub mod daemon;
pub mod daemon_config;
mod digest;
pub mod error;
pub mod event;
pub mod handshake;
pub mod method;
pub mod rpc;
pub mod settings;
pub mod task_activity;
pub use task_activity::{
    TaskActivityDto, TaskActivityPage, TaskActivityQuery, TaskRuntimeDto, TaskSegmentDto,
    TaskSourceBytesDto,
};

pub use agent::{
    AgentLoginResult, AgentPreferencesDto, AgentPreferencesPatchResult, AgentSessionDto,
    AuthVerificationDto, CUSTOM_CATEGORIES_PREF_KEY, CaptureCreateGroupParams,
    CapturePreviewParams, CaptureResolveParams, CloudConnectionDto, CloudConnectionState,
    CloudDevice, CloudEndpointDto, CloudEndpointSetParams, CloudOrder, CloudPlan,
    CloudPlanCampaign, CloudPlanCampaignStage, CloudProfile, CloudReferralCode,
    CloudReferralCodesResult, CloudReferralRecord, CloudReferralRecordsResult, CloudReferralRule,
    CloudReferralSummary, CloudReferralValidateResult, CloudUser, CloudUserStatus,
    CustomCategoryDto, DiagnosticCheckDto, DiagnosticLevel, DiagnosticRepairParams,
    DiagnosticsReportDto, Entitlements, FILE_ICON_PER_FILE_EXTENSIONS, GatewayPatchParams,
    GatewayStatusDto, LinkAddressParams, LinkApproveParams, LinkDeviceParams, LinkDiscoveryParams,
    LinkDispatchParams, LinkDispatchResult, LinkPairBeginParams, LinkPairFinishParams,
    LinkPairingCodeDto, LinkPairingRequestDto, LogExportParams, LogExportResult, LogPathsDto,
    OriginIdCheckResult, PathStyle, PendingCaptureDto, PlatformFileIconDto, PlatformFileIconParams,
    PlatformIntegrationDto, PlatformOpenPathParams, PlatformToggleParams,
    PlatformUrlProtocolParams, PowerArmParams, PowerStatusDto, ReleaseNoteDto, RemoteCommandAction,
    RemoteCommandParams, RemoteDispatchParams, RemoteDispatchResult, RemoteTaskDto,
    RemoteTaskStatus, ShellStatusDto, SyncLocalOnlyParams, SyncStatusDto, TrayUnavailableReason,
    UpdateCheckParams, UpdateCheckResultDto,
};
pub use daemon::{
    ApiInfo, BtFileDto, CdnConfigApplyParams, CdnNodeDto, CdnReportAckParams, CdnReportLeaseDto,
    ChangeTaskUrlParams, ComponentFfmpegStatus, ComponentInstallParams, ComponentKind,
    ComponentParams, ComponentProbeDto, ComponentRepairParams, ComponentStatusDto,
    ComponentVersions, ComponentYtdlpStatus, ConnPolicySummaryDto, CreateGroupRequest,
    CreateGroupResponse, CreateQueueRequest, CreateTaskRequest, CreatedTask, DaemonConfigPatch,
    DaemonConfigSnapshot, DaemonCreateTaskParams, DaemonDeleteTasksParams, DaemonRuntimeStatsDto,
    DaemonTaskIdsParams, DiagnosticsProbeParams, DiagnosticsProbeResult, DownloadRequest,
    Ed2kServerSubRefreshResponse, FileMissingUpdateDto, FsEntry, FsListResponse,
    GatewayMigrationExport, GroupDto, GroupItemRequest, HlsQualityOptionDto, InstallFfmpegRequest,
    InstallPluginDevRequest, InstalledPlugin, LATER_QUEUE_ID, LinkAuth, LinkCodeResponse,
    LinkDeviceInfo, LinkDeviceTaskRequest, LinkDevicesResponse, LinkDiscoveredPeer,
    LinkDiscoveredResponse, LinkDiscoveryRequest, LinkMigrationExport, LinkOkResponse,
    LinkPairApproveRequest, LinkPairBeginRequest, LinkPairBeginResponse, LinkPairConfirmOutcome,
    LinkPairConfirmRequest, LinkPairFinishRequest, LinkPairFinishResponse, LinkPairHelloRequest,
    LinkPairHelloResponse, LinkPairRevealRequest, LinkPairRevealResponse, LinkPingInfo,
    LinkProbeRequest, LinkTaskRequest, LogFileDto, LogsResponse, MAIN_QUEUE_ID, MarketEntryDto,
    MarketInstallRequest, MigrationAckParams, MoveQueueRequest, PluginAuthRequest,
    PluginAuthResponse, PluginDto, PreviewItemDto, PreviewVariantDto, ProxyTestRequest,
    ProxyTestResponse, QueueDto, QueuePositionDto, QueueScheduleRequest, RenameTaskRequest,
    ReorderQueueRequest, RequestBody, ResolvePreviewRequest, ResolvePreviewResponse,
    ResolveVariantOptionDto, ResultMessage, RssItemActionRequest, RssItemDto, RssSourceDto,
    RssValidateRequest, RssValidateResponse, SegmentDetailDto, SelectionKind, SelectionOutcome,
    SelectionRequestDto, SelectionResolutionDto, SetPluginEnabledRequest, SettingFieldDto,
    SettingOptionDto, SetupRequest, SetupStatusResponse, SiteAuthCredentialDto,
    SiteAuthDeleteParams, SiteAuthEntryDto, SiteAuthGetParams, SiteAuthMatchParams,
    SiteAuthSaveRequest, StatsResponse, StorageProbeDto, StorageProbeFailure, StorageProbeRole,
    StorageProbeTarget, SystemProxyDto, TaskDto, TokenResponse, TrackerSubRefreshResponse,
    UpdateQueueRequest, WebhookDeliveriesResponse, WebhookDeliveryDto, WebhookPresetDto,
    WebhookSimulateResponse, WebhookTestRequest, WebhookTestResponse, WsClientMsg, WsServerMsg,
};
pub use daemon_config::{
    BT_MSE_MODES, BT_SEED_LIMIT_OPERATORS, BT_SEED_THEN_ACTIONS, BT_SEED_TIME_UNITS,
    DAEMON_CONFIG_FIELDS, DaemonConfigError, DaemonConfigField, DaemonConfigKind,
    FILE_EXISTS_BEHAVIORS, FILE_MISSING_ACTIONS, PROXY_MODES, PROXY_TYPES, daemon_config_default,
    daemon_config_field, is_public_daemon_config_key, normalize_daemon_config_patch,
    normalize_daemon_config_value,
};
pub use error::{
    APPLICATION_ERROR_CODE, ApplicationErrorCode, ErrorReason, INTERNAL_ERROR_CODE,
    INVALID_PARAMS_CODE, INVALID_REQUEST_CODE, METHOD_NOT_FOUND_CODE, PARSE_ERROR_CODE,
    RpcErrorData, RpcErrorObject,
};
pub use event::{
    AgentEvent, AgentSnapshot, DaemonEvent, DaemonSnapshot, EventFrame, ServiceEvent, Snapshot,
    SnapshotBody, WEBHOOK_DELIVERY_LIMIT, accepted_runtime_status, apply_agent_event,
    apply_daemon_event, merge_webhook_deliveries,
};
pub use rpc::{
    APP_VERSION, CLOSE_REASON_SERVICE_QUIT, ClientHello, JSONRPC_VERSION, MIN_PROTOCOL_VERSION,
    PROTOCOL_VERSION, RequestId, RpcFailureResponse, RpcIncoming, RpcNotification, RpcRequest,
    RpcResponse, RpcSuccessResponse, ServiceHello, ServiceRole, negotiate_protocol,
    validate_first_request,
};
pub use settings::{
    SYNC_SETTING_SPECS, SettingOwner, SettingSpec, SettingValueKind, daemon_config_to_value,
    setting_spec, setting_value_kind, validate_value, value_to_daemon_config,
};

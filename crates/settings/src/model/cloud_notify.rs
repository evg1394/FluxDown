//! 云端推送的纯逻辑：默认标签、渠道种类与卡片状态、用量、隐私摘要。
//!
//! 不依赖 GPUI / 翻译器，页面与对话框只消费这里的结论。判定样例与 Web 端
//! （`web/src/pages/webhooks/cloudLogic.ts`）逐条一致。

use std::collections::BTreeSet;

use fluxdown_protocol::{
    CloudNotifyChannelDto, CloudNotifyDeliveryDto, CloudNotifyKindDto, CloudNotifyOverviewDto,
    CloudNotifyUsageDto, ErrorReason,
};

// ───────────────────────── 页内标签 ─────────────────────────

/// 推送通知页的分段标签。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PushTab {
    Cloud,
    SelfHosted,
}

impl PushTab {
    pub(crate) const ALL: [Self; 2] = [Self::Cloud, Self::SelfHosted];

    pub(crate) fn label_key(self) -> &'static str {
        match self {
            Self::Cloud => "pushTabCloud",
            Self::SelfHosted => "pushTabSelfHosted",
        }
    }
}

/// 默认标签：本机已配置自托管端点 → 自托管；否则 → 云端推送。
pub(crate) fn default_tab(self_hosted_endpoints: usize) -> PushTab {
    if self_hosted_endpoints > 0 {
        PushTab::SelfHosted
    } else {
        PushTab::Cloud
    }
}

// ───────────────────────── 渠道种类 ─────────────────────────

/// 云端渠道种类：云端只做依赖 FluxDown 自有基础设施的两种——邮件（官方 SMTP）与 Telegram
/// （官方机器人）。固定顺序与 `CloudNotifyStateDto.catalog` 一致。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum ChannelKind {
    Email,
    Telegram,
}

impl ChannelKind {
    pub(crate) const ALL: [Self; 2] = [Self::Email, Self::Telegram];

    pub(crate) fn wire(self) -> &'static str {
        match self {
            Self::Email => "email",
            Self::Telegram => "telegram",
        }
    }

    pub(crate) fn name_key(self) -> &'static str {
        match self {
            Self::Email => "cloudNotifyKindEmail",
            Self::Telegram => "cloudNotifyKindTelegram",
        }
    }

    pub(crate) fn desc_key(self) -> &'static str {
        match self {
            Self::Email => "cloudNotifyKindEmailDesc",
            Self::Telegram => "cloudNotifyKindTelegramDesc",
        }
    }
}

/// 对话框「保存」是否可用：名称非空、事件至少一个。
pub(crate) fn can_save(name: &str, event_count: usize) -> bool {
    !name.trim().is_empty() && event_count > 0
}

// ───────────────────────── 邮件渠道：收件邮箱列表 ─────────────────────────

/// 单个邮件渠道的收件地址上限。
pub(crate) const MAX_RECIPIENTS: usize = fluxdown_protocol::CLOUD_NOTIFY_EMAIL_MAX_ADDRESSES;

/// 邮箱基本格式：`name@domain.tld`，无空白、无多余 `@`，trim 后不超过 254 字节
/// （与账号模块的登录 / 改邮箱校验一致）。
pub(crate) fn is_valid_email(email: &str) -> bool {
    let email = email.trim();
    email.split_once('@').is_some_and(|(name, domain)| {
        !name.is_empty()
            && !domain.contains('@')
            && domain
                .split_once('.')
                .is_some_and(|(a, b)| !a.is_empty() && !b.is_empty())
    }) && !email.chars().any(char::is_whitespace)
        && email.len() <= 254
}

/// 地址规范化：trim + 小写（与云端一致，列表去重与提交都用它）。
pub(crate) fn normalize_address(address: &str) -> String {
    address.trim().to_lowercase()
}

/// 两个邮箱地址是否相同（忽略首尾空白与大小写）。
pub(crate) fn same_address(left: &str, right: &str) -> bool {
    normalize_address(left) == normalize_address(right)
}

/// 对话框打开时的收件列表：新建 = 只有账号邮箱；编辑 = 渠道现有地址（trim + 忽略大小写去重，保持顺序和原文）。
/// 旧数据没有 `addresses` 时退回 `target`，再没有则账号邮箱。取不到账号邮箱时新建列表为空。
pub(crate) fn initial_recipients(
    existing: Option<&CloudNotifyChannelDto>,
    account_email: &str,
) -> Vec<String> {
    let mut list: Vec<String> = Vec::new();
    let source: Vec<&str> = match existing {
        Some(channel) if !channel.addresses.is_empty() => {
            channel.addresses.iter().map(String::as_str).collect()
        }
        Some(channel) if !channel.target.trim().is_empty() => vec![channel.target.as_str()],
        _ => vec![account_email],
    };
    for address in source {
        let address = address.trim().to_owned();
        if !address.is_empty() && !list.iter().any(|item| same_address(item, &address)) {
            list.push(address);
        }
    }
    list
}

/// 列表里是否还能再加（< 5）。
pub(crate) fn can_add_more(list: &[String]) -> bool {
    list.len() < MAX_RECIPIENTS
}

/// 内联「添加邮箱」表单里地址输入的问题；空输入不报错（只是不能提交）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AddressIssue {
    /// 格式不正确。
    Invalid,
    /// 已在列表里。
    Exists,
}

pub(crate) fn address_issue(input: &str, list: &[String]) -> Option<AddressIssue> {
    let input = input.trim();
    if input.is_empty() {
        None
    } else if !is_valid_email(input) {
        Some(AddressIssue::Invalid)
    } else if list.iter().any(|item| same_address(item, input)) {
        Some(AddressIssue::Exists)
    } else {
        None
    }
}

/// 添加这个地址要不要验证码：只有账号邮箱（被移除后加回）直接加回，其余一律需要验证码。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AddMode {
    Direct,
    NeedsCode,
}

pub(crate) fn add_mode(input: &str, account_email: &str) -> AddMode {
    if same_address(input, account_email) {
        AddMode::Direct
    } else {
        AddMode::NeedsCode
    }
}

/// 输入通过格式 / 重复 / 上限检查，可以进入添加流程。
fn addable(input: &str, list: &[String]) -> bool {
    !input.trim().is_empty()
        && is_valid_email(input)
        && address_issue(input, list).is_none()
        && can_add_more(list)
}

/// 「发送验证码」是否可点：需要验证码的合法新地址、不在倒计时 / 发送中。
pub(crate) fn can_send_code(
    input: &str,
    list: &[String],
    account_email: &str,
    cooldown_secs: u32,
    sending: bool,
) -> bool {
    addable(input, list)
        && add_mode(input, account_email) == AddMode::NeedsCode
        && cooldown_secs == 0
        && !sending
}

/// 「验证」是否可点：同上，且验证码是 6 位数字、不在验证中。
pub(crate) fn can_verify(
    input: &str,
    code: &str,
    list: &[String],
    account_email: &str,
    verifying: bool,
) -> bool {
    addable(input, list)
        && add_mode(input, account_email) == AddMode::NeedsCode
        && is_code_complete(code)
        && !verifying
}

/// 免验证码直接加入（账号邮箱）是否可点。
pub(crate) fn can_add_direct(input: &str, list: &[String], account_email: &str) -> bool {
    addable(input, list) && add_mode(input, account_email) == AddMode::Direct
}

/// 把地址（trim 后的原文）加入列表：账号邮箱插到最前，其余追加；格式不对 / 已存在 / 已满返回 `false`。
pub(crate) fn add_recipient(list: &mut Vec<String>, input: &str, account_email: &str) -> bool {
    if !addable(input, list) {
        return false;
    }
    let address = input.trim().to_owned();
    if same_address(&address, account_email) {
        list.insert(0, address);
    } else {
        list.push(address);
    }
    true
}

/// 只剩 1 个时不可移除（渠道至少要有一个收件人）。
pub(crate) fn can_remove(list: &[String]) -> bool {
    list.len() > 1
}

/// 从列表移除；不在列表或只剩 1 个返回 `false`。
pub(crate) fn remove_recipient(list: &mut Vec<String>, address: &str) -> bool {
    if !can_remove(list) {
        return false;
    }
    let before = list.len();
    list.retain(|item| !same_address(item, address));
    list.len() != before
}

/// 收件地址行的徽标：账号邮箱 / 已验证。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RecipientBadge {
    Account,
    Verified,
}

pub(crate) fn recipient_badge(address: &str, account_email: &str) -> RecipientBadge {
    if same_address(address, account_email) {
        RecipientBadge::Account
    } else {
        RecipientBadge::Verified
    }
}

/// 邮件渠道对话框「保存」是否可用：名称 + 事件 + 至少一个收件人。
pub(crate) fn email_can_save(name: &str, event_count: usize, recipients: &[String]) -> bool {
    can_save(name, event_count) && !recipients.is_empty()
}

/// 渠道卡片第二行的收件目标。
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum RecipientSummary {
    None,
    One(String),
    /// 多个地址：显示 `cloudNotifyEmailMore`（`{email} 等 {n} 个邮箱`）。
    Many {
        first: String,
        total: usize,
    },
}

pub(crate) fn recipient_summary(channel: &CloudNotifyChannelDto) -> RecipientSummary {
    match channel.addresses.as_slice() {
        [] if channel.target.is_empty() => RecipientSummary::None,
        [] => RecipientSummary::One(channel.target.clone()),
        [only] => RecipientSummary::One(only.clone()),
        [first, ..] => RecipientSummary::Many {
            first: first.clone(),
            total: channel.addresses.len(),
        },
    }
}

/// 验证码是否填完整（6 位数字）。
pub(crate) fn is_code_complete(code: &str) -> bool {
    let code = code.trim();
    code.len() == 6 && code.chars().all(|ch| ch.is_ascii_digit())
}

/// 验证码有效期（秒）→ 提示里的「N 分钟」，至少 1。
pub(crate) fn code_minutes(expires_in_secs: u32) -> u32 {
    expires_in_secs.div_ceil(60).max(1)
}

// ───────────────────────── 事件与设备 ─────────────────────────

/// 新建渠道默认订阅的事件（完成 + 失败）。
pub(crate) const DEFAULT_EVENTS: [&str; 2] = ["task.completed", "task.failed"];

/// 按 `order`（事件目录顺序）输出已选事件，保证提交顺序稳定。
pub(crate) fn ordered_events<'a>(
    selected: &BTreeSet<String>,
    order: impl IntoIterator<Item = &'a str>,
) -> Vec<String> {
    order
        .into_iter()
        .filter(|wire| selected.contains(*wire))
        .map(str::to_owned)
        .collect()
}

// ───────────────────────── 卡片状态 ─────────────────────────

/// 渠道卡片状态：暂不可用 > 需修复 > 暂停 > 已连接（越靠前优先级越高）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CardState {
    /// 已连接且在推送。
    Connected,
    /// 已连接但被用户暂停。
    Paused,
    /// 连续投递失败（≥3），需要修复。
    Failing,
    /// 该种类服务端暂不可用（如 SMTP 停用 / 机器人未就绪）：投递实际发不出去，渠道弱化显示。
    Unavailable,
}

/// 已有渠道的状态：failing > paused（`enabled = false`）> connected。
pub(crate) fn channel_state(channel: &CloudNotifyChannelDto) -> CardState {
    if channel.status == "failing" {
        CardState::Failing
    } else if !channel.enabled {
        CardState::Paused
    } else {
        CardState::Connected
    }
}

/// 云端推送是否对本客户端开放：服务端目录非空（`None` 未知、空 = 管理员全部关闭，都视为关闭）。
pub(crate) fn cloud_enabled(catalog: Option<&[CloudNotifyKindDto]>) -> bool {
    catalog.is_some_and(|kinds| !kinds.is_empty())
}

/// 分段标签只在云端开放时显示；关闭时页面直接是自托管内容。
pub(crate) fn tabs_visible(cloud_enabled: bool) -> bool {
    cloud_enabled
}

/// 当前标签：云端关闭恒为自托管；开启时用户选择优先，否则按默认规则。
pub(crate) fn effective_tab(
    cloud_enabled: bool,
    chosen: Option<PushTab>,
    self_hosted_endpoints: usize,
) -> PushTab {
    if !cloud_enabled {
        return PushTab::SelfHosted;
    }
    chosen.unwrap_or_else(|| default_tab(self_hosted_endpoints))
}

/// 页头描述文案键：云端关闭时只剩自托管，沿用 Webhook 自身的描述。
pub(crate) fn page_desc_key(cloud_enabled: bool) -> &'static str {
    if cloud_enabled {
        "pushNavDesc"
    } else {
        "webhookEmptyDesc"
    }
}

/// 自托管空态的「改用云端推送」链接：云端关闭时隐藏。
pub(crate) fn show_try_cloud(cloud_enabled: bool) -> bool {
    cloud_enabled
}

/// 渠道卡片第二行（种类名）：渠道名与种类显示名相同（仅忽略首尾空白）时不重复显示。
pub(crate) fn show_channel_subtitle(channel_name: &str, kind_name: &str) -> bool {
    channel_name.trim() != kind_name.trim()
}

/// 目录里该种类是否开放且配置就绪；不在目录 = 管理员关闭。
pub(crate) fn kind_available(catalog: &[CloudNotifyKindDto], kind: ChannelKind) -> bool {
    catalog
        .iter()
        .any(|entry| entry.kind == kind.wire() && entry.available)
}

/// 目录里是否有该种类（无论配置是否就绪）。
pub(crate) fn kind_offered(catalog: &[CloudNotifyKindDto], kind: ChannelKind) -> bool {
    catalog.iter().any(|entry| entry.kind == kind.wire())
}

/// 渠道数已达套餐上限（`max_channels == 0` = 不限）。
pub(crate) fn channel_limit_reached(overview: &CloudNotifyOverviewDto) -> bool {
    overview.max_channels > 0 && overview.channels.len() >= overview.max_channels as usize
}

/// 「添加渠道」被整体禁用的原因。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AddBlock {
    /// 套餐（含用户级覆盖）未开通云端推送。
    PlanDisabled,
    /// 渠道数已达套餐上限。
    LimitReached,
}

/// 整个「添加渠道」按钮是否被禁用；`None` = 可用。套餐未开通优先于满额。
pub(crate) fn add_blocked(overview: &CloudNotifyOverviewDto) -> Option<AddBlock> {
    if !overview.enabled {
        Some(AddBlock::PlanDisabled)
    } else if channel_limit_reached(overview) {
        Some(AddBlock::LimitReached)
    } else {
        None
    }
}

/// 「添加渠道」下拉的一项：目录里开放的种类；`available = false`（服务端配置未就绪）置灰。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct AddOption {
    pub kind: ChannelKind,
    pub available: bool,
}

/// 目录 → 下拉项（固定种类顺序，目录外 / 未知种类不出现）。
pub(crate) fn add_options(catalog: &[CloudNotifyKindDto]) -> Vec<AddOption> {
    ChannelKind::ALL
        .into_iter()
        .filter(|kind| kind_offered(catalog, *kind))
        .map(|kind| AddOption {
            kind,
            available: kind_available(catalog, kind),
        })
        .collect()
}

/// 网格里的一张卡片（只对应已存在的渠道）。
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct CardSpec {
    pub kind: ChannelKind,
    pub state: CardState,
    pub channel: CloudNotifyChannelDto,
}

/// 按固定种类顺序展开已存在的渠道，同种类多个渠道各一张；不为未添加的种类生成占位卡。
/// 目录外的种类与未知种类（历史遗留）不显示。没有任何渠道 = 空态。
pub(crate) fn build_cards(
    overview: &CloudNotifyOverviewDto,
    catalog: &[CloudNotifyKindDto],
) -> Vec<CardSpec> {
    let mut cards = Vec::new();
    for kind in ChannelKind::ALL {
        if !kind_offered(catalog, kind) {
            continue;
        }
        cards.extend(
            overview
                .channels
                .iter()
                .filter(|channel| channel.kind == kind.wire())
                .map(|channel| CardSpec {
                    kind,
                    state: if kind_available(catalog, kind) {
                        channel_state(channel)
                    } else {
                        CardState::Unavailable
                    },
                    channel: channel.clone(),
                }),
        );
    }
    cards
}

/// 渠道网格列数：一个种类一列，两列等宽（同种类多个渠道向下折行，行尾不足补空位）。
pub(crate) const GRID_COLUMNS: usize = ChannelKind::ALL.len();

// ───────────────────────── 用量 ─────────────────────────

/// 单个周期的用量（`limit == 0` = 不限）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Meter {
    pub used: u32,
    pub limit: u32,
}

impl Meter {
    /// 满额：有限额且已用 ≥ 上限。
    pub(crate) fn exhausted(self) -> bool {
        self.limit > 0 && self.used >= self.limit
    }

    /// 进度比例 0..=1；不限为 0。
    pub(crate) fn ratio(self) -> f32 {
        if self.limit == 0 {
            return 0.;
        }
        (self.used as f32 / self.limit as f32).clamp(0., 1.)
    }

    /// 上限文案：不限时用 `unlimited`。
    pub(crate) fn limit_label(self, unlimited: &str) -> String {
        if self.limit == 0 {
            unlimited.to_owned()
        } else {
            self.limit.to_string()
        }
    }
}

pub(crate) fn daily_meter(usage: &CloudNotifyUsageDto) -> Meter {
    Meter {
        used: usage.daily_used,
        limit: usage.daily_limit,
    }
}

pub(crate) fn monthly_meter(usage: &CloudNotifyUsageDto) -> Meter {
    Meter {
        used: usage.monthly_used,
        limit: usage.monthly_limit,
    }
}

/// 满额时的恢复时间（RFC 3339）：月满取月重置，否则日满取日重置；未满额为 `None`。
pub(crate) fn exhausted_reset_at(usage: &CloudNotifyUsageDto) -> Option<&str> {
    if monthly_meter(usage).exhausted() {
        Some(&usage.monthly_reset_at)
    } else if daily_meter(usage).exhausted() {
        Some(&usage.daily_reset_at)
    } else {
        None
    }
}

/// RFC 3339 → 本地时区 `MM-DD HH:mm`（与 Web `formatShortTime` 一致）；无法解析返回空串。
pub(crate) fn short_time(value: &str) -> String {
    short_time_in(value, &chrono::Local)
}

/// [`short_time`] 的可指定时区版本（测试用）。
pub(crate) fn short_time_in<Tz>(value: &str, zone: &Tz) -> String
where
    Tz: chrono::TimeZone,
    Tz::Offset: std::fmt::Display,
{
    chrono::DateTime::parse_from_rfc3339(value).map_or_else(
        |_| String::new(),
        |time| time.with_timezone(zone).format("%m-%d %H:%M").to_string(),
    )
}

/// 用量条右侧的重置时间文本：不限额或时间无法解析时不显示（与 Web `Meter` 一致）。
pub(crate) fn reset_display(meter: Meter, reset_at: &str) -> Option<String> {
    if meter.limit == 0 {
        return None;
    }
    let time = short_time(reset_at);
    (!time.is_empty()).then_some(time)
}

// ───────────────────────── 隐私摘要 ─────────────────────────

/// 上报字段的文案键：文件名 / 大小 / 状态恒发送；下载地址、保存位置各自可选（地址先于位置）。
pub(crate) fn privacy_field_keys(include_url: bool, include_save_dir: bool) -> Vec<&'static str> {
    let mut keys = vec![
        "cloudNotifyFieldFileName",
        "cloudNotifyFieldSize",
        "cloudNotifyFieldStatus",
    ];
    if include_url {
        keys.push("cloudNotifyFieldUrl");
    }
    if include_save_dir {
        keys.push("cloudNotifyFieldSaveDir");
    }
    keys
}

/// 字段分隔符：中文用顿号，其余用逗号 + 空格。
pub(crate) fn field_separator(locale: &str) -> &'static str {
    if locale.to_ascii_lowercase().starts_with("zh") {
        "、"
    } else {
        ", "
    }
}

// ───────────────────────── 投递记录 ─────────────────────────

/// 投递状态的语气（决定状态点与文字颜色）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DeliveryTone {
    Neutral,
    Success,
    Error,
    Warning,
}

/// 投递状态 → `(文案键, 语气)`；未知状态返回 `None` 文案（调用方显示原文）。
pub(crate) fn delivery_status(status: &str) -> (Option<&'static str>, DeliveryTone) {
    match status {
        "pending" => (Some("cloudNotifyDeliveryPending"), DeliveryTone::Neutral),
        "sent" => (Some("cloudNotifyDeliverySent"), DeliveryTone::Success),
        "failed" => (Some("cloudNotifyDeliveryFailed"), DeliveryTone::Error),
        "dropped" => (Some("cloudNotifyDeliveryDropped"), DeliveryTone::Warning),
        _ => (None, DeliveryTone::Neutral),
    }
}

/// 失败记录显示「重试 n/max」。
pub(crate) fn shows_retry(delivery: &CloudNotifyDeliveryDto) -> bool {
    delivery.status == "failed" && delivery.attempts > 0
}

/// 「加载更多」分页：按 id 去重追加，保持服务端「新的在前」顺序。
pub(crate) fn append_page(
    existing: &mut Vec<CloudNotifyDeliveryDto>,
    page: Vec<CloudNotifyDeliveryDto>,
) {
    for item in page {
        if !existing.iter().any(|entry| entry.id == item.id) {
            existing.push(item);
        }
    }
}

/// 投递记录列表 = 云端第一页快照（`recent`，状态以它为准）+ 已「加载更多」追加的更早记录
/// （`older`，已出现在快照里的按 id 去掉）。保持服务端「新的在前」顺序。
pub(crate) fn merge_deliveries(
    recent: &[CloudNotifyDeliveryDto],
    older: &[CloudNotifyDeliveryDto],
) -> Vec<CloudNotifyDeliveryDto> {
    let mut merged = recent.to_vec();
    append_page(&mut merged, older.to_vec());
    merged
}

// ───────────────────────── 错误与绑定 ─────────────────────────

/// 保存 / 测试失败的本地化归类。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FailureKind {
    Offline,
    ChannelLimit,
    PlanDisabled,
    /// 邮箱验证码错误或已过期。
    InvalidCode,
    /// 发码 / 保存被限流。
    RateLimited,
    Other,
}

pub(crate) fn classify_failure(reason: Option<ErrorReason>) -> FailureKind {
    match reason {
        Some(ErrorReason::CloudUnreachable) => FailureKind::Offline,
        Some(ErrorReason::NotifyChannelLimit) => FailureKind::ChannelLimit,
        Some(ErrorReason::NotifyDisabled) => FailureKind::PlanDisabled,
        Some(ErrorReason::InvalidVerificationCode) => FailureKind::InvalidCode,
        Some(ErrorReason::RateLimited) => FailureKind::RateLimited,
        _ => FailureKind::Other,
    }
}

/// Telegram 绑定轮询结论。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BindPhase {
    Pending,
    Bound,
    Expired,
}

/// 未知状态按等待处理（继续轮询，直到绑定码过期）。
pub(crate) fn bind_phase(status: &str) -> BindPhase {
    match status {
        "bound" => BindPhase::Bound,
        "expired" => BindPhase::Expired,
        _ => BindPhase::Pending,
    }
}

// ───────────────────────── 账号展示 ─────────────────────────

/// 头像首字母：昵称优先，其次邮箱；取首个字母 / 数字 / 汉字并大写。
pub(crate) fn avatar_initial(nickname: &str, email: &str) -> String {
    [nickname, email]
        .into_iter()
        .filter_map(|text| text.trim().chars().find(|ch| ch.is_alphanumeric()))
        .next()
        .map_or_else(|| "?".to_owned(), |ch| ch.to_uppercase().collect())
}

#[cfg(test)]
mod tests {
    use fluxdown_protocol::CloudNotifyKindDto;

    use super::*;

    fn channel(kind: &str, enabled: bool, status: &str) -> CloudNotifyChannelDto {
        CloudNotifyChannelDto {
            id: format!("{kind}-{enabled}-{status}"),
            kind: kind.to_owned(),
            name: kind.to_owned(),
            enabled,
            status: status.to_owned(),
            ..Default::default()
        }
    }

    fn overview(channels: Vec<CloudNotifyChannelDto>) -> CloudNotifyOverviewDto {
        CloudNotifyOverviewDto {
            enabled: true,
            max_channels: 0,
            channels,
            ..Default::default()
        }
    }

    /// 目录：固定顺序的全部种类，去掉 `absent`，`unavailable` 标记为配置未就绪。
    fn catalog(absent: &[&str], unavailable: &[&str]) -> Vec<CloudNotifyKindDto> {
        ChannelKind::ALL
            .into_iter()
            .filter(|kind| !absent.contains(&kind.wire()))
            .map(|kind| CloudNotifyKindDto {
                kind: kind.wire().to_owned(),
                available: !unavailable.contains(&kind.wire()),
            })
            .collect()
    }

    #[test]
    fn default_tab_follows_self_hosted_endpoints() {
        assert_eq!(default_tab(0), PushTab::Cloud);
        assert_eq!(default_tab(1), PushTab::SelfHosted);
        assert_eq!(default_tab(7), PushTab::SelfHosted);
    }

    #[test]
    fn save_requires_name_and_at_least_one_event() {
        assert!(can_save("手机", 1));
        assert!(can_save("邮箱", 2));
        assert!(!can_save("  ", 1));
        assert!(!can_save("手机", 0));
    }

    #[test]
    fn card_state_priority_is_failing_paused_connected() {
        assert_eq!(
            channel_state(&channel("email", true, "ok")),
            CardState::Connected
        );
        assert_eq!(
            channel_state(&channel("email", false, "ok")),
            CardState::Paused
        );
        assert_eq!(
            channel_state(&channel("email", true, "failing")),
            CardState::Failing
        );
        assert_eq!(
            channel_state(&channel("email", false, "failing")),
            CardState::Failing
        );
    }

    #[test]
    fn cards_only_for_existing_channels_in_catalog_order() {
        let catalog = catalog(&[], &["telegram"]);
        let cards = build_cards(
            &overview(vec![
                channel("telegram", true, "ok"),
                channel("email", true, "ok"),
                channel("email", false, "ok"),
                channel("email", true, "failing"),
                channel("bark", true, "ok"),
            ]),
            &catalog,
        );
        let summary: Vec<_> = cards.iter().map(|card| (card.kind, card.state)).collect();
        assert_eq!(
            summary,
            vec![
                (ChannelKind::Email, CardState::Connected),
                (ChannelKind::Email, CardState::Paused),
                (ChannelKind::Email, CardState::Failing),
                // 种类配置暂不可用（实际发不出去）：优先于渠道自身状态，仍显示已有渠道。
                (ChannelKind::Telegram, CardState::Unavailable),
            ]
        );
    }

    #[test]
    fn no_channels_means_no_cards_and_no_placeholders() {
        assert!(build_cards(&overview(Vec::new()), &catalog(&[], &[])).is_empty());
    }

    #[test]
    fn kinds_missing_from_catalog_have_no_cards_even_with_channels() {
        // 管理员关闭 / 已移除的种类：不显示其渠道（含历史遗留的未知种类）。
        let catalog = catalog(&["telegram"], &[]);
        let cards = build_cards(
            &overview(vec![
                channel("telegram", true, "ok"),
                channel("email", true, "ok"),
                channel("bark", true, "ok"),
            ]),
            &catalog,
        );
        let kinds: Vec<_> = cards.iter().map(|card| card.kind).collect();
        assert_eq!(kinds, [ChannelKind::Email]);
    }

    #[test]
    fn add_blocked_prefers_plan_over_limit() {
        let mut overview = overview(vec![channel("email", true, "ok")]);
        assert_eq!(add_blocked(&overview), None);
        overview.max_channels = 1;
        assert_eq!(add_blocked(&overview), Some(AddBlock::LimitReached));
        overview.max_channels = 2;
        assert_eq!(add_blocked(&overview), None);
        overview.enabled = false;
        assert_eq!(add_blocked(&overview), Some(AddBlock::PlanDisabled));
        overview.max_channels = 1;
        assert_eq!(add_blocked(&overview), Some(AddBlock::PlanDisabled));
    }

    #[test]
    fn add_options_follow_catalog_and_mark_unavailable() {
        let options = add_options(&catalog(&[], &["telegram"]));
        assert_eq!(
            options,
            [
                AddOption {
                    kind: ChannelKind::Email,
                    available: true
                },
                AddOption {
                    kind: ChannelKind::Telegram,
                    available: false
                },
            ]
        );
        let only_email = add_options(&catalog(&["telegram"], &[]));
        assert_eq!(only_email.len(), 1);
        assert!(add_options(&[]).is_empty());
    }

    #[test]
    fn email_address_validation_matches_account_rules() {
        for ok in ["a@b.co", "  a@b.co  ", "first.last@mail.example.org"] {
            assert!(is_valid_email(ok), "{ok}");
        }
        for bad in [
            "", "a", "a@", "@b.co", "a@b", "a@.co", "a@b.", "a@@b.co", "a b@c.de",
        ] {
            assert!(!is_valid_email(bad), "{bad}");
        }
        let long_local = "a".repeat(250);
        assert!(!is_valid_email(&format!("{long_local}@b.co")));
        assert!(is_valid_email(&format!("{}@b.co", "a".repeat(240))));
        assert_eq!(normalize_address("  Boss@Corp.COM "), "boss@corp.com");
    }

    fn emails(list: &[&str]) -> Vec<String> {
        list.iter().map(|item| (*item).to_owned()).collect()
    }

    fn email_channel(addresses: &[&str], target: &str) -> CloudNotifyChannelDto {
        CloudNotifyChannelDto {
            kind: "email".to_owned(),
            addresses: emails(addresses),
            target: target.to_owned(),
            ..Default::default()
        }
    }

    const ACCOUNT: &str = "Me@Example.com";

    #[test]
    fn initial_recipients_default_to_account_email() {
        assert_eq!(initial_recipients(None, ACCOUNT), ["Me@Example.com"]);
        // 编辑：用渠道现有地址（trim + 忽略大小写去重，保持顺序和原文）。
        let channel = email_channel(&["Me@example.com", "boss@corp.com", "BOSS@corp.com"], "x");
        assert_eq!(
            initial_recipients(Some(&channel), ACCOUNT),
            ["Me@example.com", "boss@corp.com"]
        );
        // 旧数据没有 addresses：退回 target；都没有则账号邮箱。
        assert_eq!(
            initial_recipients(Some(&email_channel(&[], "old@corp.com")), ACCOUNT),
            ["old@corp.com"]
        );
        assert_eq!(
            initial_recipients(Some(&email_channel(&[], "")), ACCOUNT),
            ["Me@Example.com"]
        );
        // 未取到账号邮箱时新建列表为空（保存不可用，直到概览到达）。
        assert!(initial_recipients(None, "").is_empty());
    }

    #[test]
    fn add_flow_modes_issues_and_limits() {
        let list = emails(&["boss@corp.com"]);
        // 只有账号邮箱（被移除后加回）免验证码，其余一律需要。
        assert_eq!(add_mode("ME@example.com", ACCOUNT), AddMode::Direct);
        assert_eq!(add_mode("boss@corp.com", ACCOUNT), AddMode::NeedsCode);
        assert_eq!(add_mode("new@corp.com", ACCOUNT), AddMode::NeedsCode);

        assert_eq!(address_issue("", &list), None);
        assert_eq!(address_issue("nope", &list), Some(AddressIssue::Invalid));
        assert_eq!(
            address_issue("BOSS@corp.com", &list),
            Some(AddressIssue::Exists)
        );
        assert_eq!(address_issue("new@corp.com", &list), None);

        let send = |input: &str, cooldown, sending| {
            can_send_code(input, &list, ACCOUNT, cooldown, sending)
        };
        assert!(send("new@corp.com", 0, false));
        assert!(!send("new@corp.com", 30, false));
        assert!(!send("new@corp.com", 0, true));
        assert!(!send("bad", 0, false));
        assert!(!send("boss@corp.com", 0, false), "已在列表里");
        assert!(!send("me@example.com", 0, false), "账号邮箱不需要验证码");

        let verify =
            |input: &str, code: &str, verifying| can_verify(input, code, &list, ACCOUNT, verifying);
        assert!(verify("new@corp.com", "123456", false));
        assert!(verify("new@corp.com", " 123456 ", false));
        assert!(!verify("new@corp.com", "12345", false));
        assert!(!verify("new@corp.com", "12345a", false));
        assert!(!verify("new@corp.com", "123456", true));
        assert!(!verify("boss@corp.com", "123456", false));
        assert!(!verify("me@example.com", "123456", false));

        assert!(can_add_direct("me@example.com", &list, ACCOUNT));
        assert!(!can_add_direct("new@corp.com", &list, ACCOUNT));
        assert!(!can_add_direct("boss@corp.com", &list, ACCOUNT));

        // 满 5 个：任何添加路径都关闭。
        let full = emails(&["a@x.co", "b@x.co", "c@x.co", "d@x.co", "e@x.co"]);
        assert_eq!(full.len(), MAX_RECIPIENTS);
        assert!(!can_add_more(&full));
        assert!(can_add_more(&full[..4]));
        assert!(!can_send_code("new@corp.com", &full, ACCOUNT, 0, false));
        assert!(!can_verify("new@corp.com", "123456", &full, ACCOUNT, false));
        assert!(!can_add_direct("me@example.com", &full, ACCOUNT));
    }

    #[test]
    fn recipients_add_dedupe_remove() {
        let mut list = emails(&["Boss@Corp.com"]);
        // 账号邮箱加回时插到最前；其余追加，保存 trim 后的原文。
        assert!(add_recipient(&mut list, " ME@example.com ", ACCOUNT));
        assert_eq!(list, ["ME@example.com", "Boss@Corp.com"]);
        assert!(add_recipient(&mut list, " New@Corp.com ", ACCOUNT));
        assert_eq!(list, ["ME@example.com", "Boss@Corp.com", "New@Corp.com"]);
        assert!(!add_recipient(&mut list, "BOSS@corp.com", ACCOUNT), "去重");
        assert!(!add_recipient(&mut list, "bad", ACCOUNT), "格式不对不加入");
        for item in ["d@x.co", "e@x.co"] {
            assert!(add_recipient(&mut list, item, ACCOUNT));
        }
        assert_eq!(list.len(), MAX_RECIPIENTS);
        assert!(!add_recipient(&mut list, "f@x.co", ACCOUNT), "上限 5 个");

        assert!(can_remove(&list));
        assert!(remove_recipient(&mut list, "BOSS@corp.com"));
        assert_eq!(list.len(), 4);
        assert!(!remove_recipient(&mut list, "nobody@x.co"));
        // 只剩 1 个时不可移除。
        let mut one = emails(&["me@example.com"]);
        assert!(!can_remove(&one));
        assert!(!remove_recipient(&mut one, "me@example.com"));
        assert_eq!(one.len(), 1);
    }

    #[test]
    fn recipient_badges_and_save_gate() {
        assert_eq!(
            recipient_badge("ME@example.com", ACCOUNT),
            RecipientBadge::Account
        );
        assert_eq!(
            recipient_badge("boss@corp.com", ACCOUNT),
            RecipientBadge::Verified
        );
        let list = emails(&["me@example.com"]);
        assert!(email_can_save("邮箱", 1, &list));
        assert!(!email_can_save("邮箱", 1, &[]), "至少一个收件人");
        assert!(!email_can_save("  ", 1, &list));
        assert!(!email_can_save("邮箱", 0, &list));
    }

    #[test]
    fn card_recipient_summary() {
        assert_eq!(
            recipient_summary(&email_channel(&["a@x.co"], "a@x.co")),
            RecipientSummary::One("a@x.co".to_owned())
        );
        assert_eq!(
            recipient_summary(&email_channel(&["a@x.co", "b@x.co", "c@x.co"], "a@x.co")),
            RecipientSummary::Many {
                first: "a@x.co".to_owned(),
                total: 3
            }
        );
        // 没有 addresses（Telegram / 旧数据）：显示 target；都没有则无。
        assert_eq!(
            recipient_summary(&email_channel(&[], "@bot")),
            RecipientSummary::One("@bot".to_owned())
        );
        assert_eq!(
            recipient_summary(&email_channel(&[], "")),
            RecipientSummary::None
        );
    }

    #[test]
    fn code_helpers() {
        assert!(is_code_complete("000000"));
        assert!(!is_code_complete("00000"));
        assert!(!is_code_complete("0000000"));
        assert_eq!(code_minutes(600), 10);
        assert_eq!(code_minutes(601), 11);
        assert_eq!(code_minutes(30), 1);
        assert_eq!(code_minutes(0), 1);
    }

    #[test]
    fn cloud_enabled_needs_non_empty_catalog() {
        assert!(!cloud_enabled(None));
        assert!(!cloud_enabled(Some(&[])));
        assert!(cloud_enabled(Some(&catalog(&[], &[]))));
        assert!(cloud_enabled(Some(&catalog(&["telegram"], &[]))));
        // 目录里一个种类都没有 = 全部关闭。
        assert!(!cloud_enabled(Some(&catalog(&["email", "telegram"], &[]))));
    }

    #[test]
    fn tab_visibility_and_effective_tab() {
        // 云端关闭：不显示标签，恒为自托管，用户选择无效。
        assert!(!tabs_visible(false));
        assert_eq!(
            effective_tab(false, Some(PushTab::Cloud), 0),
            PushTab::SelfHosted
        );
        assert_eq!(effective_tab(false, None, 0), PushTab::SelfHosted);
        // 云端开启：用户选择优先，否则按默认规则。
        assert!(tabs_visible(true));
        assert_eq!(effective_tab(true, None, 0), PushTab::Cloud);
        assert_eq!(effective_tab(true, None, 2), PushTab::SelfHosted);
        assert_eq!(effective_tab(true, Some(PushTab::Cloud), 2), PushTab::Cloud);
        assert_eq!(
            effective_tab(true, Some(PushTab::SelfHosted), 0),
            PushTab::SelfHosted
        );
    }

    #[test]
    fn page_description_and_try_cloud_follow_cloud_enabled() {
        assert_eq!(page_desc_key(true), "pushNavDesc");
        assert_eq!(page_desc_key(false), "webhookEmptyDesc");
        assert!(show_try_cloud(true));
        assert!(!show_try_cloud(false));
    }

    #[test]
    fn channel_second_line_hidden_when_name_equals_kind_name() {
        assert!(!show_channel_subtitle("邮件", "邮件"));
        assert!(!show_channel_subtitle("  邮件 ", "邮件"));
        // 严格相等（仅去首尾空白），不做大小写折叠，便于 Web 逐字对齐。
        assert!(show_channel_subtitle("Email", "email"));
        assert!(show_channel_subtitle("我的邮箱", "邮件"));
        assert!(show_channel_subtitle("", "邮件"));
    }

    #[test]
    fn meter_zero_limit_is_unlimited_and_never_exhausted() {
        let meter = Meter {
            used: 999,
            limit: 0,
        };
        assert_eq!(meter.limit, 0);
        assert!(!meter.exhausted());
        assert!(meter.ratio().abs() < f32::EPSILON);
        assert_eq!(meter.limit_label("∞"), "∞");
    }

    #[test]
    fn meter_exhaustion_and_ratio() {
        assert!(
            !Meter {
                used: 19,
                limit: 20
            }
            .exhausted()
        );
        assert!(
            Meter {
                used: 20,
                limit: 20
            }
            .exhausted()
        );
        assert!(
            Meter {
                used: 25,
                limit: 20
            }
            .exhausted()
        );
        assert!((Meter { used: 5, limit: 20 }.ratio() - 0.25).abs() < 1e-6);
        assert!(
            (Meter {
                used: 25,
                limit: 20
            }
            .ratio()
                - 1.)
                .abs()
                < 1e-6
        );
        assert_eq!(Meter { used: 1, limit: 20 }.limit_label("∞"), "20");
    }

    #[test]
    fn exhausted_reset_prefers_month_then_day() {
        let mut usage = CloudNotifyUsageDto {
            daily_used: 20,
            daily_limit: 20,
            monthly_used: 30,
            monthly_limit: 300,
            daily_reset_at: "2026-10-10T16:00:00Z".to_owned(),
            monthly_reset_at: "2026-10-31T16:00:00Z".to_owned(),
        };
        assert_eq!(exhausted_reset_at(&usage), Some("2026-10-10T16:00:00Z"));
        usage.monthly_used = 300;
        assert_eq!(exhausted_reset_at(&usage), Some("2026-10-31T16:00:00Z"));
        usage.monthly_used = 1;
        usage.daily_used = 3;
        assert_eq!(exhausted_reset_at(&usage), None);
        usage.daily_limit = 0;
        usage.daily_used = 9999;
        assert_eq!(exhausted_reset_at(&usage), None);
    }

    #[test]
    fn short_time_is_mm_dd_hh_mm_in_the_given_zone_and_empty_on_garbage() {
        let utc = chrono::FixedOffset::east_opt(0).expect("utc");
        let cst = chrono::FixedOffset::east_opt(8 * 3600).expect("+08:00");
        assert_eq!(short_time_in("2026-10-09T10:05:00Z", &utc), "10-09 10:05");
        assert_eq!(short_time_in("2026-10-09T10:05:00Z", &cst), "10-09 18:05");
        // 跨日 / 跨月：以目标时区的本地日期为准。
        assert_eq!(short_time_in("2026-10-31T20:30:00Z", &cst), "11-01 04:30");
        assert_eq!(
            short_time_in("2026-10-09T10:05:00+08:00", &utc),
            "10-09 02:05"
        );
        assert_eq!(short_time_in("", &utc), "");
        assert_eq!(short_time_in("nope", &utc), "");
    }

    #[test]
    fn reset_display_hides_unlimited_and_unparseable() {
        let limited = Meter { used: 1, limit: 20 };
        let unlimited = Meter { used: 99, limit: 0 };
        assert!(reset_display(limited, "2026-10-09T10:05:00Z").is_some());
        assert_eq!(reset_display(unlimited, "2026-10-09T10:05:00Z"), None);
        assert_eq!(reset_display(limited, ""), None);
        assert_eq!(reset_display(limited, "nope"), None);
    }

    #[test]
    fn privacy_summary_order_is_url_before_save_dir() {
        assert_eq!(
            privacy_field_keys(false, false),
            [
                "cloudNotifyFieldFileName",
                "cloudNotifyFieldSize",
                "cloudNotifyFieldStatus"
            ]
        );
        assert_eq!(
            privacy_field_keys(true, false).last(),
            Some(&"cloudNotifyFieldUrl")
        );
        assert_eq!(
            privacy_field_keys(false, true).last(),
            Some(&"cloudNotifyFieldSaveDir")
        );
        assert_eq!(
            privacy_field_keys(true, true)[3..],
            ["cloudNotifyFieldUrl", "cloudNotifyFieldSaveDir"]
        );
    }

    #[test]
    fn separator_follows_locale() {
        assert_eq!(field_separator("zh"), "、");
        assert_eq!(field_separator("zh-CN"), "、");
        assert_eq!(field_separator("en"), ", ");
    }

    #[test]
    fn delivery_status_mapping() {
        assert_eq!(
            delivery_status("dropped"),
            (Some("cloudNotifyDeliveryDropped"), DeliveryTone::Warning)
        );
        assert_eq!(
            delivery_status("sent"),
            (Some("cloudNotifyDeliverySent"), DeliveryTone::Success)
        );
        assert_eq!(delivery_status("weird"), (None, DeliveryTone::Neutral));
    }

    #[test]
    fn retry_only_for_failed_with_attempts() {
        let mut delivery = CloudNotifyDeliveryDto {
            status: "failed".to_owned(),
            attempts: 2,
            max_attempts: 4,
            ..Default::default()
        };
        assert!(shows_retry(&delivery));
        delivery.attempts = 0;
        assert!(!shows_retry(&delivery));
        delivery.attempts = 2;
        delivery.status = "sent".to_owned();
        assert!(!shows_retry(&delivery));
    }

    #[test]
    fn append_page_dedupes_by_id_and_keeps_order() {
        let item = |id: &str| CloudNotifyDeliveryDto {
            id: id.to_owned(),
            ..Default::default()
        };
        let mut list = vec![item("a"), item("b")];
        append_page(&mut list, vec![item("b"), item("c"), item("d")]);
        let ids: Vec<_> = list.iter().map(|entry| entry.id.as_str()).collect();
        assert_eq!(ids, ["a", "b", "c", "d"]);
    }

    #[test]
    fn merged_deliveries_prefer_snapshot_and_dedupe_older_pages() {
        let item = |id: &str, status: &str| CloudNotifyDeliveryDto {
            id: id.to_owned(),
            status: status.to_owned(),
            ..Default::default()
        };
        let recent = [item("c", "sent"), item("b", "sent")];
        // 旧页里的 b 是更早时拉到的 pending：以快照为准，且不重复；d 是真正更早的记录。
        let older = [
            item("b", "pending"),
            item("d", "failed"),
            item("d", "failed"),
        ];
        let merged = merge_deliveries(&recent, &older);
        let view: Vec<_> = merged
            .iter()
            .map(|entry| (entry.id.as_str(), entry.status.as_str()))
            .collect();
        assert_eq!(view, [("c", "sent"), ("b", "sent"), ("d", "failed")]);
        assert_eq!(merge_deliveries(&recent, &[]).len(), 2);
        assert!(merge_deliveries(&[], &[]).is_empty());
    }

    #[test]
    fn failure_classification() {
        assert_eq!(
            classify_failure(Some(ErrorReason::CloudUnreachable)),
            FailureKind::Offline
        );
        assert_eq!(
            classify_failure(Some(ErrorReason::NotifyChannelLimit)),
            FailureKind::ChannelLimit
        );
        assert_eq!(
            classify_failure(Some(ErrorReason::NotifyDisabled)),
            FailureKind::PlanDisabled
        );
        assert_eq!(classify_failure(None), FailureKind::Other);
        assert_eq!(
            classify_failure(Some(ErrorReason::RateLimited)),
            FailureKind::RateLimited
        );
        assert_eq!(
            classify_failure(Some(ErrorReason::InvalidVerificationCode)),
            FailureKind::InvalidCode
        );
        assert_eq!(
            classify_failure(Some(ErrorReason::NotifyTargetInvalid)),
            FailureKind::Other
        );
    }

    #[test]
    fn bind_phase_defaults_to_pending() {
        assert_eq!(bind_phase("bound"), BindPhase::Bound);
        assert_eq!(bind_phase("expired"), BindPhase::Expired);
        assert_eq!(bind_phase("pending"), BindPhase::Pending);
        assert_eq!(bind_phase("???"), BindPhase::Pending);
    }

    #[test]
    fn ordered_events_follow_catalog_order() {
        let selected: BTreeSet<String> = ["task.failed", "task.created", "bogus"]
            .into_iter()
            .map(str::to_owned)
            .collect();
        assert_eq!(
            ordered_events(&selected, ["task.created", "task.completed", "task.failed"]),
            ["task.created", "task.failed"]
        );
    }

    #[test]
    fn avatar_initial_prefers_nickname_then_email() {
        assert_eq!(avatar_initial("小明", "a@b.c"), "小");
        assert_eq!(avatar_initial("  ", "zed@b.c"), "Z");
        assert_eq!(avatar_initial("", ""), "?");
        assert_eq!(avatar_initial("!!", "x@y"), "X");
    }
}

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:rinf/rinf.dart';

import '../bindings/bindings.dart';
import '../services/log_service.dart';
import 'custom_category.dart';

/// 下载引擎相关配置（持久化在 Rust SQLite 中）
class SettingsProvider extends ChangeNotifier {
  /// 全局单例引用，供无 context 场景读取设置
  static SettingsProvider? globalInstance;

  String _defaultSaveDir = _platformDefaultSaveDir();
  int _defaultSegments = 0; // 0 = 自动（由 Rust segment_advisor 动态计算）
  int _autoMaxConnections = 16; // 自动模式下智能调度的最大连接数上限
  int _maxConcurrentTasks = 5;
  int _speedLimitBytes = 0; // 0 = 无限制
  int _uploadLimitBytes = 0; // 全局上传限速（B/s，仅 BT 上传含做种；0 = 无限制）
  bool _autoCheckUpdate = true; // 默认启动时自动检查更新
  String _updateChannel = 'stable'; // 更新渠道：stable 稳定版 / frontier 预览版（含预发布）
  bool _notifyOnComplete = true; // 默认任务完成时弹出通知
  bool _silentDownloadEnabled = false; // 免打扰下载：外部请求不弹确认框直接下载
  bool _silentSkipSelection = false; // 免打扰子开关：跳过 BT/HLS/变体二次选择弹窗

  // 自定义分类
  List<CustomCategory> _customCategories = [];

  // 代理设置
  String _proxyMode = 'none'; // none / system / manual / auto
  String _proxyType = 'http'; // http / https / socks4 / socks5
  String _proxyHost = '';
  String _proxyPort = '';

  // 代理防抖支持
  Timer? _proxyDebounceTimer;
  final Set<String> _pendingProxyKeys = {};

  // BT 设置
  bool _btEnabled = true; // 启用 BitTorrent
  bool _btEnableDht = true; // DHT 分布式哈希表
  bool _btEnableUpnp = true; // UPnP 端口映射
  int _btPortStart = 6881; // 监听端口起始
  int _btPortEnd = 6891; // 监听端口结束
  String _btCustomTrackers = ''; // 用户自定义 Tracker 列表（换行分隔）

  bool _btAutoReseed = true; // 启动时自动继续做种（非用户手动停止的已完成任务）
  bool _btSeedEnabled = true; // 完成后自动做种（关闭则 BT 任务完成即停止做种）

  // UA 设置
  String _globalUserAgent = ''; // 空字符串 = 使用内置 Chrome UA

  // 默认队列设置
  String _defaultQueueId = ''; // 空字符串 = 默认队列

  // 文件已存在时的处理方式（'rename' = 自动重命名，'overwrite' = 覆盖旧文件）
  String _fileExistsBehavior = 'rename';

  // 新建下载对话框上次选择的线程数（'' = 未记录，'auto' = 自动，数字串 = 固定）
  String _lastDialogThreads = '';

  // 下载位置自动使用上次保存的位置（开启后新建下载默认目录跟随上次下载的目录）
  bool _rememberLastSaveDir = false;

  // 上次下载确认时使用的保存目录（'' = 未记录）
  String _lastSaveDir = '';

  /// 配置是否已从 Rust 端加载完成
  bool _loaded = false;
  final Completer<void> _loadedCompleter = Completer<void>();

  StreamSubscription<RustSignalPack<ConfigLoaded>>? _configSub;

  SettingsProvider() {
    logInfo('Settings', 'constructor, setting globalInstance');
    globalInstance = this;
    _startListening();
  }

  @override
  void dispose() {
    logInfo('Settings', 'dispose');
    _proxyDebounceTimer?.cancel();
    _configSub?.cancel();
    if (globalInstance == this) {
      globalInstance = null;
    }
    super.dispose();
  }

  // ---------------------------------------------------------------------------
  // Getters
  // ---------------------------------------------------------------------------

  bool get loaded => _loaded;

  /// Completes after the first full configuration snapshot has been applied.
  Future<void> get whenLoaded => _loadedCompleter.future;
  String get defaultSaveDir => _defaultSaveDir;
  int get defaultSegments => _defaultSegments;
  int get autoMaxConnections => _autoMaxConnections;

  int get maxConcurrentTasks => _maxConcurrentTasks;
  int get speedLimitBytes => _speedLimitBytes;
  int get uploadLimitBytes => _uploadLimitBytes;
  bool get autoCheckUpdate => _autoCheckUpdate;
  String get updateChannel => _updateChannel;
  bool get notifyOnComplete => _notifyOnComplete;
  bool get silentDownloadEnabled => _silentDownloadEnabled;
  bool get silentSkipSelection => _silentSkipSelection;

  /// 可见的分类（排序后），供侧边栏使用
  List<CustomCategory> get visibleCategories =>
      _customCategories.where((c) => c.visible).toList()
        ..sort((a, b) => a.position.compareTo(b.position));

  /// 按分类规则解析文件的保存目录：
  /// 普通分类（按 position 排序）→ other 分类（无普通分类命中时）。
  /// 无匹配时返回 ''，由调用方决定回退目录。
  ///
  /// [fileName] 为空或无扩展名时，回退用 [url] 路径末段派生的文件名参与匹配
  /// （浏览器扩展右键下载常只带 URL、不带已解析文件名，需靠 URL 扩展名归类）。
  ///
  /// 快速下载对话框、独立小窗、免打扰静默路径与外部下载请求共用本解析器。
  String resolveCategorySaveDir(String fileName, {String url = ''}) {
    var name = fileName;
    if ((name.isEmpty || !name.contains('.')) && url.isNotEmpty) {
      final derived = _fileNameFromUrl(url);
      if (derived.isNotEmpty) name = derived;
    }
    if (name.isEmpty) return '';
    final categories = visibleCategories;
    final normals = categories
        .where((c) => c.builtinType != 'all' && c.builtinType != 'other')
        .toList();
    for (final cat in normals) {
      if (cat.saveDir.isNotEmpty && cat.matches(name)) {
        return cat.saveDir;
      }
    }
    final otherCat = categories
        .where((c) => c.builtinType == 'other')
        .firstOrNull;
    if (otherCat != null &&
        otherCat.saveDir.isNotEmpty &&
        !normals.any((c) => c.matches(name))) {
      return otherCat.saveDir;
    }
    return '';
  }

  /// 从 URL 中提取文件名（取最后一段路径，须含 '.'），失败返回 ''。
  static String _fileNameFromUrl(String url) {
    try {
      final uri = Uri.parse(url.trim());
      final segments = uri.pathSegments;
      if (segments.isNotEmpty) {
        final last = Uri.decodeComponent(segments.last);
        if (last.contains('.')) return last;
      }
    } catch (_) {}
    return '';
  }

  // 代理设置 Getters
  String get proxyMode => _proxyMode;
  String get proxyType => _proxyType;
  String get proxyHost => _proxyHost;
  String get proxyPort => _proxyPort;

  // BT 设置 Getters
  bool get btEnabled => _btEnabled;
  bool get btEnableDht => _btEnableDht;
  bool get btEnableUpnp => _btEnableUpnp;
  int get btPortStart => _btPortStart;
  int get btPortEnd => _btPortEnd;
  String get btCustomTrackers => _btCustomTrackers;

  bool get btAutoReseed => _btAutoReseed;
  bool get btSeedEnabled => _btSeedEnabled;

  // UA 设置 Getter
  String get globalUserAgent => _globalUserAgent;

  // 默认队列 Getter
  String get defaultQueueId => _defaultQueueId;

  // 文件已存在时处理方式 Getter
  String get fileExistsBehavior => _fileExistsBehavior;

  // 新建下载对话框上次选择的线程数 Getter
  String get lastDialogThreads => _lastDialogThreads;

  /// 生效的默认保存目录：开关开启且已有记录时返回上次保存位置，否则返回固定默认目录
  String get effectiveDefaultSaveDir =>
      _rememberLastSaveDir && _lastSaveDir.isNotEmpty
      ? _lastSaveDir
      : _defaultSaveDir;

  // ---------------------------------------------------------------------------
  // Setters — 修改值 + 通知 Rust 持久化
  // ---------------------------------------------------------------------------

  void setDefaultSaveDir(String value) {
    if (_defaultSaveDir == value) return;
    _defaultSaveDir = value;
    notifyListeners();
    _saveToRust('default_save_dir', value);
  }

  void setDefaultSegments(int value) {
    if (_defaultSegments == value) return;
    _defaultSegments = value;
    notifyListeners();
    _saveToRust('default_segments', value.toString());
  }

  void setAutoMaxConnections(int value) {
    if (_autoMaxConnections == value) return;
    _autoMaxConnections = value;
    notifyListeners();
    _saveToRust('auto_max_connections', value.toString());
  }

  /// 记住新建下载对话框中用户选择的线程数（'auto' 或数字字符串）
  void setLastDialogThreads(String value) {
    if (_lastDialogThreads == value) return;
    _lastDialogThreads = value;
    notifyListeners();
    _saveToRust('last_dialog_threads', value);
  }

  /// 记录下载确认时使用的保存目录（无条件记录，开关开启后立即生效）
  void recordLastSaveDir(String dir) {
    if (dir.isEmpty || _lastSaveDir == dir) return;
    _lastSaveDir = dir;
    if (_rememberLastSaveDir) notifyListeners();
    _saveToRust('last_save_dir', dir);
  }

  void setMaxConcurrentTasks(int value) {
    if (_maxConcurrentTasks == value) return;
    _maxConcurrentTasks = value;
    notifyListeners();
    _saveToRust('max_concurrent_tasks', value.toString());
  }

  void setSpeedLimitBytes(int value) {
    if (_speedLimitBytes == value) return;
    _speedLimitBytes = value;
    notifyListeners();
    _saveToRust('speed_limit_bytes', value.toString());
  }

  void setUploadLimitBytes(int value) {
    if (_uploadLimitBytes == value) return;
    _uploadLimitBytes = value;
    notifyListeners();
    _saveToRust('upload_limit_bytes', value.toString());
  }

  /// 设置更新渠道（'stable' 稳定版 / 'frontier' 预览版）。
  void setUpdateChannel(String value) {
    if (_updateChannel == value) return;
    _updateChannel = value;
    notifyListeners();
    _saveToRust('update_channel', value);
  }

  void setNotifyOnComplete(bool value) {
    if (_notifyOnComplete == value) return;
    _notifyOnComplete = value;
    notifyListeners();
    _saveToRust('notify_on_complete', value.toString());
  }

  /// 持久化分类列表。同时写入「程序」分类迁移 marker：任何一次用户主导的
  /// 分类变更都意味着当前列表是用户意愿，启动迁移不得再补插「程序」分类。
  void _persistCategories() {
    _saveToRust(
      'custom_categories',
      CustomCategory.encodeList(_customCategories),
    );
    _saveToRust('program_category_migrated', 'true');
  }

  // 代理设置 Setters

  void setProxyMode(String value) {
    if (_proxyMode == value) return;
    _proxyMode = value;
    notifyListeners();
    _saveProxyConfig('proxy_mode', value);
  }

  void setProxyType(String value) {
    if (_proxyType == value) return;
    _proxyType = value;
    notifyListeners();
    _saveProxyConfig('proxy_type', value);
  }

  void setProxyHost(String value) {
    if (_proxyHost == value) return;
    _proxyHost = value;
    notifyListeners();
    _saveProxyConfig('proxy_host', value);
  }

  void setProxyPort(String value) {
    if (_proxyPort == value) return;
    _proxyPort = value;
    notifyListeners();
    _saveProxyConfig('proxy_port', value);
  }

  // BT 设置 Setters
  void setBtEnabled(bool value) {
    if (_btEnabled == value) return;
    _btEnabled = value;
    notifyListeners();
    _saveToRust('bt_enabled', value.toString());
  }

  void setBtEnableDht(bool value) {
    if (_btEnableDht == value) return;
    _btEnableDht = value;
    notifyListeners();
    _saveToRust('bt_enable_dht', value.toString());
  }

  void setBtEnableUpnp(bool value) {
    if (_btEnableUpnp == value) return;
    _btEnableUpnp = value;
    notifyListeners();
    _saveToRust('bt_enable_upnp', value.toString());
  }

  void setBtCustomTrackers(String value) {
    if (_btCustomTrackers == value) return;
    _btCustomTrackers = value;
    notifyListeners();
    _saveToRust('bt_custom_trackers', value);
  }

  void setBtAutoReseed(bool value) {
    if (_btAutoReseed == value) return;
    _btAutoReseed = value;
    notifyListeners();
    _saveToRust('bt_auto_reseed', value ? '1' : '0');
  }

  void setBtSeedEnabled(bool value) {
    if (_btSeedEnabled == value) return;
    _btSeedEnabled = value;
    notifyListeners();
    _saveToRust('bt_seed_enabled', value ? '1' : '0');
  }

  // UA 设置 Setter

  void setGlobalUserAgent(String value) {
    if (_globalUserAgent == value) return;
    _globalUserAgent = value;
    notifyListeners();
    _saveToRust('global_user_agent', value);
  }

  // 文件已存在时处理方式 Setter
  void setFileExistsBehavior(String value) {
    if (_fileExistsBehavior == value) return;
    _fileExistsBehavior = value;
    notifyListeners();
    _saveToRust('file_exists_behavior', value);
  }

  // ---------------------------------------------------------------------------
  // 请求 Rust 端加载配置
  // ---------------------------------------------------------------------------

  void requestConfig() {
    const RequestConfig().sendSignalToRust();
  }

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  void _startListening() {
    _configSub = ConfigLoaded.rustSignalStream.listen(_onConfigLoaded);
  }

  void _onConfigLoaded(RustSignalPack<ConfigLoaded> pack) {
    applyLoadedConfig(pack.message.entries);
  }

  /// Applies config entries loaded from Rust.
  /// Exposed for tests; production code receives them via the signal stream.
  @visibleForTesting
  void applyLoadedConfig(List<ConfigEntry> entries) {
    logInfo('Settings', '_onConfigLoaded: ${entries.length} entries');
    // 追踪「程序」分类迁移是否已执行过（键存在 = 已迁移，删除不再复活）。
    bool programCategoryMigrated = false;
    for (final entry in entries) {
      switch (entry.key) {
        case 'default_save_dir':
          _defaultSaveDir = entry.value;
        case 'default_segments':
          _defaultSegments = int.tryParse(entry.value) ?? 0;
        case 'auto_max_connections':
          _autoMaxConnections = int.tryParse(entry.value) ?? 16;
        case 'max_concurrent_tasks':
          _maxConcurrentTasks = int.tryParse(entry.value) ?? 5;
        case 'speed_limit_bytes':
          _speedLimitBytes = int.tryParse(entry.value) ?? 0;
        case 'upload_limit_bytes':
          _uploadLimitBytes = int.tryParse(entry.value) ?? 0;
        case 'auto_check_update':
          _autoCheckUpdate = entry.value == 'true';
        case 'update_channel':
          _updateChannel = entry.value.isEmpty ? 'stable' : entry.value;
        case 'bt_enabled':
          _btEnabled = entry.value == 'true';
        case 'bt_enable_dht':
          _btEnableDht = entry.value == 'true';
        case 'bt_enable_upnp':
          _btEnableUpnp = entry.value == 'true';
        case 'bt_port_start':
          _btPortStart = int.tryParse(entry.value) ?? 6881;
        case 'bt_port_end':
          _btPortEnd = int.tryParse(entry.value) ?? 6891;
        case 'bt_custom_trackers':
          _btCustomTrackers = entry.value;
        case 'bt_auto_reseed':
          _btAutoReseed = entry.value != '0';
        case 'bt_seed_enabled':
          _btSeedEnabled = entry.value != '0';
        case 'notify_on_complete':
          _notifyOnComplete = entry.value != 'false'; // 默认 true
        case 'silent_download_enabled':
          _silentDownloadEnabled = entry.value == 'true'; // 默认 false
        case 'silent_skip_selection':
          _silentSkipSelection = entry.value == 'true'; // 默认 false
        // 代理键落盘走 200ms 防抖（_saveProxyConfig）：若某键仍在待写队列，
        // 说明内存值比引擎快照新，跳过回写，否则防抖到期会把被快照覆盖的
        // 旧值重新持久化。
        case 'proxy_mode' when !_pendingProxyKeys.contains('proxy_mode'):
          _proxyMode = entry.value;
        case 'proxy_type' when !_pendingProxyKeys.contains('proxy_type'):
          _proxyType = entry.value;
        case 'proxy_host' when !_pendingProxyKeys.contains('proxy_host'):
          _proxyHost = entry.value;
        case 'proxy_port' when !_pendingProxyKeys.contains('proxy_port'):
          _proxyPort = entry.value;
        case 'global_user_agent':
          _globalUserAgent = entry.value;
        case 'default_queue_id':
          _defaultQueueId = entry.value;
        case 'file_exists_behavior':
          _fileExistsBehavior = entry.value.isEmpty ? 'rename' : entry.value;
        case 'last_dialog_threads':
          _lastDialogThreads = entry.value;
        case 'remember_last_save_dir':
          _rememberLastSaveDir = entry.value == 'true';
        case 'last_save_dir':
          _lastSaveDir = entry.value;
        case 'custom_categories':
          _customCategories = CustomCategory.decodeList(entry.value);
        case 'program_category_migrated':
          programCategoryMigrated = true;
      }
    }
    _loaded = true;
    if (!_loadedCompleter.isCompleted) {
      _loadedCompleter.complete();
    }
    notifyListeners();
    // 首次启动：若无自定义分类配置，使用内置默认分类
    if (_customCategories.isEmpty) {
      _customCategories = CustomCategory.defaultCategories();
    }
    // 一次性迁移：为旧配置补充「程序」内置分类（插到「压缩包」之前）。
    // 以显式 marker 键判定是否已迁移——不能用「列表里没有 program」当判据，
    // 否则用户删除该内置分类后每次启动都会被重新插回。
    if (!programCategoryMigrated) {
      if (!_customCategories.any((c) => c.builtinType == 'program')) {
        final defaults = CustomCategory.defaultCategories();
        final program = defaults.firstWhere((c) => c.builtinType == 'program');
        final archiveIdx = _customCategories.indexWhere(
          (c) => c.builtinType == 'archive',
        );
        final insertAt = archiveIdx >= 0
            ? archiveIdx
            : _customCategories.length;
        final pos = archiveIdx >= 0
            ? _customCategories[archiveIdx].position
            : program.position;
        _customCategories.insert(insertAt, program.copyWith(position: pos));
        // 顺延后续分类的 position，保证排序稳定
        for (var i = insertAt + 1; i < _customCategories.length; i++) {
          final c = _customCategories[i];
          if (c.position >= pos && c.position < 100) {
            _customCategories[i] = c.copyWith(position: c.position + 1);
          }
        }
        _persistCategories();
      }
    }
  }

  void _saveToRust(String key, String value) {
    SaveConfig(key: key, value: value).sendSignalToRust();
  }

  /// 代理配置防抖保存：200ms 内的多次变更合并为一次批量发送，
  /// 避免用户连续输入时触发多次 reqwest Client 重建。
  void _saveProxyConfig(String key, String value) {
    _pendingProxyKeys.add(key);
    _proxyDebounceTimer?.cancel();
    _proxyDebounceTimer = Timer(const Duration(milliseconds: 200), () {
      for (final k in _pendingProxyKeys) {
        _saveToRust(k, _proxyValueForKey(k));
      }
      _pendingProxyKeys.clear();
      _proxyDebounceTimer = null;
    });
  }

  /// 从当前内存状态读取代理字段值（供防抖 timer 回调使用）。
  String _proxyValueForKey(String key) => switch (key) {
    'proxy_mode' => _proxyMode,
    'proxy_type' => _proxyType,
    'proxy_host' => _proxyHost,
    'proxy_port' => _proxyPort,
    _ => '',
  };

  /// 平台默认下载目录（公开只读：供移动端判断「用户是否已自定义」）
  static String get platformDefaultSaveDir => _platformDefaultSaveDir();

  /// 平台默认下载目录。
  ///
  /// **非 Android 平台不在 Dart 侧推导**：真实默认值由 Rust 用系统 API 解析
  /// （iOS 见 `native/engine/src/user_dirs.rs`），首次运行写入 config 并随
  /// 配置下发到这里；配置到达前 UI 只显示占位符，不会写出错路径。
  static String _platformDefaultSaveDir() {
    if (Platform.isAndroid) {
      // 应用专属外部目录，无需存储权限即可写入；
      // 公共 Download 目录（SAF/MediaStore）作为后续跟进项。
      // 与 Rust 侧 `download_actor::default_save_dir` 的 Android 分支保持一致。
      return '/storage/emulated/0/Android/data/com.fluxdown.app/files/Download';
    }
    return '';
  }
}

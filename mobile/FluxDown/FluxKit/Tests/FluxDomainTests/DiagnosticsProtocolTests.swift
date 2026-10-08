import FluxDomain
import Foundation
import Testing

/// 诊断 / 日志 DTO 解码（serde camelCase 真实形状）+ 级别宽松解码。
struct DiagnosticsProtocolTests {
    private static func decode<T: Decodable>(_ json: String, as type: T.Type = T.self) throws -> T {
        try ProtocolJSON.decode(T.self, from: Data(json.utf8), what: "test")
    }

    private static let reportJSON = """
    {"generatedAtUnixMs":1760000000123,"appVersion":"1.4.0","platform":"linux-x86_64",
     "agentDataDir":"/data/fluxdown","daemonConnected":true,
     "checks":[
       {"id":"log_dir","target":"/data/fluxdown/logs","level":"ok","detail":"writable","hint":"",
        "repair":{"action":"open_log_dir","target":"/data/fluxdown/logs"}},
       {"id":"save_dir","target":"/data/dl","level":"warn","detail":"free space low","hint":"low_disk_space"},
       {"id":"component","level":"error","detail":"yt-dlp blocked","hint":"component_blocked_fixable",
        "repair":{"action":"fix_component","target":"yt-dlp"}},
       {"id":"daemon","level":"info","detail":"connected"}
     ]}
    """

    @Test func reportDecodesChecksWithOptionalFields() throws {
        let report = try Self.decode(Self.reportJSON, as: DiagnosticsReportDto.self)
        #expect(report.generatedAtUnixMs == 1_760_000_000_123)
        #expect(report.appVersion == "1.4.0")
        #expect(report.platform == "linux-x86_64")
        #expect(report.agentDataDir == "/data/fluxdown")
        #expect(report.daemonConnected)
        #expect(report.checks.count == 4)

        let logDir = report.checks[0]
        #expect(logDir.id == "log_dir")
        #expect(logDir.level == .ok)
        #expect(logDir.repair == DiagnosticRepairParams(action: "open_log_dir", target: "/data/fluxdown/logs"))

        let save = report.checks[1]
        #expect(save.level == .warn)
        #expect(save.hint == "low_disk_space")
        #expect(save.repair == nil)

        // `target` / `hint` 带 serde default：旧主机缺键 → 空串；`repair` 缺键 → nil。
        let component = report.checks[2]
        #expect(component.target.isEmpty)
        #expect(component.repair == DiagnosticRepairParams(action: "fix_component", target: "yt-dlp"))
        let daemon = report.checks[3]
        #expect(daemon.target.isEmpty)
        #expect(daemon.hint.isEmpty)
        #expect(daemon.level == .info)
    }

    @Test func issueCountIsWarnPlusError() throws {
        let report = try Self.decode(Self.reportJSON, as: DiagnosticsReportDto.self)
        #expect(report.checks.filter(\.level.isIssue).count == 2)
    }

    @Test func unknownLevelIsKeptAndNotAnIssue() throws {
        let check = try Self.decode(#"{"id":"x","level":"fatal","detail":"d"}"#, as: DiagnosticCheckDto.self)
        #expect(check.level == .unknown("fatal"))
        #expect(!check.level.isIssue)
        #expect(check.level.reportTag == "FATAL")
        let data = try ProtocolJSON.encode(check, what: "check")
        #expect(String(decoding: data, as: UTF8.self).contains(#""level":"fatal""#))
    }

    @Test func levelWireRoundTrips() throws {
        for (wire, level) in [("info", DiagnosticLevel.info), ("ok", .ok), ("warn", .warn), ("error", .error)] {
            #expect(DiagnosticLevel(wire: wire) == level)
            #expect(level.wire == wire)
        }
        #expect(DiagnosticLevel.warn.reportTag == "WARN")
    }

    @Test func repairParamsEncodeBothFields() throws {
        let data = try ProtocolJSON.encode(DiagnosticRepairParams(action: "fix_component", target: "ffmpeg"), what: "repair")
        #expect(String(decoding: data, as: UTF8.self) == #"{"action":"fix_component","target":"ffmpeg"}"#)
        let bare = try Self.decode(#"{"action":"enable_service"}"#, as: DiagnosticRepairParams.self)
        #expect(bare.target.isEmpty)
    }

    @Test func logExportEncodesAndDecodes() throws {
        let params = try ProtocolJSON.encode(LogExportParams(targetPath: "/tmp/fluxdown-logs.zip"), what: "export")
        #expect(String(decoding: params, as: UTF8.self) == #"{"targetPath":"/tmp/fluxdown-logs.zip"}"#)
        let result = try Self.decode(#"{"path":"/tmp/fluxdown-logs.zip","bytes":48213}"#, as: LogExportResult.self)
        #expect(result.path == "/tmp/fluxdown-logs.zip")
        #expect(result.bytes == 48213)
    }

    @Test func logPathsDecode() throws {
        let paths = try Self.decode(#"{"agentLogDir":"/a/logs","daemonLogDir":"/d/logs"}"#, as: LogPathsDto.self)
        #expect(paths.agentLogDir == "/a/logs")
        #expect(paths.daemonLogDir == "/d/logs")
    }

    @Test func daemonDescribeIgnoresComponentsAndFutureKeys() throws {
        let json = """
        {"service":{"role":"daemon","serviceName":"fluxdown-daemon","serviceVersion":"1.4.0","protocolVersion":7,
                    "instanceId":"abc","capabilities":["daemon.tasks","daemon.queues"]},
         "tasks":12,"queues":3,"groups":1,"configRevision":42,"logDir":"/d/logs",
         "components":[{"component":"ffmpeg","status":{"source":"none"}}],"future":true}
        """
        let describe = try Self.decode(json, as: DaemonDiagnosticsDescribe.self)
        #expect(describe.service.serviceName == "fluxdown-daemon")
        #expect(describe.service.serviceVersion == "1.4.0")
        #expect(describe.service.protocolVersion == 7)
        #expect(describe.service.capabilities == ["daemon.tasks", "daemon.queues"])
        #expect(describe.tasks == 12)
        #expect(describe.queues == 3)
        #expect(describe.groups == 1)
        #expect(describe.configRevision == 42)
        #expect(describe.logDir == "/d/logs")
    }
}

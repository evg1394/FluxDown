package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private fun check(action: String?) =
        DiagnosticCheckDto(id = "x", repair = action?.let { DiagnosticRepairParams(it, "t") })

    @Test
    fun camelMatchesWeb() {
        assertEquals("CheckDisk", DiagnosticsLogic.camel("check_disk"))
        assertEquals("SaveDir", DiagnosticsLogic.camel("save_dir"))
        assertEquals("RefreshTrackers", DiagnosticsLogic.camel("refreshTrackers"))
        assertEquals("doctorCheckLogDir", DiagnosticsLogic.checkKey("log_dir"))
        assertEquals("doctorHintLowDiskSpace", DiagnosticsLogic.hintKey("low_disk_space"))
        assertEquals("doctorActionFixComponent", DiagnosticsLogic.actionKey("fix_component"))
    }

    @Test
    fun desktopOnlyChecksAreHidden() {
        val checks = listOf("nmh_binary", "url_protocol", "autostart", "notifications", "elevated_run", "save_dir", "daemon", "log_dir")
            .map { DiagnosticCheckDto(id = it) }
        assertEquals(listOf("save_dir", "daemon", "log_dir"), DiagnosticsLogic.visibleChecks(checks).map { it.id })
    }

    @Test
    fun repairHidesDesktopActionsAndUnlabelledOnes() {
        val labelled: (String) -> Boolean = { true }
        assertEquals("t", DiagnosticsLogic.repair(check("fix_component"), labelled)?.target)
        assertNotNull(DiagnosticsLogic.repair(check("enable_service"), labelled))
        for (hidden in listOf("open_log_dir", "openLogDir", "fix_dir_access", "open_settings", "reregister", "register", "test_notification")) {
            assertNull(hidden, DiagnosticsLogic.repair(check(hidden), labelled))
        }
        assertNull(DiagnosticsLogic.repair(check(null), labelled))
        assertNull(DiagnosticsLogic.repair(check("fix_component")) { false })
    }

    @Test
    fun repairErrorReasons() {
        assertEquals("doctorRepairCancelled", DiagnosticsLogic.repairErrorKey("elevationCancelled"))
        assertEquals("doctorRepairUnavailable", DiagnosticsLogic.repairErrorKey("elevationUnavailable"))
        assertEquals("doctorRepairRunningElevated", DiagnosticsLogic.repairErrorKey("runningElevated"))
        assertEquals("doctorRepairIncomplete", DiagnosticsLogic.repairErrorKey("repairIncomplete"))
        assertEquals("doctorRepairNotApplicable", DiagnosticsLogic.repairErrorKey("repairNotApplicable"))
        assertNull(DiagnosticsLogic.repairErrorKey("other"))
        assertNull(DiagnosticsLogic.repairErrorKey(null))
    }

    @Test
    fun issueCountIsWarnPlusError() {
        val checks = listOf(
            DiagnosticCheckDto("a", level = DiagnosticLevel.Ok),
            DiagnosticCheckDto("b", level = DiagnosticLevel.Warn),
            DiagnosticCheckDto("c", level = DiagnosticLevel.Error),
            DiagnosticCheckDto("d", level = DiagnosticLevel.Info),
            DiagnosticCheckDto("e", level = DiagnosticLevel.Unknown("fatal")),
        )
        assertEquals(2, DiagnosticsLogic.issueCount(checks))
    }

    @Test
    fun worstLevelOrdering() {
        assertEquals(DiagnosticLevel.Warn, DiagnosticsLogic.worst(listOf(DiagnosticLevel.Ok, DiagnosticLevel.Info, DiagnosticLevel.Warn)))
        assertEquals(DiagnosticLevel.Error, DiagnosticsLogic.worst(listOf(DiagnosticLevel.Ok, DiagnosticLevel.Error, DiagnosticLevel.Warn)))
        assertEquals(DiagnosticLevel.Info, DiagnosticsLogic.worst(listOf(DiagnosticLevel.Ok, DiagnosticLevel.Info)))
        assertEquals(DiagnosticLevel.Ok, DiagnosticsLogic.worst(emptyList()))
        // unknown 按 info 处理：压过 ok、不压过 warn
        val unknown = DiagnosticLevel.Unknown("x")
        assertEquals(unknown, DiagnosticsLogic.worst(listOf(DiagnosticLevel.Ok, unknown)))
        assertEquals(DiagnosticLevel.Warn, DiagnosticsLogic.worst(listOf(unknown, DiagnosticLevel.Warn)))
    }

    @Test
    fun levelWireRoundTrip() {
        for (level in listOf(DiagnosticLevel.Info, DiagnosticLevel.Ok, DiagnosticLevel.Warn, DiagnosticLevel.Error)) {
            assertEquals(level, DiagnosticLevel.fromWire(level.wire))
        }
        assertEquals(DiagnosticLevel.Unknown("fatal"), DiagnosticLevel.fromWire("fatal"))
        assertEquals("WARN", DiagnosticLevel.Warn.reportTag)
        assertEquals("FATAL", DiagnosticLevel.Unknown("fatal").reportTag)
    }

    @Test
    fun reportParsesChecksWithOptionalFields() {
        val report = DiagnosticsReportDto.fromJson(
            Json.parse(
                """{"generatedAtUnixMs":1700000000000,"appVersion":"1.4.0","platform":"linux-x86_64","agentDataDir":"/data",
                |"daemonConnected":true,"checks":[
                |{"id":"save_dir","target":"/dl","level":"warn","detail":"low","hint":"check_disk","repair":{"action":"fix_dir_access","target":"/dl"}},
                |{"id":"daemon","level":"ok","detail":"fine"},
                |{"level":"ok","detail":"no id: skipped"}]}""".trimMargin(),
            ),
        )
        assertEquals(1_700_000_000_000L, report.generatedAtUnixMs)
        assertEquals("1.4.0", report.appVersion)
        assertTrue(report.daemonConnected)
        assertEquals(listOf("save_dir", "daemon"), report.checks.map { it.id })
        val first = report.checks[0]
        assertEquals(DiagnosticLevel.Warn, first.level)
        assertEquals("check_disk", first.hint)
        assertEquals(DiagnosticRepairParams("fix_dir_access", "/dl"), first.repair)
        val second = report.checks[1]
        assertEquals("", second.target)
        assertEquals("", second.hint)
        assertNull(second.repair)
    }

    @Test
    fun repairParamsWireShape() {
        assertEquals("""{"action":"fix_component","target":"t"}""", DiagnosticRepairParams("fix_component", "t").toJson().toJson())
        assertEquals("""{"action":"enable_service","target":""}""", DiagnosticRepairParams("enable_service").toJson().toJson())
        assertEquals("""{"targetPath":"/tmp/a.zip"}""", LogExportParams("/tmp/a.zip").toJson().toJson())
    }

    @Test
    fun describeAndLogResultsParseLeniently() {
        val env = DaemonDiagnosticsDescribe.fromJson(
            Json.parse("""{"service":{"serviceVersion":"1.4.0","protocolVersion":7},"tasks":3,"queues":2,"configRevision":9,"logDir":"/logs","components":[{}]}"""),
        )
        assertEquals("1.4.0", env.service.serviceVersion)
        assertEquals(7L, env.service.protocolVersion)
        assertEquals(3L, env.tasks)
        assertEquals(0L, env.groups)
        assertEquals("/logs", env.logDir)
        assertEquals(DaemonDiagnosticsDescribe(), DaemonDiagnosticsDescribe.fromJson(Json.parse("{}")))

        assertEquals(LogExportResult("/tmp/x.zip", 42), LogExportResult.fromJson(Json.parse("""{"path":"/tmp/x.zip","bytes":42}""")))
        assertNull(LogExportResult.fromJson(Json.parse("""{"bytes":42}""")))
        assertEquals(LogPathsDto("/a", ""), LogPathsDto.fromJson(Json.parse("""{"agentLogDir":"/a"}""")))
    }

    @Test
    fun repairTagDistinguishesSameIdAndTarget() {
        val c = DiagnosticCheckDto("save_dir", target = "/dl")
        assertEquals("0-save_dir-/dl", DiagnosticsLogic.repairTag(0, c))
        assertFalse(DiagnosticsLogic.repairTag(0, c) == DiagnosticsLogic.repairTag(1, c))
    }

    @Test
    fun reportListsEveryCheckWithHint() {
        val input = DiagnosticsLogic.ReportInput(
            appVersion = "1.2 (3)",
            deviceDescription = "Android 16 · Pixel",
            generatedAtUnixMs = 0,
            device = listOf(DiagnosticsLogic.ReportLine(DiagnosticLevel.Ok, "Notifications", detail = "Allowed")),
            host = DiagnosticsLogic.HostPart(
                name = "NAS", appVersion = "1.4.0", platform = "linux-x86_64", dataDir = "/data", daemonConnected = true,
                daemonSummary = "1.4.0 · 3 tasks",
                lines = listOf(
                    DiagnosticsLogic.ReportLine(DiagnosticLevel.Warn, "Default download folder", "/dl", "low", "free space"),
                ),
            ),
        )
        val text = DiagnosticsLogic.renderReport(input)
        assertTrue(text.startsWith("FluxDown 1.2 (3) · Android 16 · Pixel · 1970-01-01T00:00:00Z\n"))
        assertTrue(text.contains("[OK] Notifications: Allowed\n"))
        assertTrue(text.contains("[Download host: NAS]\n"))
        assertTrue(text.contains("agent data dir: /data\n"))
        assertTrue(text.contains("daemon connected: true\n"))
        assertTrue(text.contains("daemon: 1.4.0 · 3 tasks\n"))
        assertTrue(text.contains("[WARN] Default download folder (/dl): low\n  hint: free space\n"))
    }

    @Test
    fun reportWithoutHostOmitsHostSection() {
        val input = DiagnosticsLogic.ReportInput("1", "Android", 0, emptyList(), null)
        assertFalse(DiagnosticsLogic.renderReport(input).contains("Download host"))
    }

    @Test
    fun recordResetsOnHostChange() {
        val record = DiagnosticsRecord()
        record.record("a", 2)
        record.reset(ifHostIsNot = "a")
        assertEquals(2, record.issues)
        record.reset(ifHostIsNot = "b")
        assertNull(record.issues)
        assertNull(record.hostId)
        record.record("b", 0)
        assertEquals(0, record.issues)
        record.reset(ifHostIsNot = "b")
        assertEquals(0, record.issues)
    }
}

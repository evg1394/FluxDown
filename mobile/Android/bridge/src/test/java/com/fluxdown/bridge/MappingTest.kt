package com.fluxdown.bridge

import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostEvent
import com.fluxdown.core.host.HostSignal
import com.fluxdown.core.model.FileExistsAction
import com.fluxdown.core.model.SeedingStatus
import com.fluxdown.core.model.SelectionKind
import com.fluxdown.core.model.SelectionOutcome
import com.fluxdown.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingTest {
    @Test
    fun everyErrorCodeMapsToItsNamesake() {
        for (dto in ErrorCodeDto.entries) {
            val core = dto.toCore()
            assertEquals(dto.name.replace("_", "").lowercase(), core.name.lowercase())
        }
    }

    @Test
    fun rpcExceptionKeepsReasonAndRetryable() {
        val host = FluxException.Rpc(ErrorCodeDto.CONFLICT, "revisionConflict", true, "stale revision").toHost()
        assertEquals(HostErrorCode.Conflict, host.code)
        assertEquals("revisionConflict", host.reason)
        assertTrue(host.retryable)
        assertEquals("stale revision", host.message)
    }

    @Test
    fun transportIsRetryableUnavailable() {
        val host = FluxException.Transport("connection refused").toHost()
        assertEquals(HostErrorCode.Unavailable, host.code)
        assertTrue(host.retryable)
        assertNull(host.reason)
        assertEquals("connection refused", host.message)
    }

    @Test
    fun fatalSignalCarriesTheError() {
        val signal = HostSignalDto.Fatal(HostErrorDto(ErrorCodeDto.UNAUTHORIZED, null, false, "bad key")).toCore()
        val error = (signal as HostSignal.Fatal).error
        assertEquals(HostErrorCode.Unauthorized, error.code)
        assertEquals("bad key", error.message)
        assertSame(HostSignal.Stale, HostSignalDto.Stale.toCore())
    }

    @Test
    fun taskDtoMapsStatusSourceBytesAndSeeding() {
        val task = task(status = 5, seeding = 1).toCore()
        assertEquals(TaskStatus.Preparing, task.status)
        assertEquals(SeedingStatus.Seeding, task.seedingStatus)
        assertEquals(6L, task.sourceBytes.total)
        assertEquals(1_700_000_000L, task.createdAt)
        assertEquals(TaskStatus.Unknown, task(status = 99, seeding = 99).toCore().status)
    }

    @Test
    fun unsignedFieldsConvertToLong() {
        val runtime = TaskRuntimeDto("t", ULong.MAX_VALUE.shr(1), 3u, null, 10, emptyList()).toCore()
        assertEquals(Long.MAX_VALUE, runtime.sampleSequence)
        assertEquals(3, runtime.activeTransfers)
        assertNull(runtime.connectedPeers)

        val config = HostEventDto.ConfigChanged(mapOf("k" to "v"), 42uL).toCore() as HostEvent.ConfigChanged
        assertEquals(42L, config.revision)
        assertEquals(mapOf("k" to "v"), config.values)
    }

    @Test
    fun progressEventIsPassedThroughUnchanged() {
        val event = HostEventDto.TaskProgress("t", 4, 1, 2, 3, 4, "f", "deleted", 5, 0).toCore()
        assertEquals(HostEvent.TaskProgress("t", 4, 1, 2, 3, 4, "f", "deleted", 5, 0), event)
    }

    @Test
    fun sectionAndNoticeEventsKeepNameAndJson() {
        assertEquals(
            HostEvent.SectionChanged("agent.gateway", """{"takeoverEnabled":true}"""),
            HostEventDto.SectionChanged("agent.gateway", """{"takeoverEnabled":true}""").toCore(),
        )
        assertEquals(
            HostEvent.Notice("duplicateTorrent", """{"type":"duplicateTorrent"}"""),
            HostEventDto.Notice("duplicateTorrent", """{"type":"duplicateTorrent"}""").toCore(),
        )
    }

    @Test
    fun selectionOutcomesRoundTrip() {
        val outcomes = listOf(
            SelectionOutcome.Hls(2),
            SelectionOutcome.Bt(listOf(0, 3)),
            SelectionOutcome.Variant(1),
            SelectionOutcome.FileExists(FileExistsAction.Rename),
            SelectionOutcome.FileExists(FileExistsAction.Overwrite),
            SelectionOutcome.FileExists(FileExistsAction.Skip),
            SelectionOutcome.Cancelled,
        )
        for (outcome in outcomes) assertEquals(outcome, outcome.toDto().toCore())
    }

    @Test
    fun fileExistsKindKeepsEveryField() {
        val dto = SelectionKindDto.FileExists(
            fileName = "a.bin",
            saveDir = "/data/dl",
            existingSize = 1_048_576uL,
            existingModifiedUnixMs = 1_700_000_000_000L,
            incomingSize = null,
            renamePreview = "a (1).bin",
            actions = listOf(FileExistsActionDto.RENAME, FileExistsActionDto.OVERWRITE),
        )
        assertEquals(
            SelectionKind.FileExists(
                fileName = "a.bin",
                saveDir = "/data/dl",
                existingSize = 1_048_576L,
                existingModifiedUnixMs = 1_700_000_000_000L,
                incomingSize = null,
                renamePreview = "a (1).bin",
                actions = listOf(FileExistsAction.Rename, FileExistsAction.Overwrite),
            ),
            dto.toCore(),
        )
    }

    private fun task(status: Int, seeding: Int) = TaskDto(
        taskId = "t1", url = "https://example.com/a.bin", originUrl = "", fileName = "a.bin", saveDir = "/data",
        status = status, downloadedBytes = 5, totalBytes = 10, errorMessage = "", createdAt = 1_700_000_000,
        completedAt = 0, queueId = "main", groupId = "", rssSourceId = "", fileMissing = false, autoRoute = "",
        sourceCdn = 1, sourceProxy = 2, sourceNic = 3, uploadedBytes = 0, seedingStatus = seeding,
        seedingMessage = "", seedingTimeSecs = 0,
    )
}

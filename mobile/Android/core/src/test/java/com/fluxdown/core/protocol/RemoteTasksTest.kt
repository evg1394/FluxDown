package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTasksTest {
    @Test
    fun parsesSectionLenientlyAndKeepsUnknownStatus() {
        val tasks = RemoteTaskDto.listFromJson(
            Json.parse(
                """[{"id":"a","toDevice":"mac","url":"https://x/a.zip","fileName":null,"status":"downloading",
                   "totalBytes":100,"downloadedBytes":40,"speed":7,"progress":0.4,"updatedAt":"2026-01-02"},
                   {"id":"b","status":"teleporting"},
                   {"toDevice":"no-id"}]""",
            ),
        )
        assertEquals(listOf("a", "b"), tasks.map { it.id })
        assertEquals("", tasks[0].fileName)
        assertEquals(RemoteTaskStatus.Downloading, tasks[0].status)
        assertEquals(100L, tasks[0].totalBytes)
        assertEquals(0.4, tasks[0].progress, 1e-9)
        assertEquals(RemoteTaskStatus.Unknown("teleporting"), tasks[1].status)
        assertNull(tasks[1].totalBytes)
        assertTrue(RemoteTaskDto.listFromJson(Json.parse("null")).isEmpty())
    }

    @Test
    fun commandMatrixMatchesGpui() {
        val all = RemoteCommandAction.entries
        fun allowed(s: RemoteTaskStatus) = all.filter { s.allows(it) }
        val running = listOf(RemoteCommandAction.Pause, RemoteCommandAction.Cancel, RemoteCommandAction.Delete)
        assertEquals(running, allowed(RemoteTaskStatus.Pending))
        assertEquals(running, allowed(RemoteTaskStatus.Accepted))
        assertEquals(running, allowed(RemoteTaskStatus.Downloading))
        assertEquals(listOf(RemoteCommandAction.Resume, RemoteCommandAction.Cancel, RemoteCommandAction.Delete), allowed(RemoteTaskStatus.Paused))
        for (s in listOf(RemoteTaskStatus.Completed, RemoteTaskStatus.Failed, RemoteTaskStatus.Canceled)) {
            assertEquals(listOf(RemoteCommandAction.Delete), allowed(s))
        }
        assertTrue(allowed(RemoteTaskStatus.Unknown("x")).isEmpty())
    }

    @Test
    fun pauseResumeNeedTargetOnlineButUnknownPresenceDoesNotBlock() {
        val running = RemoteTaskDto("t", status = RemoteTaskStatus.Downloading)
        assertFalse(RemoteTaskRules.canIssue(RemoteCommandAction.Pause, running, targetOnline = false))
        assertTrue(RemoteTaskRules.canIssue(RemoteCommandAction.Pause, running, targetOnline = null))
        assertTrue(RemoteTaskRules.canIssue(RemoteCommandAction.Cancel, running, targetOnline = false))
        assertEquals(RemoteCommandAction.Pause, RemoteTaskRules.primaryAction(running))
        assertEquals(RemoteCommandAction.Resume, RemoteTaskRules.primaryAction(running.copy(status = RemoteTaskStatus.Paused)))
        assertNull(RemoteTaskRules.primaryAction(running.copy(status = RemoteTaskStatus.Completed)))
    }

    @Test
    fun visibleDropsMirrorsOfCurrentDevice() {
        val tasks = listOf(
            RemoteTaskDto("done-new", toDevice = "mac", status = RemoteTaskStatus.Completed, updatedAt = "2026-03"),
            RemoteTaskDto("run-old", toDevice = "mac", status = RemoteTaskStatus.Downloading, updatedAt = "2026-01"),
            RemoteTaskDto("mine", toDevice = "me", status = RemoteTaskStatus.Downloading, updatedAt = "2026-04"),
            RemoteTaskDto("run-new", toDevice = "pc", status = RemoteTaskStatus.Paused, updatedAt = "2026-02"),
        )
        val visible = RemoteTaskRules.visible(tasks, currentDeviceId = "me")
        assertEquals(listOf("done-new", "run-old", "run-new"), visible.map { it.id })
        assertEquals(4, RemoteTaskRules.visible(tasks, currentDeviceId = null).size)
    }

    @Test
    fun createdAtParsesIsoWithOrWithoutFraction() {
        assertEquals(1_767_225_600L, RemoteTaskDto("a", createdAt = "2026-01-01T00:00:00Z").createdAtSeconds)
        assertEquals(1_767_225_600L, RemoteTaskDto("a", createdAt = "2026-01-01T08:00:00.123456+08:00").createdAtSeconds)
        assertEquals(0L, RemoteTaskDto("a", createdAt = "").createdAtSeconds)
        assertEquals(0L, RemoteTaskDto("a", createdAt = "yesterday").createdAtSeconds)
    }

    @Test
    fun commandParamsOmitMissingCommandId() {
        assertEquals(
            """{"taskId":"t","action":"delete","deleteFiles":true}""",
            RemoteCommandParams("t", RemoteCommandAction.Delete, deleteFiles = true).toJson().toJson(),
        )
    }
}

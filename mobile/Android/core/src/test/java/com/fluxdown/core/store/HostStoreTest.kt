package com.fluxdown.core.store

import com.fluxdown.core.host.HostEvent
import com.fluxdown.core.host.HostSignal
import com.fluxdown.core.host.HostSnapshot
import com.fluxdown.core.model.HostInfo
import com.fluxdown.core.model.RuntimeStats
import com.fluxdown.core.model.Segment
import com.fluxdown.core.model.Task
import com.fluxdown.core.model.TaskRuntime
import com.fluxdown.core.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreTest {
    private fun task(id: String, status: TaskStatus, done: Long = 50, total: Long = 100) =
        Task(taskId = id, url = "https://x/$id", originUrl = "", fileName = "$id.bin", saveDir = "/d", status = status, downloadedBytes = done, totalBytes = total)

    private fun seg(i: Int, active: Boolean?) = Segment(i, i * 10L, i * 10L + 9, 5, active)

    private fun runtime(id: String, seq: Long, segs: List<Segment>) = TaskRuntime(id, seq, 2, null, 100, segs)

    private fun snapshot(vararg tasks: Task, runtime: Map<String, TaskRuntime> = emptyMap()) = HostSignal.Snapshot(
        HostSnapshot(
            info = HostInfo("t", "1", 7, emptySet()), daemonConnected = true, tasks = tasks.toList(), runtime = runtime,
            queues = emptyList(), queuePositions = emptyMap(), groups = emptyList(), stats = RuntimeStats(), priorityTaskId = null,
            pendingSelections = emptyList(), config = emptyMap(), configRevision = 1, rssSources = emptyList(),
            cloudDevices = emptyList(), linkDevices = emptyList(),
        ),
    )

    private fun progress(id: String, status: TaskStatus, speed: Long, error: String = "") = HostSignal.Event(
        HostEvent.TaskProgress(id, status.wire, 60, 100, speed, 0, "", error, 0, 0),
    )

    private fun TestScope.store(): HostStore = HostStore(this, clock = { 1_000_000 })

    @Test
    fun progressWithDeletedSentinelRemovesTaskAndRuntime() = runTest {
        val s = store()
        s.apply(snapshot(task("a", TaskStatus.Downloading), runtime = mapOf("a" to runtime("a", 1, listOf(seg(0, true))))))
        s.apply(progress("a", TaskStatus.Failed, 0, error = "deleted"))
        assertNull(s.state.value.task("a"))
        assertFalse("a" in s.state.value.runtime)
    }

    @Test
    fun staleRuntimeSampleIsDroppedAndEmptySegmentsKeepPrevious() = runTest {
        val s = store()
        s.apply(snapshot(task("a", TaskStatus.Downloading), runtime = mapOf("a" to runtime("a", 5, listOf(seg(0, true), seg(1, true))))))
        s.apply(HostSignal.Event(HostEvent.TaskRuntimeChanged(runtime("a", 4, listOf(seg(0, false))))))
        advanceUntilIdle()
        assertEquals(2, s.state.value.runtime.getValue("a").segments.size)

        s.apply(HostSignal.Event(HostEvent.TaskRuntimeChanged(runtime("a", 6, emptyList()).copy(activeTransfers = 1))))
        advanceUntilIdle()
        val rt = s.state.value.runtime.getValue("a")
        assertEquals(6, rt.sampleSequence)
        assertEquals(2, rt.segments.size)
        assertEquals(1, rt.activeTransfers)
    }

    @Test
    fun pausingClearsActiveSegmentsAndLiveSpeed() = runTest {
        val s = store()
        s.apply(snapshot(task("a", TaskStatus.Downloading), runtime = mapOf("a" to runtime("a", 1, listOf(seg(0, true), seg(1, null))))))
        s.apply(progress("a", TaskStatus.Downloading, 4096))
        advanceUntilIdle()
        assertEquals(4096, s.state.value.speeds.getValue("a").down)

        s.apply(progress("a", TaskStatus.Paused, 4096))
        val st = s.state.value
        assertEquals(TaskStatus.Paused, st.task("a")?.status)
        assertEquals(0L, st.speeds.getValue("a").down)
        assertTrue(st.runtime.getValue("a").segments.all { it.active == false })
        assertEquals(0, st.runtime.getValue("a").activeTransfers)
    }

    @Test
    fun staleSignalMakesStateReadOnlyAndDropsRuntimeAndSpeeds() = runTest {
        val s = store()
        s.apply(snapshot(task("a", TaskStatus.Downloading), runtime = mapOf("a" to runtime("a", 1, listOf(seg(0, true))))))
        s.apply(progress("a", TaskStatus.Downloading, 100))
        s.apply(HostSignal.Stale)
        val st = s.state.value
        assertTrue(st.isReadOnly)
        assertTrue(st.runtime.isEmpty())
        assertTrue(st.speeds.isEmpty())
        assertEquals(1, st.tasks.size)
    }

    @Test
    fun highFrequencyProgressIsCoalescedIntoOnePublish() = runTest {
        val s = store()
        s.apply(snapshot(task("a", TaskStatus.Downloading)))
        val before = s.state.value
        repeat(20) { s.apply(progress("a", TaskStatus.Downloading, it.toLong() + 1)) }
        assertTrue("non-structural progress must not publish synchronously", s.state.value === before)
        advanceUntilIdle()
        assertEquals(20L, s.state.value.speeds.getValue("a").down)
    }

    @Test
    fun sectionChangedReplacesTheSectionImmediatelyAndNoticesDoNotTouchState() = runTest {
        val s = store()
        s.apply(
            HostSignal.Snapshot(
                HostSnapshot(
                    info = HostInfo("t", "1", 7, emptySet()),
                    daemonConnected = true,
                    tasks = emptyList(),
                    runtime = emptyMap(),
                    queues = emptyList(),
                    queuePositions = emptyMap(),
                    groups = emptyList(),
                    stats = RuntimeStats(),
                    priorityTaskId = null,
                    pendingSelections = emptyList(),
                    config = emptyMap(),
                    configRevision = 1,
                    rssSources = emptyList(),
                    cloudDevices = emptyList(),
                    linkDevices = emptyList(),
                    sections = mapOf("agent.gateway" to """{"takeoverEnabled":false}"""),
                ),
            ),
        )
        assertEquals("""{"takeoverEnabled":false}""", s.state.value.sections["agent.gateway"])
        s.apply(HostSignal.Event(HostEvent.SectionChanged("agent.gateway", """{"takeoverEnabled":true}""")))
        assertEquals("""{"takeoverEnabled":true}""", s.state.value.sections["agent.gateway"])
        val before = s.state.value
        s.apply(HostSignal.Event(HostEvent.Notice("captureTasksStarted", """["t1"]""")))
        advanceUntilIdle()
        assertEquals(before, s.state.value)
    }
}

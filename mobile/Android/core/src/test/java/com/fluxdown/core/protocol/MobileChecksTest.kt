package com.fluxdown.core.protocol

import com.fluxdown.core.protocol.MobileNetworkSnapshot.Reachability
import com.fluxdown.core.protocol.MobileNetworkSnapshot.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileChecksTest {
    private val fmt: (Long) -> String = { "$it B" }

    @Test
    fun notificationLevels() {
        assertEquals(DiagnosticLevel.Ok, MobileChecks.notifications(NotificationState.Enabled).level)
        assertNull(MobileChecks.notifications(NotificationState.Enabled).fix)
        val off = MobileChecks.notifications(NotificationState.Disabled)
        assertEquals(DiagnosticLevel.Warn, off.level)
        assertEquals(MobileFix.NotificationSettings, off.fix)
        assertTrue(off.hint.isNotEmpty())
        val channels = MobileChecks.notifications(NotificationState.ChannelsBlocked)
        assertEquals(DiagnosticLevel.Warn, channels.level)
        assertEquals(MobileFix.NotificationSettings, channels.fix)
    }

    @Test
    fun backgroundLevelsAndFixPriority() {
        val ok = MobileChecks.background(backgroundRestricted = false, batteryUnrestricted = true, powerSave = false)
        assertEquals(DiagnosticLevel.Ok, ok.level)
        assertNull(ok.fix)
        // 仍受电池优化：只是 info，但给出入口
        val optimized = MobileChecks.background(false, false, false)
        assertEquals(DiagnosticLevel.Info, optimized.level)
        assertEquals(MobileFix.BatteryOptimization, optimized.fix)
        val saver = MobileChecks.background(false, true, true)
        assertEquals(DiagnosticLevel.Warn, saver.level)
        assertEquals(MobileFix.BatterySaver, saver.fix)
        // 后台受限最严重，修复指向应用详情
        val restricted = MobileChecks.background(true, false, true)
        assertEquals(DiagnosticLevel.Warn, restricted.level)
        assertEquals(MobileFix.AppDetails, restricted.fix)
        assertEquals(3, restricted.detail.size)
    }

    @Test
    fun storageLevels() {
        assertEquals(DiagnosticLevel.Error, MobileChecks.storage(false, 10L shl 30, fmt).level)
        assertEquals(DiagnosticLevel.Info, MobileChecks.storage(true, null, fmt).level)
        assertEquals(DiagnosticLevel.Error, MobileChecks.storage(true, 50L shl 20, fmt).level)
        assertEquals(DiagnosticLevel.Warn, MobileChecks.storage(true, 500L shl 20, fmt).level)
        assertEquals(DiagnosticLevel.Ok, MobileChecks.storage(true, 5L shl 30, fmt).level)
        // 边界：恰好等于阈值不算低
        assertEquals(DiagnosticLevel.Warn, MobileChecks.storage(true, MobileChecks.LOW_STORAGE_ERROR, fmt).level)
        assertEquals(DiagnosticLevel.Ok, MobileChecks.storage(true, MobileChecks.LOW_STORAGE_WARNING, fmt).level)
    }

    @Test
    fun storageDetailCarriesFormattedFreeSpace() {
        val detail = MobileChecks.storage(true, 2048, fmt).detail.single()
        assertEquals(MobileCode.STORAGE_WRITABLE_FREE, detail.code)
        assertEquals("2048 B", detail.args["free"])
    }

    @Test
    fun linkLevels() {
        val mine = LinkProbe(LinkHandler.This)
        assertEquals(DiagnosticLevel.Ok, MobileChecks.links(mine, mine, mine).level)
        // `.torrent` 缺失 / 被别人处理只是 info
        val missing = LinkProbe(LinkHandler.Missing)
        assertEquals(DiagnosticLevel.Info, MobileChecks.links(mine, mine, missing).level)
        assertEquals(DiagnosticLevel.Info, MobileChecks.links(mine, mine, LinkProbe(LinkHandler.Other, "com.x")).level)
        // 每次询问：info，不算问题
        assertEquals(DiagnosticLevel.Info, MobileChecks.links(LinkProbe(LinkHandler.Ask), mine, mine).level)
        // magnet / ed2k 没声明或被抢走：warn
        assertEquals(DiagnosticLevel.Warn, MobileChecks.links(missing, mine, mine).level)
        assertEquals(DiagnosticLevel.Warn, MobileChecks.links(mine, LinkProbe(LinkHandler.Other, "com.x"), mine).level)
    }

    @Test
    fun linkWarnPointsAtTheOtherDefaultHandler() {
        val mine = LinkProbe(LinkHandler.This)
        val other = MobileChecks.links(LinkProbe(LinkHandler.Other, "com.torrent.app"), mine, mine)
        assertEquals(MobileFix.AppDetails, other.fix)
        assertEquals("com.torrent.app", other.fixPackage)
        assertTrue(other.hint.isNotEmpty())
        // 声明缺失：指向本应用详情（fixPackage = null）
        val missing = MobileChecks.links(LinkProbe(LinkHandler.Missing), mine, mine)
        assertEquals(MobileFix.AppDetails, missing.fix)
        assertNull(missing.fixPackage)
        // 无问题时没有修复入口
        assertNull(MobileChecks.links(mine, mine, mine).fix)
    }

    private fun snapshot(
        reachability: Reachability = Reachability.Satisfied,
        transport: Transport = Transport.Wifi,
        metered: Boolean = false,
        dataSaver: Boolean = false,
    ) = MobileNetworkSnapshot(reachability, transport, metered, dataSaver, supportsIPv4 = true, supportsIPv6 = true)

    @Test
    fun networkLevels() {
        assertEquals(DiagnosticLevel.Info, MobileChecks.network(null).level)
        assertEquals(DiagnosticLevel.Error, MobileChecks.network(snapshot(Reachability.Unsatisfied, Transport.None)).level)
        assertEquals(DiagnosticLevel.Warn, MobileChecks.network(snapshot(Reachability.Unvalidated)).level)
        assertEquals(DiagnosticLevel.Ok, MobileChecks.network(snapshot()).level)
        assertEquals(DiagnosticLevel.Info, MobileChecks.network(snapshot(transport = Transport.Cellular, metered = true)).level)
        val saver = MobileChecks.network(snapshot(metered = true, dataSaver = true))
        assertEquals(DiagnosticLevel.Warn, saver.level)
        assertEquals(MobileFix.DataSaver, saver.fix)
    }

    @Test
    fun networkDetailListsTransportAndAddressFamilies() {
        val detail = MobileChecks.network(snapshot()).detail
        assertEquals(MobileCode.NET_WIFI, detail[0].code)
        assertEquals("IPv4 + IPv6", detail[1].args["text"])
        val v4 = MobileChecks.network(snapshot().copy(supportsIPv6 = false)).detail
        assertEquals("IPv4", v4[1].args["text"])
        val none = MobileChecks.network(snapshot().copy(supportsIPv4 = false, supportsIPv6 = false)).detail
        assertEquals(1, none.size)
    }

    @Test
    fun engineLevels() {
        assertEquals(DiagnosticLevel.Ok, MobileChecks.engine(EngineState.Live, "1.4.0", 7, null).level)
        assertEquals(DiagnosticLevel.Info, MobileChecks.engine(EngineState.Connecting, null, null, null).level)
        assertEquals(DiagnosticLevel.Warn, MobileChecks.engine(EngineState.Stale, "1.4.0", 7, null).level)
        val failed = MobileChecks.engine(EngineState.Failed, null, null, "no route")
        assertEquals(DiagnosticLevel.Error, failed.level)
        assertEquals("no route", failed.detail.single().args["text"])
        assertEquals(MobileCode.ENGINE_FAILED, MobileChecks.engine(EngineState.Failed, null, null, null).detail.single().code)
    }

    @Test
    fun engineLiveShowsVersionOnlyWhenKnown() {
        assertEquals(2, MobileChecks.engine(EngineState.Live, "1.4.0", 7, null).detail.size)
        assertEquals(1, MobileChecks.engine(EngineState.Live, null, null, null).detail.size)
    }

    @Test
    fun issueCountIsWarnPlusError() {
        val checks = listOf(
            MobileCheck(MobileCheckId.Storage, DiagnosticLevel.Warn, emptyList()),
            MobileCheck(MobileCheckId.Network, DiagnosticLevel.Error, emptyList()),
            MobileCheck(MobileCheckId.Engine, DiagnosticLevel.Ok, emptyList()),
            MobileCheck(MobileCheckId.Links, DiagnosticLevel.Info, emptyList()),
        )
        assertEquals(2, MobileChecks.issueCount(checks))
    }
}

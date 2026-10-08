package com.fluxdown.core.protocol

import com.fluxdown.core.model.CloudDevice
import com.fluxdown.core.model.LinkDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 iOS `RemoteTasksProtocolTests` 的同一组下发规则用例。 */
class DevicesTest {
    @Test
    fun pathStyleInferenceAndAbsolutePaths() {
        assertEquals(PathStyle.Windows, PathStyle.effective(null, "Windows"))
        assertEquals(PathStyle.Posix, PathStyle.effective("posix", "windows"))
        assertEquals(PathStyle.Posix, PathStyle.effective("weird", "android"))
        assertNull(PathStyle.effective(null, "web"))
        assertTrue(PathStyle.Windows.isAbsolute("C:\\dl"))
        assertTrue(PathStyle.Windows.isAbsolute("d:/dl"))
        assertTrue(PathStyle.Windows.isAbsolute("\\\\server\\share"))
        assertFalse(PathStyle.Windows.isAbsolute("/home"))
        assertFalse(PathStyle.Windows.isAbsolute("C:"))
        assertTrue(PathStyle.Posix.isAbsolute(" /mnt/dl "))
        assertFalse(PathStyle.Posix.isAbsolute("dl"))
        assertFalse(PathStyle.Unknown("q").isAbsolute("/x"))
    }

    @Test
    fun remoteSaveDirFollowsTargetPathStyle() {
        assertEquals(DeviceRules.SaveDirCheck.UseDefault, DeviceRules.checkSaveDir("  ", PathStyle.Posix))
        assertEquals(DeviceRules.SaveDirCheck.Explicit("/mnt/dl"), DeviceRules.checkSaveDir(" /mnt/dl ", PathStyle.Posix))
        assertEquals(DeviceRules.SaveDirCheck.Invalid, DeviceRules.checkSaveDir("C:\\dl", PathStyle.Posix))
        assertEquals(DeviceRules.SaveDirCheck.Invalid, DeviceRules.checkSaveDir("/mnt", PathStyle.Windows))
        assertEquals(DeviceRules.SaveDirCheck.Explicit("D:\\x"), DeviceRules.checkSaveDir("D:\\x", PathStyle.Windows))
        // 风格未知：无法判断，非空即放行。
        assertEquals(DeviceRules.SaveDirCheck.Explicit("whatever"), DeviceRules.checkSaveDir("whatever", null))
        assertEquals(DeviceRules.SaveDirCheck.Explicit("whatever"), DeviceRules.checkSaveDir("whatever", PathStyle.Unknown("?")))
        assertEquals("D:\\Downloads", PathStyle.example(PathStyle.Windows))
        assertEquals("/home/user/Downloads", PathStyle.example(null))
    }

    @Test
    fun dispatchTargetsSkipCurrentDedupeAndDisambiguate() {
        val cloud = listOf(
            CloudDevice("me-1", "Me", "ios", isOnline = true, isCurrent = true, appVersion = null),
            CloudDevice("pc-aa11", " Office ", "windows", isOnline = false, isCurrent = false, appVersion = null, defaultSaveDir = "  "),
            CloudDevice("pc-aa11", "dup", null, isOnline = true, isCurrent = false, appVersion = null),
            CloudDevice("", "no id", null, isOnline = true, isCurrent = false, appVersion = null),
            CloudDevice("nas-bb22", "office", "linux", isOnline = true, isCurrent = false, appVersion = null, defaultSaveDir = " /srv/dl ", pathStyle = "windows"),
        )
        val link = listOf(
            LinkDevice("fp:cc-33", "", null, online = true, defaultSaveDir = "/x", pathStyle = "posix"),
            LinkDevice("", "ghost", null, online = true),
        )
        val targets = DeviceRules.dispatchTargets(cloud, link, cloudPresenceKnown = true, localReady = true)
        assertEquals(listOf("cloud:pc-aa11", "cloud:nas-bb22", "link:fp:cc-33"), targets.map { it.id })
        // 同名（忽略大小写与首尾空白）追加短码；空名用短码。
        assertEquals(listOf("Office · aa11", "office · bb22", "cc33"), targets.map { it.name })
        assertEquals(listOf(false, true, true), targets.map { it.online })
        // 空白目录读作无；自报风格优先于平台推断。
        assertNull(targets[0].defaultSaveDir)
        assertEquals(PathStyle.Windows, targets[0].pathStyle)
        assertEquals("/srv/dl", targets[1].defaultSaveDir)
        assertEquals(PathStyle.Windows, targets[1].pathStyle)
        assertTrue(targets[0].isCloud && targets[0].isOffline)
        assertTrue(!targets[2].isCloud && !targets[2].isOffline)
    }

    @Test
    fun dispatchTargetPresenceUnknownUntilTrusted() {
        val cloud = listOf(CloudDevice("pc", "PC", null, isOnline = false, isCurrent = false, appVersion = null))
        val link = listOf(LinkDevice("fp", "Mac", "macos", online = false))
        // 云端 presence 不可信：云设备在线未知（不当作离线），局域网设备仍按本地探测。
        val cloudUnknown = DeviceRules.dispatchTargets(cloud, link, cloudPresenceKnown = false, localReady = true)
        assertEquals(listOf(null, false), cloudUnknown.map { it.online })
        assertFalse(cloudUnknown[0].isOffline)
        // 本地服务未就绪：两类都未知。
        val notReady = DeviceRules.dispatchTargets(cloud, link, cloudPresenceKnown = true, localReady = false)
        assertEquals(listOf(null, null), notReady.map { it.online })
    }

    @Test
    fun dispatchParamsOmitEmptyOptionals() {
        assertEquals("""{"toDevice":"d","url":"http://x/a"}""", RemoteDispatchParams("d", "http://x/a").toJson().toJson())
        assertEquals(
            """{"toDevice":"d","url":"u","fileName":"a.bin","saveDir":"/dl"}""",
            RemoteDispatchParams("d", "u", "a.bin", "/dl").toJson().toJson(),
        )
        assertEquals("""{"fingerprint":"fp","url":"u"}""", LinkDispatchParams("fp", "u").toJson().toJson())
    }
}

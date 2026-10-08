package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 组件 DTO 解码（相邻标记 wire 形状，对应 iOS `componentStatusUsesAdjacentTag…`）与通知载荷。 */
class ComponentsTest {
    @Test
    fun componentStatusUsesAdjacentTagAndToleratesUnknownComponent() {
        val json = """
        [{"component":"ffmpeg","status":{"source":"managed","path":"/x/ffmpeg","version":"7.1","managedVersion":"7.1","systemPath":"","managedSupported":true}},
         {"component":"ytdlp","status":{"source":"none","path":"","version":"","managedVersion":"","systemPath":"/usr/bin/yt-dlp","managedSupported":false}},
         {"component":"future","status":{"source":"system"}}]
        """
        val list = ComponentStatusDto.listFromJson(Json.parse(json))
        assertEquals(ComponentKind.Ffmpeg, list[0].component)
        assertTrue(list[0].status.hasManagedInstall)
        assertEquals(ComponentKind.Ytdlp, list[1].component)
        assertFalse(list[1].status.managedSupported)
        assertEquals(ComponentKind.Unknown("future"), list[2].component)
        assertEquals("system", list[2].status.source)
        // 缺省字段：managedSupported 默认 true。
        assertTrue(list[2].status.managedSupported)
    }

    @Test
    fun versionsDefaultSelectionKeepsCurrentOtherwiseLatestStable() {
        val versions = ComponentVersions.fromJson(Json.parse("""{"versions":["7.1","7.0"],"latestStable":"7.1"}"""))
        assertEquals("7.0", versions.defaultSelection("7.0"))
        assertEquals("7.1", versions.defaultSelection("6"))
        assertEquals("7.1", versions.defaultSelection(null))
        assertEquals("7.1", ComponentVersions(listOf("7.1", "7.0")).defaultSelection(null))
        assertNull(ComponentVersions().defaultSelection(null))
    }

    @Test
    fun paramsMatchWireShape() {
        assertEquals("""{"component":"ytdlp"}""", ComponentKind.Ytdlp.installParams(null).toJson())
        assertEquals("""{"component":"ffmpeg","version":"7.1"}""", ComponentKind.Ffmpeg.installParams("7.1").toJson())
        assertEquals("""{"component":"ffmpeg"}""", ComponentKind.Ffmpeg.params().toJson())
        assertEquals("component.ffmpeg.path", ComponentKind.Ffmpeg.manualPathConfigKey)
        assertEquals("component.ytdlp.path", ComponentKind.Ytdlp.manualPathConfigKey)
        assertNull(ComponentKind.Unknown("x").manualPathConfigKey)
    }

    @Test
    fun noticesDecodeAndProgressFractionClamps() {
        val progress = ComponentProgressNotice.fromJson(Json.parse("""{"component":"ffmpeg","downloadedBytes":50,"totalBytes":200}"""))!!
        assertEquals(0.25, progress.fraction!!, 1e-9)
        assertEquals(1.0, ComponentProgressNotice(ComponentKind.Ffmpeg, 500, 200).fraction!!, 1e-9)
        assertNull(ComponentProgressNotice(ComponentKind.Ffmpeg, 500, 0).fraction)
        val result = ComponentResultNotice.fromJson(Json.parse("""{"component":"ytdlp","ok":false}"""))!!
        assertEquals(ComponentKind.Ytdlp, result.component)
        assertFalse(result.ok)
        assertEquals("", result.message)
    }
}

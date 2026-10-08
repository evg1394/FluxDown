package com.fluxdown.core.capture

import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.bool
import com.fluxdown.core.protocol.get
import com.fluxdown.core.protocol.str
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.torrent` 导入校验与 `daemon.task.create` 参数（同 iOS `torrentValidationAndCreateParams`）。 */
class TorrentFileTest {
    private val torrent = "d8:announce3:url4:infod6:lengthi1e4:name1:aee".toByteArray()

    @Test fun rejectsEmpty() {
        assertEquals(TorrentFile.Issue.Empty, TorrentFile.validate(ByteArray(0)))
    }

    @Test fun rejectsOversize() {
        assertEquals(TorrentFile.Issue.TooLarge, TorrentFile.validate(ByteArray(TorrentFile.MAX_BYTES + 1)))
        // 恰好等于上限不算超限（内容不合法才失败）
        assertEquals(TorrentFile.Issue.NotTorrent, TorrentFile.validate(ByteArray(TorrentFile.MAX_BYTES)))
    }

    @Test fun rejectsNonBencode() {
        assertEquals(TorrentFile.Issue.NotTorrent, TorrentFile.validate("<html>login</html>".toByteArray()))
        assertEquals(TorrentFile.Issue.NotTorrent, TorrentFile.validate("d8:announce3:foo".toByteArray()))
        assertEquals(TorrentFile.Issue.NotTorrent, TorrentFile.validate("x4:info".toByteArray()))
    }

    @Test fun acceptsValidTorrent() {
        assertNull(TorrentFile.validate(torrent))
    }

    @Test fun fileNameMatchIgnoresCase() {
        assertTrue(TorrentFile.isTorrentFileName("Movie.TORRENT"))
        assertFalse(TorrentFile.isTorrentFileName("a.txt"))
    }

    @Test fun createParamsCarryBase64Torrent() {
        val json = Json.parse(TorrentFile.createParams(torrent, "/d", "later", true).toJson())
        val request = json["request"]
        assertEquals("", request.str("url"))
        assertEquals(Base64.getEncoder().encodeToString(torrent), request.str("torrentB64"))
        assertTrue(request.bool("startPaused", false))
        assertEquals("later", request.str("queueId"))
        assertEquals("/d", request.str("saveDir"))
        assertFalse(json.bool("unattended", true))
    }
}

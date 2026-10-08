package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogExportTest {
    private fun url(endpoint: String): String? = LogExportHttp.exportUrl(endpoint)

    @Test
    fun exportUrlNormalizesEndpoint() {
        assertEquals("http://nas.local:17800/api/web/logs/export", url("http://nas.local:17800"))
        assertEquals("http://nas.local:17800/api/web/logs/export", url("ws://nas.local:17800/rpc"))
        assertEquals("https://dl.example.com/api/web/logs/export", url("wss://dl.example.com/"))
        assertEquals("https://example.com/fluxdown/api/web/logs/export", url("https://example.com/fluxdown/rpc/"))
        assertEquals("http://192.168.1.5:17800/api/web/logs/export", url("192.168.1.5:17800"))
        assertEquals("https://h.example/api/web/logs/export", url("  https://h.example?x=1#frag "))
    }

    @Test
    fun exportUrlKeepsReverseProxySubpath() {
        assertEquals("https://h.example/a/b/api/web/logs/export", url("https://h.example/a/b"))
        assertEquals("https://h.example/a/b/api/web/logs/export", url("wss://h.example/a/b/rpc"))
        // 只丢尾部的 /rpc，路径中段的 rpc 保留
        assertEquals("https://h.example/rpc/x/api/web/logs/export", url("https://h.example/rpc/x"))
    }

    @Test
    fun exportUrlSchemeIsCaseInsensitive() {
        assertEquals("https://h.example/api/web/logs/export", url("HTTPS://h.example"))
        assertEquals("http://h.example/api/web/logs/export", url("WS://h.example/rpc"))
    }

    @Test
    fun exportUrlRejectsUnsupportedOrMalformedAddresses() {
        assertNull(url("ftp://x"))
        assertNull(url(""))
        assertNull(url("   "))
        assertNull(url("http://"))
        assertNull(url("http:///only/path"))
        assertNull(url("http://host:notaport"))
        assertNull(url("http://host:99999"))
        assertNull(url("http://bad host"))
    }

    @Test
    fun exportUrlHandlesIpv6AndUserinfo() {
        assertEquals("http://[::1]:17800/api/web/logs/export", url("[::1]:17800"))
        assertEquals("https://[fe80::1]/api/web/logs/export", url("https://[fe80::1]/rpc"))
        assertEquals("https://u:p@h.example/api/web/logs/export", url("https://u:p@h.example"))
    }
}

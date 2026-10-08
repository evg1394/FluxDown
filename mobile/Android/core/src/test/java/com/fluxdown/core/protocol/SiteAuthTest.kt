package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 站点凭据 / 代理测试 / 系统代理 DTO 的真实 JSON 形状（camelCase，镜像 `native/protocol/src/daemon.rs`）。 */
class SiteAuthTest {
    @Test
    fun listDecodesEntriesWithoutPasswords() {
        val json = """[{"site":"example.com","user":"alice"},{"site":"nas.local:8080","user":"bob"}]"""
        assertEquals(
            listOf(SiteAuthEntryDto("example.com", "alice"), SiteAuthEntryDto("nas.local:8080", "bob")),
            SiteAuthEntryDto.listFromJson(Json.parse(json)),
        )
        assertTrue(SiteAuthEntryDto.listFromJson(Json.parse("[]")).isEmpty())
    }

    @Test
    fun entryDecodingIsLenientAboutMissingFields() {
        assertEquals(SiteAuthEntryDto("example.com", ""), SiteAuthEntryDto.fromJson(Json.parse("""{"site":"example.com"}""")))
        val extra = SiteAuthEntryDto.fromJson(Json.parse("""{"site":"a.b","user":"u","future":1}"""))
        assertEquals("u", extra.user)
    }

    @Test
    fun getAndMatchResultsAreOptional() {
        val present = SiteAuthCredentialDto.fromJsonOrNull(
            Json.parse("""{"site":"example.com","user":"alice","pass":"s3cret"}"""),
        )
        assertEquals(SiteAuthCredentialDto("example.com", "alice", "s3cret"), present)
        assertNull(SiteAuthCredentialDto.fromJsonOrNull(Json.parse("null")))
    }

    @Test
    fun descriptionsNeverPrintThePassword() {
        assertFalse(SiteAuthCredentialDto("example.com", "alice", "s3cret").toString().contains("s3cret"))
        assertFalse(SiteAuthSaveRequest("example.com", "alice", "s3cret").toString().contains("s3cret"))
        assertFalse(ProxyTestRequest("http", "127.0.0.1", "1080", "u", "s3cret").toString().contains("s3cret"))
    }

    @Test
    fun requestsEncodeCamelCaseKeys() {
        assertEquals(
            """{"site":"example.com","user":"alice","pass":"pw"}""",
            SiteAuthSaveRequest("example.com", "alice", "pw").toJson().toJson(),
        )
        assertEquals("""{"site":"example.com"}""", SiteAuthDeleteParams("example.com").toJson().toJson())
        assertEquals("""{"site":"https://example.com/x"}""", SiteAuthGetParams("https://example.com/x").toJson().toJson())
        assertEquals(
            """{"url":"https://example.com/f.zip"}""",
            SiteAuthMatchParams("https://example.com/f.zip").toJson().toJson(),
        )
    }

    @Test
    fun proxyTestRequestEncodesAllFieldsAsStrings() {
        val request = ProxyTestRequest("socks5", "127.0.0.1", "1080", "u", "p")
        val json = request.toJson()
        assertEquals("socks5", json.str("proxyType"))
        assertEquals("127.0.0.1", json.str("host"))
        assertEquals("1080", json.str("port"))
        assertEquals("u", json.str("username"))
        assertEquals("p", json.str("password"))
        assertEquals(request, ProxyTestRequest.fromJson(json))
    }

    @Test
    fun proxyTestRequestDefaultsCredentialsToEmpty() {
        val request = ProxyTestRequest("http", "10.0.0.1", "8080")
        assertTrue(request.username.isEmpty() && request.password.isEmpty())
        assertEquals(
            ProxyTestRequest("http", "h", "1"),
            ProxyTestRequest.fromJson(Json.parse("""{"proxyType":"http","host":"h","port":"1"}""")),
        )
    }

    @Test
    fun proxyTestResponseDecodesLatency() {
        assertEquals(128L, ProxyTestResponse.fromJson(Json.parse("""{"latencyMs":128}""")).latencyMs)
        assertEquals(0L, ProxyTestResponse.fromJson(Json.parse("{}")).latencyMs)
    }

    @Test
    fun systemProxyShapes() {
        val json = """{"detected":true,"proxyType":"http","host":"192.168.1.2","port":7890,"noList":"localhost,*.local"}"""
        assertEquals(
            SystemProxyDto(true, "http", "192.168.1.2", 7890, "localhost,*.local"),
            SystemProxyDto.fromJson(Json.parse(json)),
        )
        val none = """{"detected":false,"proxyType":"","host":"","port":0,"noList":""}"""
        assertEquals(SystemProxyDto(), SystemProxyDto.fromJson(Json.parse(none)))
        assertFalse(SystemProxyDto.fromJson(Json.parse("{}")).detected)
    }
}

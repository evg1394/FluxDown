import FluxDomain
import Foundation
import Testing

/// 站点凭据 / 代理测试 / 系统代理 DTO 的真实 JSON 形状（camelCase，镜像 `native/protocol/src/daemon.rs`）。
struct SiteAuthProtocolTests {
    private func data(_ json: String) -> Data { Data(json.utf8) }

    // MARK: - 站点凭据

    @Test func listDecodesEntriesWithoutPasswords() throws {
        let json = #"[{"site":"example.com","user":"alice"},{"site":"nas.local:8080","user":"bob"}]"#
        let entries = try ProtocolJSON.decode([SiteAuthEntryDto].self, from: data(json), what: "daemon.siteAuth.list")
        #expect(entries == [
            SiteAuthEntryDto(site: "example.com", user: "alice"),
            SiteAuthEntryDto(site: "nas.local:8080", user: "bob"),
        ])
        #expect(entries.map(\.id) == ["example.com", "nas.local:8080"])
    }

    @Test func emptyListDecodes() throws {
        let entries = try ProtocolJSON.decode([SiteAuthEntryDto].self, from: data("[]"), what: "daemon.siteAuth.clear")
        #expect(entries.isEmpty)
    }

    @Test func entryDecodingIsLenientAboutMissingFields() throws {
        let entry = try ProtocolJSON.decode(SiteAuthEntryDto.self, from: data(#"{"site":"example.com"}"#), what: "entry")
        #expect(entry == SiteAuthEntryDto(site: "example.com", user: ""))
        let extra = try ProtocolJSON.decode(SiteAuthEntryDto.self, from: data(#"{"site":"a.b","user":"u","future":1}"#), what: "entry")
        #expect(extra.user == "u")
    }

    @Test func getAndMatchResultsAreOptional() throws {
        let present = try ProtocolJSON.decode(
            SiteAuthCredentialDto?.self,
            from: data(#"{"site":"example.com","user":"alice","pass":"s3cret"}"#),
            what: "daemon.siteAuth.get"
        )
        #expect(present == SiteAuthCredentialDto(site: "example.com", user: "alice", pass: "s3cret"))

        let absent = try ProtocolJSON.decode(SiteAuthCredentialDto?.self, from: data("null"), what: "daemon.siteAuth.match")
        #expect(absent == nil)
    }

    @Test func credentialDescriptionNeverPrintsThePassword() {
        let credential = SiteAuthCredentialDto(site: "example.com", user: "alice", pass: "s3cret")
        #expect(!credential.description.contains("s3cret"))
        #expect(!"\(credential)".contains("s3cret"))
        #expect(!String(reflecting: credential).contains("s3cret"))
        let request = SiteAuthSaveRequest(site: "example.com", user: "alice", pass: "s3cret")
        #expect(!"\(request)".contains("s3cret"))
        let proxy = ProxyTestRequest(proxyType: "http", host: "127.0.0.1", port: "1080", username: "u", password: "s3cret")
        #expect(!"\(proxy)".contains("s3cret"))
    }

    @Test func requestsEncodeCamelCaseKeys() throws {
        let save = try ProtocolJSON.encode(SiteAuthSaveRequest(site: "example.com", user: "alice", pass: "pw"), what: "save")
        #expect(String(decoding: save, as: UTF8.self) == #"{"pass":"pw","site":"example.com","user":"alice"}"#)
        let delete = try ProtocolJSON.encode(SiteAuthDeleteParams(site: "example.com"), what: "delete")
        #expect(String(decoding: delete, as: UTF8.self) == #"{"site":"example.com"}"#)
        let get = try ProtocolJSON.encode(SiteAuthGetParams(site: "https://example.com/x"), what: "get")
        #expect(String(decoding: get, as: UTF8.self) == #"{"site":"https://example.com/x"}"#)
        let match = try ProtocolJSON.encode(SiteAuthMatchParams(url: "https://example.com/f.zip"), what: "match")
        #expect(String(decoding: match, as: UTF8.self) == #"{"url":"https://example.com/f.zip"}"#)
    }

    @Test func saveResultIsTheRedactedEntry() throws {
        let entry = try ProtocolJSON.decode(SiteAuthEntryDto.self, from: data(#"{"site":"example.com","user":"alice"}"#), what: "daemon.siteAuth.save")
        #expect(entry.site == "example.com")
        #expect(entry.user == "alice")
    }

    // MARK: - 代理测试

    @Test func proxyTestRequestEncodesAllFieldsAsStrings() throws {
        let request = ProxyTestRequest(proxyType: "socks5", host: "127.0.0.1", port: "1080", username: "u", password: "p")
        let encoded = try ProtocolJSON.encode(request, what: "daemon.config.proxyTest")
        #expect(String(decoding: encoded, as: UTF8.self)
            == #"{"host":"127.0.0.1","password":"p","port":"1080","proxyType":"socks5","username":"u"}"#)
    }

    @Test func proxyTestRequestDefaultsCredentialsToEmpty() throws {
        let request = ProxyTestRequest(proxyType: "http", host: "10.0.0.1", port: "8080")
        #expect(request.username.isEmpty)
        #expect(request.password.isEmpty)
        let decoded = try ProtocolJSON.decode(ProxyTestRequest.self, from: data(#"{"proxyType":"http","host":"h","port":"1"}"#), what: "req")
        #expect(decoded == ProxyTestRequest(proxyType: "http", host: "h", port: "1"))
    }

    @Test func proxyTestResponseDecodesLatency() throws {
        let response = try ProtocolJSON.decode(ProxyTestResponse.self, from: data(#"{"latencyMs":128}"#), what: "daemon.config.proxyTest")
        #expect(response.latencyMs == 128)
        let missing = try ProtocolJSON.decode(ProxyTestResponse.self, from: data("{}"), what: "daemon.config.proxyTest")
        #expect(missing.latencyMs == 0)
    }

    // MARK: - 系统代理

    @Test func systemProxyDetectedShape() throws {
        let json = #"{"detected":true,"proxyType":"http","host":"192.168.1.2","port":7890,"noList":"localhost,*.local"}"#
        let dto = try ProtocolJSON.decode(SystemProxyDto.self, from: data(json), what: "daemon.config.systemProxy")
        #expect(dto == SystemProxyDto(detected: true, proxyType: "http", host: "192.168.1.2", port: 7890, noList: "localhost,*.local"))
    }

    @Test func systemProxyNotDetectedShape() throws {
        let json = #"{"detected":false,"proxyType":"","host":"","port":0,"noList":""}"#
        let dto = try ProtocolJSON.decode(SystemProxyDto.self, from: data(json), what: "daemon.config.systemProxy")
        #expect(dto == SystemProxyDto())
        let empty = try ProtocolJSON.decode(SystemProxyDto.self, from: data("{}"), what: "daemon.config.systemProxy")
        #expect(!empty.detected)
    }
}

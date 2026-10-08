package com.fluxdown.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fluxdown.core.model.HostRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * 已保存的远端主机（`fluxdown-agent --server`）：`hosts.remote` = JSON 数组
 * `[{id, name, endpoint, key}]`，`key` 为 [SecretBox] 密文（以 id 为 AAD），明文访问密钥从不落盘。
 * 本机主机不入库（恒存在，id = [HostRef.Local.ID]）。
 */
class HostRepo(private val ds: DataStore<Preferences>, private val box: SecretBox) {
    private class Entry(val ref: HostRef.Remote, val sealedKey: String)

    private object K {
        val remote = stringPreferencesKey("hosts.remote")
    }

    val remotes: Flow<List<HostRef.Remote>> = ds.data.map { p -> decode(p[K.remote]).map { it.ref } }

    /** 解密访问密钥；条目不存在或密钥已不可读（设备迁移 / Keystore 失效）→ null。 */
    suspend fun accessKey(id: String): String? {
        val entry = decode(ds.data.first()[K.remote]).firstOrNull { it.ref.id == id } ?: return null
        return try {
            box.open(entry.sealedKey, aad = id)
        } catch (_: SecretBox.SecretUnavailableException) {
            null
        }
    }

    suspend fun add(name: String, endpoint: String, accessKey: String): HostRef.Remote {
        val id = UUID.randomUUID().toString()
        val ref = HostRef.Remote(id = id, displayName = name, endpoint = endpoint)
        val entry = Entry(ref, box.seal(accessKey, aad = id))
        ds.edit { p -> p[K.remote] = encode(decode(p[K.remote]) + entry) }
        return ref
    }

    /** 原地更换访问密钥（主机侧已改密钥）；条目不存在 → false。 */
    suspend fun updateAccessKey(id: String, accessKey: String): Boolean {
        var found = false
        ds.edit { p ->
            val entries = decode(p[K.remote]).map { e ->
                if (e.ref.id != id) {
                    e
                } else {
                    found = true
                    Entry(e.ref, box.seal(accessKey, aad = id))
                }
            }
            if (found) p[K.remote] = encode(entries)
        }
        return found
    }

    suspend fun remove(id: String) {
        ds.edit { p ->
            val kept = decode(p[K.remote]).filter { it.ref.id != id }
            if (kept.isEmpty()) p.remove(K.remote) else p[K.remote] = encode(kept)
        }
    }

    private fun encode(entries: List<Entry>): String = JSONArray().also { arr ->
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.ref.id)
                    .put("name", e.ref.displayName)
                    .put("endpoint", e.ref.endpoint)
                    .put("key", e.sealedKey),
            )
        }
    }.toString()

    /** 损坏的整体 / 单条一律丢弃（返回已能解析的部分），不让坏数据拖垮启动。 */
    private fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrEmpty()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val endpoint = o.optString("endpoint")
            val key = o.optString("key")
            if (id.isEmpty() || endpoint.isEmpty() || key.isEmpty()) return@mapNotNull null
            Entry(HostRef.Remote(id, o.optString("name").ifEmpty { endpoint }, endpoint), key)
        }
    }
}

package com.fluxdown.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 列表分组（PC 视图弹层同序）。 */
enum class GroupBy(val wire: String) { None("none"), Status("status"), Date("date"), Type("type"), Queue("queue"), Site("site"), Group("group") }

/** 排序键；Smart = 智能排序（活跃优先 → 最近）。 */
enum class SortKey(val wire: String) { Smart("smart"), Created("created"), Name("name"), Size("size"), Progress("progress"), Speed("speed"), Status("status") }

enum class Density(val wire: String) { Comfortable("comfortable"), Compact("compact") }

/** 卡片显示字段（PC “列”换算）。 */
enum class CardField(val wire: String) { Size("size"), Speed("speed"), Eta("eta"), Protocol("protocol"), Site("site"), Queue("queue"), Created("created") }

/** 设备本地视图偏好（不随主机切换、不上云）。 */
data class ViewPrefs(
    val groupBy: GroupBy = GroupBy.None,
    val sortKey: SortKey = SortKey.Smart,
    val ascending: Boolean = false,
    val density: Density = Density.Comfortable,
    val fields: Set<CardField> = DEFAULT_FIELDS,
) {
    /** 与默认不同 → 视图按钮显示 on 态。 */
    val customized: Boolean
        get() = groupBy != GroupBy.None || sortKey != SortKey.Smart || density != Density.Comfortable

    companion object {
        val DEFAULT_FIELDS = linkedSetOf(CardField.Size, CardField.Speed, CardField.Eta, CardField.Protocol)
    }
}

class ViewPrefsRepo(private val ds: DataStore<Preferences>) {
    private object K {
        val group = stringPreferencesKey("ui.downloads.group_by")
        val sort = stringPreferencesKey("ui.downloads.sort_key")
        val dir = stringPreferencesKey("ui.downloads.sort_dir")
        val density = stringPreferencesKey("ui.downloads.density")
        val fields = stringPreferencesKey("ui.downloads.card_fields")
    }

    val state: Flow<ViewPrefs> = ds.data.map { p ->
        ViewPrefs(
            groupBy = GroupBy.entries.firstOrNull { it.wire == p[K.group] } ?: GroupBy.None,
            sortKey = SortKey.entries.firstOrNull { it.wire == p[K.sort] } ?: SortKey.Smart,
            ascending = p[K.dir] == "asc",
            density = Density.entries.firstOrNull { it.wire == p[K.density] } ?: Density.Comfortable,
            fields = p[K.fields]?.let { raw ->
                raw.split(',').mapNotNull { w -> CardField.entries.firstOrNull { it.wire == w } }.toCollection(linkedSetOf())
            } ?: ViewPrefs.DEFAULT_FIELDS,
        )
    }

    suspend fun update(prefs: ViewPrefs) = ds.edit {
        it[K.group] = prefs.groupBy.wire
        it[K.sort] = prefs.sortKey.wire
        it[K.dir] = if (prefs.ascending) "asc" else "desc"
        it[K.density] = prefs.density.wire
        it[K.fields] = prefs.fields.joinToString(",") { f -> f.wire }
    }
}

package com.fluxdown.core.model

/**
 * 自定义分类（`CustomCategoryDto`，偏好键 `custom_categories`）。
 * 列表由主机快照下发（Rust `CustomCategoryDto::from_preference`：空 / 损坏回退内置基线），
 * Kotlin 只做匹配，不另维护分类规则来源。
 */
data class Category(
    val id: String,
    /** 内置分类为空串：显示名走 i18n（`categoryVideo` …）。 */
    val name: String,
    val icon: String,
    val extensions: List<String>,
    /** 非空 = 正则模式（`matchMode = regex`）。 */
    val regexPattern: String = "",
    val position: Int,
    val visible: Boolean = true,
    /** all / video / audio / document / image / program / archive / other；自定义为 null。 */
    val builtinType: String? = null,
) {
    val isAll: Boolean get() = builtinType == "all"
    val isOther: Boolean get() = builtinType == "other"

    companion object {
        /** 与 `native/protocol/src/agent.rs::CustomCategoryDto::builtin_defaults` 同序同扩展名（主机未下发时的展示基线）。 */
        val BUILTIN: List<Category> = listOf(
            builtin("all", "folders", emptyList(), 0),
            builtin("video", "film", listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m3u8"), 1),
            builtin("audio", "music", listOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus"), 2),
            builtin("document", "fileText", listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "md"), 3),
            builtin("image", "image", listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif"), 4),
            builtin("program", "cpu", listOf("exe", "msi", "dmg", "pkg", "deb", "rpm", "apk", "appimage"), 5),
            builtin("archive", "archive", listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"), 6),
            builtin("other", "file", emptyList(), 7),
        )

        private fun builtin(type: String, icon: String, exts: List<String>, position: Int) =
            Category("builtin_$type", "", icon, exts, "", position, true, type)
    }
}

/** 按 position 排序的匹配索引（语义同 GPUI `CategoryIndex`）：首个命中的具体分类；无 → other。 */
class CategoryIndex(categories: List<Category>) {
    val ordered: List<Category> = categories.sortedBy { it.position }
    private val other: Category? = ordered.firstOrNull { it.isOther }
    private val rules: List<Pair<Category, (String) -> Boolean>> = ordered
        .filter { !it.isAll && !it.isOther }
        .map { c ->
            val m: (String) -> Boolean = if (c.regexPattern.isNotEmpty()) {
                val re = runCatching { Regex(c.regexPattern, RegexOption.IGNORE_CASE) }.getOrNull()
                val match: (String) -> Boolean = { name -> re?.containsMatchIn(name) == true }
                match
            } else {
                val exts = c.extensions.map { it.lowercase().removePrefix(".") }.toSet()
                val match: (String) -> Boolean = { name -> name.substringAfterLast('.', "").lowercase() in exts }
                match
            }
            c to m
        }

    /** 任务命中的首个具体分类；都不命中 → other（无 other 时 null）。 */
    fun categoryOf(fileName: String): Category? = rules.firstOrNull { it.second(fileName) }?.first ?: other

    /** 任务是否属于分类（all 恒真；other = 未命中任何具体分类）。 */
    fun matches(category: Category, fileName: String): Boolean = when {
        category.isAll -> true
        category.isOther -> rules.none { it.second(fileName) }
        else -> rules.firstOrNull { it.first.id == category.id }?.second?.invoke(fileName) == true
    }
}

import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 文案单一事实源 = 仓库根 `assets/i18n/{en,zh}.json`（App / GPUI / Web 共用，AGENTS.md §5）。
 * 构建期生成 `values/strings.xml`（en，默认）与 `values-zh/strings.xml`，键名原样（camelCase），
 * 占位符 `{name}` 原样保留，由 `:app` 的 `fill()` 运行期替换。禁止在 res/ 里手写同名文案。
 */
abstract class GenerateI18nResources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val i18nDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        mapOf("en" to "values", "zh" to "values-zh").forEach { (lang, dir) ->
            val src = i18nDir.file("$lang.json").get().asFile
            @Suppress("UNCHECKED_CAST")
            val map = JsonSlurper().parse(src) as Map<String, Any?>
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
                for ((k, v) in map) {
                    if (v !is String) continue
                    append("    <string name=\"").append(k).append("\" formatted=\"false\">")
                        .append(escape(v)).append("</string>\n")
                }
                append("</resources>\n")
            }
            File(out, dir).apply { mkdirs() }.resolve("strings_i18n.xml").writeText(xml)
        }
    }

    private fun escape(s: String): String {
        val b = StringBuilder(s.length + 8)
        s.forEachIndexed { i, c ->
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '\\' -> b.append("\\\\")
                '\'' -> b.append("\\'")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\t' -> b.append("\\t")
                '@', '?' -> if (i == 0) b.append('\\').append(c) else b.append(c)
                else -> b.append(c)
            }
        }
        return b.toString()
    }
}

val generateI18n = tasks.register<GenerateI18nResources>("generateI18nResources") {
    i18nDir.set(rootProject.layout.projectDirectory.dir("../../assets/i18n"))
    outputDir.set(layout.buildDirectory.dir("generated/i18n/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateI18n, GenerateI18nResources::outputDir)
    }
}

android {
    namespace = "com.fluxdown.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fluxdown.app"
        // API 31：RenderEffect 背景模糊为材质基线（docs/mobile-ui README §0），无低于 31 的分支
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // 只打包 :bridge 实际编译了 libfluxdown_mobile.so 的 ABI（JNA aar 自带 armeabi / mips 等无对应引擎库的 ABI，
        // 留着会让这些设备装得上却在加载引擎时崩溃）。与 :bridge 同源：Gradle 属性 fluxdown.abis。
        ndk {
            abiFilters += providers.gradleProperty("fluxdown.abis").orElse("arm64-v8a,x86_64").get()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // 仅打包有基线翻译的语言（assets/i18n 的 en / zh）；生成 localeConfig 供系统“应用语言”设置列出
        localeFilters += listOf("en", "zh")
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":fluxui"))
    implementation(project(":bridge"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

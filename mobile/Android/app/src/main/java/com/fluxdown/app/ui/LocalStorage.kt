package com.fluxdown.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * 本机主机的默认保存目录：应用专属外部目录（无需存储权限，卸载时随应用清理）。
 * 与首次启动播种给引擎的 `default_save_dir` 同源，“恢复默认”也回到这里。
 */
fun defaultLocalSaveDir(context: Context): File =
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")

/**
 * 系统公共下载目录下的应用子目录（`/storage/emulated/0/Download/FluxDown`）。
 * Android 11+ 分区存储允许应用不申请权限直接在 Download/ 下创建任意类型文件（仅能管理自己创建的文件）。
 */
fun publicDownloadSaveDir(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FluxDown")

/** 共享存储（应用专属目录与 Download/ 之外）需要“所有文件访问”才能按路径写入。 */
fun canRequestAllFilesAccess(path: String): Boolean =
    path.startsWith(Environment.getExternalStorageDirectory().path) && !Environment.isExternalStorageManager()

/** 跳到系统“所有文件访问”授权页（本应用）。 */
fun allFilesAccessIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

/**
 * 本机主机的保存目录能否写入（不存在则尝试创建）。
 *
 * Android 分区存储下，应用无权限的目录（如拼错的 `Android/dta/...`、其他应用的私有目录）
 * 创建 / 写入会得到 EPERM，引擎只能在下载开始后报 `Operation not permitted`；提交前在这里拦住。
 * 远端主机的路径在服务器上，不能在手机侧校验。
 */
fun isLocalDirWritable(path: String): Boolean {
    if (path.isBlank()) return true
    val dir = File(path.trim())
    if (!dir.isAbsolute) return false
    return (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
}

/** SAF 目录树 URI → 绝对路径（`primary:Download/X` → `/storage/emulated/0/Download/X`）；无法映射为 null。 */
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "").trim('/')
    val base = when {
        volume == "primary" -> "/storage/emulated/0"
        volume.isNotEmpty() && docId.contains(':') && volume != "raw" && volume != "home" -> "/storage/$volume"
        volume == "raw" -> return rel.takeIf { docId.substringAfter(':').startsWith("/") }?.let { "/$it" }
        else -> return null
    }
    return if (rel.isEmpty()) base else "$base/$rel"
}

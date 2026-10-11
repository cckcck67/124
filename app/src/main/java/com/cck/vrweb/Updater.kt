package com.cck.vrweb

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 自動更新:開啟時到 GitHub Releases 讀 version.json,
 * 有新版就詢問,同意後下載 APK 並打開系統的安裝畫面(使用者只要按「安裝」)。
 */
class Updater(private val activity: Activity, private val onDialogClosed: () -> Unit) {

    companion object {
        private const val BASE = "https://github.com/cckcck67/124/releases/download/latest/"
        private const val VERSION_URL = BASE + "version.json"
        const val APK_URL = BASE + "VrWebPlayer.apk"

        /** 用手機的瀏覽器下載 APK(小米等手機擋下 App 內安裝時的備用方式) */
        fun openInBrowser(context: Context) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (e: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun currentVersion(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /**
     * 背景檢查;有新版才跳出詢問。
     * manual = true(設定頁的「檢查更新」)時,沒有新版或連不上也會告訴使用者。
     */
    fun check(manual: Boolean = false) {
        Thread {
            try {
                val json = JSONObject(download(VERSION_URL).toString(Charsets.UTF_8))
                val code = json.optLong("code")
                val name = json.optString("name")
                if (code > currentVersion()) main.post { ask(name) }
                else if (manual) main.post { Toast.makeText(activity, "已經是最新版本", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                // 沒網路或還沒有發佈版本:自動檢查時略過
                if (manual) main.post { Toast.makeText(activity, "無法檢查更新,請確認網路", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun ask(name: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("有新版本")
            .setMessage("新版本 $name 已經可以下載,要現在更新嗎?\n(書籤和設定會保留)")
            .setPositiveButton("更新") { _, _ -> startUpdate() }
            .setNeutralButton("用瀏覽器下載") { _, _ -> openInBrowser(activity) }
            .setNegativeButton("以後再說", null)
            .setOnDismissListener { onDialogClosed() }
            .show()
    }

    private fun startUpdate() {
        // 第一次要允許「安裝未知應用程式」
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("需要允許安裝")
                .setMessage("請在下一個畫面開啟「允許安裝未知應用程式」,再回到這裡按一次「更新」。")
                .setPositiveButton("前往設定") { _, _ ->
                    activity.startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)))
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener { onDialogClosed() }
                .show()
            return
        }

        val progress = AlertDialog.Builder(activity)
            .setTitle("下載新版本")
            .setMessage("下載中…")
            .setCancelable(false)
            .show()

        Thread {
            try {
                val file = ApkProvider.file(activity)
                download(APK_URL, file) { pct ->
                    main.post { progress.setMessage("下載中… $pct%") }
                }
                main.post {
                    progress.dismiss()
                    onDialogClosed()
                    install()
                }
            } catch (e: Exception) {
                main.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("更新失敗")
                        .setMessage("無法下載新版本:${e.message}")
                        .setPositiveButton("用瀏覽器下載") { _, _ -> openInBrowser(activity) }
                        .setNegativeButton("取消", null)
                        .setOnDismissListener { onDialogClosed() }
                        .show()
                }
            }
        }.start()
    }

    /** 下載小檔案到記憶體(會自動跟著 GitHub 的轉址) */
    private fun download(url: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        open(url) { conn ->
            conn.inputStream.use { it.copyTo(out) }
        }
        return out.toByteArray()
    }

    /** 下載到檔案,回報 0~100 */
    private fun download(url: String, file: File, onProgress: (Int) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".part")
        open(url) { conn ->
            val total = conn.contentLength.toLong()
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
        file.delete()
        if (!tmp.renameTo(file)) throw Exception("無法儲存檔案")
    }

    private fun open(url: String, body: (HttpURLConnection) -> Unit) {
        var target = URL(url)
        repeat(5) {
            val conn = target.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val codeHttp = conn.responseCode
            if (codeHttp in 300..399) {
                target = URL(target, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (codeHttp != 200) throw Exception("HTTP $codeHttp")
            body(conn)
            return
        }
        throw Exception("轉址太多次")
    }

    /**
     * 交給系統的安裝程式(和用瀏覽器下載後點開一樣的方式;小米手機會擋 App 內直接安裝)。
     * 打不開安裝畫面就改用瀏覽器下載。
     */
    private fun install() {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ApkProvider.uri(activity), ApkProvider.MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activity, "打不開安裝畫面,改用瀏覽器下載新版", Toast.LENGTH_LONG).show()
            openInBrowser(activity)
        }
    }
}

/** 把下載好的新版 APK 提供給系統安裝程式讀取(只有這一個檔案,不對外公開) */
class ApkProvider : ContentProvider() {
    companion object {
        const val MIME = "application/vnd.android.package-archive"
        private const val NAME = "VrWebPlayer.apk"
        fun file(ctx: Context) = File(File(ctx.cacheDir, "update").apply { mkdirs() }, NAME)
        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.apk/$NAME")
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(context!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(context!!)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) NAME else null })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

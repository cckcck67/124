package com.cck.vrweb

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 自動更新:開啟時到 GitHub Releases 讀 version.json,
 * 有新版就詢問,同意後下載 APK 並交給系統安裝(使用者只要按「安裝」)。
 */
class Updater(private val activity: Activity, private val onDialogClosed: () -> Unit) {

    companion object {
        private const val BASE = "https://github.com/cckcck67/124/releases/download/latest/"
        private const val VERSION_URL = BASE + "version.json"
        private const val APK_URL = BASE + "VrWebPlayer.apk"
    }

    private val main = Handler(Looper.getMainLooper())

    private fun currentVersion(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 背景檢查;有新版才跳出詢問,沒有或連不上就安靜結束 */
    fun check() {
        Thread {
            try {
                val json = JSONObject(download(VERSION_URL).toString(Charsets.UTF_8))
                val code = json.optLong("code")
                val name = json.optString("name")
                if (code > currentVersion()) main.post { ask(name) }
            } catch (e: Exception) {
                // 沒網路或還沒有發佈版本:略過
            }
        }.start()
    }

    private fun ask(name: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("有新版本")
            .setMessage("新版本 $name 已經可以下載,要現在更新嗎?\n(書籤和設定會保留)")
            .setPositiveButton("更新") { _, _ -> startUpdate() }
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
                val apk = download(APK_URL) { pct ->
                    main.post { progress.setMessage("下載中… $pct%") }
                }
                main.post { progress.setMessage("準備安裝…") }
                install(apk)
                main.post { progress.dismiss(); onDialogClosed() }
            } catch (e: Exception) {
                main.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("更新失敗")
                        .setMessage("無法下載新版本:${e.message}")
                        .setPositiveButton("確定", null)
                        .setOnDismissListener { onDialogClosed() }
                        .show()
                }
            }
        }.start()
    }

    /** 下載整個檔案(會自動跟著 GitHub 的轉址) */
    private fun download(url: String, onProgress: ((Int) -> Unit)? = null): ByteArray {
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
            val total = conn.contentLength
            val out = java.io.ByteArrayOutputStream(if (total > 0) total else 1 shl 20)
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (total > 0 && onProgress != null) {
                        val pct = (out.size() * 100L / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            return out.toByteArray()
        }
        throw Exception("轉址太多次")
    }

    /** 用系統的套件安裝程式安裝;系統會跳出確認畫面 */
    private fun install(apk: ByteArray) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("VrWebPlayer.apk", 0, apk.size.toLong()).use { out ->
                out.write(apk)
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                activity, id, Intent(activity, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** 接收安裝結果:需要使用者確認時,打開系統的安裝確認畫面 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
        }
    }
}

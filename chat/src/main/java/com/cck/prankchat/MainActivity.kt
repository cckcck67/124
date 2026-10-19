package com.cck.prankchat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File

/**
 * 整人聊天(偽裝成 LINE 好友捷徑):打開直接進聊天室,離開時打開真的 LINE。
 * 畫面全部在 assets/index.html,這裡只負責
 * 存檔(聊天內容存在 App 自己的資料夾)、選圖片 / 選檔案、狀態列顏色、返回鍵、接收 LINE 分享過來的聊天記錄。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_FILE = 1
        private const val MAX_IMPORT = 8 * 1024 * 1024
        private const val LINE_PACKAGE = "jp.naver.line.android"
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingImport: String? = null
    private var pageReady = false
    private val dataFile by lazy { File(filesDir, "chats.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setBars("#ffffff")
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.textZoom = 100            // 不跟系統字型放大,排版和 LINE 一樣
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                flushImport()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        setContentView(web)
        handleIntent(intent)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // LINE「傳送聊天記錄」→ 選這個 App:收到的是 .txt 檔(EXTRA_STREAM),有些版本直接給文字(EXTRA_TEXT)
    private fun handleIntent(i: Intent?) {
        i ?: return
        val text = try {
            when (i.action) {
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) readUri(uri) else i.getStringExtra(Intent.EXTRA_TEXT)
                }
                Intent.ACTION_VIEW -> i.data?.let { readUri(it) }
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return
        pendingImport = text
        flushImport()
    }

    private fun readUri(uri: Uri): String? = contentResolver.openInputStream(uri)?.use { s ->
        val bytes = s.readBytes()
        if (bytes.size > MAX_IMPORT) null else String(bytes, Charsets.UTF_8)
    }

    private fun flushImport() {
        val t = pendingImport ?: return
        if (!pageReady) return
        pendingImport = null
        web.evaluateJavascript("window.importLineText(${JSONObject.quote(t)})", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    // 返回鍵:先關編輯面板 / 剪圖 / 所有聊天,已經在聊天室就打開 LINE
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pageReady) {
            openLine()
            return
        }
        web.evaluateJavascript("window.appBack && window.appBack() ? 1 : 0") { r ->
            if (r != "1") openLine()
        }
    }

    // 離開:打開 LINE(停在 LINE 上次的畫面),這個 App 整個關掉、不留在最近使用
    private fun openLine() {
        try {
            packageManager.getLaunchIntentForPackage(LINE_PACKAGE)?.let { startActivity(it) }
        } catch (e: Exception) {
            // 沒裝 LINE 就單純關掉
        }
        finishAndRemoveTask()
    }

    private fun setBars(color: String) {
        val c = try { Color.parseColor(color) } catch (e: Exception) { Color.WHITE }
        window.statusBarColor = c
        window.navigationBarColor = Color.WHITE
        // 背景亮 → 狀態列用深色圖示
        val light = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 140
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    inner class Bridge {
        @JavascriptInterface
        fun load(): String = try {
            if (dataFile.exists()) dataFile.readText() else ""
        } catch (e: Exception) {
            ""
        }

        // 先寫到暫存檔再改名,存到一半被關掉也不會壞掉
        @JavascriptInterface
        fun save(json: String) {
            synchronized(this) {
                val tmp = File(filesDir, "chats.json.tmp")
                tmp.writeText(json)
                tmp.renameTo(dataFile)
            }
        }

        @JavascriptInterface
        fun openLine() {
            runOnUiThread { this@MainActivity.openLine() }
        }

        @JavascriptInterface
        fun setBars(color: String) {
            runOnUiThread { this@MainActivity.setBars(color) }
        }
    }
}

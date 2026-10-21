package com.cck.mealplanner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File

/**
 * 員工餐:菜單抽卡、叫貨單。
 * 畫面全部在 assets/index.html(邏輯在 logic.js、初始資料在 seed.js),這裡只負責
 * 存檔(資料存在 App 自己的資料夾)、複製到剪貼簿、傳到 LINE、返回鍵、狀態列顏色。
 */
class MainActivity : Activity() {

    companion object {
        private const val LINE_PACKAGE = "jp.naver.line.android"
    }

    private lateinit var web: WebView
    private var pageReady = false
    private val dataFile by lazy { File(filesDir, "meal.json") }
    private val prevFile by lazy { File(filesDir, "meal.prev.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepPrevious()
        window.statusBarColor = Color.parseColor("#D9661F")
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.textZoom = 100            // 不跟系統字型放大,避免排版擠在一起
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    // 每次打開 App 先留一份上次的資料(存檔萬一壞掉,畫面會改讀這份,不用重新輸入)
    private fun keepPrevious() {
        try {
            if (!dataFile.exists() || dataFile.length() == 0L) return
            val text = dataFile.readText()
            JSONObject(text)   // 存檔壞掉就不要蓋掉之前好的備份
            prevFile.writeText(text)
        } catch (e: Exception) {
            // 備份失敗不影響使用
        }
    }

    // 切到背景前把還沒寫進檔案的資料存好
    override fun onPause() {
        super.onPause()
        if (pageReady) web.evaluateJavascript("window.saveNow && window.saveNow()", null)
    }

    // 返回鍵:先關面板 / 回上一頁 / 回今日,已經在今日就離開
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pageReady) {
            finish()
            return
        }
        web.evaluateJavascript("window.appBack && window.appBack() ? 1 : 0") { r ->
            if (r != "1") finish()
        }
    }

    private fun shareToLine(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val line = Intent(send).setPackage(LINE_PACKAGE)
        try {
            if (line.resolveActivity(packageManager) != null) startActivity(line)
            else startActivity(Intent.createChooser(send, "傳送叫貨單"))
        } catch (e: Exception) {
            // 沒有可以分享的 App:畫面上已經複製到剪貼簿了
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun load(): String = try {
            if (dataFile.exists()) dataFile.readText() else ""
        } catch (e: Exception) {
            ""
        }

        @JavascriptInterface
        fun loadPrev(): String = try {
            if (prevFile.exists()) prevFile.readText() else ""
        } catch (e: Exception) {
            ""
        }

        // 先寫到暫存檔再改名,存到一半被關掉也不會壞掉
        @JavascriptInterface
        fun save(json: String) {
            synchronized(this) {
                val tmp = File(filesDir, "meal.json.tmp")
                tmp.writeText(json)
                tmp.renameTo(dataFile)
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("員工餐", text))
            }
        }

        // 傳到 LINE 時也先複製一份,LINE 沒有帶入文字時可以直接貼上
        @JavascriptInterface
        fun shareLine(text: String) {
            runOnUiThread {
                val cm = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("員工餐", text))
                shareToLine(text)
            }
        }
    }
}

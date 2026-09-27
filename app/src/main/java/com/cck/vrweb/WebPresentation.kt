package com.cck.vrweb

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 顯示在「看不見的虛擬螢幕」上的瀏覽器。
 * 它的畫面會被 VrRenderer 當成材質畫出來;觸控由 MainActivity 轉送進來。
 */
class WebPresentation(outer: Context, display: Display) : Presentation(outer, display) {

    private lateinit var root: FrameLayout
    lateinit var webView: WebView
        private set
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(context)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // 部分網站會擋 WebView 標記,移除後較像一般手機瀏覽器
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // 擋掉 intent:// 等跳 App 的連結
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // 網頁播放器按「全螢幕」時,Chromium 會交出一個播放器 View
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                removeCustomView()
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            override fun onHideCustomView() {
                removeCustomView()
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        pendingUrl?.let { webView.loadUrl(it) }
        pendingUrl = null
    }

    private fun removeCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback = null
    }

    fun loadUrl(url: String) {
        if (::webView.isInitialized) webView.loadUrl(url) else pendingUrl = url
    }

    val currentUrl: String?
        get() = if (::webView.isInitialized) webView.url else null

    /** 返回鍵:先退出影片全螢幕,再回上一頁 */
    fun handleBack(): Boolean {
        if (customView != null) {
            val cb = customCallback
            removeCustomView()
            cb?.onCustomViewHidden()
            return true
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    /** 直接轉送觸控事件(座標為虛擬螢幕像素) */
    fun dispatchTouch(ev: MotionEvent) {
        window?.decorView?.dispatchTouchEvent(ev)
    }

    /** 在 (x, y) 模擬點擊 count 次;count = 2 即雙擊(YouTube 等快轉 10 秒) */
    fun tap(x: Float, y: Float, count: Int = 1) {
        for (i in 0 until count) {
            handler.postDelayed({
                val down = SystemClock.uptimeMillis()
                send(down, down, MotionEvent.ACTION_DOWN, x, y)
                handler.postDelayed({
                    send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                }, 40)
            }, i * 120L)
        }
    }

    private fun send(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        dispatchTouch(e)
        e.recycle()
    }

    fun release() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
    }
}

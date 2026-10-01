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
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

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
        // 虛擬螢幕上的視窗不能搶輸入焦點,否則 App 上方網址列叫不出鍵盤
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(context)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // 雙指縮放(不顯示縮放按鈕)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 部分網站會擋 WebView 標記,移除後較像一般手機瀏覽器
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // 擋掉 intent:// 等跳 App 的連結
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // 有些網站禁止縮放,把限制拿掉,讓雙指可以放大縮小
                view.evaluateJavascript(ALLOW_ZOOM, null)
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

    val currentTitle: String?
        get() = if (::webView.isInitialized) webView.title else null

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

    // ---------------- 影片控制(直接操作網頁中的 <video>) ----------------

    class VideoStatus(val current: Double, val duration: Double, val paused: Boolean)

    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        if (!::webView.isInitialized) { cb?.invoke(null); return }
        webView.evaluateJavascript(code) { cb?.invoke(it) }
    }

    /** 回傳 null = 找不到影片 */
    fun videoStatus(cb: (VideoStatus?) -> Unit) {
        js("(function(){$FIND_VIDEO if(!v)return null;" +
                "return [v.currentTime,isFinite(v.duration)?v.duration:0,v.paused?1:0];})()") { r ->
            val st = try {
                if (r == null || r == "null") null else JSONArray(r).let {
                    VideoStatus(it.optDouble(0, 0.0), it.optDouble(1, 0.0), it.optInt(2) == 1)
                }
            } catch (e: Exception) { null }
            cb(st)
        }
    }

    /** 快進/倒退秒數;找不到影片時改用左右側雙擊 */
    fun seekBy(sec: Int) {
        js("(function(){$FIND_VIDEO if(!v)return false;" +
                "v.currentTime=Math.max(0,v.currentTime+($sec));return true;})()") { r ->
            if (r != "true") tap(MainActivity.SRC_W * (if (sec < 0) 0.2f else 0.8f), MainActivity.SRC_H * 0.5f, 2)
        }
    }

    fun seekTo(fraction: Float) {
        js("(function(){$FIND_VIDEO if(!v||!isFinite(v.duration))return false;" +
                "v.currentTime=v.duration*$fraction;return true;})()")
    }

    /** 播放/暫停;找不到影片時點一下畫面中央 */
    fun togglePlay() {
        js("(function(){$FIND_VIDEO if(!v)return false;" +
                "if(v.paused)v.play();else v.pause();return true;})()") { r ->
            if (r != "true") tap(MainActivity.SRC_W * 0.5f, MainActivity.SRC_H * 0.5f, 1)
        }
    }

    /**
     * 影片在網頁畫面中的位置 [x, y, 寬, 高](比例,y 向下);找不到回傳 null。
     * player = true 時取 YouTube 播放器整塊。
     */
    fun videoRect(player: Boolean, cb: (FloatArray?) -> Unit) {
        js("(function(pl){var e=null;" +
                "if(pl){e=document.getElementById('movie_player')||document.querySelector('.html5-video-player');}" +
                "if(!e){$FIND_VIDEO e=v;}" +
                "if(!e)return null;var r=e.getBoundingClientRect(),W=window.innerWidth,H=window.innerHeight;" +
                "if(!W||!H||r.width<20||r.height<20)return null;" +
                "var x=r.left,y=r.top,w=r.width,h=r.height;" +
                // <video> 內的實際畫面(扣掉上下或左右黑邊)
                "if(e.tagName==='VIDEO'&&e.videoWidth&&e.videoHeight){var a=e.videoWidth/e.videoHeight;" +
                "if(a>w/h){var nh=w/a;y+=(h-nh)/2;h=nh;}else{var nw=h*a;x+=(w-nw)/2;w=nw;}}" +
                "return [x/W,y/H,w/W,h/H];})($player)") { r ->
            val rect = try {
                if (r == null || r == "null") null else JSONArray(r).let { a ->
                    FloatArray(4) { a.optDouble(it, 0.0).toFloat() }
                }
            } catch (e: Exception) { null }
            cb(rect)
        }
    }

    /** 影片原始寬高(像素);找不到影片回傳 null */
    fun videoSize(cb: (Int, Int) -> Unit) {
        js("(function(){$FIND_VIDEO if(!v||!v.videoWidth)return null;return [v.videoWidth,v.videoHeight];})()") { r ->
            try {
                if (r != null && r != "null") {
                    val a = JSONArray(r)
                    cb(a.optInt(0), a.optInt(1))
                }
            } catch (e: Exception) {}
        }
    }

    /** 播放速度(1 = 正常) */
    fun setSpeed(rate: Float) {
        js("(function(){$FIND_VIDEO if(v)v.playbackRate=$rate;})()")
    }

    /** 網頁目前往下捲了多少(CSS 像素) */
    fun scrollTop(cb: (Float) -> Unit) {
        js("(window.scrollY||document.documentElement.scrollTop||0)") { r ->
            cb(r?.toFloatOrNull() ?: 0f)
        }
    }

    // ---------------- 網頁文字輸入框 ----------------

    /** (x, y) 是否點在文字輸入框上;是的話回呼 (目前內容, 提示文字, 是否密碼) */
    fun inputAt(x: Float, y: Float, cb: (String, String, Boolean) -> Unit) {
        js("(function(x,y){var d=window.devicePixelRatio||1,vv=window.visualViewport,s=vv?vv.scale:1;" +
                "var cx=(vv?vv.offsetLeft:0)+x/(d*s),cy=(vv?vv.offsetTop:0)+y/(d*s);" +
                "var e=document.elementFromPoint(cx,cy);" +
                "while(e&&e.shadowRoot){var i=e.shadowRoot.elementFromPoint(cx,cy);if(!i||i===e)break;e=i;}" +
                "if(!e)return null;var t=e.tagName,ty=(e.type||'').toLowerCase();" +
                "var ok=t==='TEXTAREA'||e.isContentEditable||" +
                "(t==='INPUT'&&/^(text|search|email|url|tel|number|password|)$/.test(ty));" +
                "if(!ok)return null;window.__vrInput=e;" +
                "return {v:(e.isContentEditable?e.textContent:e.value)||'',p:e.placeholder||e.getAttribute('aria-label')||'',pw:ty==='password'};" +
                "})($x,$y)") { r ->
            if (r == null || r == "null") return@js
            try {
                val o = JSONObject(r)
                cb(o.optString("v"), o.optString("p"), o.optBoolean("pw"))
            } catch (e: Exception) {}
        }
    }

    /** 把文字填進剛才點到的輸入框;submit = 同時按 Enter 送出 */
    fun setInputText(text: String, submit: Boolean) {
        js("(function(s,sub){var e=window.__vrInput;if(!e)return;e.focus();" +
                "if(e.isContentEditable){e.textContent=s;}else{" +
                "var pr=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "Object.getOwnPropertyDescriptor(pr,'value').set.call(e,s);}" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                "e.dispatchEvent(new Event('change',{bubbles:true}));" +
                "if(!sub)return;var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};" +
                "var free=e.dispatchEvent(new KeyboardEvent('keydown',o));" +
                "e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));" +
                "if(free&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}" +
                "})(${JSONObject.quote(text)},$submit)")
    }

    fun release() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
    }
}

// 找出頁面上最主要的影片:正在播放的優先,其次面積最大的
private const val FIND_VIDEO =
    "var v=null,best=-1;[].forEach.call(document.querySelectorAll('video'),function(x){" +
    "var a=x.offsetWidth*x.offsetHeight+(x.paused?0:1e9);if(x.readyState>0&&a>best){best=a;v=x;}});"

// 拿掉網頁「禁止縮放」的設定(user-scalable=no、maximum-scale=1)
private const val ALLOW_ZOOM =
    "(function(){var m=document.querySelector('meta[name=viewport]');if(!m)return;" +
    "var c=m.getAttribute('content')||'';" +
    "c=c.replace(/user-scalable\\s*=\\s*(no|0)/i,'user-scalable=yes')" +
    ".replace(/maximum-scale\\s*=\\s*[0-9.]+/i,'maximum-scale=5');" +
    "m.setAttribute('content',c);})()"

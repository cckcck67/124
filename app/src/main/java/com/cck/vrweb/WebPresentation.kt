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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * 顯示在「看不見的虛擬螢幕」上的瀏覽器。
 * 它的畫面會被 VrRenderer 當成材質畫出來;觸控由 MainActivity 轉送進來。
 */
class WebPresentation(
    outer: Context, display: Display,
    private var screenW: Int, private var screenH: Int   // 網頁大小(像素);虛擬螢幕是正方形,網頁在左上角
) : Presentation(outer, display) {

    /** 改變網頁大小(直式 / 橫式):只重新排版,不會重新載入 */
    fun setPageSize(w: Int, h: Int) {
        screenW = w
        screenH = h
        if (!::root.isInitialized) return
        for (i in 0 until root.childCount) root.getChildAt(i).layoutParams = pageParams()
    }

    private fun pageParams() = FrameLayout.LayoutParams(screenW, screenH, android.view.Gravity.TOP or android.view.Gravity.START)

    companion object {
        const val APP_ORIGIN = "https://appassets.androidplatform.net"

        /** App 內建的空中遊覽頁面 */
        fun isFlight(url: String?) = url?.startsWith("$APP_ORIGIN/assets/flight.html") == true

        /**
         * YouTube 會把 App 內網頁的 VR 影片降級成平面 360p,所以 YouTube 用手機版 Chrome 的身分
         * (正常的手機版排版);VR 影片另外用上層的 VR 播放器(電腦版官方嵌入播放器)
         */
        fun isYouTube(url: String?): Boolean {
            val host = try { android.net.Uri.parse(url ?: return false).host ?: return false } catch (e: Exception) { return false }
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }

        private fun chromeMajor(base: String) = Regex("Chrome/(\\d+)").find(base)?.groupValues?.get(1) ?: "120"

        /** 手機版 Chrome 的識別字串;Chrome 版本沿用 WebView 本身的版本 */
        fun mobileChromeUserAgent(base: String) =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/${chromeMajor(base)}.0.0.0 Mobile Safari/537.36"

        /** 電腦版 Chrome 的識別字串 */
        fun desktopChromeUserAgent(base: String) =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/${chromeMajor(base)}.0.0.0 Safari/537.36"
    }

    /** App 內建頁面(首頁、空中遊覽)要用的 Google 金鑰;由 MainActivity 提供 */
    var appKey: () -> String = { "" }
    /** 網頁影片進入 / 離開全螢幕 */
    var onFullscreen: (Boolean) -> Unit = {}
    /** App 內建頁面的指令(網址 vrweb://指令),例如 vrweb://key = 設定金鑰 */
    var onAppCommand: (String) -> Unit = {}

    // 內建頁面用 https://appassets.androidplatform.net/assets/… 提供(金鑰可限定只給這個網址用)
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    }

    private var appUa = ""       // 一般網站:App 內網頁(拿掉 wv 標記)
    private var mobileUa = ""    // YouTube:手機版 Chrome
    private var desktopUa = ""   // 上層 VR 播放器:電腦版 Chrome(官方播放器才能用程式控制看的方向)

    // ---------------- 上層 VR 播放器 ----------------

    /**
     * 蓋在原本網頁上方的 VR 播放器(YouTube 官方嵌入播放器);原本的網頁留在下面不動。
     * 關掉時只隱藏、下次重複使用(不在它自己的回呼裡銷毀,避免之後無法再開)
     */
    private var overlay: WebView? = null
    var vrPlayerOpen = false
        private set

    /** 面板、轉頭等操作的對象:有上層播放器時操作它,否則操作原本的網頁 */
    private val active: WebView get() = overlay?.takeIf { vrPlayerOpen } ?: webView

    /** 原本網頁的影片是否在全螢幕 */
    val isFullscreen get() = customView != null

    @SuppressLint("SetJavaScriptEnabled")
    fun openVrPlayer(videoId: String, startSec: Double) {
        if (!::root.isInitialized) return
        val w = overlay ?: WebView(context).also { w ->
            with(w.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                textZoom = 100
                userAgentString = desktopUa
            }
            blockOrientation(w)
            w.setBackgroundColor(Color.BLACK)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return true
                    return scheme != "http" && scheme != "https"
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)
            }
            root.addView(w, pageParams())
            overlay = w
        }
        w.bringToFront()
        w.visibility = View.VISIBLE
        vrPlayerOpen = true
        w.loadUrl("$APP_ORIGIN/assets/ytvr.html?v=" + android.net.Uri.encode(videoId) + "&t=" + startSec)
    }

    /** 關掉上層播放器,回傳看到的時間點(秒;拿不到為 -1) */
    fun closeVrPlayer(cb: (Double) -> Unit) {
        val w = overlay
        if (w == null || !vrPlayerOpen) return cb(-1.0)
        vrPlayerOpen = false
        w.evaluateJavascript("(function(){$FIND_VIDEO return v?v.currentTime:-1;})()") { r ->
            // 停止播放並隱藏,留著下次用
            w.loadUrl("about:blank")
            w.visibility = View.GONE
            cb(r?.toDoubleOrNull() ?: -1.0)
        }
    }

    /** 原本網頁的影片跳到某個時間點並暫停(關掉上層播放器後) */
    fun seekAndPauseMain(sec: Double) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("(function(){$FIND_VIDEO if(!v)return;" +
                (if (sec >= 0) "v.currentTime=$sec;" else "") + "v.pause();})()", null)
    }

    /** 離開網頁影片的全螢幕 */
    fun exitFullscreen() {
        if (customView == null) return
        val cb = customCallback
        removeCustomView()
        cb?.onCustomViewHidden()
    }

    /** 不讓網頁自己用手機陀螺儀轉動 VR 影片視角(改由 App 依頭部方向控制) */
    private fun blockOrientation(w: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, BLOCK_ORIENTATION,
                    setOf("https://youtube.com", "https://*.youtube.com", "https://www.youtube-nocookie.com"))
            }
        } catch (e: Throwable) {}
    }

    /** 依要前往的網址切換身分;有改變回傳 true(之後的載入才會套用) */
    private fun applyUa(url: String?): Boolean {
        val want = if (isYouTube(url)) mobileUa else appUa
        if (webView.settings.userAgentString == want) return false
        webView.settings.userAgentString = want
        return true
    }

    private var resumeSec = -1.0   // 重新載入後要跳回的播放位置(切換畫質用)

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
            appUa = userAgentString.replace("; wv", "")
            mobileUa = mobileChromeUserAgent(userAgentString)
            desktopUa = desktopChromeUserAgent(userAgentString)
            // 字的大小固定 100%,不跟系統字型大小放大(放大會讓網頁排版擠在一起、字被切掉)
            textZoom = 100
            userAgentString = appUa
        }
        // YouTube:不讓網頁自己用手機陀螺儀轉動 VR 影片視角(改由 App 依頭部方向控制)
        blockOrientation(webView)
        // 不在每次連線附上 App 名稱(X-Requested-With),網站較難認出是 App 內的網頁
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
            }
        } catch (e: Throwable) {}
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                if (scheme == "vrweb") { onAppCommand(request.url.host ?: ""); return true }
                if (scheme != "http" && scheme != "https") return true  // 擋掉 intent:// 等跳 App 的連結
                // 進出 YouTube 時換身分,用新身分重新載入
                if (request.isForMainFrame && applyUa(request.url.toString())) {
                    view.loadUrl(request.url.toString())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                // 內建頁面:把金鑰交給網頁(只給 App 自己的頁面)
                if (url?.startsWith(APP_ORIGIN) == true) {
                    view.evaluateJavascript("window.vrSetKey&&vrSetKey(${JSONObject.quote(appKey())})", null)
                }
                // 有些網站禁止縮放,把限制拿掉,讓雙指可以放大縮小
                view.evaluateJavascript(ALLOW_ZOOM, null)
                // 切換畫質後重新載入:等影片出現,跳回原本的位置繼續播
                if (resumeSec >= 0) {
                    view.evaluateJavascript("(function(t){var n=0,h=setInterval(function(){n++;" +
                        "var v=document.querySelector('video');" +
                        "if(v&&v.readyState>0){if(t>1)v.currentTime=t;if(v.play)v.play();clearInterval(h);}" +
                        "else if(n>40)clearInterval(h);},500);})($resumeSec)", null)
                    resumeSec = -1.0
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // 網頁播放器按「全螢幕」時,Chromium 會交出一個播放器 View
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                removeCustomView()
                customView = view
                customCallback = callback
                root.addView(view, pageParams())
                onFullscreen(true)
            }
            override fun onHideCustomView() {
                removeCustomView()
                onFullscreen(false)
            }
        }

        root.addView(webView, pageParams())
        setContentView(root)

        pendingUrl?.let { applyUa(it); webView.loadUrl(it) }
        pendingUrl = null
    }

    private fun removeCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback = null
    }

    /** 載入網址,載入完成後跳到指定秒數繼續播放 */
    fun loadUrl(url: String, resumeAt: Double) {
        resumeSec = resumeAt
        loadUrl(url)
    }

    fun loadUrl(url: String) {
        if (::webView.isInitialized) { applyUa(url); webView.loadUrl(url) } else pendingUrl = url
    }

    fun reload() {
        if (::webView.isInitialized) webView.reload()
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

    /** 在目前操作的網頁(上層 VR 播放器或原本的網頁)執行程式 */
    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        if (!::webView.isInitialized) { cb?.invoke(null); return }
        active.evaluateJavascript(code) { cb?.invoke(it) }
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
            if (r != "true") tap(screenW * (if (sec < 0) 0.2f else 0.8f), screenH * 0.5f, 2)
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
            if (r != "true") tap(screenW * 0.5f, screenH * 0.5f, 1)
        }
    }

    /** YouTube 的 360 / VR180 影片(網頁版只有單眼,可以控制看的方向) */
    /** 回傳投影格式(MESH = VR180、EQUIRECTANGULAR = 360 …);不是 VR 影片回傳空字串 */
    fun youtubeSpherical(cb: (String) -> Unit) {
        // 手機版頁面也要能判斷(可能沒有 setSphericalProperties),所以只看影片格式
        js("(function(){var mp=document.getElementById('movie_player');" +
            "var pr=(mp&&mp.getPlayerResponse&&mp.getPlayerResponse())||window.ytInitialPlayerResponse||{};" +
            "var fs=(pr.streamingData&&pr.streamingData.adaptiveFormats)||[],p='';" +
            "fs.forEach(function(f){if(!p&&f.projectionType&&f.projectionType!=='RECTANGULAR')p=f.projectionType;});" +
            "return p;})()") {
            cb(it?.trim('"')?.takeIf { s -> s != "null" } ?: "")
        }
    }

    /** 暫停網頁裡所有影片(回首頁時) */
    fun pauseAll() {
        js("document.querySelectorAll('video').forEach(function(v){v.pause();})")
    }

    /** 執行網頁裡的程式(空中遊覽的指令等),回傳結果字串 */
    fun run(code: String, cb: ((String?) -> Unit)? = null) = js(code, cb)

    /**
     * 環顧模式看的方向(度):yaw 為 YouTube 的方向(頭右轉 = 減少)、pitch 向上為正,fov = 視野。
     * 空中遊覽頁面用 vrLook(向右為正, 向上為正);YouTube 用播放器的 setSphericalProperties。
     */
    fun lookAt(yaw: Float, pitch: Float, fov: Float) {
        // 電腦版 YouTube 頁面(禁止嵌入時的備用)沒有 vrLook:先裝上和 VR 播放器相同的邊界處理
        js("(function(){if(!window.vrLook){$LOOK_SHIM}" +
            "if(window.vrLook)vrLook(${-yaw},$pitch,$fov);})()")
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
        overlay?.destroy()
        overlay = null
        vrPlayerOpen = false
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
    }
}

// 找出頁面上最主要的影片:正在播放的優先,其次面積最大的
private const val FIND_VIDEO =
    "var v=null,best=-1;[].forEach.call(document.querySelectorAll('video'),function(x){" +
    "var a=x.offsetWidth*x.offsetHeight+(x.paused?0:1e9);if(x.readyState>0&&a>best){best=a;v=x;}});" +
    "if(!v&&window.vrMedia)v=window.vrMedia;"   // VR 播放器(官方嵌入播放器)

// 電腦版 YouTube 頁面用的 vrLook(和 ytvr.html 相同):設定方向後讀回實際方向,
// 被彈回原點或卡在邊緣就把那裡記成邊界,之後停在邊界不再超過
private const val LOOK_SHIM =
    "var mp=document.getElementById('movie_player');if(!mp||!mp.setSphericalProperties)return;" +
    "var lim={lo:-180,hi:180,plo:-90,phi:90},pend=null,okY=0,okP=0,missY=0,lastAy=0;" +
    "function sg(a){a=((a%360)+360)%360;return a>180?a-360:a;}" +
    "function cl(v,a,b){return Math.max(a,Math.min(b,v));}" +
    "window.vrLook=function(right,up,fov){" +
    "if(pend&&mp.getSphericalProperties){var s=mp.getSphericalProperties()||{};" +
    "if(typeof s.yaw==='number'){var ay=sg(s.yaw);" +
    "if(Math.abs(ay-pend.y)<=4){okY=pend.y;missY=0;}" +
    "else if(Math.abs(ay)<2&&Math.abs(pend.y)>8){if(pend.y>0)lim.hi=Math.max(0,okY);else lim.lo=Math.min(0,okY);missY=0;}" +
    "else if(++missY>=3&&Math.abs(ay-lastAy)<1.5){if(pend.y>0)lim.hi=Math.max(0,ay);else lim.lo=Math.min(0,ay);missY=0;}" +
    "lastAy=ay;}" +
    "if(typeof s.pitch==='number'){if(Math.abs(s.pitch-pend.p)<=4)okP=pend.p;" +
    "else if(Math.abs(s.pitch)<2&&Math.abs(pend.p)>8){if(pend.p>0)lim.phi=Math.max(0,okP);else lim.plo=Math.min(0,okP);}}}" +
    "var y=cl(-right,lim.lo,lim.hi),p=cl(up,lim.plo,lim.phi);" +
    "var o={yaw:(y+360)%360,pitch:p,roll:0};if(fov)o.fov=fov;mp.setSphericalProperties(o);pend={y:y,p:p};};"

// 擋掉網頁的陀螺儀事件(在網頁自己的程式之前先攔下)
private const val BLOCK_ORIENTATION =
    "['deviceorientation','deviceorientationabsolute','devicemotion'].forEach(function(t){" +
    "window.addEventListener(t,function(e){e.stopImmediatePropagation();},true);});"

// 拿掉網頁「禁止縮放」的設定(user-scalable=no、maximum-scale=1)
private const val ALLOW_ZOOM =
    "(function(){var m=document.querySelector('meta[name=viewport]');if(!m)return;" +
    "var c=m.getAttribute('content')||'';" +
    "c=c.replace(/user-scalable\\s*=\\s*(no|0)/i,'user-scalable=yes')" +
    ".replace(/maximum-scale\\s*=\\s*[0-9.]+/i,'maximum-scale=5');" +
    "m.setAttribute('content',c);})()"

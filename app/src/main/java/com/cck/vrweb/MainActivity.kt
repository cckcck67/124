package com.cck.vrweb

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {

    companion object {
        // 網頁所在虛擬螢幕的畫質(16:9)。DPI 跟著解析度等比例調整,網頁排版都和 1280x720@240 相同
        val QUALITY_W = intArrayOf(1920, 2560, 3840)
        val QUALITY_H = intArrayOf(1080, 1440, 2160)
        val QUALITY_DPI = intArrayOf(360, 480, 720)
        val QUALITY_NAMES = arrayOf("1080p", "1440p", "4K")
        const val QUALITY_4K = 2

        const val SENSOR_PERIOD_US = 10_000   // 頭部感應器約每秒 100 次,在背景執行緒處理

        // 低頭控制面板(單位:弧度,以水平線為準)
        // 叫出面板的角度(弧度,相對於正前方)
        const val SIT_OPEN = 0.873f      // 坐姿:低頭約 50 度打開
        const val SIT_CLOSE = 0.70f      //       回到約 40 度內關閉
        const val LIE_OPEN = 0.436f      // 躺平(VR180 / 上下VR):抬頭約 25 度打開
        const val LIE_CLOSE = 0.30f      //       回到約 17 度內關閉
        const val LIE_OPEN_FLAT = 0.349f // 躺平(2D / 左右3D):抬頭約 20 度打開
        const val LIE_CLOSE_FLAT = 0.24f //       回到約 14 度內關閉
        const val MENU_Y_RANGE = 0.48f   // 面板一端到另一端 = 頭轉約 27 度
        const val OPEN_GRACE_MS = 500L   // 面板剛打開的這段時間不觸發任何按鈕
        const val MENU_YAW_RANGE = 1.35f // 面板左端到右端 = 轉頭約 77 度
        const val CURSOR_TAU = 0.06f     // 游標平滑的時間常數(秒),越大越穩、越慢
        const val RECENTER_DELAY_MS = 3000L  // 按「置中」後倒數 3 秒,給時間抬頭看正前方
        const val ENTER_DELAY_MS = 5000L     // 進入 VR 後倒數 5 秒置中,給時間把手機放進盒子
        const val DWELL_MS = 1200L       // 看著按鈕多久觸發
        const val DWELL_BAR_MS = 1500L   // 看著進度條同一點多久跳轉
        const val REPEAT_MS = 500L       // 音量鍵持續看著時的重複間隔
        const val FWD_REPEAT_MS = 800L   // 快進鍵持續看著時的重複間隔

        const val TICK_MS = 400L         // 定時檢查網頁捲動 / 影片位置
        const val UPDATE_CHECK_MS = 30 * 60 * 1000L
        const val PULL_SHOW_PX = 120f    // 網頁在最上面時再往下拉多少就叫出網址列
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var topBar: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var gesture: GestureDetector

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private var displaySurface: Surface? = null
    private var quality = 1                 // QUALITY_* 的索引,預設 1440p
    private var srcW = QUALITY_W[1]
    private var srcH = QUALITY_H[1]
    @Volatile private var vrMode = false

    // 頭部感應器在背景執行緒處理,算好的結果交給主執行緒更新面板
    private var sensorThread: HandlerThread? = null
    @Volatile private var sensorAz = 0f
    @Volatile private var sensorDown = 0f
    @Volatile private var menuStepPosted = false
    private var lastCursorAt = 0L
    private val headBuf = arrayOf(FloatArray(9), FloatArray(9))   // 兩份輪流用,避免每次產生新陣列
    private var headIdx = 0
    private var ticking = false
    private var lastScrollY = 0f
    private var barShown = true         // 網址列:網頁捲到最上面後再往下拉才出現,往下捲就收起

    // 頭部方向
    private val rot = FloatArray(9)
    @Volatile private var reverseLandscape = false
    // 參考方向(右、上、前):VR180 的影片方向,以及判斷「低頭」的基準。
    // 進入 VR 時 = 水平面、目前面向;置中後 = 當時頭的完整方向(可躺著看)
    private val refR = FloatArray(3)
    private val refU = FloatArray(3)
    private val refF = FloatArray(3)
    @Volatile private var needLevelRef = true
    @Volatile private var recenter = false
    @Volatile private var recenterAt = 0L
    @Volatile private var lastAz = 0f

    // 控制面板
    private val menu = VrMenu()
    private var menuOpen = false
    private var menuOpenedAt = 0L
    private var menuAz = 0f           // 面板打開時的面向 = 面板正中間
    private var hoverStart = 0L
    private var hoverAnchorX = 0f
    private var dwellDone = false
    private var lastPanelDraw = 0L
    private var lastStatusPoll = 0L

    // 2D 模式點擊偵測(用來判斷是否點到網頁文字框)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downScrollY = 0f
    private var inputDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vr", MODE_PRIVATE)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        quality = prefs.getInt("quality", 1).coerceIn(0, QUALITY_NAMES.size - 1)
        srcW = QUALITY_W[quality]
        srcH = QUALITY_H[quality]
        renderer = VrRenderer(srcW, srcH) { st -> handler.post { attachSurface(st) } }
        renderer.useMipmap = quality == QUALITY_4K
        renderer.distortion = prefs.getFloat("k", 0.15f)
        VrMenu.lying = prefs.getBoolean("lying", false)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            // 有變化才重畫:新影片畫面、轉頭(球面模式)、面板更新時才排重畫,靜止時不耗電
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        renderer.onDirty = { redraw() }
        setMode(prefs.getInt("mode", VrRenderer.MODE_2D).coerceIn(0, VrMenu.MODE_NAMES.size - 1))

        buildUi()
        setupGestures()
        hideSystemBars()

    }

    /** 排一次重畫(畫面元件建立前呼叫會略過;任何執行緒都可以呼叫) */
    private fun redraw() {
        if (::glView.isInitialized) glView.requestRender()
    }

    /** 背景檢查有沒有新版本(VR 模式中不打擾;回到 App 時最多每 30 分鐘檢查一次) */
    private var lastUpdateCheck = 0L

    private fun checkUpdateSoon() {
        val now = SystemClock.uptimeMillis()
        if (lastUpdateCheck != 0L && now - lastUpdateCheck < UPDATE_CHECK_MS) return
        lastUpdateCheck = now
        handler.postDelayed({
            if (!vrMode && !isFinishing) Updater(this) { hideSystemBars() }.check()
        }, 3000)
    }

    // ---------------- 介面 ----------------

    private fun buildUi() {
        val bg = 0xF01E1E1E.toInt()

        urlInput = EditText(this).apply {
            setSingleLine()
            hint = "網址 / 關鍵字(yt 開頭 = 搜尋 YouTube)"
            setText(prefs.getString("url", "https://m.youtube.com"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> go(); true }
            setOnClickListener { showKeyboard() }
            setOnFocusChangeListener { _, has -> if (has) showKeyboard() }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            addView(button("←") { presentation?.handleBack() })
            addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("★") { showBookmarks() })
            addView(button("前往") { go() })
            addView(button("VR") { setVr(true) })
        }

        // 網址列疊在畫面上,出現/隱藏時網頁大小不變
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    // ---------------- 各模式設定(VR 面板的「設定」頁用 − / + 調整) ----------------

    /** 一個設定項目;數值依模式分開存(key 前面加 "m模式_"),def 是沒存過時的預設值 */
    private class Setting(
        val key: String, val name: String, val step: Float, val min: Float, val max: Float,
        val format: String, val def: (Int) -> Float, val apply: (Float) -> Unit
    )

    private val flatSettings by lazy { listOf(
        Setting("zoom", "畫面大小", 0.05f, 1f, 2.5f, "%.2f倍", { prefs.getFloat("zoom", 1.4f) }) { renderer.zoom = it },
        Setting("lift", "畫面高低", 0.01f, -0.3f, 0.3f, "%+.2f", { prefs.getFloat("lift", 0.05f) }) { renderer.lift = it },
        Setting("ipd", "左右間距", 0.005f, -0.3f, 0.1f, "%+.3f", { prefs.getFloat("ipd2", defaultIpd()) }) { renderer.ipd = it },
        Setting("tilt", "翻轉角度", 0.5f, -10f, 10f, "%+.1f度",
            { Math.toDegrees(prefs.getFloat("tilt", 0f).toDouble()).toFloat() }) {
            renderer.tilt = Math.toRadians(it.toDouble()).toFloat()
        },
        Setting("gap", "中間空隙", 0.005f, 0f, 0.2f, "%.3f", { prefs.getFloat("gapFlat", 0.1f) }) { renderer.gap = it },
        Setting("depth", "面板距離", 0.005f, -0.1f, 0.1f, "%+.3f", { prefs.getFloat("panelDepth", 0f) }) { renderer.panelDepth = it },
    ) }

    private val sphereSettings by lazy { listOf(
        Setting("fov", "VR視野", 2f, 60f, 130f, "%.0f度", { prefs.getFloat("fov", 90f) }) { renderer.fovDeg = it },
        Setting("ipd", "左右間距", 0.005f, -0.3f, 0.1f, "%+.3f", { prefs.getFloat("ipd2", defaultIpd()) }) { renderer.ipd = it },
        Setting("gap", "中間空隙", 0.005f, 0f, 0.2f, "%.3f", { prefs.getFloat("gapSphere", 0.03f) }) { renderer.gap = it },
        Setting("depth", "面板距離", 0.005f, -0.1f, 0.1f, "%+.3f", { prefs.getFloat("panelDepth", 0f) }) { renderer.panelDepth = it },
    ) }

    // 播放速度(所有模式共用,不存)
    private val speeds = floatArrayOf(0.5f, 1f, 1.5f)
    private var speedIndex = 1

    private fun modeSettings(m: Int) = if (VrRenderer.isSphere(m)) sphereSettings else flatSettings

    private fun settingValue(m: Int, st: Setting) = prefs.getFloat("m${m}_${st.key}", st.def(m))

    /** 切換模式:套用這個模式自己存的設定 */
    private fun setMode(m: Int) {
        renderer.mode = m
        menu.mode = m
        prefs.edit().putInt("mode", m).apply()
        for (st in modeSettings(m)) st.apply(settingValue(m, st))
        redraw()
    }

    /** 設定頁第 i 項 + / −(最後一項是播放速度) */
    private fun adjustSetting(i: Int, dir: Int) {
        val m = renderer.mode
        val list = modeSettings(m)
        if (i == list.size) {
            speedIndex = (speedIndex + dir).coerceIn(0, speeds.size - 1)
            presentation?.setSpeed(speeds[speedIndex])
            return
        }
        if (i == list.size + 1) {   // 畫質(所有模式共用)
            setQuality((quality + dir).coerceIn(0, QUALITY_NAMES.size - 1))
            return
        }
        val st = list.getOrNull(i) ?: return
        val v = (settingValue(m, st) + dir * st.step).coerceIn(st.min, st.max)
        prefs.edit().putFloat("m${m}_${st.key}", v).apply()
        st.apply(v)
        redraw()
    }

    private fun refreshSettingRows() {
        val m = renderer.mode
        menu.settingRows = modeSettings(m).map { it.name to it.format.format(settingValue(m, it)) } +
                ("速度" to "${speeds[speedIndex]}x") + ("畫質" to QUALITY_NAMES[quality])
    }

    /**
     * 依螢幕實際寬度算出兩眼畫面要往中間靠多少,
     * 讓兩個畫面中心距離接近一般 VR 盒子的鏡片距離(約 63mm)。
     */
    private fun defaultIpd(): Float {
        val m = resources.displayMetrics
        val w = maxOf(m.widthPixels, m.heightPixels).toFloat()
        val dpi = (m.xdpi + m.ydpi) / 2f
        if (dpi <= 0f) return -0.05f
        val eyeW = w / 2f
        val lensPx = 63f / 25.4f * dpi
        return (-(eyeW - lensPx) / 2f / eyeW).coerceIn(-0.25f, 0f)
    }

    private fun go() {
        val t = urlInput.text.toString().trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("yt ") -> "https://m.youtube.com/results?search_query=" + Uri.encode(t.substring(3))
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        openUrl(url)
    }

    private fun openUrl(url: String) {
        urlInput.setText(url)
        presentation?.loadUrl(url)
        hideKeyboard()
        // 前往新網址後先收起網址列,在網頁最上面再往下拉才出現
        barShown = false
        updateTopBar()
    }

    private fun updateTopBar() {
        if (vrMode) return
        val show = urlInput.hasFocus() || barShown
        topBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------- 書籤 ----------------

    private class Bookmark(var title: String, var url: String)

    private fun loadBookmarks(): MutableList<Bookmark> {
        val list = mutableListOf<Bookmark>()
        try {
            val a = org.json.JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(Bookmark(o.optString("t"), o.optString("u")))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val a = org.json.JSONArray()
        for (b in list) a.put(org.json.JSONObject().put("t", b.title).put("u", b.url))
        prefs.edit().putString("bookmarks", a.toString()).apply()
    }

    /** 書籤清單:點一下開啟,長按編輯 / 刪除 */
    private fun showBookmarks() {
        hideKeyboard()
        val list = loadBookmarks()
        val names = list.map { it.title.ifBlank { it.url } }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (list.isEmpty()) "書籤(還沒有書籤)" else "書籤(長按可編輯)")
            .setItems(names) { _, i -> openUrl(list[i].url) }
            .setPositiveButton("加入目前網頁") { _, _ ->
                val url = presentation?.currentUrl ?: urlInput.text.toString()
                if (url.isNotBlank()) editBookmark(list, -1, Bookmark(presentation?.currentTitle ?: "", url))
            }
            .setNegativeButton("關閉", null)
            .create()
        dlg.setOnShowListener {
            dlg.listView?.setOnItemLongClickListener { _, _, i, _ ->
                dlg.dismiss()
                editBookmark(list, i, list[i])
                true
            }
        }
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
    }

    /** 新增(index = -1)或編輯書籤的名稱與網址 */
    private fun editBookmark(list: MutableList<Bookmark>, index: Int, b: Bookmark) {
        val title = EditText(this).apply { setSingleLine(); hint = "名稱"; setText(b.title) }
        val url = EditText(this).apply {
            setSingleLine(); hint = "網址"; setText(b.url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(title)
            addView(url)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "加入書籤" else "編輯書籤")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                val nb = Bookmark(title.text.toString().trim(), u)
                if (index < 0) list.add(nb) else list[index] = nb
                saveBookmarks(list)
            }
            .setNegativeButton("取消", null)
        if (index >= 0) builder.setNeutralButton("刪除") { _, _ ->
            list.removeAt(index)
            saveBookmarks(list)
        }
        val dlg = builder.create()
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
    }

    private fun showKeyboard() {
        urlInput.requestFocus()
        urlInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------- 虛擬螢幕 ----------------

    private fun attachSurface(st: SurfaceTexture) {
        val surface = Surface(st)
        displaySurface = surface
        virtualDisplay?.let { it.surface = surface; return }
        createDisplay(prefs.getString("url", "https://m.youtube.com")!!, -1.0)
    }

    /** 建立虛擬螢幕與上面的網頁(私有虛擬螢幕:只顯示本 App 內容,不需要螢幕錄製權限) */
    private fun createDisplay(url: String, resumeAt: Double) {
        val surface = displaySurface ?: return
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val vd = dm.createVirtualDisplay(
            "vr-web", srcW, srcH, QUALITY_DPI[quality], surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = vd
        presentation = WebPresentation(this, vd.display, srcW, srcH).also {
            it.show()
            it.loadUrl(url, resumeAt)
        }
    }

    /**
     * 切換畫質:Android 的網頁視窗不支援直接改解析度,
     * 所以重建虛擬螢幕並重新載入目前網頁,載入後跳回原本的播放位置。
     */
    private fun setQuality(q: Int) {
        if (q == quality) return
        quality = q
        prefs.edit().putInt("quality", q).apply()
        srcW = QUALITY_W[q]
        srcH = QUALITY_H[q]
        val url = presentation?.currentUrl ?: prefs.getString("url", "https://m.youtube.com")!!
        val at = if (menu.hasVideo) menu.current else -1.0
        presentation?.release()
        presentation?.dismiss()
        virtualDisplay?.release()
        presentation = null
        virtualDisplay = null
        renderer.setBufferSize(srcW, srcH)
        renderer.useMipmap = q == QUALITY_4K
        createDisplay(url, at)
    }

    // ---------------- 定時檢查 ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val p = presentation
            if (p != null) {
                if (!vrMode) {
                    // 網頁捲到最上面才顯示網址列
                    p.scrollTop { y ->
                        lastScrollY = y
                        if (y > 4f && barShown) {   // 往下捲就收起網址列
                            barShown = false
                            updateTopBar()
                        }
                    }
                }
                if (renderer.vrMode && renderer.mode != VrRenderer.MODE_2D) {
                    // 找出影片在網頁中的位置,VR 畫面只取影片本身
                    p.videoRect(false) { r ->
                        renderer.crop = if (r == null) floatArrayOf(0f, 0f, 1f, 1f) else {
                            val x = r[0].coerceIn(0f, 1f)
                            val y = r[1].coerceIn(0f, 1f)
                            val w = r[2].coerceIn(0.05f, 1f - x)
                            val h = r[3].coerceIn(0.05f, 1f - y)
                            floatArrayOf(x, 1f - y - h, w, h)   // 轉成 y 向上
                        }
                        redraw()
                    }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------------- 模式切換 ----------------

    private fun setVr(on: Boolean) {
        vrMode = on
        renderer.vrMode = on
        renderer.showCursor = false
        setMenu(false)
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        reverseLandscape = displayRotation() == Surface.ROTATION_270
        needLevelRef = true
        recenterAt = 0L
        renderer.showHud = false
        hideKeyboard()
        hideSystemBars()
        if (on) {
            autoDetectMode()
            startRecenterCountdown(ENTER_DELAY_MS)   // 倒數時把手機放進盒子、看正前方
        }
        redraw()
    }

    /**
     * 依影片比例猜格式:約 2:1 → VR180(左右兩個半球),約 1:1 → 上下VR;
     * 其他比例若原本是球面模式就回到 2D,否則維持(2D / 左右3D 看不出來)。猜錯可用面板「模式」改。
     */
    private fun autoDetectMode() {
        presentation?.videoSize { w, h ->
            if (w <= 0 || h <= 0) return@videoSize
            val r = w.toFloat() / h
            val m = when {
                r in 1.85f..2.15f -> VrRenderer.MODE_VR180
                r in 0.9f..1.1f -> VrRenderer.MODE_TB360
                VrRenderer.isSphere(renderer.mode) -> VrRenderer.MODE_2D
                else -> renderer.mode
            }
            if (m != renderer.mode) setMode(m)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_90
        else windowManager.defaultDisplay.rotation

    // ---------------- 觸控 ----------------

    private fun setupGestures() {
        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSelect(); return true }
            override fun onDoubleTap(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) { recenter = true }
        })

        glView.setOnTouchListener { _, ev ->
            if (vrMode) gesture.onTouchEvent(ev) else forward2D(ev)
            true
        }
    }

    /** 2D 模式:把每根手指的座標換算成虛擬螢幕座標,直接操作網頁(雙指可縮放) */
    private fun forward2D(ev: MotionEvent) {
        val action = ev.actionMasked
        val r = renderer.fitRect(glView.width.toFloat(), glView.height.toFloat())
        val n = ev.pointerCount
        val props = Array(n) { MotionEvent.PointerProperties().also { p -> ev.getPointerProperties(it, p) } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also { c ->
                ev.getPointerCoords(i, c)
                c.x = (c.x - r[0]) / r[2] * srcW
                c.y = (c.y - r[1]) / r[3] * srcH
            }
        }
        val x = coords[0].x
        val y = coords[0].y
        val e = MotionEvent.obtain(ev.downTime, ev.eventTime, ev.action, n, props, coords,
            ev.metaState, ev.buttonState, 1f, 1f, ev.deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        presentation?.dispatchTouch(e)
        e.recycle()
        if (n > 1) return   // 雙指縮放時不做下面的單指判斷

        // 網頁已在最上面時再往下拉 → 叫出網址列(不會一捲到頂就擋住網頁)
        if (action == MotionEvent.ACTION_DOWN) downScrollY = lastScrollY
        if (action == MotionEvent.ACTION_MOVE && !barShown && downScrollY <= 4f && lastScrollY <= 4f &&
            ev.y - downY > PULL_SHOW_PX) {
            barShown = true
            updateTopBar()
        }

        // 點一下(不是滑動)網頁文字框 → 跳出輸入視窗,用手機鍵盤打字
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.x; downY = ev.y; downAt = ev.eventTime
        } else if (action == MotionEvent.ACTION_UP &&
            abs(ev.x - downX) < 30 && abs(ev.y - downY) < 30 && ev.eventTime - downAt < 600) {
            handler.postDelayed({
                if (vrMode || inputDialog?.isShowing == true) return@postDelayed
                presentation?.inputAt(x, y) { value, hint, password -> askWebInput(value, hint, password) }
            }, 250)
        }
    }

    private fun askWebInput(value: String, hint: String, password: Boolean) {
        if (vrMode || inputDialog?.isShowing == true) return
        val et = EditText(this).apply {
            setSingleLine()
            setText(value)
            setSelection(text.length)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or
                    (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = FrameLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(et)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("輸入文字")
            .setView(box)
            .setPositiveButton("送出") { _, _ -> presentation?.setInputText(et.text.toString(), true) }
            .setNeutralButton("只填入") { _, _ -> presentation?.setInputText(et.text.toString(), false) }
            .setNegativeButton("取消", null)
            .create()
        et.setOnEditorActionListener { _, _, _ ->
            presentation?.setInputText(et.text.toString(), true)
            dlg.dismiss()
            true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.setOnDismissListener { hideSystemBars() }
        inputDialog = dlg
        dlg.show()
        et.requestFocus()
    }

    /** VR 模式點一下螢幕 / 按遙控器確認鍵 */
    private fun onSelect() {
        when {
            menuOpen -> if (menu.hover != VrMenu.NONE) activate(menu.hover)
            else -> presentation?.togglePlay()      // 沒開面板時點一下 = 播放/暫停
        }
    }

    // ---------------- 藍牙搖桿 / 遙控器 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!vrMode) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> presentation?.seekBy(-10)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> presentation?.seekBy(10)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> presentation?.togglePlay()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> onSelect()
            KeyEvent.KEYCODE_DPAD_UP -> changeVolume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_DPAD_DOWN -> changeVolume(AudioManager.ADJUST_LOWER)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            vrMode -> setVr(false)
            presentation?.handleBack() == true -> {}
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------- 頭部追蹤 ----------------

    /** 背景執行緒(VR-Sensor):算頭部方向,球面模式直接更新渲染用的矩陣 */
    override fun onSensorChanged(event: SensorEvent) {
        if (!vrMode) return
        val r = rot
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // 手機裝置座標軸在世界座標(東、北、上)中的方向
        val xw0 = r[0]; val xw1 = r[3]; val xw2 = r[6]
        val yw0 = r[1]; val yw1 = r[4]; val yw2 = r[7]
        // 橫放、螢幕朝向眼睛:視線 = 裝置 -Z;畫面右 / 上依橫放方向決定
        val f0 = -r[2]; val f1 = -r[5]; val f2 = -r[8]
        val s = if (reverseLandscape) 1f else -1f
        val r0 = s * yw0; val r1 = s * yw1; val r2 = s * yw2
        val u0 = -s * xw0; val u1 = -s * xw1; val u2 = -s * xw2

        if (needLevelRef) {
            // 水平面上、目前面向的方向當正前方
            val yaw = atan2(f0, f1)
            val sy = sin(yaw); val cy = cos(yaw)
            refR[0] = cy; refR[1] = -sy; refR[2] = 0f
            refU[0] = 0f; refU[1] = 0f; refU[2] = 1f
            refF[0] = sy; refF[1] = cy; refF[2] = 0f
            needLevelRef = false
        }
        val at = recenterAt
        if (recenter || (at > 0 && SystemClock.uptimeMillis() >= at)) {
            // 目前頭的完整方向當正前方(躺著看天花板也可以)
            refR[0] = r0; refR[1] = r1; refR[2] = r2
            refU[0] = u0; refU[1] = u1; refU[2] = u2
            refF[0] = f0; refF[1] = f1; refF[2] = f2
            recenter = false
            recenterAt = 0L
            renderer.showHud = false
            redraw()
        }

        if (VrRenderer.isSphere(renderer.mode)) {
            // 相機座標(右、上、前) → 參考座標(右、上、前),直行優先;兩份陣列輪流寫,不產生新物件
            headIdx = 1 - headIdx
            val h = headBuf[headIdx]
            h[0] = dot(refR, r0, r1, r2); h[1] = dot(refU, r0, r1, r2); h[2] = dot(refF, r0, r1, r2)
            h[3] = dot(refR, u0, u1, u2); h[4] = dot(refU, u0, u1, u2); h[5] = dot(refF, u0, u1, u2)
            h[6] = dot(refR, f0, f1, f2); h[7] = dot(refU, f0, f1, f2); h[8] = dot(refF, f0, f1, f2)
            renderer.head = h
            redraw()   // 球面模式畫面跟著頭轉
        }

        // 視線在參考座標中的左右角度與低頭角度,交給主執行緒處理面板
        sensorAz = atan2(dot(refR, f0, f1, f2), dot(refF, f0, f1, f2))
        sensorDown = -asin(dot(refU, f0, f1, f2).coerceIn(-1f, 1f))
        if (!menuStepPosted) {
            menuStepPosted = true
            handler.post(menuStep)
        }
    }

    /** 主執行緒:依最新的頭部角度開關面板、移動紅點、處理注視 */
    private val menuStep = Runnable {
        menuStepPosted = false
        if (!vrMode) return@Runnable
        val az = sensorAz
        val down = sensorDown
        lastAz = az

        // ---- 控制面板:坐姿低頭打開、躺平抬頭打開,回到正前方附近就關閉 ----
        val lying = VrMenu.lying
        val tilt = if (lying) -down else down
        val sphere = VrRenderer.isSphere(renderer.mode)
        val openAt = if (!lying) SIT_OPEN else if (sphere) LIE_OPEN else LIE_OPEN_FLAT
        val closeAt = if (!lying) SIT_CLOSE else if (sphere) LIE_CLOSE else LIE_CLOSE_FLAT
        if (!menuOpen && tilt > openAt) setMenu(true)
        else if (menuOpen && tilt < closeAt) setMenu(false)
        if (!menuOpen) return@Runnable

        var dYaw = az - menuAz
        if (dYaw > PI) dYaw -= (2 * PI).toFloat()
        if (dYaw < -PI) dYaw += (2 * PI).toFloat()
        val tx = (0.5f + dYaw / MENU_YAW_RANGE * (VrMenu.X1 - VrMenu.X0)).coerceIn(VrMenu.X0, VrMenu.X1)
        // 剛打開時游標在空白列;頭再轉多一點,游標往面板外側(坐姿往下、躺平往上)移動
        val dir = if (lying) -1f else 1f
        val ty = (VrMenu.START_Y + dir * (tilt - openAt) / MENU_Y_RANGE * (VrMenu.Y1 - VrMenu.Y0))
            .coerceIn(VrMenu.Y0, VrMenu.Y1)
        // 依經過時間平滑,不受感應器頻率影響
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastCursorAt).coerceIn(1L, 100L)) / 1000f
        lastCursorAt = now
        val k = 1f - kotlin.math.exp(-dt / CURSOR_TAU)
        renderer.cursorX += (tx - renderer.cursorX) * k
        renderer.cursorY += (ty - renderer.cursorY) * k
        updateMenu(now)
        redraw()
    }

    private fun dot(a: FloatArray, x: Float, y: Float, z: Float) = a[0] * x + a[1] * y + a[2] * z

    // ---------------- 控制面板 ----------------

    private fun setMenu(open: Boolean) {
        if (menuOpen == open && renderer.showPanel == open) return
        menuOpen = open
        renderer.showPanel = open
        renderer.showCursor = open   // 紅點只跟著面板出現
        renderer.cursorX = 0.5f
        renderer.cursorY = VrMenu.START_Y   // 從最上排空位開始,不會直接落在按鈕上
        menuOpenedAt = SystemClock.uptimeMillis()
        menuAz = lastAz
        menu.hover = VrMenu.NONE
        menu.popup = VrMenu.NONE
        menu.settingsPage = false
        menu.dwell = 0f
        dwellDone = false
        redraw()
        if (open) {
            menu.volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            menu.volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            pollStatus()
            redrawPanel(force = true)
        }
    }

    private fun updateMenu(now: Long) {
        val cx = renderer.cursorX
        val t = if (now - menuOpenedAt < OPEN_GRACE_MS) VrMenu.NONE else menu.hitTest(cx, renderer.cursorY)
        if (t != menu.hover) {
            menu.hover = t
            hoverStart = now
            hoverAnchorX = cx
            dwellDone = false
        }
        if (t == VrMenu.BAR) {
            menu.hoverFrac = menu.barFraction(cx)
            // 進度條要看著同一點不動才計時
            if (abs(cx - hoverAnchorX) > 0.015f) {
                hoverAnchorX = cx
                hoverStart = now
                dwellDone = false
            }
        }
        val need = if (t == VrMenu.BAR) DWELL_BAR_MS else DWELL_MS
        menu.dwell = if (t == VrMenu.NONE || dwellDone) 0f
        else ((now - hoverStart).toFloat() / need).coerceIn(0f, 1f)

        if (t != VrMenu.NONE && !dwellDone && menu.dwell >= 1f) {
            activate(t)
            if (t == VrMenu.BTN_VOL_DOWN || t == VrMenu.BTN_VOL_UP) {
                hoverStart = now - need + REPEAT_MS   // 繼續看著 = 持續調整
            } else if (t == VrMenu.BTN_FWD) {
                hoverStart = now - need + FWD_REPEAT_MS   // 繼續看著 = 一直快進
            } else if (t >= VrMenu.SET_MINUS) {
                hoverStart = now - need + REPEAT_MS   // 設定的 − / + 繼續看著 = 持續調整
            } else {
                dwellDone = true
            }
            menu.dwell = 0f
        }

        if (now - lastStatusPoll > 500) pollStatus()
        redrawPanel()
    }

    private fun activate(target: Int) {
        val p = presentation ?: return
        when (target) {
            VrMenu.BAR -> {
                p.seekTo(menu.hoverFrac)
                menu.current = menu.hoverFrac * menu.duration
            }
            // 坐姿 / 躺平切換:同時開始置中倒數,讓正前方跟著新姿勢
            VrMenu.BTN_POSTURE -> {
                VrMenu.lying = !VrMenu.lying
                prefs.edit().putBoolean("lying", VrMenu.lying).apply()
                startRecenterCountdown()
                setMenu(false)
                return
            }
            VrMenu.BTN_FWD -> p.seekBy(10)
            VrMenu.BTN_VOL_DOWN -> changeVolume(AudioManager.ADJUST_LOWER)
            VrMenu.BTN_VOL_UP -> changeVolume(AudioManager.ADJUST_RAISE)
            // 置中:倒數 3 秒,期間轉頭看想要的正前方,再以當時的方向為準
            VrMenu.BTN_RECENTER -> startRecenterCountdown()
            // 模式:在上方拉出選項(再看一次收起)
            VrMenu.BTN_MODE -> menu.popup = if (menu.popup == target) VrMenu.NONE else target
            // 設定:切換到設定頁(只列出目前模式用得到的項目)
            VrMenu.BTN_SETTINGS -> {
                refreshSettingRows()
                menu.settingsPage = true
            }
            else -> when {
                target >= VrMenu.SET_MINUS -> {
                    adjustSetting(VrMenu.setIndex(target), if (VrMenu.isSetMinus(target)) -1 else 1)
                    refreshSettingRows()
                }
                target >= VrMenu.OPTION && menu.popup == VrMenu.BTN_MODE -> {
                    setMode(target - VrMenu.OPTION)   // 套用這個模式自己的設定
                    menu.popup = VrMenu.NONE
                }
            }
        }
        handler.postDelayed({ pollStatus() }, 300)
        redrawPanel(force = true)
    }

    // ---------------- 置中倒數 ----------------

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = VrRenderer.HUD * 0.7f
        isFakeBoldText = true
    }

    private fun startRecenterCountdown(delay: Long = RECENTER_DELAY_MS) {
        recenterAt = SystemClock.uptimeMillis() + delay
        handler.removeCallbacks(countdown)
        handler.post(countdown)
    }

    /** 畫面正中央倒數(5、4…或 3、2、1),時間到由感測器那邊完成置中並隱藏數字 */
    private val countdown = object : Runnable {
        override fun run() {
            if (recenterAt == 0L || !vrMode) { renderer.showHud = false; return }
            val left = recenterAt - SystemClock.uptimeMillis()
            if (left <= 0) return
            val n = ((left + 999) / 1000).toInt()
            renderer.updateHud { b -> drawCountdown(b, n) }
            renderer.showHud = true
            handler.postDelayed(this, (left - (n - 1) * 1000L).coerceIn(20L, 1000L))
        }
    }

    private fun drawCountdown(b: android.graphics.Bitmap, n: Int) {
        b.eraseColor(0)
        val c = Canvas(b)
        val h = VrRenderer.HUD.toFloat()
        hudPaint.style = Paint.Style.FILL
        hudPaint.color = 0xAA000000.toInt()
        c.drawCircle(h / 2, h / 2, h / 2 - 4, hudPaint)
        val y = h / 2 - (hudPaint.descent() + hudPaint.ascent()) / 2
        hudPaint.style = Paint.Style.STROKE
        hudPaint.strokeWidth = 14f
        hudPaint.color = 0xFF000000.toInt()
        c.drawText(n.toString(), h / 2, y, hudPaint)
        hudPaint.style = Paint.Style.FILL
        hudPaint.color = 0xFFFFFFFF.toInt()
        c.drawText(n.toString(), h / 2, y, hudPaint)
    }

    private fun changeVolume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)  // VR 中不顯示系統音量條
        menu.volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        redrawPanel(force = true)
    }

    private fun pollStatus() {
        lastStatusPoll = SystemClock.uptimeMillis()
        presentation?.videoStatus { st ->
            menu.hasVideo = st != null
            if (st != null) {
                menu.current = st.current
                menu.duration = st.duration
                menu.paused = st.paused
            }
            redrawPanel(force = true)
        }
    }

    private fun redrawPanel(force: Boolean = false) {
        if (!menuOpen) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPanelDraw < 33) return   // 最多約每秒 30 次
        lastPanelDraw = now
        renderer.updatePanel { menu.draw(it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- 生命週期 ----------------

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // 感應器在背景執行緒接收,不佔用主執行緒(網頁與面板)
        val t = HandlerThread("VR-Sensor").also { it.start() }
        sensorThread = t
        s?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US, Handler(t.looper)) }
        redraw()
        ticking = true
        handler.post(tick)
        checkUpdateSoon()
    }

    override fun onPause() {
        presentation?.currentUrl?.let { prefs.edit().putString("url", it).apply() }
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        ticking = false
        handler.removeCallbacks(tick)
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        presentation?.release()
        presentation?.dismiss()
        virtualDisplay?.release()
        super.onDestroy()
    }
}

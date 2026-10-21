package com.cck.vrweb

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
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
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {

    companion object {
        // 畫質:網頁長邊的像素(網頁 16:9,直式時長邊在上下)。DPI 跟著解析度等比例調整,
        // 網頁排版都和 1280x720@240 相同。虛擬螢幕是長邊 x 長邊的正方形,轉向時只改網頁大小
        val QUALITY_W = intArrayOf(1920, 2560, 3840)
        val QUALITY_DPI = intArrayOf(360, 480, 720)
        val QUALITY_NAMES = arrayOf("1080p", "1440p", "4K")
        const val QUALITY_4K = 2

        /** App 內建首頁 */
        /** 網頁瀏覽的起始頁、空中遊覽(App 內建頁面) */
        const val WEB_START = WebPresentation.APP_ORIGIN + "/assets/start.html"
        const val FLIGHT_URL = WebPresentation.APP_ORIGIN + "/assets/flight.html"

        // 目前的功能畫面
        const val SCREEN_HOME = 0
        const val SCREEN_WEB = 1
        const val SCREEN_FLIGHT = 2
        const val SCREEN_VIDEO = 3

        const val REQ_PICK_VIDEO = 11
        const val REQ_YOUTUBE = 12

        const val SENSOR_PERIOD_US = 10_000   // 頭部感應器約每秒 100 次,在背景執行緒處理

        // 低頭控制面板(單位:弧度,以水平線為準)
        // 叫出面板的角度(弧度,相對於正前方)
        const val SIT_OPEN = 0.873f      // 坐姿:低頭約 50 度打開
        const val SIT_CLOSE = 0.70f      //       回到約 40 度內關閉
        const val LIE_OPEN = 0.436f      // 躺平(VR180 / 上下VR):抬頭約 25 度打開
        const val LIE_CLOSE = 0.30f      //       回到約 17 度內關閉
        const val LIE_OPEN_FLAT = 0.349f // 躺平(2D / 左右3D):抬頭約 20 度打開
        const val LIE_CLOSE_FLAT = 0.24f //       回到約 14 度內關閉
        const val LIE_OPEN_YT = 0.611f   // 躺平(YT環顧):抬頭約 35 度打開(抬頭看影片上方時不會誤開)
        const val LIE_CLOSE_YT = 0.436f  //       回到約 25 度內關閉
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
    private var srcW = QUALITY_W[1]          // 虛擬螢幕(正方形)
    private var srcH = QUALITY_W[1]
    private var pageW = QUALITY_W[1]         // 網頁大小(橫式 16:9 或直式 9:16)
    private var pageH = QUALITY_W[1] * 9 / 16

    private var screen = SCREEN_HOME         // 目前的功能畫面
    private var pendingUrl: String? = null   // 網頁元件還沒建立時要載入的網址
    private val videoServer by lazy { LocalVideoServer(applicationContext) }
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
        // 已移除的立體照功能留下的深度模型(約 27MB),刪掉釋放空間
        java.io.File(filesDir, "depth.onnx").delete()
        java.io.File(filesDir, "diag.txt").delete()   // 已移除的診斷紀錄

        quality = prefs.getInt("quality", 1).coerceIn(0, QUALITY_NAMES.size - 1)
        srcW = QUALITY_W[quality]
        srcH = QUALITY_W[quality]
        renderer = VrRenderer(srcW, srcH) { st -> handler.post { attachSurface(st) } }
        updatePageSize()
        renderer.useMipmap = quality >= QUALITY_4K
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
        goHome()

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

    private lateinit var starButton: Button
    private var ytVrVideo = false                    // 目前網頁是 YouTube 的 360 / VR180 影片
    private lateinit var titleLabel: TextView
    private lateinit var overlayHost: FrameLayout      // 首頁 / 設定 / 我的影片(蓋在網頁上方)

    private fun buildUi() {
        val bg = 0xF01E1E1E.toInt()

        urlInput = EditText(this).apply {
            setSingleLine()
            hint = "網址 / 關鍵字(yt 開頭 = 搜尋 YouTube)"
            // 深色底、白字(原本白底白字,打的字看不到)
            setBackgroundColor(0xFF3A3A3A.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF999999.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> go(); true }
            setOnClickListener { showKeyboard() }
            setOnFocusChangeListener { _, has -> if (has) showKeyboard() }
        }
        starButton = button("★") { showBookmarks() }
        titleLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine()
            gravity = Gravity.CENTER
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            addView(button("←") { navBack() })
            addView(button("首頁") { goHome() })
            addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(starButton)
            addView(button("VR") { setVr(true) })
        }
        overlayHost = FrameLayout(this)

        // 網址列疊在畫面上,出現/隱藏時網頁大小不變
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(overlayHost, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
    }

    // ---------------- 首頁 / 設定 / 我的影片 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay(v: View?) {
        overlayHost.removeAllViews()
        v?.isClickable = true   // 擋住觸控,不會點到下面的網頁
        if (v != null) overlayHost.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayHost.visibility = if (v != null) View.VISIBLE else View.GONE
    }

    /** 上方標題列:左邊返回(首頁時是 App 名稱),右邊可放一顆按鈕 */
    private fun header(title: String, back: Boolean, right: View?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (back) addView(button("← 返回") { goHome() })
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = if (back) 22f else 30f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(right)
        }

    private fun tile(text: String, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 24f
        isAllCaps = false
        setTextColor(if (enabled) Color.WHITE else Color.GRAY)
        setBackgroundColor(if (enabled) 0xFF2F5A88.toInt() else 0xFF333333.toInt())
        setOnClickListener { onClick() }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** 首頁:功能選單,右上角是設定 */
    private fun buildHome(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tiles = listOf(
            tile("網頁瀏覽", true) { goWeb() },
            tile("空中遊覽", true) { goFlight() },
            tile("我的影片", true) { showMyVideos() },
            tile("編輯旅程\n(即將推出)", false) { toast("編輯旅程還在規劃中,敬請期待") },
        )
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                row.addView(tiles[r * 2 + c], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            addView(header("WebVR", false, button("⚙ 設定") { showSettings() }))
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(dp(8), 0, dp(8), dp(8))
            })
            addView(TextView(context).apply {
                text = "版本 " + versionName()
                setTextColor(Color.GRAY)
                gravity = Gravity.END
                setPadding(0, 0, dp(16), dp(8))
            })
        }
    }

    private fun settingRow(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    /** 設定頁(以後的設定都放這裡) */
    private fun showSettings() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(settingRow("畫質:" + QUALITY_NAMES[quality]) { chooseQuality() })
            addView(settingRow("Google 金鑰(空中遊覽):" +
                    if (prefs.getString("mapsKey", "").isNullOrBlank()) "未設定" else "已設定") { askMapsKey() })
            addView(settingRow("Google 金鑰申請、帳單與用量上限教學") { showKeyHelp() })
            addView(settingRow("檢查更新(目前版本 ${versionName()})") {
                Updater(this@MainActivity) { hideSystemBars() }.check(manual = true)
            })
        }
        showOverlay(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            addView(header("設定", true, null))
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        subScreen = true
    }

    private var subScreen = false   // 設定 / 我的影片(從首頁打開)

    /** 我的影片:從手機選影片加入清單,點一下播放、長按移除 */
    private fun showMyVideos() {
        val items = loadMyVideos()
        val listView = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1,
                items.map { it.second }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        textSize = 18f
                    }
            }
            setOnItemClickListener { _, _, i, _ -> playVideo(Uri.parse(items[i].first), items[i].second) }
            setOnItemLongClickListener { _, _, i, _ ->
                val dlg = AlertDialog.Builder(context)
                    .setTitle("從清單移除?")
                    .setMessage(items[i].second + "\n(只從清單移除,不會刪除手機裡的檔案)")
                    .setPositiveButton("移除") { _, _ ->
                        val list = loadMyVideos().filter { it.first != items[i].first }
                        saveMyVideos(list)
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(items[i].first), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {}
                        showMyVideos()
                    }
                    .setNegativeButton("取消", null)
                    .create()
                dlg.setOnDismissListener { hideSystemBars() }
                dlg.show()
                true
            }
        }
        showOverlay(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            addView(header("我的影片", true, button("＋ 加入影片") { pickVideos() }))
            if (items.isEmpty()) addView(TextView(context).apply {
                text = "還沒有影片\n按右上角「＋ 加入影片」從手機選擇\n(左右3D、VR180、360 影片進 VR 會自動判斷格式)"
                setTextColor(Color.GRAY)
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            else addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        subScreen = true
        screen = SCREEN_HOME
        updateTopBar()
    }

    private fun loadMyVideos(): List<Pair<String, String>> = try {
        val a = org.json.JSONArray(prefs.getString("myVideos", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("u") to o.optString("n") } }
    } catch (e: Exception) { emptyList() }

    private fun saveMyVideos(list: List<Pair<String, String>>) {
        val a = org.json.JSONArray()
        for ((u, n) in list) a.put(org.json.JSONObject().put("u", u).put("n", n))
        prefs.edit().putString("myVideos", a.toString()).apply()
    }

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK_VIDEO)
        } catch (e: Exception) { toast("找不到檔案選擇器") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_VIDEO || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        data.data?.let { if (it !in uris) uris.add(it) }
        val list = loadMyVideos().toMutableList()
        for (u in uris) {
            // 保留讀取權限,下次開 App 還能播放
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            if (list.any { it.first == u.toString() }) continue
            list.add(u.toString() to displayName(u))
        }
        saveMyVideos(list)
        showMyVideos()
    }

    private fun displayName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "影片"
            }
        } catch (e: Exception) {}
        return u.lastPathSegment ?: "影片"
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------- 切換功能 ----------------

    /** 回首頁:網頁瀏覽的影片先暫停(網頁保留);空中遊覽 / 我的影片直接關掉 */
    private fun goHome() {
        if (vrMode) setVr(false)
        when (screen) {
            SCREEN_WEB -> {
                presentation?.pauseAll()
                saveWebUrl()
            }
            SCREEN_FLIGHT, SCREEN_VIDEO -> loadInWeb("about:blank")
        }
        screen = SCREEN_HOME
        subScreen = false
        hideKeyboard()
        showOverlay(buildHome())
        updateTopBar()
    }

    /** 一般網頁(App 內建頁面、我的影片的播放頁不算;舊版留下的內建首頁網址也不算) */
    private fun isWebPage(url: String?) = url != null && url.startsWith("http") &&
            !url.startsWith("http://127.0.0.1") && !url.startsWith(WebPresentation.APP_ORIGIN)

    private fun saveWebUrl() {
        presentation?.currentUrl?.takeIf { isWebPage(it) }?.let { prefs.edit().putString("url", it).apply() }
    }

    /** 網頁瀏覽:回到上次的網頁(第一次是起始頁) */
    private fun goWeb() {
        val cur = presentation?.currentUrl ?: pendingUrl
        screen = SCREEN_WEB
        subScreen = false
        showOverlay(null)
        menu.buttons = VrMenu.VIDEO_BUTTONS
        applySettings()
        if (isWebPage(cur) || cur == WEB_START) { barShown = true; updateTopBar() }
        else openUrl(prefs.getString("url", null)?.takeIf { isWebPage(it) } ?: WEB_START)
        barShown = true
        updateTopBar()
    }

    private fun goFlight() {
        screen = SCREEN_FLIGHT
        subScreen = false
        showOverlay(null)
        titleLabel.text = "空中遊覽"
        menu.buttons = VrMenu.FLIGHT_BUTTONS
        applySettings()
        loadInWeb(FLIGHT_URL)
        updateTopBar()
    }

    private fun playVideo(uri: Uri, name: String) {
        screen = SCREEN_VIDEO
        subScreen = false
        showOverlay(null)
        titleLabel.text = name
        menu.buttons = VrMenu.VIDEO_BUTTONS
        applySettings()
        loadInWeb(videoServer.playerUrl(uri, name))
        updateTopBar()
    }

    /** 網址列的「←」:網頁上一頁;空中遊覽回目的地選單;我的影片回清單 */
    private fun navBack() {
        when (screen) {
            SCREEN_WEB -> when {
                presentation?.handleBack() != true -> goHome()
            }
            SCREEN_FLIGHT -> {
                val p = presentation
                if (p == null) goHome()
                else p.run("(window.vrBack?vrBack():false)") { r -> if (r != "true") goHome() }
            }
            SCREEN_VIDEO -> { goHome(); showMyVideos() }
            else -> goHome()
        }
    }

    // ---------------- 直式 / 橫式 ----------------

    /** 螢幕長邊 / 短邊(小米9 約 2.17);拿不到時用 16:9 */
    private fun screenRatio(): Float {
        val (w, h) = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
            else android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it)
            }.let { it.widthPixels to it.heightPixels }
        } catch (e: Exception) { 16 to 9 }
        if (w <= 0 || h <= 0) return 16f / 9f
        return (maxOf(w, h).toFloat() / minOf(w, h)).coerceIn(1.3f, 2.4f)
    }

    /**
     * 網頁大小跟著手機方向與螢幕比例,填滿整個螢幕(VR 一律橫式 16:9);
     * 只改排版,網頁不會重新載入
     */
    private fun updatePageSize() {
        val long = QUALITY_W[quality]
        val short = if (vrMode) long * 9 / 16 else (long / screenRatio()).toInt()
        val portrait = !vrMode &&
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        pageW = if (portrait) short else long
        pageH = if (portrait) long else short
        renderer.pageW = pageW
        renderer.pageH = pageH
        presentation?.setPageSize(pageW, pageH)
        redraw()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (vrMode) reverseLandscape = displayRotation() == Surface.ROTATION_270
        updatePageSize()
    }

    /** 網址列「畫質」:選好後重新載入目前網頁(回到原本的播放位置) */
    private fun chooseQuality() {
        hideKeyboard()
        val names = arrayOf("1080p", "1440p(預設)", "4K")
        val dlg = AlertDialog.Builder(this)
            .setTitle("畫質(越高越清楚,但越耗電)")
            .setSingleChoiceItems(names, quality) { d, i ->
                d.dismiss()
                setQuality(i)
                if (subScreen && screen == SCREEN_HOME) showSettings()   // 更新設定頁上的數值
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
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
        Setting("gap", "中間空隙", 0.005f, 0f, 0.2f, "%.3f", { prefs.getFloat("gapFlat", 0.1f) }) { renderer.gap = it },
        Setting("depth", "面板距離", 0.005f, -0.1f, 0.1f, "%+.3f", { prefs.getFloat("panelDepth", 0f) }) { renderer.panelDepth = it },
    ) }

    // 曲面巨幕的設定(2D曲面;左右3D 也放在一般設定下面)
    private fun curveExtra(minArc: Float, defArc: Float) = listOf(
        Setting("arc", "曲面弧度", 10f, minArc, 180f, "%.0f度", { defArc }) { renderer.curve = it; redraw() },
        Setting("csize", "曲面大小", 0.1f, 1f, 4.5f, "%.1f倍", { 1.8f }) { renderer.curveSize = it; redraw() },
        Setting("dome", "上下彎曲", 10f, 0f, 100f, "%.0f%%", { 0f }) { renderer.dome = it / 100f; redraw() },
        Setting("edge", "邊緣拉伸", 5f, 0f, 45f, "%.0f%%", { 0f }) { renderer.edge = it / 100f; redraw() },
    )

    private val curveSettings by lazy {
        curveExtra(30f, 120f) + flatSettings.filter { it.key != "zoom" }
    }

    // 左右3D:一般平面設定 + 曲面設定(曲面弧度 0 = 平面)
    private val sbsSettings by lazy { flatSettings + curveExtra(0f, 0f) }

    private val sphereSettings by lazy { listOf(
        Setting("fov", "VR視野", 2f, 60f, 130f, "%.0f度", { prefs.getFloat("fov", 90f) }) { renderer.fovDeg = it },
        Setting("ipd", "左右間距", 0.005f, -0.3f, 0.1f, "%+.3f", { prefs.getFloat("ipd2", defaultIpd()) }) { renderer.ipd = it },
        Setting("gap", "中間空隙", 0.005f, 0f, 0.2f, "%.3f", { prefs.getFloat("gapSphere", 0.03f) }) { renderer.gap = it },
        Setting("depth", "面板距離", 0.005f, -0.1f, 0.1f, "%+.3f", { prefs.getFloat("panelDepth", 0f) }) { renderer.panelDepth = it },
    ) }

    // 播放速度(所有模式共用,不存)
    private val speeds = floatArrayOf(0.5f, 1f, 1.5f)
    private var speedIndex = 1

    // 空中遊覽自己的設定(和影片的 VR 設定分開存,不會互相影響)
    private var flyGain = 0.7f
    private var flySpeed = 1f
    private var flyAlt = 1f

    private val flightSettings by lazy {
        listOf(
            Setting("gain", "轉頭比例", 0.05f, 0.2f, 1.5f, "%.2f", { 0.7f }) { flyGain = it; lastYtYaw = 999f },
            Setting("speed", "飛行速度", 0.25f, 0.25f, 3f, "%.2f倍", { 1f }) { flySpeed = it; sendFlightConfig() },
            Setting("alt", "飛行高度", 0.1f, 0.5f, 2.5f, "%.1f倍", { 1f }) { flyAlt = it; sendFlightConfig() },
        ) + flatSettings
    }

    /** 面板第一層的「飛快 / 飛高 ±」:改空中遊覽的速度 / 高度設定 */
    private fun bumpFlight(key: String, dir: Int) {
        val st = flightSettings.firstOrNull { it.key == key } ?: return
        val v = (settingValue(st) + dir * st.step).coerceIn(st.min, st.max)
        prefs.edit().putFloat(prefKey(st), v).apply()
        st.apply(v)
        pollStatus()
    }

    private fun sendFlightConfig() {
        if (screen == SCREEN_FLIGHT) presentation?.run("window.vrFlightConfig&&vrFlightConfig($flySpeed,$flyAlt,$vrMode)")
    }

    private fun modeSettings(m: Int) = when {
        VrRenderer.isSphere(m) -> sphereSettings
        m == VrRenderer.MODE_CURVE -> curveSettings
        m == VrRenderer.MODE_SBS -> sbsSettings
        else -> flatSettings
    }

    /** 目前功能用的設定:空中遊覽有自己的一組,其他依 VR 模式 */
    private fun currentSettings() = if (screen == SCREEN_FLIGHT) flightSettings else modeSettings(renderer.mode)

    private fun prefKey(st: Setting) =
        if (screen == SCREEN_FLIGHT) "fly_${st.key}" else "m${renderer.mode}_${st.key}"

    private fun settingValue(st: Setting) = prefs.getFloat(prefKey(st), st.def(renderer.mode))

    /** 套用目前功能 / 模式自己存的設定 */
    private fun applySettings() {
        for (st in currentSettings()) st.apply(settingValue(st))
        redraw()
    }

    /** 切換模式:套用這個模式自己存的設定 */
    private fun setMode(m: Int) {
        renderer.mode = m
        menu.mode = m
        prefs.edit().putInt("mode", m).apply()
        applySettings()
    }

    /** 設定頁第 i 項 + / −(影片時最後一項是播放速度) */
    private fun adjustSetting(i: Int, dir: Int) {
        val list = currentSettings()
        if (screen != SCREEN_FLIGHT && i == list.size) {
            speedIndex = (speedIndex + dir).coerceIn(0, speeds.size - 1)
            presentation?.setSpeed(speeds[speedIndex])
            return
        }
        val st = list.getOrNull(i) ?: return
        val v = (settingValue(st) + dir * st.step).coerceIn(st.min, st.max)
        prefs.edit().putFloat(prefKey(st), v).apply()
        st.apply(v)
        redraw()
    }

    private fun refreshSettingRows() {
        val rows = currentSettings().map { it.name to it.format.format(settingValue(it)) }
        menu.settingRows = if (screen == SCREEN_FLIGHT) rows else rows + ("速度" to "${speeds[speedIndex]}x")
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
        urlInput.setText(if (url == WEB_START) "" else url)
        loadInWeb(url)
        hideKeyboard()
        // 前往新網址後先收起網址列,在網頁最上面再往下拉才出現(起始頁不收)
        barShown = url == WEB_START
        updateTopBar()
    }

    /** 在網頁元件載入網址(還沒建立時等建立後再載入) */
    private fun loadInWeb(url: String) {
        val p = presentation
        if (p == null) pendingUrl = url else p.loadUrl(url)
    }

    /** 網址列:網頁瀏覽時依捲動顯示;空中遊覽、我的影片只留返回 / 首頁 / VR;首頁與 VR 時隱藏 */
    private fun updateTopBar() {
        if (vrMode) { topBar.visibility = View.GONE; return }
        val web = screen == SCREEN_WEB
        urlInput.visibility = if (web) View.VISIBLE else View.GONE
        starButton.visibility = if (web) View.VISIBLE else View.GONE
        titleLabel.visibility = if (web) View.GONE else View.VISIBLE
        val show = when (screen) {
            SCREEN_HOME -> false
            SCREEN_WEB -> urlInput.hasFocus() || barShown
            else -> true
        }
        topBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------- Google 金鑰(空中遊覽) ----------------

    /** 輸入 Google 地圖的 API 金鑰;只存在手機裡,不會放到 GitHub */
    private fun askMapsKey() {
        hideKeyboard()
        val et = EditText(this).apply {
            setSingleLine()
            hint = "貼上 Google API 金鑰"
            setText(prefs.getString("mapsKey", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = FrameLayout(this).apply { setPadding(48, 24, 48, 0); addView(et) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Google 金鑰(空中遊覽用)")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                prefs.edit().putString("mapsKey", et.text.toString().trim()).apply()
                if (screen == SCREEN_FLIGHT) presentation?.reload()   // 重新載入,讓頁面拿到新金鑰
                if (subScreen && screen == SCREEN_HOME) showSettings()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
    }

    /** Google 地圖拒絕這把金鑰的原因,用白話說明怎麼處理(VR 中不跳出) */
    private fun showMapError(code: String) {
        val why = when (code) {
            "BillingNotEnabledMapError" -> "這把金鑰所屬的 Google Cloud 專案沒有綁定帳單(付款方式)。\n" +
                "街景和 2D 地圖需要綁定帳單才能正常顯示(每月有免費額度)。\n\n" +
                "處理:Google Cloud 控制台 → 帳單 → 把這個專案連結到帳單帳戶。"
            "ApiNotActivatedMapError" -> "這把金鑰的專案沒有啟用「Maps JavaScript API」。\n\n" +
                "處理:Google Cloud 控制台 → API 和服務 → 程式庫 → 搜尋 Maps JavaScript API → 啟用。"
            "ApiTargetBlockedMapError" -> "金鑰的「API 限制」沒有包含這個功能需要的 API。\n\n" +
                "處理:Google Cloud 控制台 → API 和服務 → 憑證 → 這把金鑰 → API 限制,勾選 Maps JavaScript API(或先改成不限制)。"
            "RefererNotAllowedMapError" -> "金鑰的「網站限制」不允許這個 App 的網址。\n\n" +
                "處理:金鑰的應用程式限制加入 https://appassets.androidplatform.net/*(或先改成無限制)。"
            "InvalidKeyMapError" -> "金鑰無效,請到設定重新貼上金鑰。"
            "ExpiredKeyMapError" -> "金鑰已過期,請重新產生後貼上。"
            "OverQuotaMapError" -> "超過 Google 地圖的使用額度。"
            else -> "Google 地圖回報錯誤。"
        }
        if (vrMode) { toast("Google 地圖錯誤:$code(退出 VR 後看說明)"); pendingMapError = code; return }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Google 地圖錯誤")
            .setMessage("$why\n\n(錯誤代碼:$code)")
            .setPositiveButton("知道了", null)
            .setNeutralButton("看教學") { _, _ -> showKeyHelp() }
            .create()
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
    }

    private var pendingMapError: String? = null

    /** Google 金鑰申請、綁定帳單、設定用量上限的教學(在電腦上照著做比較方便) */
    private fun showKeyHelp() {
        val text = """
一、申請金鑰(約 10 分鐘,建議用電腦)
1. 開 console.cloud.google.com,用 Google 帳號登入。
2. 上方「選取專案」→「新增專案」,名稱隨意(例如 VR遊覽)→ 建立,並切換到這個專案。
3. 左上 ☰ →「API 和服務」→「程式庫」,搜尋並「啟用」:
   ・Maps JavaScript API(2D 地圖、街景)
   ・Map Tiles API(3D 空拍)
4. 「API 和服務」→「憑證」→「建立憑證」→「API 金鑰」,複製金鑰。
5. 點這把金鑰 →「應用程式限制」選「網站」,加入:
   https://appassets.androidplatform.net/*
   「API 限制」勾選上面兩個 API → 儲存。(別人拿到金鑰也不能亂用)
6. 回到本 App:設定 →「Google 金鑰」→ 貼上 → 儲存。

二、綁定帳單(街景、2D 地圖正常顯示需要)
1. ☰ →「帳單」→「連結帳單帳戶」→「建立帳單帳戶」。
2. 國家選台灣,填信用卡資料 → 完成,確認專案已連結到這個帳單帳戶。
3. Google 地圖每個功能每月有免費用量;自己和家人試用通常用不完。實際額度以 Google「Maps Platform 價格」頁面為準。

三、設定用量上限(保證不會被收錢,最重要)
1. ☰ →「API 和服務」→「已啟用的 API 和服務」→ 點「Maps JavaScript API」→「配額與系統限制」。
2. 找名稱有「per day / 每天」的項目(例如每天地圖載入次數、街景載入次數)→ 勾選 →「編輯配額」→ 設成小數字,例如 300 → 送出。
3. 「Map Tiles API」也照同樣方式,每天的上限設小一點(例如 300)。
   超過上限只會當天載不出來,不會繼續計費。
4. 再加一道提醒:☰ →「帳單」→「預算與快訊」→「建立預算」→ 金額填 NT$30 → 50%、90%、100% 寄信通知。
   (預算只會寄信提醒,不會自動停用;真正擋住費用的是第 1~3 步的每天上限)

畫面名稱可能和這裡寫的略有不同,卡住時截圖給開發者看。""".trimIndent()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Google 金鑰教學")
            .setMessage(text)
            .setPositiveButton("設定金鑰") { _, _ -> askMapsKey() }
            .setNegativeButton("關閉", null)
            .create()
        dlg.setOnDismissListener { hideSystemBars() }
        dlg.show()
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
        createDisplay(pendingUrl ?: "about:blank", -1.0)   // 開啟 App 先到首頁(網頁先空白)
        pendingUrl = null
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
        presentation = WebPresentation(this, vd.display, pageW, pageH).also {
            it.appKey = { prefs.getString("mapsKey", "") ?: "" }
            it.onAppCommand = { cmd ->
                when (cmd) {
                    "key" -> askMapsKey()
                    "keyhelp" -> showKeyHelp()
                }
            }
            it.onMapError = { code -> handler.post { showMapError(code) } }
            it.onRendererGone = { url -> handler.post { onWebCrashed(url) } }
            it.onUrlChanged = { url -> checkYouTubeVr(url) }
            it.onFullscreen = { on -> onWebFullscreen(on) }
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
        srcH = QUALITY_W[q]
        updatePageSize()
        recreateWeb()
    }

    /** 重建虛擬螢幕與網頁(換畫質 / 瀏覽器身分),回到目前網頁與播放位置 */
    private fun recreateWeb() {
        val p = presentation ?: return recreateWeb("about:blank", -1.0)
        // 先問網頁目前播放到哪裡,重新載入後跳回去
        p.videoStatus { st -> recreateWeb(p.currentUrl ?: "about:blank", st?.current ?: -1.0) }
    }

    /** 網頁被系統關掉(記憶體不足):重建網頁回到原本的網址,App 不閃退 */
    private var lastCrashAt = 0L
    private fun onWebCrashed(url: String?) {
        val now = SystemClock.uptimeMillis()
        val again = now - lastCrashAt < 60_000
        lastCrashAt = now
        if (vrMode) setVr(false)
        // 一分鐘內又被關掉:先停在空白頁,避免一直重開
        recreateWeb(if (again) "about:blank" else (url ?: "about:blank"), -1.0)
        val tip = if (quality >= QUALITY_4K) "建議到設定把畫質改成 1440p。" else "可以關掉其他 App 再試。"
        toast("手機記憶體不足,網頁被系統關掉,已重新載入。$tip")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 手機記憶體很緊時提醒(4K 畫質最吃記憶體)
        @Suppress("DEPRECATION")
        if (level == TRIM_MEMORY_RUNNING_CRITICAL && quality >= QUALITY_4K && !memTipShown) {
            memTipShown = true
            toast("手機記憶體快不夠了,建議把畫質改成 1440p")
        }
    }
    private var memTipShown = false

    private fun recreateWeb(url: String, at: Double) {
        val q = quality
        presentation?.release()
        presentation?.dismiss()
        virtualDisplay?.release()
        presentation = null
        virtualDisplay = null
        renderer.setBufferSize(srcW, srcH)
        renderer.useMipmap = q >= QUALITY_4K
        createDisplay(url, at)
    }

    // ---------------- 定時檢查 ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val p = presentation
            if (p != null) {
                if (screen == SCREEN_FLIGHT) sendFlightConfig()   // 飛行速度 / 高度(網頁重新載入後也要再給)
                if (!vrMode && screen == SCREEN_WEB) {
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
                    // YouTube 環顧:取整個播放器(畫面由 YouTube 自己轉好)
                    p.videoRect(renderer.mode == VrRenderer.MODE_YT) { r ->
                        renderer.crop = toCrop(r)
                        redraw()
                    }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 網頁回報的影片位置(y 向下)→ 渲染用的 [x, y, 寬, 高](y 向上);找不到影片 = 整頁 */
    private fun toCrop(r: FloatArray?): FloatArray {
        if (r == null) return floatArrayOf(0f, 0f, 1f, 1f)
        val x = r[0].coerceIn(0f, 1f)
        val y = r[1].coerceIn(0f, 1f)
        val w = r[2].coerceIn(0.05f, 1f - x)
        val h = r[3].coerceIn(0.05f, 1f - y)
        return floatArrayOf(x, 1f - y - h, w, h)
    }

    // ---------------- 模式切換 ----------------

    private fun setVr(on: Boolean) {
        vrMode = on
        renderer.vrMode = on
        renderer.showCursor = false
        setMenu(false)
        // VR 一律橫式;離開 VR 後跟著手機方向
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        updatePageSize()
        updateTopBar()
        menu.buttons = if (screen == SCREEN_FLIGHT) VrMenu.FLIGHT_BUTTONS else VrMenu.VIDEO_BUTTONS
        sendFlightConfig()   // 空中遊覽:告訴網頁現在是 VR(轉頭看)還是 2D(手勢)
        menu.infoText = null
        reverseLandscape = displayRotation() == Surface.ROTATION_270
        needLevelRef = true
        recenterAt = 0L
        renderer.showHud = false
        hideKeyboard()
        hideSystemBars()
        if (!on) presentation?.let { p ->
            when {
                // 一般影片在全螢幕中:離開全螢幕、停在目前的時間點(和 VR 播放器一樣回到網頁)
                screen == SCREEN_WEB && p.isFullscreen -> { p.exitFullscreen(); p.pauseAll() }
            }
        }
        if (!on) pendingMapError?.let { code -> pendingMapError = null; handler.post { showMapError(code) } }
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
        val p = presentation ?: return
        // 空中遊覽:轉頭控制 3D 地圖看的方向(用環顧模式,整頁顯示)
        if (screen == SCREEN_FLIGHT) {
            if (renderer.mode != VrRenderer.MODE_YT) setMode(VrRenderer.MODE_YT)
            handler.postDelayed({ if (vrMode) showMessage("空中遊覽", "轉頭看看", 2500) }, ENTER_DELAY_MS + 300)
            return
        }
        // YouTube 的 360 / VR180 影片 → 環顧(轉頭控制 YouTube 看的方向)
        // 先在背景直接問 YouTube 這部影片的格式(最準);問不到再從網頁裡判斷
        val url = p.currentUrl
        detectByAspect(p)
        // YouTube 的 VR 影片:App 裡只能平面看,提醒按全螢幕會改用 YouTube App
        if (screen == SCREEN_WEB && ytVrVideo) handler.postDelayed({
            if (vrMode) showMessage("VR 影片", "退出 VR 後按全螢幕,改用 YouTube App", 3500)
        }, ENTER_DELAY_MS + 300)
    }

    // ---------------- YouTube 影片格式 ----------------

    private val projCache = HashMap<String, String>()

    /** 網址中的 YouTube 影片編號;不是影片頁回傳 null */
    private fun youtubeId(url: String?): String? {
        if (!WebPresentation.isYouTube(url)) return null
        val u = Uri.parse(url)
        u.getQueryParameter("v")?.let { return it }
        val seg = u.pathSegments
        if (u.host == "youtu.be") return seg.firstOrNull()
        if (seg.size >= 2 && seg[0] in listOf("shorts", "embed", "live")) return seg[1]
        return null
    }

    /**
     * 背景向 YouTube 查影片格式:"MESH" = VR180、"EQUIRECTANGULAR" = 360、"" = 一般影片;
     * 查不到(沒網路等)回傳 null。結果會記住,同一部影片只查一次。
     */
    private fun youtubeProjection(url: String?, cb: (String?) -> Unit) {
        val id = youtubeId(url) ?: return cb("")
        projCache[id]?.let { return cb(it) }
        Thread {
            val r = try {
                val c = java.net.URL("https://www.youtube.com/watch?v=" + Uri.encode(id) + "&hl=en")
                    .openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                c.setRequestProperty("Accept-Language", "en")
                c.connectTimeout = 10000
                c.readTimeout = 15000
                val html = c.inputStream.bufferedReader().use { it.readText() }
                if (!html.contains("\"streamingData\"")) null   // 沒拿到播放資料,改用網頁判斷
                else Regex("\"projectionType\":\"(\\w+)\"").findAll(html)
                    .map { it.groupValues[1] }.firstOrNull { it != "RECTANGULAR" } ?: ""
            } catch (e: Exception) { null }
            handler.post {
                if (r != null) projCache[id] = r
                cb(r)
            }
        }.start()
    }

    /**
     * 網址換了:如果是 YouTube 的 360 / VR180 影片,網址列出現「VR 播放器」按鈕(自己按才開)。
     * 全螢幕不再自動轉向或切換,和一般瀏覽器一樣。
     */
    private fun checkYouTubeVr(url: String?) {
        val id = youtubeId(url)
        if (id == null) {
            ytVrVideo = false
            return
        }
        fun apply(proj: String?) {
            if (youtubeId(presentation?.currentUrl) != id) return   // 已經換到別的網頁
            val vr = !proj.isNullOrEmpty()
            if (vr == ytVrVideo) return
            ytVrVideo = vr
            if (vr && screen == SCREEN_WEB && !vrMode) toast("這是 VR 影片:按全螢幕會改用 YouTube App 播放")
        }
        // 先在背景問 YouTube(最準);問不到(沒網路等)等網頁載入後從網頁裡判斷
        youtubeProjection(url) { proj ->
            if (proj != null) apply(proj)
            else handler.postDelayed({ presentation?.youtubeSpherical { apply(it) } }, 3000)
        }
    }

    /**
     * 網頁影片按全螢幕:YouTube 的 360 / VR180 影片改用 YouTube App 播放
     * (App 裡的 YouTube 網頁播放器轉動範圍受限);其他影片照一般瀏覽器全螢幕
     */
    private fun onWebFullscreen(on: Boolean) {
        val p = presentation ?: return
        if (!on || vrMode || screen != SCREEN_WEB) return
        val url = p.currentUrl
        val id = youtubeId(url) ?: return
        youtubeProjection(url) { proj ->
            if (proj.isNullOrEmpty() || !p.isFullscreen || vrMode || youtubeId(p.currentUrl) != id) return@youtubeProjection
            // 剛交給 YouTube App 又馬上觸發(例如回來時網頁還在全螢幕)就不重複開
            val now = SystemClock.uptimeMillis()
            if (now - lastYtAppAt < 3000) return@youtubeProjection
            lastYtAppAt = now
            openInYouTubeApp(p, id)
        }
    }

    private var lastYtAppAt = 0L

    /**
     * 用 YouTube App 從目前的時間點全螢幕播放(App 裡的影片離開全螢幕並暫停)。
     * 也請 YouTube App 播完就關閉,回到這個 App(要看 YouTube App 的版本支不支援)
     */
    private fun openInYouTubeApp(p: WebPresentation, id: String) {
        p.videoStatus { st ->
            val t = (st?.current ?: 0.0).toInt()
            val uri = Uri.parse("https://www.youtube.com/watch?v=" + Uri.encode(id) + if (t > 0) "&t=${t}s" else "")
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")
                .putExtra("force_fullscreen", true)
                .putExtra("finish_on_ended", true)
            // 用「等結果」的方式開啟:YouTube App 會開在這個 App 的同一疊畫面裡,關閉後回到這裡
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_YOUTUBE)
            } catch (e: Exception) {
                toast("找不到 YouTube App")
                return@videoStatus
            }
            // 網頁離開全螢幕(請網頁自己離開,之後還能再全螢幕)並暫停
            p.exitFullscreen()
            p.pauseAll()
        }
    }

    private fun detectByAspect(p: WebPresentation) {
        p.videoSize { w, h ->
            if (w <= 0 || h <= 0) return@videoSize
            val r = w.toFloat() / h
            val m = when {
                r in 1.85f..2.15f -> VrRenderer.MODE_VR180
                r in 0.9f..1.1f -> VrRenderer.MODE_TB360
                VrRenderer.isSphere(renderer.mode) || renderer.mode == VrRenderer.MODE_YT -> VrRenderer.MODE_2D
                else -> renderer.mode
            }
            if (m != renderer.mode) setMode(m)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_90
        else windowManager.defaultDisplay.rotation
    } catch (e: Exception) {
        // 少數裝置/環境拿不到 display 時,不要讓 App 閃退
        try { windowManager.defaultDisplay.rotation } catch (e2: Exception) { Surface.ROTATION_90 }
    }

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
                c.x = (c.x - r[0]) / r[2] * pageW
                c.y = (c.y - r[1]) / r[3] * pageH
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
            subScreen -> goHome()                       // 設定 / 我的影片 → 首頁
            screen != SCREEN_HOME -> navBack()
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

        if (VrRenderer.isSphere(renderer.mode) || renderer.isCurved()) {
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
        // 面板打開時畫面不動,紅點才不會因為網頁忙著轉畫面而亂飄
        if (renderer.mode == VrRenderer.MODE_YT && !menuOpen) youtubeLook(az, down)

        // ---- 控制面板:坐姿低頭打開、躺平抬頭打開,回到正前方附近就關閉 ----
        val lying = VrMenu.lying
        val tilt = if (lying) -down else down
        val sphere = VrRenderer.isSphere(renderer.mode)
        val yt = renderer.mode == VrRenderer.MODE_YT
        val openAt = if (!lying) SIT_OPEN else if (yt) LIE_OPEN_YT else if (sphere) LIE_OPEN else LIE_OPEN_FLAT
        val closeAt = if (!lying) SIT_CLOSE else if (yt) LIE_CLOSE_YT else if (sphere) LIE_CLOSE else LIE_CLOSE_FLAT
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

    // 環顧(空中遊覽):把頭的方向傳給網頁(最多約每秒 30 次,沒變就不送)
    private var lastYtAt = 0L
    private var lastYtYaw = 999f
    private var lastYtPitch = 999f

    private fun youtubeLook(az: Float, down: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastYtAt < 33) return
        // 空中遊覽:頭轉的角度 × 轉頭比例 = 地圖看的方向
        val right = Math.toDegrees(az.toDouble()).toFloat() * flyGain
        val up = (-Math.toDegrees(down.toDouble()).toFloat() * flyGain).coerceIn(-89f, 89f)
        if (abs(right - lastYtYaw) < 0.2f && abs(up - lastYtPitch) < 0.2f) return
        lastYtAt = now
        lastYtYaw = right
        lastYtPitch = up
        presentation?.lookAt(right, up)
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
            // 空中遊覽
            VrMenu.BTN_FLY_PAUSE -> { p.run("window.vrFlight&&vrFlight('pause')"); menu.flyPaused = !menu.flyPaused }
            VrMenu.BTN_FLY_RESTART -> p.run("window.vrFlight&&vrFlight('restart')")
            VrMenu.BTN_FLY_NEXT -> p.run("window.vrFlight&&vrFlight('next')")
            VrMenu.BTN_FLY_LAND -> p.run("window.vrFlight&&vrFlight('land')")
            VrMenu.BTN_WALK_FWD -> { p.run("window.vrFlight&&vrFlight('fwd')"); pollStatus() }
            VrMenu.BTN_WALK_BACK -> { p.run("window.vrFlight&&vrFlight('turn')"); pollStatus() }
            VrMenu.BTN_WALK_AUTO -> { p.run("window.vrFlight&&vrFlight('auto')"); menu.walking = !menu.walking }
            VrMenu.BTN_WALK_SLOWER -> { p.run("window.vrFlight&&vrFlight('slower')"); pollStatus() }
            VrMenu.BTN_WALK_FASTER -> { p.run("window.vrFlight&&vrFlight('faster')"); pollStatus() }
            VrMenu.BTN_WALK_HOME -> p.run("window.vrFlight&&vrFlight('home')")
            VrMenu.BTN_FLY_SLOWER -> bumpFlight("speed", -1)
            VrMenu.BTN_FLY_FASTER -> bumpFlight("speed", 1)
            VrMenu.BTN_FLY_LOWER -> bumpFlight("alt", -1)
            VrMenu.BTN_FLY_HIGHER -> bumpFlight("alt", 1)
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
                    setMode(VrMenu.MODE_ORDER[target - VrMenu.OPTION])   // 套用這個模式自己的設定
                    menu.popup = VrMenu.NONE
                }
            }
        }
        handler.postDelayed({ pollStatus() }, 300)
        redrawPanel(force = true)
    }

    // ---------------- 畫面中央提示 ----------------

    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = VrRenderer.HUD * 0.17f
        isFakeBoldText = true
        color = 0xFFFFFFFF.toInt()
    }

    private val hideMsg = Runnable { hideMessage() }

    /** 畫面正中央的提示(兩行);hideAfter > 0 時自動消失 */
    private fun showMessage(line1: String, line2: String, hideAfter: Long = 0L) {
        handler.removeCallbacks(hideMsg)
        renderer.updateHud { b ->
            b.eraseColor(0)
            val c = Canvas(b)
            val h = VrRenderer.HUD.toFloat()
            msgPaint.color = 0xCC000000.toInt()
            c.drawCircle(h / 2, h / 2, h / 2 - 4, msgPaint)
            msgPaint.color = 0xFFFFFFFF.toInt()
            if (line2.isEmpty()) c.drawText(line1, h / 2, h / 2 + msgPaint.textSize * 0.35f, msgPaint)
            else {
                c.drawText(line1, h / 2, h / 2 - msgPaint.textSize * 0.25f, msgPaint)
                c.drawText(line2, h / 2, h / 2 + msgPaint.textSize * 0.95f, msgPaint)
            }
        }
        renderer.showHud = true
        if (hideAfter > 0) handler.postDelayed(hideMsg, hideAfter)
    }

    private fun hideMessage() {
        handler.removeCallbacks(hideMsg)
        if (recenterAt == 0L) renderer.showHud = false
        redraw()
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
        if (screen == SCREEN_FLIGHT) {
            presentation?.run("(window.vrFlightState?vrFlightState():null)") { r ->
                val o = try { if (r == null || r == "null") null else org.json.JSONObject(r) } catch (e: Exception) { null }
                menu.hasVideo = false
                menu.flyPaused = o?.optBoolean("paused") == true
                menu.flyLanded = o?.optBoolean("landed") == true
                menu.walking = o?.optBoolean("walking") == true
                // 降落到街景:面板換成散步用的按鈕;起飛後換回飛行按鈕
                val want = if (menu.flyLanded) VrMenu.WALK_BUTTONS else VrMenu.FLIGHT_BUTTONS
                if (!menu.buttons.contentEquals(want)) { menu.buttons = want; menu.hover = VrMenu.NONE }
                menu.infoText = if (o == null) "空中遊覽:請先在 2D 畫面選擇目的地" else if (menu.flyLanded)
                    "街景  " + o.optString("road").ifEmpty { o.optString("name") } +
                        (if (menu.walking) "  自動散步中" else "") + "  每 ${o.optInt("walkSec", 4)} 秒一步"
                else
                    "空中遊覽  " + o.optString("name") + when {
                        menu.flyLanded -> "  已降落(街景)"
                        o.optBoolean("arrived") -> "  已抵達"
                        else -> ""
                    } + (if (menu.flyPaused && !menu.flyLanded) "  已暫停" else "") +
                    "  速度 %.2f倍  高度 %.1f倍".format(flySpeed, flyAlt)
                redrawPanel(force = true)
            }
            return
        }
        menu.infoText = null
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
        if (screen == SCREEN_WEB) saveWebUrl()
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

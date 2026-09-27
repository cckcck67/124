package com.cck.vrweb

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
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
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener {

    companion object {
        // 網頁在虛擬螢幕上的解析度(16:9)。DPI 240 = 1.5 倍縮放,手機版網頁排版。
        const val SRC_W = 1280
        const val SRC_H = 720
        const val SRC_DPI = 240

        // 低頭控制面板(單位:弧度,以水平線為準)
        const val MENU_OPEN = 0.52f      // 低頭約 30 度打開
        const val MENU_CLOSE = 0.42f     // 抬頭回到約 24 度內關閉
        const val MENU_Y_RANGE = 0.36f   // 面板最上緣到最下緣 = 低頭約 21 度
        // 剛打開時(低頭 MENU_OPEN)游標正好在最上排空位的中間
        val MENU_Y_START = MENU_OPEN - (VrMenu.START_Y - VrMenu.Y0) / (VrMenu.Y1 - VrMenu.Y0) * MENU_Y_RANGE
        const val OPEN_GRACE_MS = 500L   // 面板剛打開的這段時間不觸發任何按鈕
        const val MENU_YAW_RANGE = 0.75f // 面板左端到右端 = 轉頭約 43 度
        const val SMOOTH = 0.2f          // 游標平滑(越小越穩)
        const val DWELL_MS = 1200L       // 看著按鈕多久觸發
        const val DWELL_BAR_MS = 1500L   // 看著進度條同一點多久跳轉
        const val REPEAT_MS = 500L       // 音量鍵持續看著時的重複間隔
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var gesture: GestureDetector

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private var vrMode = false
    private var menuYaw = 0f       // 面板打開時的面向 = 面板正中間
    private var basePitch = 0f     // 0 = 水平線;長按螢幕可改成目前角度(躺著看時)
    private var recenterPitch = false

    // 低頭控制面板
    private val menu = VrMenu()
    private var menuOpen = false
    private var menuOpenedAt = 0L
    private var hoverStart = 0L
    private var hoverAnchorX = 0f
    private var dwellDone = false
    private var lastPanelDraw = 0L
    private var lastStatusPoll = 0L

    // 2D 模式點擊偵測(用來判斷是否點到網頁文字框)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var inputDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vr", MODE_PRIVATE)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        renderer = VrRenderer(SRC_W, SRC_H) { st -> handler.post { attachSurface(st) } }
        renderer.ipd = prefs.getFloat("ipd2", defaultIpd())
        renderer.distortion = prefs.getFloat("k", 0.15f)
        renderer.tilt = prefs.getFloat("tilt", 0f)
        renderer.gap = prefs.getFloat("gap", 0f)
        renderer.lift = prefs.getFloat("lift", 0.05f)
        renderer.stereo = prefs.getInt("stereo", 0)
        menu.stereo = renderer.stereo
        renderer.zoom = prefs.getFloat("zoom", 1.4f)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        buildUi()
        setupGestures()
        hideSystemBars()
    }

    // ---------------- 介面 ----------------

    private fun buildUi() {
        val bg = 0xFF1E1E1E.toInt()

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
            addView(button("前往") { go() })
            addView(button("VR") { setVr(true) })
        }

        // 微調滑桿(兩排),數值會記住
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(slider("畫面大小", renderer.zoom, 1f, 2f, "%.2f倍") {
                renderer.zoom = it; save("zoom", it)
            }, weight())
            addView(slider("畫面高低", renderer.lift, -0.2f, 0.2f, "%+.2f") {
                renderer.lift = it; save("lift", it)
            }, weight())
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(slider("左右間距", renderer.ipd, -0.3f, 0.1f, "%+.2f") {
                renderer.ipd = it; save("ipd2", it)
            }, weight())
            addView(slider("中間空隙", renderer.gap, 0f, 0.15f, "%.2f") {
                renderer.gap = it; save("gap", it)
            }, weight())
            // 以度數顯示,內部用弧度
            addView(slider("翻轉角度", Math.toDegrees(renderer.tilt.toDouble()).toFloat(), -10f, 10f, "%+.1f度") {
                renderer.tilt = Math.toRadians(it.toDouble()).toFloat(); save("tilt", renderer.tilt)
            }, weight())
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(row1)
            addView(row2)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(topBar)
            addView(glView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar)
        }
        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun save(key: String, v: Float) = prefs.edit().putFloat(key, v).apply()

    private fun slider(
        label: String, init: Float, lo: Float, hi: Float, format: String,
        onChange: (Float) -> Unit
    ): View {
        val tv = TextView(this).apply {
            text = "$label ${format.format(init)}"
            setTextColor(Color.WHITE)
            setPadding(24, 0, 12, 0)
        }
        val sb = SeekBar(this).apply {
            max = 200
            progress = ((init - lo) / (hi - lo) * 200).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * p / 200f
                    tv.text = "$label ${format.format(v)}"
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv)
            addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
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
        virtualDisplay?.let { it.surface = surface; return }

        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // 私有虛擬螢幕:只顯示本 App 內容,不需要螢幕錄製權限
        val vd = dm.createVirtualDisplay(
            "vr-web", SRC_W, SRC_H, SRC_DPI, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = vd
        presentation = WebPresentation(this, vd.display).also {
            it.show()
            it.loadUrl(prefs.getString("url", "https://m.youtube.com")!!)
        }
    }

    // ---------------- 模式切換 ----------------

    private fun setVr(on: Boolean) {
        vrMode = on
        renderer.vrMode = on
        renderer.showCursor = false
        setMenu(false)
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        bottomBar.visibility = if (on) View.GONE else View.VISIBLE
        hideKeyboard()
        hideSystemBars()
    }

    // ---------------- 觸控 ----------------

    private fun setupGestures() {
        // VR 模式:點一下 = 在游標處點擊;點兩下 = 游標處雙擊;長按 = 游標重新置中
        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSelect(); return true }
            override fun onDoubleTap(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) { recenterPitch = true }
        })

        glView.setOnTouchListener { _, ev ->
            if (vrMode) gesture.onTouchEvent(ev) else forward2D(ev)
            true
        }
    }

    /** 2D 模式:把手指座標換算成虛擬螢幕座標,直接操作網頁 */
    private fun forward2D(ev: MotionEvent) {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return
        val r = renderer.fitRect(glView.width.toFloat(), glView.height.toFloat())
        val x = (ev.x - r[0]) / r[2] * SRC_W
        val y = (ev.y - r[1]) / r[3] * SRC_H
        val e = MotionEvent.obtain(ev.downTime, ev.eventTime, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        presentation?.dispatchTouch(e)
        e.recycle()

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
            else -> presentation?.togglePlay()   // 沒開面板時點一下 = 播放/暫停
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

    // ---------------- 頭部追蹤游標 ----------------

    override fun onSensorChanged(event: SensorEvent) {
        if (!vrMode) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // 手機背面朝前 = 裝置 -Z 軸就是視線方向
        val fx = -r[2]; val fy = -r[5]; val fz = -r[8]
        val yaw = atan2(fx, fy)
        val pitch = asin(fz.coerceIn(-1f, 1f))
        if (recenterPitch) { basePitch = pitch; recenterPitch = false }
        val down = basePitch - pitch   // 低頭角度

        // 低頭打開控制面板,抬頭關閉
        if (!menuOpen && down > MENU_OPEN) { menuYaw = yaw; setMenu(true) }
        else if (menuOpen && down < MENU_CLOSE) setMenu(false)
        if (!menuOpen) return

        var dYaw = yaw - menuYaw
        if (dYaw > PI) dYaw -= (2 * PI).toFloat()
        if (dYaw < -PI) dYaw += (2 * PI).toFloat()
        val tx = (0.5f + dYaw / MENU_YAW_RANGE * (VrMenu.X1 - VrMenu.X0)).coerceIn(VrMenu.X0, VrMenu.X1)
        val ty = (VrMenu.Y0 + (down - MENU_Y_START) / MENU_Y_RANGE * (VrMenu.Y1 - VrMenu.Y0))
            .coerceIn(VrMenu.Y0, VrMenu.Y1)
        renderer.cursorX += (tx - renderer.cursorX) * SMOOTH
        renderer.cursorY += (ty - renderer.cursorY) * SMOOTH
        updateMenu(SystemClock.uptimeMillis())
    }

    // ---------------- 低頭控制面板 ----------------

    private fun setMenu(open: Boolean) {
        if (menuOpen == open && renderer.showPanel == open) return
        menuOpen = open
        renderer.showPanel = open
        renderer.showCursor = open   // 紅點只跟著面板出現
        renderer.cursorX = 0.5f
        renderer.cursorY = VrMenu.START_Y   // 從最上排空位開始,不會直接落在按鈕上
        menuOpenedAt = SystemClock.uptimeMillis()
        menu.hover = VrMenu.NONE
        menu.dwell = 0f
        dwellDone = false
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
            VrMenu.BTN_BACK -> p.seekBy(-10)
            VrMenu.BTN_PLAY -> p.togglePlay()
            VrMenu.BTN_FWD -> p.seekBy(10)
            VrMenu.BTN_VOL_DOWN -> changeVolume(AudioManager.ADJUST_LOWER)
            VrMenu.BTN_VOL_UP -> changeVolume(AudioManager.ADJUST_RAISE)
            VrMenu.BTN_STEREO -> {
                val m = (renderer.stereo + 1) % VrMenu.STEREO_NAMES.size
                renderer.stereo = m
                menu.stereo = m
                prefs.edit().putInt("stereo", m).apply()
            }
        }
        handler.postDelayed({ pollStatus() }, 300)
        redrawPanel(force = true)
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
        s?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        presentation?.currentUrl?.let { prefs.edit().putString("url", it).apply() }
        sensorManager.unregisterListener(this)
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

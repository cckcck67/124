package com.cck.vrweb

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener {

    companion object {
        // 網頁在虛擬螢幕上的解析度(16:9)。DPI 240 = 1.5 倍縮放,手機版網頁排版。
        const val SRC_W = 1280
        const val SRC_H = 720
        const val SRC_DPI = 240
        const val GAZE_FOV = 1.0f   // 頭轉約 57 度 = 游標從畫面一端到另一端
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
    private var baseYaw = Float.NaN
    private var basePitch = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vr", MODE_PRIVATE)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        renderer = VrRenderer(SRC_W, SRC_H) { st -> handler.post { attachSurface(st) } }
        renderer.ipd = prefs.getFloat("ipd", 0f)
        renderer.distortion = prefs.getFloat("k", 0.15f)

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
            setOnEditorActionListener { _, _, _ -> go(); true }
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

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            addView(slider("眼距", renderer.ipd, -0.15f, 0.15f) {
                renderer.ipd = it; prefs.edit().putFloat("ipd", it).apply()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(slider("鏡片校正", renderer.distortion, 0f, 0.5f) {
                renderer.distortion = it; prefs.edit().putFloat("k", it).apply()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun slider(label: String, init: Float, lo: Float, hi: Float, onChange: (Float) -> Unit): View {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(24, 0, 12, 0)
        }
        val sb = SeekBar(this).apply {
            max = 100
            progress = ((init - lo) / (hi - lo) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(lo + (hi - lo) * p / 100f)
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

    private fun go() {
        val t = urlInput.text.toString().trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("yt ") -> "https://m.youtube.com/results?search_query=" + Uri.encode(t.substring(3))
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        presentation?.loadUrl(url)
        hideKeyboard()
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
        renderer.showCursor = on
        renderer.cursorX = 0.5f
        renderer.cursorY = 0.5f
        baseYaw = Float.NaN  // 下一筆感測器資料當作正前方
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
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tapAtCursor(1); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { tapAtCursor(2); return true }
            override fun onLongPress(e: MotionEvent) { baseYaw = Float.NaN }
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
    }

    private fun tapAtCursor(count: Int) {
        presentation?.tap(renderer.cursorX * SRC_W, renderer.cursorY * SRC_H, count)
    }

    // ---------------- 藍牙搖桿 / 遙控器 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!vrMode) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                presentation?.tap(SRC_W * 0.2f, SRC_H * 0.5f, 2)   // 左側雙擊 = 倒退
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                presentation?.tap(SRC_W * 0.8f, SRC_H * 0.5f, 2)   // 右側雙擊 = 快進
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                tapAtCursor(1)
            KeyEvent.KEYCODE_DPAD_UP ->
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
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
        if (baseYaw.isNaN()) { baseYaw = yaw; basePitch = pitch }

        var dYaw = yaw - baseYaw
        if (dYaw > PI) dYaw -= (2 * PI).toFloat()
        if (dYaw < -PI) dYaw += (2 * PI).toFloat()
        val dPitch = pitch - basePitch

        val fovV = GAZE_FOV * SRC_H / SRC_W
        val tx = (0.5f + dYaw / GAZE_FOV).coerceIn(0f, 1f)
        val ty = (0.5f - dPitch / fovV).coerceIn(0f, 1f)
        renderer.cursorX += (tx - renderer.cursorX) * 0.5f   // 輕微平滑,減少抖動
        renderer.cursorY += (ty - renderer.cursorY) * 0.5f
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

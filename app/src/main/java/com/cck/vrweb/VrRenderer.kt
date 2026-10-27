package com.cck.vrweb

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * 把虛擬螢幕(網頁)的畫面當成 OES 材質畫出來。
 * 「有變化才重畫」:新影片畫面、面板 / 數字更新時呼叫 onDirty,由 GLSurfaceView 排一次重畫。
 * 4K 畫質時先把 OES 材質複製到一張有 Mipmap 的普通材質,縮小顯示才不會閃爍。
 * - 非 VR:整頁畫一份
 * - VR 平面模式(2D / 左右3D):左右眼各畫一份平面畫面
 * - VR180 / 上下VR 模式:把攤平的球面影片還原成球面,依頭部方向顯示(真正的虛擬實境)
 * VR 模式另外疊一層:游標 + 控制面板。
 */
class VrRenderer(
    private var srcW: Int,
    private var srcH: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        const val MODE_2D = 0
        const val MODE_SBS = 1       // 左右3D(平面)
        const val MODE_VR180 = 2     // 左右3D 半球影片(左半給左眼)
        const val MODE_TB360 = 3     // 上下3D 全景影片(上半給左眼,每半是 360 度攤平圖)
        const val MODE_YT = 4        // 環顧:網頁(YouTube VR 影片、空中遊覽)依頭的方向轉好單眼畫面,兩眼看同一張
        const val MODE_CURVE = 5     // 2D曲面:2D 影片貼在圍著自己的弧形巨幕上

        /** 需要依頭部方向顯示的球面模式 */
        fun isSphere(m: Int) = m == MODE_VR180 || m == MODE_TB360

        const val HUD = 256          // 倒數數字點陣圖大小
        private const val HUD_H = 0.3f   // 倒數數字高度(佔面板層高度比例)

        // 影片用:可沿垂直軸翻轉(透視),uTilt > 0 時畫面右半往後倒
        private const val VIDEO_VS = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform float uTilt;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos.x * cos(uTilt), aPos.y, 0.0, 1.0 + aPos.x * sin(uTilt) * 0.6);
            }
        """

        private const val VS = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // 影片材質的兩種來源:OES(網頁畫面直接用)或普通 2D 材質(4K 時複製出來、有 Mipmap)
        private const val OES_HEAD = """
            #extension GL_OES_EGL_image_external : require
            #define SAMPLER samplerExternalOES
            #define SAMPLE(p) texture2D(uTex, (uSt * vec4(p, 0.0, 1.0)).xy)
        """
        private const val TEX2D_HEAD = """
            #define SAMPLER sampler2D
            #define SAMPLE(p) texture2D(uTex, p)
        """

        // 平面影片:鏡片校正 + 取來源的某一塊(uSrc = 縮放 xy、位移 zw)
        private const val FS = """
            precision mediump float;
            uniform SAMPLER uTex;
            uniform mat4 uSt;
            uniform float uK;
            uniform vec4 uSrc;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv - 0.5;
                vec2 uv = 0.5 + c * (1.0 + uK * dot(c, c));
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                uv = uv * uSrc.xy + uSrc.zw;
                gl_FragColor = SAMPLE(uv);
            }
        """

        // 把 OES 畫面原樣複製到 2D 材質(4K Mipmap 用)
        private const val BLIT_FS = """
            precision mediump float;
            uniform SAMPLER uTex;
            uniform mat4 uSt;
            varying vec2 vUv;
            void main() {
                gl_FragColor = SAMPLE(vUv);
            }
        """

        // 球面影片:每個像素算出視線方向 → 經緯度 → 影片上的位置
        // uLon = 經度換算比例(180 度影片 = 1/π,360 度影片 = 1/2π)
        private const val FS_180 = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform SAMPLER uTex;
            uniform mat4 uSt;
            uniform mat3 uHead;
            uniform vec2 uTan;
            uniform float uK;
            uniform vec4 uSrc;
            uniform float uLon;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv * 2.0 - 1.0;
                c *= 1.0 + uK * dot(c, c) * 0.25;
                vec3 d = uHead * vec3(c.x * uTan.x, c.y * uTan.y, 1.0);
                float lon = atan(d.x, d.z);
                float lat = atan(d.y, length(d.xz));
                vec2 uv = vec2(lon * uLon + 0.5, lat / 3.14159265 + 0.5);
                if (uv.x < 0.0 || uv.x > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                uv = uv * uSrc.xy + uSrc.zw;
                gl_FragColor = SAMPLE(uv);
            }
        """

        // 曲面巨幕:影片貼在圓弧螢幕上(半徑 1 的圓柱,固定在空間中,轉頭看左右)
        // uArc = 螢幕的弧度(從圓心看張開的角度)、uH = 螢幕高度、uLift = 螢幕往上移
        // uZo = 自己站的位置(0 = 圓心,越大越靠近螢幕、螢幕看起來越大)
        private const val FS_CURVE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform SAMPLER uTex;
            uniform mat4 uSt;
            uniform mat3 uHead;
            uniform vec2 uTan;
            uniform float uK;
            uniform vec4 uSrc;
            uniform float uArc;
            uniform float uH;
            uniform float uLift;
            uniform float uZo;
            uniform float uDome;
            uniform float uEdge;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv * 2.0 - 1.0;
                c *= 1.0 + uK * dot(c, c) * 0.25;
                vec3 d = uHead * vec3(c.x * uTan.x, c.y * uTan.y, 1.0);
                float cc = uZo * uZo - 1.0;
                float b = 2.0 * uZo * d.z;
                // 圓柱螢幕:從自己的位置 (0, 0, uZo) 沿視線找到螢幕上的點
                vec2 pc = vec2(0.0, 0.0);
                float a = d.x * d.x + d.z * d.z;
                if (a > 1e-6) {
                    float t = (-b + sqrt(b * b - 4.0 * a * cc)) / (2.0 * a);
                    pc = vec2(atan(t * d.x, uZo + t * d.z), t * d.y);
                } else {
                    pc = vec2(0.0, d.y > 0.0 ? 1e3 : -1e3);
                }
                // 上下彎曲:改用球形螢幕(上下也往自己彎),依比例混合
                if (uDome > 0.0) {
                    float a2 = dot(d, d);
                    float t2 = (-b + sqrt(b * b - 4.0 * a2 * cc)) / (2.0 * a2);
                    vec3 p = vec3(t2 * d.x, t2 * d.y, uZo + t2 * d.z);
                    vec2 ps = vec2(atan(p.x, p.z), asin(clamp(p.y, -1.0, 1.0)));
                    pc = mix(pc, ps, uDome);
                }
                // 邊緣拉伸:中間維持比例,越往兩邊拉得越寬
                float sx = clamp(pc.x / uArc * 2.0, -2.0, 2.0);
                float fx = (1.0 + uEdge) * sx - uEdge * sx * sx * sx;
                vec2 uv = vec2(abs(sx) > 1.0 ? -1.0 : fx * 0.5 + 0.5, (pc.y - uLift) / uH + 0.5);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                uv = uv * uSrc.xy + uSrc.zw;
                gl_FragColor = SAMPLE(uv);
            }
        """

        // 游標 + 面板,套用相同的鏡片校正,輸出預乘 alpha
        private const val OVERLAY_FS = """
            precision mediump float;
            uniform sampler2D uPanel;
            uniform float uK;
            uniform vec4 uRect;
            uniform float uShowPanel;
            uniform vec2 uCursor;
            uniform float uShowCursor;
            uniform float uAspect;
            uniform sampler2D uHud;
            uniform vec4 uHudRect;
            uniform float uShowHud;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv - 0.5;
                vec2 uv = 0.5 + c * (1.0 + uK * dot(c, c));
                vec4 col = vec4(0.0);
                if (uShowHud > 0.5 && uv.x >= uHudRect.x && uv.x <= uHudRect.z && uv.y >= uHudRect.y && uv.y <= uHudRect.w) {
                    vec2 p = (uv - uHudRect.xy) / (uHudRect.zw - uHudRect.xy);
                    col = texture2D(uHud, vec2(p.x, 1.0 - p.y));
                }
                if (uShowPanel > 0.5 && uv.x >= uRect.x && uv.x <= uRect.z && uv.y >= uRect.y && uv.y <= uRect.w) {
                    vec2 p = (uv - uRect.xy) / (uRect.zw - uRect.xy);
                    vec4 pc = texture2D(uPanel, vec2(p.x, 1.0 - p.y));
                    col = pc + col * (1.0 - pc.a);
                }
                vec2 d = uv - uCursor;
                d.x *= uAspect;
                float len = length(d);
                if (uShowCursor > 0.5 && len < 0.012) {
                    col = len < 0.007 ? vec4(1.0, 0.25, 0.25, 1.0) : vec4(1.0);
                }
                gl_FragColor = col;
            }
        """
    }

    @Volatile var vrMode = false
    @Volatile var mode = MODE_2D
    @Volatile var ipd = 0f          // 左右間距偏移(佔單眼寬度比例,負 = 往中間靠)
    @Volatile var zoom = 1.4f       // 平面模式畫面放大倍率(超出單眼範圍的部分裁掉,填滿視野)
    @Volatile var distortion = 0.15f // 桶形校正強度
    // 曲面巨幕(2D曲面模式;左右3D 設定弧度時也用)
    @Volatile var curve = 0f        // 螢幕的弧度(度);左右3D 為 0 = 一般平面畫面(跟著頭)
    @Volatile var curveSize = 1.4f  // 畫面大小:1.4 = 站在圓心,越大越靠近螢幕
    @Volatile var dome = 0f         // 上下彎曲 0~1(0 = 圓柱,1 = 球形)
    @Volatile var edge = 0f         // 邊緣拉伸 0~0.45

    /** 目前是曲面巨幕:畫面固定在空間中,需要頭部方向 */
    fun isCurved() = curve > 0.5f && (mode == MODE_CURVE || mode == MODE_SBS)
    @Volatile var lift = 0.05f      // 畫面往上移(佔高度比例),VR 盒子下緣較看不到
    @Volatile var fovDeg = 90f      // VR180 單眼水平視野(配合 VR 盒子鏡片)
    // 兩眼畫面中間的黑色空隙(佔整個寬度比例),避免看到另一眼畫面的雙影(每個模式各自設定)
    @Volatile var gap = 0.1f
    @Volatile var panelDepth = 0f   // 面板距離:兩眼面板往中間移(正 = 看起來較近,佔單眼寬度比例)
    @Volatile var showHud = false   // 畫面正中央的倒數數字
    @Volatile var showCursor = false
    @Volatile var showPanel = false
    @Volatile var cursorX = 0.5f    // 0~1,原始畫面座標
    @Volatile var cursorY = 0.5f

    /** 影片在網頁中的位置 [x, y, 寬, 高](佔網頁的比例,y 向上);整頁 = [0,0,1,1] */
    @Volatile var crop = floatArrayOf(0f, 0f, 1f, 1f)

    /**
     * 網頁在虛擬螢幕中的大小(像素)。虛擬螢幕是正方形,網頁放在左上角,
     * 直式 / 橫式只要改網頁大小,不用重建虛擬螢幕(網頁不會重新載入)。
     */
    @Volatile var pageW = 16
    @Volatile var pageH = 9

    /** 網頁在材質中的位置 [x, y, 寬, 高](材質比例,y 向上;網頁在虛擬螢幕左上角) */
    private fun pageRect(): FloatArray {
        val w = pageW.toFloat() / srcW
        val h = pageH.toFloat() / srcH
        return floatArrayOf(0f, 1f - h, w, h)
    }

    /** 網頁中的一塊(網頁比例)→ 材質中的位置 */
    private fun toTexture(c: FloatArray): FloatArray {
        val p = pageRect()
        return floatArrayOf(p[0] + c[0] * p[2], p[1] + c[1] * p[3], c[2] * p[2], c[3] * p[3])
    }

    /** 球面模式頭部方向:把「相機座標的視線」轉成「影片座標」的 3x3 矩陣(直行優先) */
    @Volatile var head = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** 需要重畫時呼叫(由 MainActivity 設成 glView.requestRender) */
    @Volatile var onDirty: () -> Unit = {}

    /** 4K 畫質時開啟 Mipmap(手機不支援時自動略過) */
    @Volatile var useMipmap = false

    private var maxTex = 4096

    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var texId = 0
    private var viewW = 1
    private var viewH = 1

    /** 一組著色器程式與它的參數位置 */
    private class Prog(val id: Int) {
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val aUv = GLES20.glGetAttribLocation(id, "aUv")
        val uSt = GLES20.glGetUniformLocation(id, "uSt")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uK = GLES20.glGetUniformLocation(id, "uK")
        val uTilt = GLES20.glGetUniformLocation(id, "uTilt")
        val uSrc = GLES20.glGetUniformLocation(id, "uSrc")
        val uHead = GLES20.glGetUniformLocation(id, "uHead")
        val uTan = GLES20.glGetUniformLocation(id, "uTan")
        val uLon = GLES20.glGetUniformLocation(id, "uLon")
        val uArc = GLES20.glGetUniformLocation(id, "uArc")
        val uH = GLES20.glGetUniformLocation(id, "uH")
        val uLift = GLES20.glGetUniformLocation(id, "uLift")
        val uZo = GLES20.glGetUniformLocation(id, "uZo")
        val uDome = GLES20.glGetUniformLocation(id, "uDome")
        val uEdge = GLES20.glGetUniformLocation(id, "uEdge")
    }

    // [0] = 直接用 OES 材質,[1] = 用 Mipmap 的 2D 材質
    private lateinit var flatProg: Array<Prog>
    private lateinit var sphereProg: Array<Prog>
    private lateinit var curveProg: Array<Prog>
    private lateinit var blitProg: Prog

    // Mipmap 用的複製材質
    private var mipSupported = false
    private var mipTex = 0
    private var fbo = 0
    private var mipW = 0
    private var mipH = 0
    private var mipFresh = false   // 複製材質已有目前畫面

    // 疊加層(游標 + 控制面板)
    private var overlay = 0
    private var panelTex = 0
    private var oPos = 0; private var oUv = 0
    private var oPanel = 0; private var oK = 0; private var oRect = 0; private var oShowPanel = 0
    private var oCursor = 0; private var oShowCursor = 0; private var oAspect = 0
    private var oHud = 0; private var oHudRect = 0; private var oShowHud = 0
    private var hudTex = 0
    private val hudBitmap = Bitmap.createBitmap(HUD, HUD, Bitmap.Config.ARGB_8888)
    private var hudDirty = false

    private val panelLock = Any()
    private val panelBitmap = Bitmap.createBitmap(VrMenu.W, VrMenu.H, Bitmap.Config.ARGB_8888)
    private var panelDirty = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    /** 將網頁等比例塞進 w*h 區域,回傳 [x, y, 寬, 高](置中) */
    fun fitRect(w: Float, h: Float): FloatArray {
        val a = pageW.toFloat() / pageH
        var rw = w
        var rh = w / a
        if (rh > h) { rh = h; rw = h * a }
        return floatArrayOf((w - rw) / 2f, (h - rh) / 2f, rw, rh)
    }

    /** 改變虛擬螢幕解析度(畫質;正方形) */
    fun setBufferSize(w: Int, h: Int) {
        srcW = w
        srcH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
        mipFresh = false
        onDirty()
    }

    /** 在主執行緒畫倒數數字;畫完後下一個畫格上傳成材質 */
    fun updateHud(draw: (Bitmap) -> Unit) {
        synchronized(panelLock) {
            draw(hudBitmap)
            hudDirty = true
        }
        onDirty()
    }

    /** 在主執行緒畫面板;畫完後下一個畫格上傳成材質 */
    fun updatePanel(draw: (Bitmap) -> Unit) {
        synchronized(panelLock) {
            draw(panelBitmap)
            panelDirty = true
        }
        onDirty()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        flatProg = arrayOf(Prog(buildProgram(VIDEO_VS, OES_HEAD + FS)), Prog(buildProgram(VIDEO_VS, TEX2D_HEAD + FS)))
        sphereProg = arrayOf(Prog(buildProgram(VS, OES_HEAD + FS_180)), Prog(buildProgram(VS, TEX2D_HEAD + FS_180)))
        curveProg = arrayOf(Prog(buildProgram(VS, OES_HEAD + FS_CURVE)), Prog(buildProgram(VS, TEX2D_HEAD + FS_CURVE)))
        blitProg = Prog(buildProgram(VS, OES_HEAD + BLIT_FS))

        // 2D 材質的 Mipmap 需要非 2 次方尺寸支援(OpenGL ES 3 以上或對應擴充)
        val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        maxTex = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        mipSupported = (ver.contains("OpenGL ES 3") || ext.contains("GL_OES_texture_npot")) && maxTex >= 3840
        mipTex = 0
        fbo = 0
        mipW = 0
        mipH = 0
        mipFresh = false

        overlay = buildProgram(VS, OVERLAY_FS)
        oPos = GLES20.glGetAttribLocation(overlay, "aPos")
        oUv = GLES20.glGetAttribLocation(overlay, "aUv")
        oPanel = GLES20.glGetUniformLocation(overlay, "uPanel")
        oK = GLES20.glGetUniformLocation(overlay, "uK")
        oRect = GLES20.glGetUniformLocation(overlay, "uRect")
        oShowPanel = GLES20.glGetUniformLocation(overlay, "uShowPanel")
        oCursor = GLES20.glGetUniformLocation(overlay, "uCursor")
        oShowCursor = GLES20.glGetUniformLocation(overlay, "uShowCursor")
        oAspect = GLES20.glGetUniformLocation(overlay, "uAspect")
        oHud = GLES20.glGetUniformLocation(overlay, "uHud")
        oHudRect = GLES20.glGetUniformLocation(overlay, "uHudRect")
        oShowHud = GLES20.glGetUniformLocation(overlay, "uShowHud")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glGenTextures(1, ids, 0)
        panelTex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenTextures(1, ids, 0)
        hudTex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        synchronized(panelLock) { panelDirty = true; hudDirty = true }  // GL 環境重建後要重新上傳
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId).apply {
            setDefaultBufferSize(srcW, srcH)
            setOnFrameAvailableListener { frameAvailable = true; onDirty() }
        }
        surfaceTexture = st
        onSurfaceReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    /** 4K 時把目前網頁畫面複製到 2D 材質並產生 Mipmap */
    private fun updateMipmap(newFrame: Boolean): Boolean {
        if (!useMipmap || !mipSupported || srcW > maxTex || srcH > maxTex) return false
        if (mipTex == 0 || mipW != srcW || mipH != srcH) {
            val ids = IntArray(1)
            if (mipTex == 0) { GLES20.glGenTextures(1, ids, 0); mipTex = ids[0] }
            if (fbo == 0) { GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0] }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mipTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, srcW, srcH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mipTex, 0)
            val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (!ok) { mipSupported = false; return false }
            mipW = srcW
            mipH = srcH
            mipFresh = false
        }
        if (newFrame || !mipFresh) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glViewport(0, 0, srcW, srcH)
            val p = blitProg
            GLES20.glUseProgram(p.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(p.uTex, 0)
            GLES20.glUniformMatrix4fv(p.uSt, 1, false, stMatrix, 0)
            bindQuad(p.aPos, p.aUv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mipTex)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            mipFresh = true
        }
        return true
    }

    /** 這一格是否用 Mipmap 的 2D 材質(否則直接用 OES) */
    private var useMip = false

    private fun bindVideoTexture(p: Prog) {
        if (useMip) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mipTex)
            GLES20.glUniform1i(p.uTex, 3)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(p.uTex, 0)
            GLES20.glUniformMatrix4fv(p.uSt, 1, false, stMatrix, 0)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val newFrame = frameAvailable
        if (newFrame) {
            frameAvailable = false
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
        }
        useMip = updateMipmap(newFrame)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!vrMode) {
            val r = fitRect(viewW.toFloat(), viewH.toFloat())
            GLES20.glViewport(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
            val p = pageRect()
            drawVideo(0f, 0f, p[2], p[3], p[0], p[1])
            return
        }

        synchronized(panelLock) {
            if (panelDirty) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, panelBitmap, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                panelDirty = false
            }
            if (hudDirty) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hudBitmap, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                hudDirty = false
            }
        }

        val eyeW = viewW / 2
        val m = mode
        val halfGap = (gap * viewW / 2f).toInt().coerceIn(0, eyeW / 2)
        // 平面 2D 模式看整頁;其他模式只取網頁中的影片區域
        val c = toTexture(if (m == MODE_2D) floatArrayOf(0f, 0f, 1f, 1f) else crop)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (eye in 0..1) {
            val r = fitRect(eyeW.toFloat(), viewH.toFloat())
            lastRectH = r[3]
            lastViewH = viewH
            val side = if (eye == 0) -1f else 1f
            // ipd < 0:兩眼畫面往中間靠;ipd > 0:往外拉開
            val shift = ipd * eyeW * side
            val cx = eye * eyeW + eyeW / 2f + shift
            val cy = viewH / 2f
            // 中間空隙:左眼裁掉右緣、右眼裁掉左緣
            if (eye == 0) GLES20.glScissor(0, 0, eyeW - halfGap, viewH)
            else GLES20.glScissor(eyeW + halfGap, 0, eyeW - halfGap, viewH)

            // 這隻眼睛取來源的哪一塊(左右3D 取左 / 右半,上下3D 取上 / 下半)
            var sx = c[2]; var sy = c[3]; var ox = c[0]; var oy = c[1]
            if (m == MODE_SBS || m == MODE_VR180) {
                sx *= 0.5f
                if (eye == 1) ox += sx
            } else if (m == MODE_TB360) {
                sy *= 0.5f
                if (eye == 0) oy += sy   // y 向上:上半在 oy + 一半
            }

            if (isSphere(m)) {
                GLES20.glViewport((cx - eyeW / 2f).toInt(), 0, eyeW, viewH)
                val lon = if (m == MODE_TB360) 0.5 / Math.PI else 1.0 / Math.PI
                drawSphere(sx, sy, ox, oy, eyeW.toFloat() / viewH, lon.toFloat())
            } else if (isCurved()) {
                GLES20.glViewport((cx - eyeW / 2f).toInt(), 0, eyeW, viewH)
                drawCurve(sx, sy, ox, oy, eyeW.toFloat() / viewH)
            } else {
                val z = zoom
                val vy = cy + lift * viewH
                GLES20.glViewport(
                    (cx - r[2] * z / 2f).toInt(), (vy - r[3] * z / 2f).toInt(),
                    (r[2] * z).toInt(), (r[3] * z).toInt()
                )
                drawVideo(distortion, 0f, sx, sy, ox, oy)
            }
            // 控制面板與游標不放大、不移動,確保完整看得到;
            // 面板距離:左眼往右、右眼往左移 = 面板看起來較近
            val ocx = cx - side * panelDepth * eyeW
            GLES20.glViewport((ocx - r[2] / 2f).toInt(), (cy - r[3] / 2f).toInt(), r[2].toInt(), r[3].toInt())
            drawOverlay()
        }
    }

    private fun bindQuad(pos: Int, uv: Int) {
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(uv)
    }

    private fun drawVideo(k: Float, tiltRad: Float, sx: Float, sy: Float, ox: Float, oy: Float) {
        val p = flatProg[if (useMip) 1 else 0]
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        bindVideoTexture(p)
        GLES20.glUniform1f(p.uK, k)
        GLES20.glUniform1f(p.uTilt, tiltRad)
        GLES20.glUniform4f(p.uSrc, sx, sy, ox, oy)
        bindQuad(p.aPos, p.aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawSphere(sx: Float, sy: Float, ox: Float, oy: Float, aspect: Float, lonScale: Float) {
        val p = sphereProg[if (useMip) 1 else 0]
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        bindVideoTexture(p)
        GLES20.glUniform1f(p.uK, distortion)
        GLES20.glUniformMatrix3fv(p.uHead, 1, false, head, 0)
        val tx = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        GLES20.glUniform2f(p.uTan, tx, tx / aspect)
        GLES20.glUniform4f(p.uSrc, sx, sy, ox, oy)
        GLES20.glUniform1f(p.uLon, lonScale)
        bindQuad(p.aPos, p.aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // 最近一次畫的單眼範圍高度(像素),紅點換算網頁位置用
    @Volatile private var lastRectH = 1f
    @Volatile private var lastViewH = 1

    /**
     * VR 平面 2D 模式:疊加層(紅點)座標 → 網頁上的位置(0~1,y 向下)。
     * 網頁畫面有放大(zoom)與往上移(lift),紅點不放大不移動,所以要換算。
     */
    fun pageAt(cx: Float, cy: Float): FloatArray {
        val z = zoom.coerceAtLeast(0.1f)
        val liftN = lift * lastViewH / lastRectH.coerceAtLeast(1f)
        return floatArrayOf(0.5f + (cx - 0.5f) / z, 0.5f + (cy - 0.5f + liftN) / z)
    }

    /** 曲面巨幕:螢幕寬度 = 張開的角度,高度依影片比例 */
    private fun drawCurve(sx: Float, sy: Float, ox: Float, oy: Float, aspect: Float) {
        val p = curveProg[if (useMip) 1 else 0]
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        bindVideoTexture(p)
        GLES20.glUniform1f(p.uK, distortion)
        GLES20.glUniformMatrix3fv(p.uHead, 1, false, head, 0)
        val tx = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        GLES20.glUniform2f(p.uTan, tx, tx / aspect)
        GLES20.glUniform4f(p.uSrc, sx, sy, ox, oy)
        val arc = Math.toRadians(curve.toDouble()).toFloat()
        val videoAspect = (sx * srcW) / (sy * srcH).coerceAtLeast(1e-6f)
        val h = arc / videoAspect.coerceAtLeast(0.1f)
        // 邊緣拉伸:螢幕往兩邊加寬(最多 330 度),中間的比例不變
        val e = edge.coerceIn(0f, 0.45f)
        val arcEff = (arc * (1f + e)).coerceAtMost(Math.toRadians(330.0).toFloat())
        GLES20.glUniform1f(p.uArc, arcEff)
        GLES20.glUniform1f(p.uEdge, e)
        GLES20.glUniform1f(p.uDome, dome.coerceIn(0f, 1f))
        GLES20.glUniform1f(p.uH, h)
        GLES20.glUniform1f(p.uLift, lift * h)
        // 畫面大小 = 和螢幕的距離:1.4 倍站在圓心,越大越靠近(螢幕越大、上下黑邊越少、要轉頭才看得到兩端)
        val dist = (1.4f / curveSize).coerceIn(0.3f, 1.8f)
        GLES20.glUniform1f(p.uZo, 1f - dist)
        bindQuad(p.aPos, p.aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOverlay() {
        val panel = showPanel
        val cursor = showCursor
        val hud = showHud
        if (!panel && !cursor && !hud) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)  // 點陣圖是預乘 alpha
        GLES20.glUseProgram(overlay)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTex)
        GLES20.glUniform1i(oPanel, 1)
        GLES20.glUniform1f(oK, distortion)
        // 著色器座標 y 向上
        GLES20.glUniform4f(oRect, VrMenu.X0, 1f - VrMenu.Y1, VrMenu.X1, 1f - VrMenu.Y0)
        GLES20.glUniform1f(oShowPanel, if (panel) 1f else 0f)
        GLES20.glUniform2f(oCursor, cursorX, 1f - cursorY)
        GLES20.glUniform1f(oShowCursor, if (cursor) 1f else 0f)
        GLES20.glUniform1f(oAspect, pageW.toFloat() / pageH)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLES20.glUniform1i(oHud, 2)
        // 倒數數字:正中央的正方形
        val hw = HUD_H * pageH / pageW / 2f
        GLES20.glUniform4f(oHudRect, 0.5f - hw, 0.5f - HUD_H / 2f, 0.5f + hw, 0.5f + HUD_H / 2f)
        GLES20.glUniform1f(oShowHud, if (hud) 1f else 0f)
        bindQuad(oPos, oUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e("VrRenderer", GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}

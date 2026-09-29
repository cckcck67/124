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
 * - 非 VR:整頁畫一份
 * - VR 平面模式(2D / 左右3D):左右眼各畫一份平面畫面
 * - VR180 / 360 模式:把攤平的球面影片還原成球面,依頭部方向顯示(真正的虛擬實境)
 * VR 模式另外疊一層:游標 + 控制面板。
 */
class VrRenderer(
    private val srcW: Int,
    private val srcH: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        const val MODE_2D = 0
        const val MODE_SBS = 1       // 左右3D(平面)
        const val MODE_VR180 = 2     // 左右3D 半球影片(左半給左眼)
        const val MODE_360 = 3       // 360 全景影片(兩眼同畫面)
        const val MODE_360TB = 4     // 360 上下3D(上半給左眼)

        /** 需要依頭部方向顯示的球面模式 */
        fun isSphere(m: Int) = m == MODE_VR180 || m == MODE_360 || m == MODE_360TB

        const val GAP = 0.1f         // 兩眼畫面中間的黑色空隙(佔整個寬度比例),避免看到另一眼畫面的雙影

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

        // 平面影片:鏡片校正 + 取來源的某一塊(uSrc = 縮放 xy、位移 zw)
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
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
                gl_FragColor = texture2D(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy);
            }
        """

        // VR180 / 360:每個像素算出視線方向 → 經緯度 → 影片上的位置
        // uLon = 經度換算比例(180 度影片 = 1/π,360 度影片 = 1/2π)
        private const val FS_180 = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTex;
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
                gl_FragColor = texture2D(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy);
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
            varying vec2 vUv;
            void main() {
                vec2 c = vUv - 0.5;
                vec2 uv = 0.5 + c * (1.0 + uK * dot(c, c));
                vec4 col = vec4(0.0);
                if (uShowPanel > 0.5 && uv.x >= uRect.x && uv.x <= uRect.z && uv.y >= uRect.y && uv.y <= uRect.w) {
                    vec2 p = (uv - uRect.xy) / (uRect.zw - uRect.xy);
                    col = texture2D(uPanel, vec2(p.x, 1.0 - p.y));
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
    @Volatile var tilt = 0f         // 兩眼畫面左右翻轉角度(弧度,左右眼相反方向)
    @Volatile var lift = 0.05f      // 畫面往上移(佔高度比例),VR 盒子下緣較看不到
    @Volatile var fovDeg = 90f      // VR180 / 360 單眼水平視野(配合 VR 盒子鏡片)
    @Volatile var showCursor = false
    @Volatile var showPanel = false
    @Volatile var cursorX = 0.5f    // 0~1,原始畫面座標
    @Volatile var cursorY = 0.5f

    /** 影片在網頁畫面中的位置 [x, y, 寬, 高](比例,y 向上);整頁 = [0,0,1,1] */
    @Volatile var crop = floatArrayOf(0f, 0f, 1f, 1f)

    /** 球面模式頭部方向:把「相機座標的視線」轉成「影片座標」的 3x3 矩陣(直行優先) */
    @Volatile var head = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var texId = 0
    private var viewW = 1
    private var viewH = 1

    // 平面影片
    private var program = 0
    private var aPos = 0; private var aUv = 0
    private var uSt = 0; private var uTex = 0; private var uK = 0
    private var uTilt = 0; private var uSrc = 0

    // VR180
    private var program180 = 0
    private var bPos = 0; private var bUv = 0
    private var bSt = 0; private var bTex = 0; private var bK = 0
    private var bHead = 0; private var bTan = 0; private var bSrc = 0; private var bLon = 0

    // 疊加層(游標 + 控制面板)
    private var overlay = 0
    private var panelTex = 0
    private var oPos = 0; private var oUv = 0
    private var oPanel = 0; private var oK = 0; private var oRect = 0; private var oShowPanel = 0
    private var oCursor = 0; private var oShowCursor = 0; private var oAspect = 0

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

    /** 將原始畫面等比例塞進 w*h 區域,回傳 [x, y, 寬, 高](置中) */
    fun fitRect(w: Float, h: Float): FloatArray {
        val a = srcW.toFloat() / srcH
        var rw = w
        var rh = w / a
        if (rh > h) { rh = h; rw = h * a }
        return floatArrayOf((w - rw) / 2f, (h - rh) / 2f, rw, rh)
    }

    /** 在主執行緒畫面板;畫完後下一個畫格上傳成材質 */
    fun updatePanel(draw: (Bitmap) -> Unit) {
        synchronized(panelLock) {
            draw(panelBitmap)
            panelDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VIDEO_VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uTilt = GLES20.glGetUniformLocation(program, "uTilt")
        uSrc = GLES20.glGetUniformLocation(program, "uSrc")

        program180 = buildProgram(VS, FS_180)
        bPos = GLES20.glGetAttribLocation(program180, "aPos")
        bUv = GLES20.glGetAttribLocation(program180, "aUv")
        bSt = GLES20.glGetUniformLocation(program180, "uSt")
        bTex = GLES20.glGetUniformLocation(program180, "uTex")
        bK = GLES20.glGetUniformLocation(program180, "uK")
        bHead = GLES20.glGetUniformLocation(program180, "uHead")
        bTan = GLES20.glGetUniformLocation(program180, "uTan")
        bSrc = GLES20.glGetUniformLocation(program180, "uSrc")
        bLon = GLES20.glGetUniformLocation(program180, "uLon")

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
        synchronized(panelLock) { panelDirty = true }  // GL 環境重建後要重新上傳
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId).apply {
            setDefaultBufferSize(srcW, srcH)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        surfaceTexture = st
        onSurfaceReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!vrMode) {
            val r = fitRect(viewW.toFloat(), viewH.toFloat())
            GLES20.glViewport(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
            drawVideo(0f, 0f, 1f, 1f, 0f, 0f)
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
        }

        val eyeW = viewW / 2
        val halfGap = (GAP * viewW / 2f).toInt().coerceIn(0, eyeW / 2)
        val m = mode
        // 平面 2D 模式看整頁;其他模式只取網頁中的影片區域
        val c = if (m == MODE_2D) floatArrayOf(0f, 0f, 1f, 1f) else crop
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (eye in 0..1) {
            val r = fitRect(eyeW.toFloat(), viewH.toFloat())
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
            } else if (m == MODE_360TB) {
                sy *= 0.5f
                if (eye == 0) oy += sy   // y 向上:上半在 oy + 一半
            }

            if (isSphere(m)) {
                GLES20.glViewport((cx - eyeW / 2f).toInt(), 0, eyeW, viewH)
                val lon = if (m == MODE_VR180) (1.0 / Math.PI) else (0.5 / Math.PI)
                drawSphere(sx, sy, ox, oy, eyeW.toFloat() / viewH, lon.toFloat())
            } else {
                val z = zoom
                val vy = cy + lift * viewH
                GLES20.glViewport(
                    (cx - r[2] * z / 2f).toInt(), (vy - r[3] * z / 2f).toInt(),
                    (r[2] * z).toInt(), (r[3] * z).toInt()
                )
                drawVideo(distortion, -side * tilt, sx, sy, ox, oy)
            }
            // 控制面板與游標不放大、不移動,確保完整看得到
            GLES20.glViewport((cx - r[2] / 2f).toInt(), (cy - r[3] / 2f).toInt(), r[2].toInt(), r[3].toInt())
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
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uK, k)
        GLES20.glUniform1f(uTilt, tiltRad)
        GLES20.glUniform4f(uSrc, sx, sy, ox, oy)
        bindQuad(aPos, aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawSphere(sx: Float, sy: Float, ox: Float, oy: Float, aspect: Float, lonScale: Float) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program180)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(bTex, 0)
        GLES20.glUniformMatrix4fv(bSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(bK, distortion)
        GLES20.glUniformMatrix3fv(bHead, 1, false, head, 0)
        val tx = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        GLES20.glUniform2f(bTan, tx, tx / aspect)
        GLES20.glUniform4f(bSrc, sx, sy, ox, oy)
        GLES20.glUniform1f(bLon, lonScale)
        bindQuad(bPos, bUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOverlay() {
        val panel = showPanel
        val cursor = showCursor
        if (!panel && !cursor) return
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
        GLES20.glUniform1f(oAspect, srcW.toFloat() / srcH)
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

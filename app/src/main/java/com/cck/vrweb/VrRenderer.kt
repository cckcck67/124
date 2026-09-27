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

/**
 * 把虛擬螢幕(網頁)的畫面當成 OES 材質,
 * 2D 模式畫一份;VR 模式左右各畫一份,可調眼距與鏡片變形校正。
 * VR 模式另外疊一層:游標 + 低頭控制面板。
 */
class VrRenderer(
    private val srcW: Int,
    private val srcH: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var vrMode = false
    @Volatile var ipd = 0f          // 左右間距偏移(佔單眼寬度比例,負 = 往中間靠)
    @Volatile var zoom = 1.4f       // VR 畫面放大倍率(超出單眼範圍的部分裁掉,填滿視野)
    @Volatile var distortion = 0.15f // 桶形校正強度
    @Volatile var showCursor = false
    @Volatile var showPanel = false
    @Volatile var cursorX = 0.5f    // 0~1,原始畫面座標
    @Volatile var cursorY = 0.5f

    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var program = 0
    private var texId = 0
    private var viewW = 1
    private var viewH = 1
    private var aPos = 0; private var aUv = 0
    private var uSt = 0; private var uTex = 0; private var uK = 0

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
        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uK = GLES20.glGetUniformLocation(program, "uK")

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

        if (vrMode) {
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
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            for (eye in 0..1) {
                val r = fitRect(eyeW.toFloat(), viewH.toFloat())
                // ipd < 0:兩眼畫面往中間靠;ipd > 0:往外拉開
                val shift = ipd * eyeW * (if (eye == 0) -1f else 1f)
                val cx = eye * eyeW + eyeW / 2f + shift
                val cy = viewH / 2f
                GLES20.glScissor(eye * eyeW, 0, eyeW, viewH)
                val z = zoom
                GLES20.glViewport(
                    (cx - r[2] * z / 2f).toInt(), (cy - r[3] * z / 2f).toInt(),
                    (r[2] * z).toInt(), (r[3] * z).toInt()
                )
                drawVideo(distortion)
                // 控制面板與游標不放大,確保完整看得到
                GLES20.glViewport((cx - r[2] / 2f).toInt(), (cy - r[3] / 2f).toInt(), r[2].toInt(), r[3].toInt())
                drawOverlay()
            }
        } else {
            val r = fitRect(viewW.toFloat(), viewH.toFloat())
            GLES20.glViewport(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
            drawVideo(0f)
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

    private fun drawVideo(k: Float) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uK, k)
        bindQuad(aPos, aUv)
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

    companion object {
        private const val VS = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uSt;
            uniform float uK;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv - 0.5;
                float r2 = dot(c, c);
                vec2 uv = 0.5 + c * (1.0 + uK * r2);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
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
}

package com.cck.vrweb

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
 */
class VrRenderer(
    private val srcW: Int,
    private val srcH: Int,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var vrMode = false
    @Volatile var ipd = 0f          // 眼距偏移(佔單眼寬度比例)
    @Volatile var distortion = 0.15f // 桶形校正強度
    @Volatile var showCursor = false
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
    private var uCursor = 0; private var uShowCursor = 0; private var uAspect = 0

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uCursor = GLES20.glGetUniformLocation(program, "uCursor")
        uShowCursor = GLES20.glGetUniformLocation(program, "uShowCursor")
        uAspect = GLES20.glGetUniformLocation(program, "uAspect")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
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

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uCursor, cursorX, 1f - cursorY)
        GLES20.glUniform1f(uShowCursor, if (showCursor) 1f else 0f)
        GLES20.glUniform1f(uAspect, srcW.toFloat() / srcH)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)

        if (vrMode) {
            val eyeW = viewW / 2
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glUniform1f(uK, distortion)
            for (eye in 0..1) {
                val r = fitRect(eyeW.toFloat(), viewH.toFloat())
                // ipd > 0:左眼往左、右眼往右(兩眼畫面拉開)
                val shift = ipd * eyeW * (if (eye == 0) -1f else 1f)
                GLES20.glScissor(eye * eyeW, 0, eyeW, viewH)
                GLES20.glViewport(
                    (eye * eyeW + r[0] + shift).toInt(), r[1].toInt(),
                    r[2].toInt(), r[3].toInt()
                )
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } else {
            GLES20.glUniform1f(uK, 0f)
            val r = fitRect(viewW.toFloat(), viewH.toFloat())
            GLES20.glViewport(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
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
            uniform vec2 uCursor;
            uniform float uShowCursor;
            uniform float uAspect;
            varying vec2 vUv;
            void main() {
                vec2 c = vUv - 0.5;
                float r2 = dot(c, c);
                vec2 uv = 0.5 + c * (1.0 + uK * r2);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec4 col = texture2D(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy);
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

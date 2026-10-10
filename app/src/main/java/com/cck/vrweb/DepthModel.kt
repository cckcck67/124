package com.cck.vrweb

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/**
 * 立體照用的深度估計(Depth Anything V2 Small,在手機上計算,不用網路、畫面不外傳)。
 * 模型約 27MB,第一次使用時從 GitHub Releases 下載,之後存在 App 裡。
 */
object DepthModel {
    private const val URL_MODEL = "https://github.com/cckcck67/124/releases/download/latest/depth.onnx"
    private const val FILE = "depth.onnx"

    // 模型輸入邊長需為 14 的倍數;長邊 518
    private const val LONG_SIDE = 518
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    private var session: OrtSession? = null

    /** 依畫面比例算模型輸入大小(寬, 高) */
    fun inputSize(aspect: Float): Pair<Int, Int> {
        fun r14(v: Float) = (Math.round(v / 14f) * 14).coerceIn(140, LONG_SIDE)
        return if (aspect >= 1f) LONG_SIDE to r14(LONG_SIDE / aspect)
        else r14(LONG_SIDE * aspect) to LONG_SIDE
    }

    fun isDownloaded(ctx: Context) = File(ctx.filesDir, FILE).let { it.exists() && it.length() > 1_000_000 }

    /** 背景執行緒:下載模型(回報 0~100) */
    fun download(ctx: Context, progress: (Int) -> Unit) {
        val tmp = File(ctx.filesDir, "$FILE.part")
        var target = URL(URL_MODEL)
        repeat(6) {
            val conn = target.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399) {
                target = URL(target, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (code != 200) throw Exception("HTTP $code")
            val total = conn.contentLength.toLong()
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
            if (!tmp.renameTo(File(ctx.filesDir, FILE))) throw Exception("無法儲存模型")
            return
        }
        throw Exception("轉址太多次")
    }

    /**
     * 背景執行緒:算深度。rgba = glReadPixels 的結果(由下往上的列),w*h 即模型輸入大小。
     * 回傳每個像素 0~255 的深度(越大越近),列的順序同樣由下往上,可直接上傳成材質。
     */
    fun estimate(ctx: Context, rgba: ByteArray, w: Int, h: Int): ByteArray {
        val s = session ?: OrtEnvironment.getEnvironment().createSession(
            File(ctx.filesDir, FILE).absolutePath,
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        ).also { session = it }
        val env = OrtEnvironment.getEnvironment()

        // NCHW、模型要由上往下的列;ImageNet 正規化
        val input = FloatBuffer.allocate(3 * w * h)
        val plane = w * h
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                val i = src + x * 4
                val o = y * w + x
                for (ch in 0..2) {
                    val v = (rgba[i + ch].toInt() and 0xFF) / 255f
                    input.put(ch * plane + o, (v - MEAN[ch]) / STD[ch])
                }
            }
        }
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong()))
        val out: FloatArray
        val ow: Int
        val oh: Int
        tensor.use {
            s.run(mapOf(s.inputNames.first() to tensor)).use { res ->
                val t = res.get(0) as OnnxTensor
                val shape = t.info.shape
                oh = shape[shape.size - 2].toInt()
                ow = shape[shape.size - 1].toInt()
                val fb = t.floatBuffer
                out = FloatArray(fb.remaining())
                fb.get(out)
            }
        }

        // 正規化到 0~255(去掉最極端的 1%,避免少數雜點拉開範圍),順便縮放到 w*h 並上下翻轉
        val sorted = out.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.01f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.99f).toInt()]
        val range = if (hi - lo > 1e-6f) hi - lo else 1f
        val res = ByteArray(w * h)
        for (y in 0 until h) {
            val sy = ((h - 1 - y).toLong() * oh / h).toInt()
            for (x in 0 until w) {
                val sx = (x.toLong() * ow / w).toInt()
                val v = ((out[sy * ow + sx] - lo) / range).coerceIn(0f, 1f)
                res[y * w + x] = (v * 255f).toInt().toByte()
            }
        }
        return smooth(res, w, h)
    }

    /** 深度圖稍微模糊:物體邊緣的遠近變化平順一點,錯開時邊緣不會被撕開 */
    private fun smooth(src: ByteArray, w: Int, h: Int, r: Int = 2): ByteArray {
        val tmp = IntArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { sum += src[y * w + xx].toInt() and 0xFF; n++ } }
            tmp[y * w + x] = sum / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { sum += tmp[yy * w + x]; n++ } }
            out[y * w + x] = (sum / n).toByte()
        }
        return out
    }
}

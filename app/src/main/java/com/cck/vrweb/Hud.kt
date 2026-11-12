package com.cck.vrweb

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 科幻風格介面(深藍底、發光藍框、斜切角、線條圖示)。
 * 手機畫面(首頁、設定、我的影片)和 VR 面板都用這裡的顏色和畫法,外觀才一致。
 * 全部是靜止的圖案,不做會動的效果(避免頭暈)。
 */
object Hud {
    const val LINE = 0xFF2FA8FF.toInt()        // 外框
    const val BRIGHT = 0xFF7FECFF.toInt()      // 選到時的亮框、進度
    const val ICON = 0xFFBFF0FF.toInt()        // 圖示線條
    const val TEXT = 0xFFEEFAFF.toInt()        // 主要文字
    const val SUB = 0xFF6FB2E6.toInt()         // 說明文字
    const val TAG = 0xFF4FA9E6.toInt()         // 英文小標
    const val DIM = 0xFF5A6F86.toInt()         // 暫停作用的按鈕文字
    const val GREEN = 0xFF4CFF9A.toInt()

    fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    // 圖示:64×64 的線條圖(只描邊),依名稱取用
    private val iconCache = HashMap<String, Path>()

    fun iconPath(name: String): Path = synchronized(iconCache) {
        iconCache.getOrPut(name) { buildIcon(name) }
    }

    private fun buildIcon(name: String): Path {
        val p = Path()
        fun r(l: Float, t: Float, rr: Float, b: Float) = RectF(l, t, rr, b)
        fun arrowUp(x: Float, top: Float, bottom: Float, w: Float) {
            p.moveTo(x, bottom); p.lineTo(x, top)
            p.moveTo(x - w, top + w); p.lineTo(x, top); p.lineTo(x + w, top + w)
        }
        fun arrowDown(x: Float, top: Float, bottom: Float, w: Float) {
            p.moveTo(x, top); p.lineTo(x, bottom)
            p.moveTo(x - w, bottom - w); p.lineTo(x, bottom); p.lineTo(x + w, bottom - w)
        }
        fun speaker() {
            p.moveTo(6f, 24f); p.lineTo(18f, 24f); p.lineTo(32f, 12f); p.lineTo(32f, 52f)
            p.lineTo(18f, 40f); p.lineTo(6f, 40f); p.close()
        }
        when (name) {
            "globe" -> {
                p.addCircle(32f, 32f, 26f, Path.Direction.CW)
                p.addOval(r(21f, 6f, 43f, 58f), Path.Direction.CW)
                p.moveTo(6f, 32f); p.lineTo(58f, 32f)
                p.moveTo(9.5f, 19f); p.lineTo(54.5f, 19f)
                p.moveTo(9.5f, 45f); p.lineTo(54.5f, 45f)
            }
            "balloon" -> {   // 熱氣球
                p.moveTo(27f, 45f)
                p.cubicTo(8f, 34f, 7f, 4f, 32f, 4f)
                p.cubicTo(57f, 4f, 56f, 34f, 37f, 45f)
                p.close()
                p.moveTo(32f, 4f); p.cubicTo(21f, 12f, 23f, 35f, 30f, 45f)
                p.moveTo(32f, 4f); p.cubicTo(43f, 12f, 41f, 35f, 34f, 45f)
                p.moveTo(27f, 45f); p.lineTo(26.5f, 51f)
                p.moveTo(37f, 45f); p.lineTo(37.5f, 51f)
                p.addRoundRect(r(25f, 51f, 39f, 60f), 2f, 2f, Path.Direction.CW)
            }
            "video" -> {
                p.addRoundRect(r(6f, 14f, 58f, 50f), 3f, 3f, Path.Direction.CW)
                p.moveTo(27f, 24f); p.lineTo(27f, 40f); p.lineTo(40f, 32f); p.close()
            }
            "pencil" -> {
                p.moveTo(12f, 52f); p.lineTo(18f, 36f); p.lineTo(44f, 10f); p.lineTo(54f, 20f)
                p.lineTo(28f, 46f); p.close()
                p.moveTo(38f, 16f); p.lineTo(48f, 26f)
                p.moveTo(18f, 36f); p.lineTo(28f, 46f)
            }
            "wrench" -> {    // 板手:直立畫好再轉 45 度
                val head = Path().apply { addCircle(32f, 16f, 12f, Path.Direction.CW) }
                val handle = Path().apply { addRoundRect(r(27f, 20f, 37f, 60f), 5f, 5f, Path.Direction.CW) }
                val jaw = Path().apply { addRect(r(28f, 0f, 36f, 13f), Path.Direction.CW) }
                try {
                    head.op(handle, Path.Op.UNION)
                    head.op(jaw, Path.Op.DIFFERENCE)
                } catch (e: Throwable) {   // 少數環境不支援路徑運算:改成直接疊在一起
                    head.addPath(handle)
                }
                head.addCircle(32f, 53f, 2.2f, Path.Direction.CW)
                head.transform(Matrix().apply { postRotate(45f, 32f, 32f) })
                p.addPath(head)
            }
            "posture" -> { arrowDown(20f, 8f, 56f, 8f); arrowUp(44f, 8f, 56f, 8f) }
            "volDown" -> { speaker(); p.moveTo(42f, 32f); p.lineTo(58f, 32f) }
            "volUp" -> { speaker(); p.moveTo(42f, 32f); p.lineTo(58f, 32f); p.moveTo(50f, 24f); p.lineTo(50f, 40f) }
            "ff" -> {
                p.moveTo(6f, 14f); p.lineTo(28f, 32f); p.lineTo(6f, 50f); p.close()
                p.moveTo(32f, 14f); p.lineTo(54f, 32f); p.lineTo(32f, 50f); p.close()
            }
            "cinema" -> {    // 電影院:弧形巨幕 + 座位
                p.moveTo(6f, 14f); p.quadTo(32f, 2f, 58f, 14f); p.lineTo(58f, 38f)
                p.quadTo(32f, 26f, 6f, 38f); p.close()
                for (x in floatArrayOf(10f, 26f, 42f)) {
                    p.moveTo(x, 56f); p.lineTo(x, 50f); p.quadTo(x + 6f, 46f, x + 12f, 50f); p.lineTo(x + 12f, 56f)
                }
            }
            "m2d" -> p.addRoundRect(r(8f, 16f, 56f, 48f), 3f, 3f, Path.Direction.CW)
            "m3d" -> {
                p.addRoundRect(r(4f, 20f, 30f, 44f), 3f, 3f, Path.Direction.CW)
                p.addRoundRect(r(34f, 20f, 60f, 44f), 3f, 3f, Path.Direction.CW)
            }
            "m180" -> {
                p.moveTo(6f, 44f); p.arcTo(r(6f, 18f, 58f, 70f), 180f, 180f, false); p.close()
                p.moveTo(32f, 44f); p.lineTo(32f, 30f)
            }
            "mtb" -> {
                p.addRoundRect(r(12f, 6f, 52f, 30f), 3f, 3f, Path.Direction.CW)
                p.addRoundRect(r(12f, 34f, 52f, 58f), 3f, 3f, Path.Direction.CW)
            }
            "m360" -> {
                p.addOval(r(6f, 20f, 58f, 44f), Path.Direction.CW)
                p.addOval(r(22f, 6f, 42f, 58f), Path.Direction.CW)
                p.moveTo(50f, 16f); p.lineTo(56f, 22f); p.lineTo(48f, 26f)
            }
            "target" -> {    // 置中
                p.addCircle(32f, 32f, 20f, Path.Direction.CW)
                p.addCircle(32f, 32f, 5f, Path.Direction.CW)
                p.moveTo(32f, 2f); p.lineTo(32f, 14f); p.moveTo(32f, 50f); p.lineTo(32f, 62f)
                p.moveTo(2f, 32f); p.lineTo(14f, 32f); p.moveTo(50f, 32f); p.lineTo(62f, 32f)
            }
            "pause" -> {
                p.addRoundRect(r(16f, 12f, 26f, 52f), 2f, 2f, Path.Direction.CW)
                p.addRoundRect(r(38f, 12f, 48f, 52f), 2f, 2f, Path.Direction.CW)
            }
            "play" -> { p.moveTo(20f, 10f); p.lineTo(50f, 32f); p.lineTo(20f, 54f); p.close() }
            "stop" -> p.addRoundRect(r(14f, 14f, 50f, 50f), 3f, 3f, Path.Direction.CW)
            "turn" -> {      // 換角度:繞一圈的箭頭
                p.arcTo(r(10f, 10f, 54f, 54f), -60f, 290f, true)
                p.moveTo(40f, 10f); p.lineTo(44f, 14f); p.lineTo(40f, 23f)
            }
            "replay" -> {
                p.arcTo(r(10f, 10f, 54f, 54f), -80f, 320f, true)
                p.moveTo(24f, 4f); p.lineTo(32f, 10f); p.lineTo(24f, 16f)
            }
            "land" -> { arrowDown(32f, 6f, 44f, 10f); p.moveTo(8f, 56f); p.lineTo(56f, 56f) }
            "takeoff" -> { arrowUp(32f, 6f, 44f, 10f); p.moveTo(8f, 56f); p.lineTo(56f, 56f) }
            "slower" -> { p.moveTo(24f, 12f); p.lineTo(42f, 32f); p.lineTo(24f, 52f) }
            "faster" -> {
                p.moveTo(14f, 12f); p.lineTo(32f, 32f); p.lineTo(14f, 52f)
                p.moveTo(34f, 12f); p.lineTo(52f, 32f); p.lineTo(34f, 52f)
            }
            "lower" -> {
                p.moveTo(14f, 14f); p.lineTo(32f, 30f); p.lineTo(50f, 14f)
                p.moveTo(14f, 34f); p.lineTo(32f, 50f); p.lineTo(50f, 34f)
            }
            "higher" -> {
                p.moveTo(14f, 30f); p.lineTo(32f, 14f); p.lineTo(50f, 30f)
                p.moveTo(14f, 50f); p.lineTo(32f, 34f); p.lineTo(50f, 50f)
            }
            "up" -> arrowUp(32f, 8f, 56f, 14f)
            "uturn" -> {
                p.moveTo(20f, 58f); p.lineTo(20f, 26f)
                p.arcTo(r(20f, 12f, 46f, 38f), 180f, 180f, false)
                p.lineTo(46f, 40f)
                p.moveTo(36f, 30f); p.lineTo(46f, 40f); p.lineTo(56f, 30f)
            }
            "walk" -> {      // 腳印
                p.addOval(r(12f, 8f, 26f, 30f), Path.Direction.CW)
                p.addOval(r(14f, 33f, 24f, 42f), Path.Direction.CW)
                p.addOval(r(38f, 20f, 52f, 42f), Path.Direction.CW)
                p.addOval(r(40f, 45f, 50f, 54f), Path.Direction.CW)
            }
            "pin" -> {       // 回降落點
                p.moveTo(32f, 60f)
                p.cubicTo(20f, 44f, 12f, 34f, 12f, 24f)
                p.arcTo(r(12f, 4f, 52f, 44f), 180f, 180f, false)
                p.cubicTo(52f, 34f, 44f, 44f, 32f, 60f)
                p.close()
                p.addCircle(32f, 24f, 7f, Path.Direction.CW)
            }
            "exit" -> {
                p.moveTo(30f, 8f); p.lineTo(10f, 8f); p.lineTo(10f, 56f); p.lineTo(30f, 56f)
                p.moveTo(24f, 32f); p.lineTo(56f, 32f)
                p.moveTo(46f, 22f); p.lineTo(56f, 32f); p.lineTo(46f, 42f)
            }
            "star" -> {
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val rr = if (i % 2 == 0) 26.0 else 11.0
                    val x = (32 + rr * Math.cos(a)).toFloat()
                    val y = (34 + rr * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "back" -> {
                p.moveTo(56f, 32f); p.lineTo(10f, 32f)
                p.moveTo(26f, 16f); p.lineTo(10f, 32f); p.lineTo(26f, 48f)
            }
            "home" -> {
                p.moveTo(6f, 30f); p.lineTo(32f, 8f); p.lineTo(58f, 30f)
                p.moveTo(14f, 24f); p.lineTo(14f, 56f); p.lineTo(50f, 56f); p.lineTo(50f, 24f)
                p.addRect(r(27f, 40f, 37f, 56f), Path.Direction.CW)
            }
            "vr" -> {
                p.moveTo(6f, 20f); p.lineTo(58f, 20f); p.lineTo(58f, 48f); p.lineTo(40f, 48f)
                p.lineTo(34f, 40f); p.lineTo(30f, 40f); p.lineTo(24f, 48f); p.lineTo(6f, 48f); p.close()
                p.addCircle(20f, 34f, 5f, Path.Direction.CW)
                p.addCircle(44f, 34f, 5f, Path.Direction.CW)
            }
            "plus" -> { p.moveTo(32f, 10f); p.lineTo(32f, 54f); p.moveTo(10f, 32f); p.lineTo(54f, 32f) }
            else -> p.addCircle(32f, 32f, 20f, Path.Direction.CW)
        }
        return p
    }
}

/**
 * 畫科幻元件的工具。每個使用者各自建立一個(Paint 不共用,VR 面板和手機畫面不會互相干擾)。
 */
class HudPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    /** 斜切角外框(左上、右下切角);notch = 上緣在 60% 處往下折一小段 */
    fun framePath(l: Float, t: Float, r: Float, b: Float, c: Float, notch: Boolean): Path {
        path.reset()
        path.moveTo(l + c, t)
        if (notch) {
            val nx = l + (r - l) * 0.6f
            val d = c * 0.4f
            path.lineTo(nx, t); path.lineTo(nx + d, t + d); path.lineTo(r, t + d)
        } else path.lineTo(r, t)
        path.lineTo(r, b - c); path.lineTo(r - c, b); path.lineTo(l, b); path.lineTo(l, t + c)
        path.close()
        return path
    }

    /** 發光的線:先畫寬而淡的光暈,再畫實線 */
    fun glow(c: Canvas, p: Path, color: Int, w: Float) {
        stroke.color = Hud.withAlpha(color, 0x28); stroke.strokeWidth = w * 4.5f; c.drawPath(p, stroke)
        stroke.color = Hud.withAlpha(color, 0x50); stroke.strokeWidth = w * 2.4f; c.drawPath(p, stroke)
        stroke.color = color; stroke.strokeWidth = w; c.drawPath(p, stroke)
    }

    /**
     * 一個面板 / 按鈕。hot = 被選到(較亮的底和框);faded = 暫停作用(暗一點)。
     * 外框的光暈會超出 [l, r]×[t, b] 約 2 倍線寬,呼叫的地方要留邊。
     */
    fun panel(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, cut: Float, line: Float,
        hot: Boolean = false, notch: Boolean = false, faded: Boolean = false, alpha: Int = 0xE6
    ) {
        val p = framePath(l, t, r, b, cut, notch)
        fill.shader = if (hot) LinearGradient(0f, b, 0f, t, 0xFF1564AE.toInt(), 0xFF0A2A50.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(l, t, r, b,
            Hud.withAlpha(0x0B2A52, alpha), Hud.withAlpha(0x04122A, alpha), Shader.TileMode.CLAMP)
        c.drawPath(p, fill)
        fill.shader = null
        // 內側光暈
        c.save()
        c.clipPath(p)
        stroke.color = Hud.withAlpha(if (hot) Hud.BRIGHT else Hud.LINE, if (hot) 0x70 else 0x40)
        stroke.strokeWidth = minOf(cut * 1.6f, (b - t) * 0.3f)
        c.drawPath(p, stroke)
        c.restore()
        val color = when {
            hot -> Hud.BRIGHT
            faded -> 0xFF2A4A6E.toInt()
            else -> Hud.LINE
        }
        glow(c, p, color, if (hot) line * 1.4f else line)
    }

    /** 底部一排刻度 */
    fun ticks(c: Canvas, l: Float, r: Float, y: Float, h: Float, step: Float, color: Int = Hud.LINE) {
        stroke.color = Hud.withAlpha(color, 0x99)
        stroke.strokeWidth = maxOf(1f, step * 0.22f)
        var x = l
        while (x <= r) { c.drawLine(x, y, x, y + h, stroke); x += step }
    }

    /** 圖示外圍的雷達圈(靜止不轉) */
    fun ring(c: Canvas, cx: Float, cy: Float, rad: Float, line: Float, faded: Boolean = false) {
        val col = if (faded) 0xFF2A4A6E.toInt() else Hud.LINE
        fill.color = Hud.withAlpha(0x0A2A52, 0x99)
        c.drawCircle(cx, cy, rad * 0.82f, fill)
        rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        stroke.color = col; stroke.strokeWidth = line * 1.3f
        for (a in floatArrayOf(-80f, 20f, 110f, 200f)) c.drawArc(rect, a, 62f, false, stroke)
        stroke.color = Hud.withAlpha(col, 0x50); stroke.strokeWidth = line * 3.5f
        for (a in floatArrayOf(-80f, 110f)) c.drawArc(rect, a, 62f, false, stroke)
        stroke.color = if (faded) col else 0xFF5FD8FF.toInt(); stroke.strokeWidth = line
        c.drawCircle(cx, cy, rad * 0.82f, stroke)
        stroke.strokeWidth = line * 1.3f
        val o = rad * 1.08f; val i = rad * 0.9f
        c.drawLine(cx, cy - o, cx, cy - i, stroke); c.drawLine(cx, cy + i, cx, cy + o, stroke)
        c.drawLine(cx - o, cy, cx - i, cy, stroke); c.drawLine(cx + i, cy, cx + o, cy, stroke)
    }

    /** 線條圖示,size = 寬高(像素);weight = 64 格中的線寬 */
    fun icon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int = Hud.ICON, weight: Float = 3.5f) {
        val s = size / 64f
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(s, s)
        val p = Hud.iconPath(name)
        stroke.color = Hud.withAlpha(Hud.LINE, 0x60); stroke.strokeWidth = weight * 2.6f
        c.drawPath(p, stroke)
        stroke.color = color; stroke.strokeWidth = weight
        c.drawPath(p, stroke)
        c.restore()
    }

    /** 發光文字(置中,y = 基線);超過 maxW 會縮小 */
    fun glowText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Hud.TEXT,
                 maxW: Float = Float.MAX_VALUE, glowColor: Int = Hud.LINE, align: Paint.Align = Paint.Align.CENTER) {
        text.textAlign = align
        text.textSize = size
        val w = text.measureText(s)
        if (w > maxW && w > 0) text.textSize = size * maxW / w
        text.color = color
        if (glowColor != 0) text.setShadowLayer(size * 0.25f, 0f, 0f, glowColor)
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
        text.textAlign = Paint.Align.CENTER
    }

    /** 等寬英文小標 */
    fun tag(c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.RIGHT, color: Int = Hud.TAG) {
        val tf = text.typeface
        text.typeface = Typeface.MONOSPACE
        text.letterSpacing = 0.15f
        text.textAlign = align
        text.textSize = size
        text.color = color
        c.drawText(s, x, y, text)
        text.typeface = tf
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
    }

    /** 斜邊的平行四邊形(資訊框、進度條) */
    fun slant(l: Float, t: Float, r: Float, b: Float, d: Float): Path {
        path.reset()
        path.moveTo(l + d, t); path.lineTo(r, t); path.lineTo(r - d, b); path.lineTo(l, b); path.close()
        return path
    }

    fun fillPath(c: Canvas, p: Path, color: Int, shader: Shader? = null) {
        fill.shader = shader
        fill.color = color
        c.drawPath(p, fill)
        fill.shader = null
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, filled: Boolean, w: Float = 2f) {
        if (filled) { fill.color = color; c.drawCircle(cx, cy, r, fill) }
        else { stroke.color = color; stroke.strokeWidth = w; c.drawCircle(cx, cy, r, stroke) }
    }

    fun arc(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int, w: Float) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.color = color; stroke.strokeWidth = w
        c.drawArc(rect, start, sweep, false, stroke)
    }
}

/** 整頁背景:深藍漸層 + 格線 + 淡掃描線 + 四角發光框角 */
class HudScreenDrawable(private val density: Float) : Drawable() {
    private val hp = HudPainter()
    private val bg = Paint()
    private val grid = Paint().apply { color = 0x262A6FB0; strokeWidth = 1f }
    private val scan = Paint().apply { color = 0x07FFFFFF; strokeWidth = 1f }
    private val cornerPath = Path()

    override fun onBoundsChange(b: Rect) {
        bg.shader = RadialGradient(b.exactCenterX(), b.height() * 0.45f, maxOf(b.width(), b.height()) * 0.75f,
            intArrayOf(0xFF0D2C5A.toInt(), 0xFF061027.toInt(), 0xFF01040B.toInt()), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        c.drawRect(b, bg)
        val step = 32 * density
        var x = step
        while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
        var y = step
        while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
        y = 0f
        val s = 3 * density
        while (y < h) { c.drawLine(0f, y, w, y, scan); y += s }
        // 四角框角
        val m = 6 * density; val len = 26 * density
        cornerPath.reset()
        cornerPath.moveTo(m, m + len); cornerPath.lineTo(m, m); cornerPath.lineTo(m + len, m)
        cornerPath.moveTo(w - m - len, m); cornerPath.lineTo(w - m, m); cornerPath.lineTo(w - m, m + len)
        cornerPath.moveTo(m, h - m - len); cornerPath.lineTo(m, h - m); cornerPath.lineTo(m + len, h - m)
        cornerPath.moveTo(w - m - len, h - m); cornerPath.lineTo(w - m, h - m); cornerPath.lineTo(w - m, h - m - len)
        hp.glow(c, cornerPath, 0xFF5FD8FF.toInt(), 2.5f * density)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** 按鈕 / 輸入框的外框(按下時變亮);也提供內距 */
class HudFrameDrawable(
    private val density: Float, private val cutDp: Float = 9f, private val notch: Boolean = false,
    private val padH: Float = 14f, private val padV: Float = 8f
) : Drawable() {
    private val hp = HudPainter()
    private var pressed = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed) || state.contains(android.R.attr.state_focused)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set((padH * density).toInt(), (padV * density).toInt(), (padH * density).toInt(), (padV * density).toInt())
        return true
    }

    override fun draw(c: Canvas) {
        val m = 4 * density
        val b = bounds
        hp.panel(c, b.left + m, b.top + m, b.right - m, b.bottom - m, cutDp * density, 1.5f * density,
            hot = pressed, notch = notch)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 放在文字旁邊的小圖示(Button 的 compound drawable) */
class HudIconDrawable(private val name: String, private val sizePx: Int) : Drawable() {
    private val hp = HudPainter()
    init { setBounds(0, 0, sizePx, sizePx) }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: Canvas) {
        hp.icon(c, name, bounds.exactCenterX(), bounds.exactCenterY(), sizePx.toFloat(), weight = 5f)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 網址列的底:深藍半透明 + 底部一條發光線 */
class HudBarDrawable(private val density: Float) : Drawable() {
    private val hp = HudPainter()
    private val bg = Paint().apply { color = 0xF0050E22.toInt() }
    override fun draw(c: Canvas) {
        val b = bounds
        c.drawRect(b, bg)
        val y = b.bottom - 1.5f * density
        hp.line(c, b.left.toFloat(), y, b.right.toFloat(), y, Hud.withAlpha(Hud.LINE, 0x50), 4 * density)
        hp.line(c, b.left.toFloat(), y, b.right.toFloat(), y, Hud.LINE, 1.5f * density)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 首頁標題旁的裝飾線:一段刻度 + 漸淡的發光線 */
class HudLineView(context: Context) : View(context) {
    private val hp = HudPainter()
    private val d = resources.displayMetrics.density
    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val w = width.toFloat()
        hp.fillPath(c, hp.slant(0f, y - 1.5f * d, w, y + 1.5f * d, 0f), 0,
            LinearGradient(0f, 0f, w, 0f, Hud.LINE, Hud.withAlpha(Hud.LINE, 0x10), Shader.TileMode.CLAMP))
        var x = 0f
        val seg = 5 * d
        while (x < minOf(w * 0.3f, 90 * d)) {
            hp.fillPath(c, hp.slant(x, y - 5 * d, x + seg * 0.6f, y + 5 * d, 0f), 0xFF5FD8FF.toInt())
            x += seg
        }
    }
}

/**
 * 首頁的大卡片:雷達圈裡的圖示 + 名稱 + 說明,右上角英文編號,底部刻度。
 * 卡片寬時圖示在左、字在右;窄(直式手機)時圖示在上、字在下。
 */
class HudTile(
    context: Context, private val title: String, private val sub: String,
    private val iconName: String, private val tagText: String, private val active: Boolean
) : View(context) {
    private val hp = HudPainter()
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    init {
        isClickable = true
        isFocusable = true
        contentDescription = title
        if (!active) alpha = 0.45f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val m = 6 * d
        val l = m; val t = m; val r = width - m; val b = height - m
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return
        val cut = minOf(18 * d, h * 0.15f)
        hp.panel(c, l, t, r, b, cut, 2f * d, hot = isPressed, notch = true, faded = !active)
        hp.tag(c, tagText, r - 14 * d, t + cut * 0.4f + 16 * d, 11 * sp)
        hp.ticks(c, l + 22 * d, r - 22 * d, b - 13 * d, 5 * d, 9 * d)

        val wide = w > h * 1.25f
        if (wide) {
            val rad = minOf(h * 0.27f, w * 0.13f)
            val cx = l + 22 * d + rad
            val cy = t + h * 0.47f
            hp.ring(c, cx, cy, rad, 1.6f * d, faded = !active)
            hp.icon(c, iconName, cx, cy, rad * 0.95f, weight = 3.8f)
            val tx = cx + rad + 18 * d
            val maxW = r - tx - 12 * d
            hp.glowText(c, title, tx, cy + 2 * d, minOf(30 * sp, h * 0.24f), maxW = maxW, align = Paint.Align.LEFT)
            hp.glowText(c, sub, tx, cy + 2 * d + minOf(24 * sp, h * 0.2f), minOf(15 * sp, h * 0.11f),
                color = Hud.SUB, maxW = maxW, glowColor = 0, align = Paint.Align.LEFT)
        } else {
            val rad = minOf(w * 0.22f, h * 0.2f)
            val cx = l + w / 2
            val cy = t + h * 0.36f
            hp.ring(c, cx, cy, rad, 1.6f * d, faded = !active)
            hp.icon(c, iconName, cx, cy, rad * 0.95f, weight = 3.8f)
            val ts = minOf(28 * sp, w * 0.16f)
            val ty = cy + rad + 14 * d + ts
            hp.glowText(c, title, cx, ty, ts, maxW = w - 20 * d)
            hp.glowText(c, sub, cx, ty + 22 * sp, 13 * sp, color = Hud.SUB, maxW = w - 20 * d, glowColor = 0)
        }
    }
}


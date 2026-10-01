package com.cck.vrweb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 控制面板。坐姿:低頭叫出,放在畫面下方;躺平:抬頭叫出,放在畫面上方。
 * 各排由「靠近畫面中央」往外依序是:
 * 空白列(游標從這裡開始,不會誤觸)→ 進度條 → 按鈕 → 資訊列。
 * 「速度」「模式」按鈕會拉出選項列,看著選項就能選;選項拉出時其他按鈕暫停作用。
 * 用「注視停留」選取(看著不動一下子就觸發),不用打開 VR 盒子點螢幕。
 * 座標:cx, cy 為畫面比例(0~1,y 向下)。
 */
class VrMenu {

    companion object {
        // 面板在畫面上的位置(比例,y 向下)
        const val X0 = 0.2f
        const val X1 = 0.8f
        private const val HEIGHT = 0.35f

        /** 躺平模式:面板在畫面上方 */
        @Volatile var lying = false
        val Y0 get() = if (lying) 0f else 1f - HEIGHT
        val Y1 get() = if (lying) HEIGHT else 1f

        // 面板點陣圖大小
        const val W = 1280
        const val H = 432

        // 坐姿(面板在下方)時各排的上下緣;躺平時上下鏡射
        private const val START_END = 70f    // 空白列
        private const val BAR_END = 192f     // 進度條 / 選項列
        private const val BTN_END = 352f     // 按鈕列,以下是資訊列
        private const val BAR_L = 50f
        private const val BAR_R = 1230f
        private const val ROW_L = 20f
        private const val ROW_R = 1260f

        const val NONE = -1
        const val BAR = 100
        const val OPTION = 200               // 選項列第 i 個 = OPTION + i
        const val BTN_POSTURE = 0
        const val BTN_VOL_DOWN = 1
        const val BTN_VOL_UP = 2
        const val BTN_FWD = 3
        const val BTN_SPEED = 4
        const val BTN_MODE = 5
        const val BTN_RECENTER = 6
        private val LABELS = arrayOf("坐姿", "音量－", "音量＋", "快進10秒", "速度", "模式", "置中")

        // 順序對應 VrRenderer.MODE_*
        val MODE_NAMES = arrayOf("2D", "左右3D", "VR180")
        val SPEEDS = floatArrayOf(0.5f, 1f, 1.5f)
        private val SPEED_NAMES = arrayOf("0.5x", "1x", "1.5x")

        /** 空白列正中間(游標起始位置)的畫面比例 y */
        val START_Y get() = Y0 + yOf(START_END / 2f) / H * (Y1 - Y0)

        /** 坐姿版面的 y → 目前版面的 y(躺平時上下鏡射) */
        private fun yOf(y: Float) = if (lying) H - y else y

        fun fmt(sec: Double): String {
            val s = sec.toLong().coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }
    }

    // 目前狀態(由 MainActivity 更新)
    var hover = NONE
    var hoverFrac = 0f      // 注視進度條的位置(0~1)
    var dwell = 0f          // 注視停留進度(0~1)
    var hasVideo = false
    var current = 0.0
    var duration = 0.0
    var paused = false
    var volume = 0
    var volumeMax = 15
    var mode = 0            // VrRenderer.MODE_*
    var speed = 1           // SPEEDS 的索引
    var popup = NONE        // 目前拉出選項的按鈕(BTN_SPEED / BTN_MODE)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC101010.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xFFFFD040.toInt()
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF60D060.toInt()
    }
    private val rect = RectF()

    private fun canSeek() = hasVideo && duration > 0

    fun optionNames(): Array<String> = if (popup == BTN_SPEED) SPEED_NAMES else MODE_NAMES
    private fun selectedOption() = if (popup == BTN_SPEED) speed else mode

    /** 坐姿版面中 [a, b) 這一排在目前版面的上下緣 */
    private fun band(a: Float, b: Float): Pair<Float, Float> =
        if (lying) Pair(H - b, H - a) else Pair(a, b)

    /** 游標在面板上指到什麼 */
    fun hitTest(cx: Float, cy: Float): Int {
        if (cx < X0 || cx > X1 || cy < Y0 || cy > Y1) return NONE
        val px = (cx - X0) / (X1 - X0) * W
        val py = yOf((cy - Y0) / (Y1 - Y0) * H)   // 換回坐姿版面座標
        if (py < START_END || py >= BTN_END || px < ROW_L || px > ROW_R) return NONE
        if (py < BAR_END) {
            if (popup != NONE) {
                val n = optionNames().size
                val i = ((px - ROW_L) / ((ROW_R - ROW_L) / n)).toInt()
                return if (i in 0 until n) OPTION + i else NONE
            }
            return if (canSeek() && px >= BAR_L - 20 && px <= BAR_R + 20) BAR else NONE
        }
        val i = ((px - ROW_L) / ((ROW_R - ROW_L) / LABELS.size)).toInt()
        if (i !in LABELS.indices) return NONE
        // 選項拉出時,只有原本那顆按鈕(再看一次 = 收起)有作用,避免移動時誤觸
        return if (popup != NONE && i != popup) NONE else i
    }

    fun barFraction(cx: Float): Float {
        val px = (cx - X0) / (X1 - X0) * W
        return ((px - BAR_L) / (BAR_R - BAR_L)).coerceIn(0f, 1f)
    }

    private fun drawButton(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, hot: Boolean, dw: Float, label: String
    ) {
        rect.set(l, t, r, b)
        fillPaint.color = if (hot) 0xFF2F5A88.toInt() else 0xFF2A2A2A.toInt()
        c.drawRoundRect(rect, 18f, 18f, fillPaint)
        if (dw > 0f) {
            fillPaint.color = 0xFF5B9BE0.toInt()
            rect.set(l, t, l + (r - l) * dw, b)
            c.drawRoundRect(rect, 18f, 18f, fillPaint)
        }
        c.drawText(label, (l + r) / 2, (t + b) / 2 + 14, centerText)
    }

    fun draw(b: Bitmap) {
        b.eraseColor(0)
        val c = Canvas(b)
        rect.set(0f, 0f, W.toFloat(), H.toFloat())
        c.drawRoundRect(rect, 28f, 28f, bgPaint)

        // ---- 進度條,或拉出的選項列 ----
        val (barTop, barBottom) = band(START_END, BAR_END)
        val barY = (barTop + barBottom) / 2 + 4
        centerText.textSize = 38f
        if (popup != NONE) {
            val names = optionNames()
            val w = (ROW_R - ROW_L) / names.size
            for (i in names.indices) {
                val l = ROW_L + i * w + 6
                val r = ROW_L + (i + 1) * w - 6
                val hot = hover == OPTION + i
                drawButton(c, l, barTop + 8, r, barBottom - 6, hot, if (hot) dwell else 0f, names[i])
                if (i == selectedOption()) {
                    rect.set(l, barTop + 8, r, barBottom - 6)
                    c.drawRoundRect(rect, 18f, 18f, selPaint)
                }
            }
        } else if (canSeek()) {
            fillPaint.color = 0x66FFFFFF
            rect.set(BAR_L, barY - 9, BAR_R, barY + 9)
            c.drawRoundRect(rect, 9f, 9f, fillPaint)
            val played = BAR_L + (BAR_R - BAR_L) * (current / duration).toFloat().coerceIn(0f, 1f)
            fillPaint.color = 0xFFFF3030.toInt()
            rect.set(BAR_L, barY - 9, played, barY + 9)
            c.drawRoundRect(rect, 9f, 9f, fillPaint)
            fillPaint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(played, barY, 14f, fillPaint)

            if (hover == BAR) {
                val x = BAR_L + (BAR_R - BAR_L) * hoverFrac
                fillPaint.color = 0xFFFFD040.toInt()
                c.drawRect(x - 3, barY - 24, x + 3, barY + 24, fillPaint)
                rect.set(x - 28, barY - 28, x + 28, barY + 28)
                c.drawArc(rect, -90f, 360f * dwell, false, ringPaint)
                centerText.textSize = 30f
                c.drawText(fmt(hoverFrac * duration), x.coerceIn(60f, W - 60f), barY - 36, centerText)
            }
        }

        // ---- 按鈕 ----
        val (btnTop, btnBottom) = band(BAR_END, BTN_END)
        centerText.textSize = 34f
        val bw = (ROW_R - ROW_L) / LABELS.size
        for (i in LABELS.indices) {
            val label = when (i) {
                BTN_POSTURE -> if (lying) "躺平" else "坐姿"
                BTN_SPEED -> "速度 ${SPEED_NAMES[speed]}"
                BTN_MODE -> MODE_NAMES[mode]
                else -> LABELS[i]
            }
            centerText.color = if (popup != NONE && popup != i) 0xFF777777.toInt() else 0xFFFFFFFF.toInt()
            drawButton(c, ROW_L + i * bw + 6, btnTop + 6, ROW_L + (i + 1) * bw - 6, btnBottom - 6,
                hover == i || popup == i, if (hover == i) dwell else 0f, label)
        }
        centerText.color = 0xFFFFFFFF.toInt()

        // ---- 資訊列(不能點) ----
        val (infoTop, infoBottom) = band(BTN_END, H.toFloat())
        centerText.textSize = 34f
        centerText.color = 0xFFBBBBBB.toInt()
        val time = if (canSeek()) "${fmt(current)} / ${fmt(duration)}" else "找不到可控制的影片"
        val state = if (hasVideo && paused) "    已暫停" else ""
        c.drawText("$time$state    音量 $volume    ${MODE_NAMES[mode]}",
            W / 2f, (infoTop + infoBottom) / 2 + 12, centerText)
        centerText.color = 0xFFFFFFFF.toInt()
    }
}

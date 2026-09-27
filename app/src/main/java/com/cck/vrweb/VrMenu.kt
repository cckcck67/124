package com.cck.vrweb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 低頭才出現的控制面板:
 * 最上排是資訊列(空位,游標從這裡開始,不會誤觸)、中間進度條、最下排按鈕。
 * 用「注視停留」選取(看著不動一下子就觸發),不用打開 VR 盒子點螢幕。
 * 座標:cx, cy 為畫面比例(0~1,y 向下)。
 */
class VrMenu {

    companion object {
        // 面板在畫面上的位置(比例,y 向下)
        const val X0 = 0.2f
        const val X1 = 0.8f
        const val Y0 = 0.62f
        const val Y1 = 0.98f

        // 面板點陣圖大小
        const val W = 1280
        const val H = 432

        const val INFO_END = 100f            // 資訊列(空位)下緣
        const val ROW_SPLIT = 235f           // 進度條/按鈕分界
        private const val BAR_L = 50f
        private const val BAR_R = 1230f
        private const val BAR_Y = 175f
        private const val BTN_L = 20f
        private const val BTN_R = 1260f
        private const val BTN_TOP = 250f
        private const val BTN_BOTTOM = 415f

        const val NONE = -1
        const val BAR = 100
        const val BTN_BACK = 0
        const val BTN_PLAY = 1
        const val BTN_FWD = 2
        const val BTN_VOL_DOWN = 3
        const val BTN_VOL_UP = 4
        const val BTN_STEREO = 5
        private val LABELS = arrayOf("倒退10秒", "播放/暫停", "快進10秒", "音量－", "音量＋", "3D模式")
        val STEREO_NAMES = arrayOf("2D", "左右3D", "上下3D")

        /** 資訊列正中間(游標起始位置)的畫面比例 y */
        val START_Y get() = Y0 + (INFO_END / 2f) / H * (Y1 - Y0)

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
    var stereo = 0          // 0 = 2D, 1 = 左右3D, 2 = 上下3D

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC101010.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xFFFFD040.toInt()
    }
    private val rect = RectF()

    private fun canSeek() = hasVideo && duration > 0

    /** 游標在面板上指到什麼 */
    fun hitTest(cx: Float, cy: Float): Int {
        if (cx < X0 || cx > X1 || cy < Y0 || cy > Y1) return NONE
        val px = (cx - X0) / (X1 - X0) * W
        val py = (cy - Y0) / (Y1 - Y0) * H
        if (py < INFO_END) return NONE
        if (py < ROW_SPLIT) {
            return if (canSeek() && px >= BAR_L - 20 && px <= BAR_R + 20) BAR else NONE
        }
        if (px < BTN_L) return NONE
        val i = ((px - BTN_L) / ((BTN_R - BTN_L) / LABELS.size)).toInt()
        return if (i in LABELS.indices) i else NONE
    }

    fun barFraction(cx: Float): Float {
        val px = (cx - X0) / (X1 - X0) * W
        return ((px - BAR_L) / (BAR_R - BAR_L)).coerceIn(0f, 1f)
    }

    fun draw(b: Bitmap) {
        b.eraseColor(0)
        val c = Canvas(b)
        rect.set(0f, 0f, W.toFloat(), H.toFloat())
        c.drawRoundRect(rect, 28f, 28f, bgPaint)

        // ---- 資訊列(不能點) ----
        centerText.textSize = 36f
        centerText.color = 0xFFBBBBBB.toInt()
        val time = if (canSeek()) "${fmt(current)} / ${fmt(duration)}" else "找不到可控制的影片"
        c.drawText("$time    音量 $volume    ${STEREO_NAMES[stereo]}", W / 2f, 64f, centerText)
        centerText.color = 0xFFFFFFFF.toInt()

        // ---- 進度條 ----
        if (canSeek()) {
            fillPaint.color = 0x66FFFFFF
            rect.set(BAR_L, BAR_Y - 9, BAR_R, BAR_Y + 9)
            c.drawRoundRect(rect, 9f, 9f, fillPaint)
            val played = BAR_L + (BAR_R - BAR_L) * (current / duration).toFloat().coerceIn(0f, 1f)
            fillPaint.color = 0xFFFF3030.toInt()
            rect.set(BAR_L, BAR_Y - 9, played, BAR_Y + 9)
            c.drawRoundRect(rect, 9f, 9f, fillPaint)
            fillPaint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(played, BAR_Y, 14f, fillPaint)

            if (hover == BAR) {
                val x = BAR_L + (BAR_R - BAR_L) * hoverFrac
                fillPaint.color = 0xFFFFD040.toInt()
                c.drawRect(x - 3, BAR_Y - 24, x + 3, BAR_Y + 24, fillPaint)
                rect.set(x - 28, BAR_Y - 28, x + 28, BAR_Y + 28)
                c.drawArc(rect, -90f, 360f * dwell, false, ringPaint)
                centerText.textSize = 30f
                c.drawText(fmt(hoverFrac * duration), x.coerceIn(60f, W - 60f), BAR_Y - 40, centerText)
            }
        }

        // ---- 按鈕 ----
        centerText.textSize = 38f
        val bw = (BTN_R - BTN_L) / LABELS.size
        for (i in LABELS.indices) {
            val l = BTN_L + i * bw + 6
            val r = BTN_L + (i + 1) * bw - 6
            rect.set(l, BTN_TOP, r, BTN_BOTTOM)
            fillPaint.color = if (hover == i) 0xFF2F5A88.toInt() else 0xFF2A2A2A.toInt()
            c.drawRoundRect(rect, 18f, 18f, fillPaint)
            if (hover == i && dwell > 0f) {
                fillPaint.color = 0xFF5B9BE0.toInt()
                rect.set(l, BTN_TOP, l + (r - l) * dwell, BTN_BOTTOM)
                c.drawRoundRect(rect, 18f, 18f, fillPaint)
            }
            val label = when {
                i == BTN_PLAY && hasVideo -> if (paused) "播放" else "暫停"
                i == BTN_STEREO -> STEREO_NAMES[stereo]
                else -> LABELS[i]
            }
            c.drawText(label, (l + r) / 2, (BTN_TOP + BTN_BOTTOM) / 2 + 14, centerText)
        }
    }
}

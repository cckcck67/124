package com.cck.vrweb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 控制面板。坐姿:低頭叫出,放在畫面下方;躺平:抬頭叫出,放在畫面上方。
 * 各排由「靠近畫面中央」往外依序是:
 * 空白列(游標從這裡開始,不會誤觸)→ 進度條 → 按鈕 → 資訊列。
 * 「模式」按鈕會拉出選項列,看著選項就能選;選項拉出時其他按鈕暫停作用。
 * 「設定」按鈕切換到設定頁:每項有 − / + 按鈕,邊看邊調;抬頭收起面板就離開設定頁。
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
        const val SET_MINUS = 300            // 設定頁第 i 項的 − = SET_MINUS + 2i,+ = SET_MINUS + 2i + 1
        const val BTN_POSTURE = 0
        const val BTN_VOL_DOWN = 1
        const val BTN_VOL_UP = 2
        const val BTN_FWD = 3
        const val BTN_MODE = 4
        const val BTN_RECENTER = 5
        const val BTN_SETTINGS = 6
        const val BTN_FLY_PAUSE = 7      // 空中遊覽:暫停 / 繼續
        const val BTN_FLY_RESTART = 8    //           重新飛這一段
        const val BTN_FLY_NEXT = 9       //           換一個方向接近
        const val BTN_FLY_LAND = 10      //           降落到街景 / 起飛
        const val BTN_FLY_SLOWER = 11    //           飛慢一點
        const val BTN_FLY_FASTER = 12    //           飛快一點
        const val BTN_FLY_LOWER = 13     //           飛低一點
        const val BTN_FLY_HIGHER = 14    //           飛高一點
        const val BTN_WALK_FWD = 15      // 街景散步:前進
        const val BTN_WALK_BACK = 16     //           迴轉(整個畫面轉 180 度往回走)
        const val BTN_WALK_AUTO = 17     //           自動散步 / 停止
        const val BTN_WALK_SLOWER = 18   //           走慢一點
        const val BTN_WALK_FASTER = 19   //           走快一點
        const val BTN_WALK_HOME = 20     //           回降落點
        const val BTN_END_REPLAY = 21    // 影片播完:重播
        const val BTN_END_WEB = 22       //           回到網頁(VR 中用紅點操作網頁)
        const val BTN_END_EXIT = 23      //           離開 VR
        const val BTN_WEB = 24           // 影片:停止播放,進入 VR 網頁操作(紅點)
        private val LABELS = arrayOf("坐姿", "音量－", "音量＋", "快進10秒", "模式", "置中", "設定",
            "暫停", "重飛", "換角度", "降落", "飛快－", "飛快＋", "飛低", "飛高",
            "前進", "迴轉", "自動走", "走快－", "走快＋", "回降落點",
            "", "", "", "網頁")

        /** 影片(網頁、我的影片)用的按鈕 */
        val VIDEO_BUTTONS = intArrayOf(BTN_POSTURE, BTN_VOL_DOWN, BTN_VOL_UP, BTN_FWD, BTN_MODE, BTN_RECENTER, BTN_SETTINGS)
        /** 網頁影片:多一個「網頁」(停止播放,在 VR 中用紅點操作網頁) */
        val WEB_VIDEO_BUTTONS = intArrayOf(BTN_POSTURE, BTN_VOL_DOWN, BTN_VOL_UP, BTN_FWD, BTN_MODE, BTN_WEB,
            BTN_RECENTER, BTN_SETTINGS)
        /** 空中遊覽用的按鈕(設定也是空中遊覽自己的,和影片分開) */
        // 給長輩、小孩用:速度、高度直接放在第一層
        val FLIGHT_BUTTONS = intArrayOf(BTN_POSTURE, BTN_FLY_PAUSE, BTN_FLY_NEXT, BTN_FLY_LAND,
            BTN_FLY_SLOWER, BTN_FLY_FASTER, BTN_FLY_LOWER, BTN_FLY_HIGHER, BTN_RECENTER, BTN_SETTINGS)

        /** 降落到街景後:散步用的按鈕 */
        val WALK_BUTTONS = intArrayOf(BTN_POSTURE, BTN_WALK_FWD, BTN_WALK_BACK, BTN_WALK_AUTO,
            BTN_WALK_SLOWER, BTN_WALK_FASTER, BTN_WALK_HOME, BTN_FLY_LAND, BTN_RECENTER, BTN_SETTINGS)

        // 順序對應 VrRenderer.MODE_*
        val MODE_NAMES = arrayOf("2D", "左右3D", "VR180", "上下VR", "環顧", "電影院")
        // 「模式」選單裡的排列順序(電影院排在 2D 後面)
        val MODE_ORDER = intArrayOf(0, 5, 1, 2, 3, 4)

        // 設定頁:兩欄(項目多時三欄),每格 = 名稱與數值、−、+
        private const val SET_TOP = 80f
        private const val SET_ROW_H = 86f

        fun isSetMinus(t: Int) = t >= SET_MINUS && (t - SET_MINUS) % 2 == 0
        fun setIndex(t: Int) = (t - SET_MINUS) / 2

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
    var popup = NONE        // 目前拉出選項的按鈕(BTN_MODE)
    var settingsPage = false
    var buttons = VIDEO_BUTTONS     // 目前顯示的按鈕(依功能切換)
    var infoText: String? = null    // 資訊列文字;null = 顯示影片時間
    var flyPaused = false
    var flyLanded = false
    var walking = false        // 街景自動散步中
    var endPage = false        // 影片播完的選單(重播 / 回到網頁 / 離開 VR)
    var settingRows: List<Pair<String, String>> = emptyList()   // 設定頁每項的 (名稱, 數值)

    private val hp = HudPainter()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun canSeek() = hasVideo && duration > 0

    fun optionNames(): Array<String> = Array(MODE_ORDER.size) { MODE_NAMES[MODE_ORDER[it]] }
    private fun selectedOption() = MODE_ORDER.indexOf(mode)

    // ---- 設定頁格子位置(坐姿版面座標) ----
    private fun cellCount() = settingRows.size   // 不設「返回」:抬頭收起面板就離開設定頁
    private fun cols() = if (cellCount() > 8) 3 else 2  // 超過 4 排就改三欄,才放得進面板
    private fun cellLeft(i: Int) = ROW_L + (i % cols()) * cellW()
    private fun cellTop(i: Int) = SET_TOP + (i / cols()) * SET_ROW_H
    private fun cellW() = (ROW_R - ROW_L) / cols()

    private fun hitSettings(px: Float, py: Float): Int {
        if (py < SET_TOP) return NONE
        val row = ((py - SET_TOP) / SET_ROW_H).toInt()
        val col = ((px - ROW_L) / cellW()).toInt().coerceIn(0, cols() - 1)
        val i = row * cols() + col
        if (i >= cellCount()) return NONE
        val x = px - cellLeft(i)
        val w = cellW()
        return when {
            x > w * 0.79f -> SET_MINUS + 2 * i + 1   // +
            x > w * 0.58f -> SET_MINUS + 2 * i       // −
            else -> NONE
        }
    }

    /** 坐姿版面中 [a, b) 這一排在目前版面的上下緣 */
    private fun band(a: Float, b: Float): Pair<Float, Float> =
        if (lying) Pair(H - b, H - a) else Pair(a, b)

    /** 游標在面板上指到什麼 */
    fun hitTest(cx: Float, cy: Float): Int {
        if (cx < X0 || cx > X1 || cy < Y0 || cy > Y1) return NONE
        val px = (cx - X0) / (X1 - X0) * W
        val py = yOf((cy - Y0) / (Y1 - Y0) * H)   // 換回坐姿版面座標
        if (px < ROW_L || px > ROW_R) return NONE
        if (endPage) {   // 影片播完:三個大按鈕各佔三分之一
            val i = ((px - ROW_L) / ((ROW_R - ROW_L) / 3f)).toInt()
            return when (i) { 0 -> BTN_END_REPLAY; 1 -> BTN_END_WEB; 2 -> BTN_END_EXIT; else -> NONE }
        }
        if (settingsPage) return hitSettings(px, py)
        if (py < START_END || py >= BTN_END) return NONE
        if (py < BAR_END) {
            if (popup != NONE) {
                val n = optionNames().size
                val i = ((px - ROW_L) / ((ROW_R - ROW_L) / n)).toInt()
                return if (i in 0 until n) OPTION + i else NONE
            }
            return if (canSeek() && px >= BAR_L - 20 && px <= BAR_R + 20) BAR else NONE
        }
        val i = ((px - ROW_L) / ((ROW_R - ROW_L) / buttons.size)).toInt()
        if (i !in buttons.indices) return NONE
        val id = buttons[i]
        // 選項拉出時,只有原本那顆按鈕(再看一次 = 收起)有作用,避免移動時誤觸
        return if (popup != NONE && id != popup) NONE else id
    }

    fun barFraction(cx: Float): Float {
        val px = (cx - X0) / (X1 - X0) * W
        return ((px - BAR_L) / (BAR_R - BAR_L)).coerceIn(0f, 1f)
    }

    /** 按鈕上的圖示名稱(Hud.iconPath) */
    private fun iconFor(id: Int): String? = when (id) {
        BTN_POSTURE -> "posture"
        BTN_VOL_DOWN -> "volDown"
        BTN_VOL_UP -> "volUp"
        BTN_FWD -> "ff"
        BTN_MODE -> modeIcon(mode)
        BTN_RECENTER -> "target"
        BTN_SETTINGS -> "wrench"
        BTN_FLY_PAUSE -> if (flyPaused) "play" else "pause"
        BTN_FLY_RESTART -> "replay"
        BTN_FLY_NEXT -> "turn"
        BTN_FLY_LAND -> if (flyLanded) "takeoff" else "land"
        BTN_FLY_SLOWER, BTN_WALK_SLOWER -> "slower"
        BTN_FLY_FASTER, BTN_WALK_FASTER -> "faster"
        BTN_FLY_LOWER -> "lower"
        BTN_FLY_HIGHER -> "higher"
        BTN_WALK_FWD -> "up"
        BTN_WALK_BACK -> "uturn"
        BTN_WALK_AUTO -> if (walking) "stop" else "walk"
        BTN_WALK_HOME -> "pin"
        BTN_WEB -> "globe"
        else -> null
    }

    private fun modeIcon(m: Int) = when (m) {
        VrRenderer.MODE_CURVE -> "cinema"
        1 -> "m3d"
        2 -> "m180"
        3 -> "mtb"
        4 -> "m360"
        else -> "m2d"
    }

    /**
     * 科幻風格按鈕:斜切角發光框;有圖示時圖示在上、字在下。
     * 注視停留時,按鈕底部的亮條由左往右填滿(dw = 0~1)。
     */
    private fun drawButton(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, hot: Boolean, dw: Float, label: String,
        icon: String? = null, faded: Boolean = false, textSize: Float = 34f
    ) {
        val h = b - t
        val cut = minOf(16f, h * 0.18f)
        hp.panel(c, l, t, r, b, cut, 2.5f, hot = hot, faded = faded)
        if (dw > 0f) {
            c.save()
            c.clipPath(hp.framePath(l, t, r, b, cut, false))
            val x = l + (r - l) * dw.coerceIn(0f, 1f)
            rect.set(l, t, x, b)
            fillPaint.color = 0x335FD8FF
            c.drawRect(rect, fillPaint)
            fillPaint.color = Hud.withAlpha(Hud.BRIGHT, 0x60)
            c.drawRect(l, b - 18f, x, b, fillPaint)
            fillPaint.color = Hud.BRIGHT
            c.drawRect(l, b - 11f, x, b, fillPaint)
            c.restore()
        }
        val maxW = (r - l) - 16
        val color = if (faded) Hud.DIM else Hud.TEXT
        if (icon != null && h >= 100f) {
            val size = minOf(h * 0.4f, (r - l) * 0.55f)
            hp.icon(c, icon, (l + r) / 2, t + h * 0.36f, size, if (faded) Hud.DIM else Hud.ICON, weight = 4.5f)
            hp.glowText(c, label, (l + r) / 2, b - h * 0.14f, textSize, color, maxW, if (faded) 0 else Hud.LINE)
        } else {
            hp.glowText(c, label, (l + r) / 2, (t + b) / 2 + textSize * 0.36f, textSize, color, maxW,
                if (faded) 0 else Hud.LINE)
        }
    }

    /** 面板最外側空白列的英文小標(不能點,只是裝飾) */
    private fun drawTag(c: Canvas, s: String) {
        val (t, bt) = band(0f, START_END)
        val y = (t + bt) / 2 + 9f
        hp.tag(c, s, W - 56f, y, 24f)
        hp.tag(c, "◆ WebVR", 56f, y, 24f, Paint.Align.LEFT)
    }

    /** 影片播完:重播 / 回到網頁 / 離開 VR(大圖示,底下一行字) */
    private fun drawEnd(c: Canvas) {
        val icons = arrayOf("replay", "globe", "exit")
        val names = arrayOf("重播", "回到網頁", "離開VR")
        val ids = intArrayOf(BTN_END_REPLAY, BTN_END_WEB, BTN_END_EXIT)
        val w = (ROW_R - ROW_L) / 3f
        for (i in 0..2) {
            val l = ROW_L + i * w + 14
            val r = ROW_L + (i + 1) * w - 14
            val hot = hover == ids[i]
            drawButton(c, l, 30f, r, H - 30f, hot, if (hot) dwell else 0f, "")
            val cx = (l + r) / 2
            hp.ring(c, cx, H / 2f - 40f, 105f, 3f)
            hp.icon(c, icons[i], cx, H / 2f - 40f, 120f, weight = 4f)
            hp.glowText(c, names[i], cx, H - 62f, 44f, maxW = r - l - 20)
        }
    }

    private fun drawSettings(c: Canvas) {
        drawTag(c, "CONTROL // SETTINGS")
        val w = cellW()
        for (i in 0 until cellCount()) {
            val l = cellLeft(i) + 8
            val (t, bt) = band(cellTop(i) + 6, cellTop(i) + SET_ROW_H - 6)
            val (name, value) = settingRows[i]
            hp.panel(c, l, t, l + w * 0.56f, bt, 12f, 2f)
            // 名稱和數值分兩行,三欄時也放得下
            val mid = (t + bt) / 2
            val tw = w * 0.56f - 16
            hp.glowText(c, name, l + w * 0.28f, mid - 6, 28f, Hud.TEXT, tw, 0)
            hp.glowText(c, value, l + w * 0.28f, mid + 28, 28f, Hud.BRIGHT, tw, 0)
            val minus = SET_MINUS + 2 * i
            drawButton(c, l + w * 0.59f, t, l + w * 0.78f, bt, hover == minus, if (hover == minus) dwell else 0f,
                "−", textSize = 46f)
            drawButton(c, l + w * 0.80f, t, l + w - 14, bt, hover == minus + 1, if (hover == minus + 1) dwell else 0f,
                "+", textSize = 46f)
        }
    }

    fun draw(b: Bitmap) {
        b.eraseColor(0)
        val c = Canvas(b)
        hp.panel(c, 10f, 10f, W - 10f, H - 10f, 34f, 3f, notch = true, alpha = 0xE0)
        if (endPage) {
            drawEnd(c)
            return
        }
        if (settingsPage) {
            drawSettings(c)
            return
        }
        drawTag(c, when {
            buttons === FLIGHT_BUTTONS -> "CONTROL // FLIGHT"
            buttons === WALK_BUTTONS -> "CONTROL // WALK"
            else -> "CONTROL // VIDEO"
        })

        // ---- 進度條,或拉出的選項列 ----
        val (barTop, barBottom) = band(START_END, BAR_END)
        val barY = (barTop + barBottom) / 2 + 4
        if (popup != NONE) {
            val names = optionNames()
            val w = (ROW_R - ROW_L) / names.size
            for (i in names.indices) {
                val l = ROW_L + i * w + 8
                val r = ROW_L + (i + 1) * w - 8
                val hot = hover == OPTION + i
                drawButton(c, l, barTop + 8, r, barBottom - 6, hot, if (hot) dwell else 0f, names[i],
                    modeIcon(MODE_ORDER[i]), textSize = 30f)
                if (i == selectedOption()) hp.glow(c, hp.framePath(l, barTop + 8, r, barBottom - 6, 14f, false), Hud.GREEN, 3f)
            }
        } else if (canSeek()) {
            hp.fillPath(c, hp.slant(BAR_L, barY - 8, BAR_R, barY + 8, 8f), 0xFF12314F.toInt())
            val played = BAR_L + (BAR_R - BAR_L) * (current / duration).toFloat().coerceIn(0f, 1f)
            if (played > BAR_L + 10) {
                hp.fillPath(c, hp.slant(BAR_L - 4, barY - 14, played + 4, barY + 14, 10f), Hud.withAlpha(0x4CC8FF, 0x40))
                hp.fillPath(c, hp.slant(BAR_L, barY - 8, played, barY + 8, 8f), 0,
                    LinearGradient(BAR_L, 0f, played, 0f, 0xFF1D6CFF.toInt(), 0xFF6FE4FF.toInt(), Shader.TileMode.CLAMP))
            }
            // 目前位置:瞄準器樣式的圓點
            hp.circle(c, played, barY, 26f, Hud.withAlpha(Hud.BRIGHT, 0x30), true)
            for (a in floatArrayOf(-70f, 20f, 110f, 200f)) hp.arc(c, played, barY, 22f, a, 50f, Hud.BRIGHT, 4f)
            hp.circle(c, played, barY, 11f, 0xFFDFF8FF.toInt(), true)
            hp.tag(c, fmt(0.0), BAR_L, barY + 40, 24f, Paint.Align.LEFT)
            hp.tag(c, fmt(duration), BAR_R, barY + 40, 24f, Paint.Align.RIGHT)

            if (hover == BAR) {
                val x = BAR_L + (BAR_R - BAR_L) * hoverFrac
                hp.line(c, x, barY - 26, x, barY + 26, Hud.withAlpha(Hud.BRIGHT, 0x60), 12f)
                hp.line(c, x, barY - 26, x, barY + 26, Hud.BRIGHT, 5f)
                hp.arc(c, x, barY, 30f, -90f, 360f * dwell, Hud.BRIGHT, 7f)
                hp.glowText(c, fmt(hoverFrac * duration), x.coerceIn(60f, W - 60f), barY - 38, 30f)
            }
        }

        // ---- 按鈕 ----
        val (btnTop, btnBottom) = band(BAR_END, BTN_END)
        val bw = (ROW_R - ROW_L) / buttons.size
        for (i in buttons.indices) {
            val id = buttons[i]
            val label = when (id) {
                BTN_POSTURE -> if (lying) "躺平" else "坐姿"
                BTN_MODE -> MODE_NAMES[mode]
                BTN_FLY_PAUSE -> if (flyPaused) "繼續" else "暫停"
                BTN_FLY_LAND -> if (flyLanded) "起飛" else "降落"
                BTN_WALK_AUTO -> if (walking) "停止" else "自動走"
                else -> LABELS[id]
            }
            drawButton(c, ROW_L + i * bw + 6, btnTop + 8, ROW_L + (i + 1) * bw - 6, btnBottom - 8,
                hover == id || popup == id, if (hover == id) dwell else 0f, label, iconFor(id),
                faded = popup != NONE && popup != id, textSize = 32f)
        }

        // ---- 資訊列(不能點) ----
        val (infoTop, infoBottom) = band(BTN_END, H.toFloat())
        val info = infoText ?: run {
            val time = if (canSeek()) "${fmt(current)} / ${fmt(duration)}" else "找不到可控制的影片"
            val state = if (hasVideo && paused) "    已暫停" else ""
            "$time$state    音量 $volume    ${MODE_NAMES[mode]}"
        }
        val my = (infoTop + infoBottom) / 2
        hp.text.textSize = 30f
        val tw = minOf(hp.text.measureText(info), W - 200f)
        val bx0 = W / 2f - tw / 2 - 40; val bx1 = W / 2f + tw / 2 + 40
        val box = hp.slant(bx0, my - 26, bx1, my + 26, 14f)
        hp.fillPath(c, box, 0xCC06182F.toInt())
        hp.glow(c, box, 0xFF2A6FB0.toInt(), 2f)
        hp.glowText(c, info, W / 2f, my + 11, 30f, 0xFFCFEAFF.toInt(), W - 200f, 0)
    }
}

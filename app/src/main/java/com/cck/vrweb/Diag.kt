package com.cck.vrweb

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 診斷紀錄:記下 YouTube VR 轉頭時的數據(要求的角度、YouTube 實際的角度、邊界…),
 * 存在手機裡,從「設定」傳給 Claude 分析。只留最新的 MAX 行。
 */
object Diag {
    private const val MAX = 2500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var dirty = false

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "diag.txt")
        file = f
        try { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) } } catch (e: Exception) {}
    }

    @Synchronized
    fun log(msg: String) {
        lines.addLast(fmt.format(Date()) + " " + msg)
        while (lines.size > MAX) lines.removeFirst()
        dirty = true
    }

    @Synchronized
    fun count() = lines.size

    @Synchronized
    fun text() = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        dirty = true
        save()
    }

    /** 寫進檔案(App 暫停時),關掉 App 後紀錄還在 */
    @Synchronized
    fun save() {
        if (!dirty) return
        try { file?.writeText(lines.joinToString("\n")) } catch (e: Exception) {}
        dirty = false
    }
}

package com.cck.vrweb

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * 「我的影片」用的小型本機伺服器(只接受手機自己 127.0.0.1 的連線)。
 * 播放頁面與影片都從這裡提供,影片支援分段讀取(Range),可以拖進度條。
 * 網址裡有隨機密碼,其他 App 猜不到。
 */
class LocalVideoServer(private val context: Context) {

    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private val videos = HashMap<String, Uri>()

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread({ handle(c) }, "LocalVideo").start()
            }
        }, "LocalVideoServer").start()
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (e: Exception) {}
        server = null
    }

    /** 播放某部影片的頁面網址 */
    fun playerUrl(uri: Uri, name: String): String {
        start()
        val id = Integer.toHexString(uri.toString().hashCode())
        synchronized(videos) { videos[id] = uri }
        return "http://127.0.0.1:$port/$token/player.html?v=" + Uri.encode("/$token/v/$id") +
                "&t=" + Uri.encode(name)
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            try {
                val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val req = input.readLine() ?: return
                var range: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substring(6).trim()
                }
                val parts = req.split(" ")
                if (parts.size < 2) return
                val head = parts[0] == "HEAD"
                val path = parts[1].substringBefore('?')
                val out = c.getOutputStream()
                when {
                    path == "/$token/player.html" -> sendAsset(out, "player.html", head)
                    path.startsWith("/$token/v/") -> {
                        val uri = synchronized(videos) { videos[path.substringAfterLast('/')] }
                        if (uri == null) status(out, 404) else sendVideo(out, uri, range, head)
                    }
                    else -> status(out, 404)
                }
                out.flush()
            } catch (e: Exception) {
                // 播放器中途取消連線(拖進度條時)是正常的
            }
        }
    }

    private fun status(out: OutputStream, code: Int) {
        out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun sendAsset(out: OutputStream, name: String, head: Boolean) {
        val data = context.assets.open(name).use { it.readBytes() }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        if (!head) out.write(data)
    }

    private fun sizeOf(uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (e: Exception) {}
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }

    private fun sendVideo(out: OutputStream, uri: Uri, range: String?, head: Boolean) {
        val total = sizeOf(uri)
        val type = context.contentResolver.getType(uri) ?: "video/mp4"
        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        var partial = false
        if (range != null && total > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) {          // 最後 N 個位元組
                    start = (total - b.toLong()).coerceAtLeast(0)
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                }
                if (start > end) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                partial = true
            }
        }
        val len = if (total > 0) end - start + 1 else -1L
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (head) return
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fin ->
                val ch = fin.channel
                ch.position(start)
                val buf = ByteArray(256 * 1024)
                var left = if (len >= 0) len else Long.MAX_VALUE
                while (left > 0) {
                    val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }
}

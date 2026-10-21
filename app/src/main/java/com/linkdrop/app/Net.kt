package com.linkdrop.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class CancelledException : Exception()

fun fmtBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < 2) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

object Net {
    /** Shouts on the Wi-Fi; the laptop answers with its port. Returns e.g. http://192.168.1.5:3000 */
    fun discover(): String? = try {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 1500
            val msg = "LINKDROP?".toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 41234))
            val p = DatagramPacket(ByteArray(64), 64)
            s.receive(p)
            val t = String(p.data, 0, p.length)
            if (t.startsWith("LINKDROP:")) "http://${p.address.hostAddress}:${t.substring(9).trim()}" else null
        }
    } catch (e: Exception) { null }

    fun normalize(a: String): String {
        var s = a.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        if (!Regex(":\\d+$").containsMatchIn(s)) s += ":3000"
        return s
    }

    fun open(base: String, path: String, method: String = "GET"): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 15000
        c.setRequestProperty("X-Client", "app")
        return c
    }

    fun getText(base: String, path: String): String {
        val c = open(base, path)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun post(base: String, path: String, json: String? = null): String {
        val c = open(base, path, "POST")
        try {
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun ping(base: String): Boolean = try { getText(base, "/api/info"); true } catch (e: Exception) { false }
}

/** Measures speed (bytes per second) and pushes progress to the screen. */
class Meter(private val item: Item) {
    private var t = System.currentTimeMillis()
    private var b = 0L
    fun update(total: Long) {
        item.done = total
        val n = System.currentTimeMillis()
        if (n - t >= 500) {
            item.speed = (total - b) * 1000 / (n - t)
            t = n; b = total
            State.changed()
        }
    }
}

object Transfer {
    /** Laptop -> phone. Runs on a background thread. */
    fun download(base: String, item: Item, filesDir: File) {
        item.status = St.ACTIVE; item.cancel.set(false); item.done = 0; item.speed = 0; item.note = ""
        State.changed()
        val dir = File(filesDir, "inbox").apply { mkdirs() }
        val f = File(dir, item.id + "_" + item.name)
        item.file = f
        var conn: HttpURLConnection? = null
        try {
            val c = Net.open(base, "/api/outbox/${item.id}/download")
            conn = c
            if (c.responseCode != 200) throw IOException("Laptop said HTTP ${c.responseCode}")
            val meter = Meter(item)
            var total = 0L
            c.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (item.cancel.get()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        meter.update(total)
                    }
                }
            }
            item.done = total; item.size = total; item.status = St.DONE
        } catch (e: CancelledException) {
            item.status = St.CANCELLED; f.delete()
            try { Net.post(base, "/api/outbox/${item.id}/cancel") } catch (e2: Exception) { }
        } catch (e: Exception) {
            item.status = St.FAILED; item.note = e.message ?: ""; f.delete()
        } finally {
            conn?.disconnect(); item.speed = 0; State.changed()
        }
    }

    /** Phone -> laptop. Waits until you press Accept on the web page. */
    fun upload(ctx: Context, base: String, item: Item) {
        val uri = item.source ?: return
        item.status = St.WAITING_ACCEPT; item.cancel.set(false); item.done = 0; item.speed = 0; item.note = ""
        State.changed()
        var sid: String? = null
        var conn: HttpURLConnection? = null
        try {
            val offer = JSONObject(Net.post(base, "/api/inbox/offer",
                JSONObject().put("name", item.name).put("size", maxOf(item.size, 0L)).toString()))
            val id = offer.getString("id")
            sid = id
            val end = System.currentTimeMillis() + 5 * 60_000L
            while (true) {
                if (item.cancel.get()) throw CancelledException()
                val s = JSONObject(Net.getText(base, "/api/inbox/$id")).getString("status")
                if (s == "accepted") break
                if (s == "declined") { item.status = St.DECLINED; return }
                if (s == "cancelled") throw CancelledException()
                if (System.currentTimeMillis() > end) throw IOException("Nobody accepted in time")
                Thread.sleep(800)
            }
            item.status = St.ACTIVE; State.changed()
            val c = Net.open(base, "/api/inbox/$id/data", "PUT")
            conn = c
            c.doOutput = true
            c.readTimeout = 120000
            if (item.size > 0) c.setFixedLengthStreamingMode(item.size) else c.setChunkedStreamingMode(0)
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            val meter = Meter(item)
            var total = 0L
            input.use { inp ->
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (item.cancel.get()) throw CancelledException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        meter.update(total)
                    }
                }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Laptop said HTTP $code")
            item.done = total; item.status = St.DONE
        } catch (e: CancelledException) {
            item.status = St.CANCELLED
            val id = sid
            if (id != null) try { Net.post(base, "/api/inbox/$id/cancel") } catch (e2: Exception) { }
        } catch (e: Exception) {
            item.status = St.FAILED; item.note = e.message ?: ""
        } finally {
            conn?.disconnect(); item.speed = 0; State.changed()
        }
    }
}

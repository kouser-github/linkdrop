package com.sentrocx.app

import android.content.Context
import android.util.Base64
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

class Found(val base: String, val id: String)
class Call(val c: HttpURLConnection, val nonce: String)

object Net {
    /** Shouts on the Wi-Fi; the laptop answers with its port and id. */
    fun discover(): Found? = try {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 1500
            val msg = "SENTROCX?".toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 41234))
            val p = DatagramPacket(ByteArray(96), 96)
            s.receive(p)
            val parts = String(p.data, 0, p.length).trim().split(":")
            if (parts.size >= 2 && parts[0] == "SENTROCX") Found("http://${p.address.hostAddress}:${parts[1]}", parts.getOrElse(2) { "" }) else null
        }
    } catch (e: Exception) { null }

    fun normalize(a: String): String {
        var s = a.trim().trimEnd('/')
        if (!s.startsWith("http")) s = "http://$s"
        if (!Regex(":\\d+$").containsMatchIn(s)) s += ":3000"
        return s
    }

    /** Plain (unsigned) request, used only for pairing and for asking who a laptop is. */
    fun plain(base: String, path: String, json: JSONObject? = null): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000
        try {
            if (json != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    fun locate(manual: String): Found? {
        if (manual.isNotBlank()) {
            val b = normalize(manual)
            return try { Found(b, plain(b, "/api/info").optString("id")) } catch (e: Exception) { null }
        }
        return discover()
    }

    /** Opens a signed request. The laptop rejects anything not signed with the pairing key. */
    fun open(l: Link, path: String, method: String = "GET"): Call {
        val c = URL(l.base + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 15000
        val ts = System.currentTimeMillis().toString()
        val nonce = Crypto.hex(Crypto.random(8))
        val mac = Base64.encodeToString(Crypto.hmac(l.mac, "$method\n$path\n$ts\n$nonce".toByteArray()), Base64.NO_WRAP)
        c.setRequestProperty("X-Device", l.device); c.setRequestProperty("X-Ts", ts)
        c.setRequestProperty("X-Nonce", nonce); c.setRequestProperty("X-Mac", mac)
        c.setRequestProperty("X-Client", "app")
        return Call(c, nonce)
    }

    /** Signed request with an encrypted JSON body and reply. Returns the decrypted reply text. */
    fun call(l: Link, method: String, path: String, json: JSONObject? = null): String {
        val k = open(l, path, method); val c = k.c
        try {
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { it.write(Crypto.seal(l.enc, json.toString(), "req\n${k.nonce}")) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return Crypto.open(l.enc, c.inputStream.readBytes(), "res\n${k.nonce}")
        } finally { c.disconnect() }
    }

    fun ping(l: Link, laptopId: String): Boolean = try { JSONObject(call(l, "GET", "/api/info")).optString("id") == laptopId } catch (e: Exception) { false }
}

/** One pairing attempt: ECDH, commit-then-reveal, and a 6-digit code you compare on both screens. */
class PairSession(private val base: String, private val name: String) {
    private val kp = Crypto.newEc()
    private val pub = Crypto.rawPub(kp.public)
    private val nonce = Crypto.random(16)
    private var sid = ""
    private var srvPub = ByteArray(0)

    fun start() {
        val r = Net.plain(base, "/api/pair/start", JSONObject().put("name", name).put("commit", Crypto.hex(Crypto.sha(pub, nonce))))
        sid = r.getString("sid"); srvPub = Crypto.unhex(r.getString("pub"))
    }
    fun reveal(): String {
        Net.plain(base, "/api/pair/reveal", JSONObject().put("sid", sid).put("pub", Crypto.hex(pub)).put("nonce", Crypto.hex(nonce)))
        return Crypto.sas(pub, srvPub, nonce)
    }
    fun confirm() { Net.plain(base, "/api/pair/confirm", JSONObject().put("sid", sid)) }
    fun status(): JSONObject = Net.plain(base, "/api/pair/status", JSONObject().put("sid", sid))
    fun cancel() { Net.plain(base, "/api/pair/cancel", JSONObject().put("sid", sid)) }
    fun keys(): ByteArray = Crypto.pairKeys(Crypto.ecdh(kp, srvPub), pub, srvPub, nonce)
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
    fun download(l: Link, item: Item, filesDir: File) {
        item.status = St.ACTIVE; item.cancel.set(false); item.done = 0; item.speed = 0; item.note = ""
        State.changed()
        val dir = File(filesDir, "inbox").apply { mkdirs() }
        val f = File(dir, item.id + "_" + item.name)
        item.file = f
        var conn: HttpURLConnection? = null
        try {
            val k = Net.open(l, "/api/outbox/${item.id}/download")
            val c = k.c
            conn = c
            if (c.responseCode != 200) throw IOException("Laptop said HTTP ${c.responseCode}")
            val meter = Meter(item)
            val total = c.inputStream.use { inp ->
                f.outputStream().use { out -> Crypto.decryptStream(inp, out, l.enc, item.id, "d", item.cancel) { meter.update(it) } }
            }
            item.done = total; item.size = total; item.status = St.DONE
        } catch (e: CancelledException) {
            item.status = St.CANCELLED; f.delete()
            try { Net.call(l, "POST", "/api/outbox/${item.id}/cancel") } catch (e2: Exception) { }
        } catch (e: Exception) {
            item.status = St.FAILED; item.note = e.message ?: ""; f.delete()
        } finally {
            conn?.disconnect(); item.speed = 0; State.changed()
        }
    }

    /** Phone -> laptop. Waits until you press Accept on the web page. */
    fun upload(ctx: Context, l: Link, item: Item) {
        val uri = item.source ?: return
        item.status = St.WAITING_ACCEPT; item.cancel.set(false); item.done = 0; item.speed = 0; item.note = ""
        State.changed()
        var sid: String? = null
        var conn: HttpURLConnection? = null
        try {
            val offer = JSONObject(Net.call(l, "POST", "/api/inbox/offer",
                JSONObject().put("name", item.name).put("size", maxOf(item.size, 0L))))
            val id = offer.getString("id")
            sid = id
            val end = System.currentTimeMillis() + 5 * 60_000L
            while (true) {
                if (item.cancel.get()) throw CancelledException()
                val s = JSONObject(Net.call(l, "GET", "/api/inbox/$id")).getString("status")
                if (s == "accepted") break
                if (s == "declined") { item.status = St.DECLINED; return }
                if (s == "cancelled") throw CancelledException()
                if (System.currentTimeMillis() > end) throw IOException("Nobody accepted in time")
                Thread.sleep(800)
            }
            item.status = St.ACTIVE; State.changed()
            val k = Net.open(l, "/api/inbox/$id/data", "PUT")
            val c = k.c
            conn = c
            c.doOutput = true
            c.readTimeout = 120000
            c.setChunkedStreamingMode(0)
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            val meter = Meter(item)
            input.use { inp ->
                c.outputStream.use { out -> Crypto.encryptStream(inp, out, l.enc, id, "u", item.cancel) { meter.update(it) } }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Laptop said HTTP $code")
            item.done = maxOf(item.done, item.size); item.status = St.DONE
        } catch (e: CancelledException) {
            item.status = St.CANCELLED
            val id = sid
            if (id != null) try { Net.call(l, "POST", "/api/inbox/$id/cancel") } catch (e2: Exception) { }
        } catch (e: Exception) {
            item.status = St.FAILED; item.note = e.message ?: ""
        } finally {
            conn?.disconnect(); item.speed = 0; State.changed()
        }
    }
}

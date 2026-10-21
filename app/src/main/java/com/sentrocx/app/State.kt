package com.sentrocx.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class Dir { RECEIVED, SENT }
enum class St { QUEUED, WAITING_ACCEPT, ACTIVE, DONE, FAILED, CANCELLED, DECLINED }

class Item(val id: String, val name: String, val dir: Dir, @Volatile var size: Long) {
    @Volatile var status = St.QUEUED
    @Volatile var done = 0L
    @Volatile var speed = 0L
    @Volatile var note = ""
    var file: File? = null      // received file (in app storage until you Save it)
    var source: Uri? = null     // file chosen on the phone, kept so Retry works
    val cancel = AtomicBoolean(false)
}

/** A live connection to a paired laptop. */
class Link(val base: String, val device: String, val enc: ByteArray, val mac: ByteArray)

/** What we remember about a paired laptop. */
class Pairing(val laptopId: String, val deviceId: String, val laptopName: String, val enc: ByteArray, val mac: ByteArray)

object State {
    val items: MutableList<Item> = java.util.Collections.synchronizedList(mutableListOf<Item>())
    @Volatile var link: Link? = null
    @Volatile var connected = false
    @Volatile var paused = false
    @Volatile var listener: (() -> Unit)? = null
    fun changed() { listener?.invoke() }
    fun snapshot(): List<Item> = synchronized(items) { items.toList() }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("sentrocx", Context.MODE_PRIVATE)
    fun manual(c: Context): String = sp(c).getString("manual", "") ?: ""
    fun setManual(c: Context, v: String) = sp(c).edit().putString("manual", v).apply()
    fun folder(c: Context): String? = sp(c).getString("folder", null)
    fun setFolder(c: Context, v: String) = sp(c).edit().putString("folder", v).apply()
    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = sp(c).edit().putBoolean("paused", v).apply()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String?) = Base64.decode(s ?: "", Base64.NO_WRAP)
    fun pairing(c: Context): Pairing? {
        val s = sp(c); val lid = s.getString("lid", null) ?: return null
        return Pairing(lid, s.getString("did", "") ?: "", s.getString("lname", "your laptop") ?: "your laptop",
            unb64(s.getString("enc", null)), unb64(s.getString("mac", null)))
    }
    fun setPairing(c: Context, p: Pairing) = sp(c).edit().putString("lid", p.laptopId).putString("did", p.deviceId)
        .putString("lname", p.laptopName).putString("enc", b64(p.enc)).putString("mac", b64(p.mac)).apply()
    fun clearPairing(c: Context) = sp(c).edit().remove("lid").remove("did").remove("lname").remove("enc").remove("mac").apply()
}

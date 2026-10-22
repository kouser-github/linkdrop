package com.sentrocx.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
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
class Link(val base: String, val device: String, val enc: ByteArray, val mac: ByteArray, val laptopId: String = "")

/** What we remember about a saved laptop. */
class Pairing(
    val laptopId: String,
    val deviceId: String,
    var nickname: String,
    val enc: ByteArray,
    val mac: ByteArray,
    var autoConnect: Boolean = true
)

object State {
    val items: MutableList<Item> = java.util.Collections.synchronizedList(mutableListOf<Item>())
    @Volatile var link: Link? = null
    @Volatile var activeLaptop: Pairing? = null
    @Volatile var targetLaptopId: String? = null
    @Volatile var connected = false
    @Volatile var paused = false
    @Volatile var serviceRunning = false
    @Volatile var removedByLaptop: String? = null
    @Volatile var listener: (() -> Unit)? = null

    fun changed() { listener?.invoke() }
    fun snapshot(): List<Item> = synchronized(items) { items.toList() }

    fun hasActiveTransfers(): Boolean = snapshot().any {
        it.status == St.QUEUED || it.status == St.WAITING_ACCEPT || it.status == St.ACTIVE
    }

    /** Cancels all active transfers. Returns true if at least one transfer was active. */
    fun cancelActiveTransfers(): Boolean {
        val active = snapshot().filter {
            it.status == St.QUEUED || it.status == St.WAITING_ACCEPT || it.status == St.ACTIVE
        }
        if (active.isEmpty()) return false
        for (it in active) {
            it.cancel.set(true)
            if (it.status == St.QUEUED) it.status = St.CANCELLED
        }
        changed()
        return true
    }
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

    /** Returns all saved laptops, migrating legacy Stage 1 single-laptop storage if present. */
    fun laptops(c: Context): List<Pairing> {
        val s = sp(c)
        val raw = s.getString("laptops", null)
        if (raw == null) {
            val legacyLid = s.getString("lid", null)
            if (legacyLid != null) {
                val migrated = Pairing(
                    legacyLid,
                    s.getString("did", "") ?: "",
                    s.getString("lname", "Laptop") ?: "Laptop",
                    unb64(s.getString("enc", null)),
                    unb64(s.getString("mac", null)),
                    true
                )
                saveLaptops(c, listOf(migrated))
                s.edit().remove("lid").remove("did").remove("lname").remove("enc").remove("mac").apply()
                return listOf(migrated)
            }
            return emptyList()
        }
        val out = mutableListOf<Pairing>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Pairing(
                        o.getString("lid"),
                        o.getString("did"),
                        o.getString("nick"),
                        unb64(o.getString("enc")),
                        unb64(o.getString("mac")),
                        o.optBoolean("auto", true)
                    )
                )
            }
        } catch (e: Exception) { }
        return out
    }

    fun saveLaptops(c: Context, list: List<Pairing>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("lid", p.laptopId)
                    .put("did", p.deviceId)
                    .put("nick", p.nickname)
                    .put("enc", b64(p.enc))
                    .put("mac", b64(p.mac))
                    .put("auto", p.autoConnect)
            )
        }
        sp(c).edit().putString("laptops", arr.toString()).apply()
    }

    fun addOrUpdateLaptop(c: Context, p: Pairing) {
        val list = laptops(c).filterNot { it.laptopId == p.laptopId }.toMutableList()
        list.add(0, p)
        saveLaptops(c, list)
    }

    fun setAutoConnect(c: Context, laptopId: String, auto: Boolean) {
        val list = laptops(c)
        list.find { it.laptopId == laptopId }?.autoConnect = auto
        saveLaptops(c, list)
    }

    fun removeLaptop(c: Context, laptopId: String) {
        val list = laptops(c).filterNot { it.laptopId == laptopId }
        saveLaptops(c, list)
    }
}

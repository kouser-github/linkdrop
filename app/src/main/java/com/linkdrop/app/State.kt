package com.linkdrop.app

import android.content.Context
import android.net.Uri
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

object State {
    val items: MutableList<Item> = java.util.Collections.synchronizedList(mutableListOf<Item>())
    @Volatile var baseUrl: String? = null
    @Volatile var connected = false
    @Volatile var paused = false
    @Volatile var listener: (() -> Unit)? = null
    fun changed() { listener?.invoke() }
    fun snapshot(): List<Item> = synchronized(items) { items.toList() }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("linkdrop", Context.MODE_PRIVATE)
    fun manual(c: Context): String = sp(c).getString("manual", "") ?: ""
    fun setManual(c: Context, v: String) = sp(c).edit().putString("manual", v).apply()
    fun folder(c: Context): String? = sp(c).getString("folder", null)
    fun setFolder(c: Context, v: String) = sp(c).edit().putString("folder", v).apply()
    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = sp(c).edit().putBoolean("paused", v).apply()
}

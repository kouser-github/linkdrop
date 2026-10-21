package com.linkdrop.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.IOException

/** Runs in the background: finds the laptop and downloads files it sends, with no prompt. */
class SyncService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "LinkDrop background", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "sync")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("LinkDrop is running")
            .setContentText("Ready to receive files from your laptop")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(1, n)
        if (!running) { running = true; Thread { loop() }.start() }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun find(): String? {
        val m = Prefs.manual(this)
        if (m.isNotBlank()) { val b = Net.normalize(m); if (Net.ping(b)) return b }
        val d = Net.discover()
        return if (d != null && Net.ping(d)) d else null
    }

    private fun loop() {
        while (running) {
            var base = State.baseUrl
            if (base == null) {
                base = find()
                State.baseUrl = base
                State.connected = base != null
                State.changed()
                if (base == null) { Thread.sleep(3000); continue }
            }
            try {
                if (State.paused) Net.getText(base, "/api/info") else poll(base)
                if (!State.connected) { State.connected = true; State.changed() }
                Thread.sleep(1500)
            } catch (e: Exception) {
                State.baseUrl = null; State.connected = false; State.changed()
                Thread.sleep(2000)
            }
        }
    }

    private fun poll(base: String) {
        val arr = JSONArray(Net.getText(base, "/api/outbox"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("status") != "waiting") continue
            val id = o.getString("id")
            if (State.snapshot().any { it.id == id }) continue
            val item = Item(id, o.getString("name"), Dir.RECEIVED, o.optLong("size", 0))
            State.items.add(0, item)
            Transfer.download(base, item, filesDir)
        }
    }
}

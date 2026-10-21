package com.sentrocx.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray

/** Runs in the background: finds your paired laptop and downloads files it sends, with no prompt. */
class SyncService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "SentRocx background", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "sync")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SentRocx is running")
            .setContentText("Ready to receive files from your laptop")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(1, n)
        if (!running) { running = true; Thread { loop() }.start() }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun find(): Link? {
        val p = Prefs.pairing(this) ?: return null
        val m = Prefs.manual(this)
        val base = (if (m.isNotBlank()) Net.normalize(m) else Net.discover()?.base) ?: return null
        val l = Link(base, p.deviceId, p.enc, p.mac)
        return if (Net.ping(l, p.laptopId)) l else null
    }

    private fun loop() {
        while (running) {
            var link = State.link
            if (link == null) {
                link = find()
                State.link = link
                State.connected = link != null
                State.changed()
                if (link == null) { Thread.sleep(3000); continue }
            }
            try {
                if (State.paused) Net.call(link, "GET", "/api/info") else poll(link)
                if (!State.connected) { State.connected = true; State.changed() }
                Thread.sleep(1500)
            } catch (e: Exception) {
                State.link = null; State.connected = false; State.changed()
                Thread.sleep(2000)
            }
        }
    }

    private fun poll(l: Link) {
        val arr = JSONArray(Net.call(l, "GET", "/api/outbox"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("status") != "waiting") continue
            val id = o.getString("id")
            if (State.snapshot().any { it.id == id }) continue
            val item = Item(id, o.getString("name"), Dir.RECEIVED, o.optLong("size", 0))
            State.items.add(0, item)
            Transfer.download(l, item, filesDir)
        }
    }
}

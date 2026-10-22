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
    companion object {
        const val ACTION_STOP = "com.sentrocx.app.STOP"
    }

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!State.cancelActiveTransfers()) {
                shutdownService()
            }
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "SentRocx background", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, SyncService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "sync")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SentRocx is running")
            .setContentText("Ready to receive files from your laptop")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stopPending)
            .build()
        startForeground(1, n)
        State.serviceRunning = true
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        State.changed()
        return START_STICKY
    }

    private fun shutdownService() {
        running = false
        State.serviceRunning = false
        State.link = null
        State.activeLaptop = null
        State.targetLaptopId = null
        State.connected = false
        Net.stopDiscovery()
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        State.changed()
    }

    override fun onDestroy() {
        running = false
        State.serviceRunning = false
        Net.stopDiscovery()
        worker?.interrupt()
        State.changed()
        super.onDestroy()
    }

    private fun handleUnpaired(p: Pairing) {
        Prefs.removeLaptop(this, p.laptopId)
        if (State.targetLaptopId == p.laptopId) State.targetLaptopId = null
        State.link = null
        State.activeLaptop = null
        State.connected = false
        State.removedByLaptop = p.nickname
        State.changed()
    }

    private fun find(): Link? {
        val all = Prefs.laptops(this)
        if (all.isEmpty()) return null
        val targetId = State.targetLaptopId
        val candidates = if (targetId != null) {
            all.filter { it.laptopId == targetId }
        } else {
            all.filter { it.autoConnect }
        }
        if (candidates.isEmpty()) return null

        val m = Prefs.manual(this)
        if (m.isNotBlank()) {
            val base = Net.normalize(m)
            val infoId = try { Net.plain(base, "/api/info").optString("id") } catch (e: Exception) { "" }
            val matched = if (infoId.isNotEmpty()) candidates.find { it.laptopId == infoId } else candidates.firstOrNull()
            if (matched != null) {
                val l = Link(base, matched.deviceId, matched.enc, matched.mac, matched.laptopId)
                try {
                    if (Net.ping(l, matched.laptopId)) {
                        State.activeLaptop = matched
                        State.removedByLaptop = null
                        return l
                    }
                } catch (e: UnpairedException) {
                    handleUnpaired(matched)
                    return null
                }
            }
            return null
        }

        val discovered = Net.discoverAll()
        for (found in discovered) {
            val matched = if (found.id.isNotEmpty()) {
                candidates.find { it.laptopId == found.id }
            } else {
                candidates.firstOrNull()
            } ?: continue
            val l = Link(found.base, matched.deviceId, matched.enc, matched.mac, matched.laptopId)
            try {
                if (Net.ping(l, matched.laptopId)) {
                    State.activeLaptop = matched
                    State.removedByLaptop = null
                    return l
                }
            } catch (e: UnpairedException) {
                handleUnpaired(matched)
            }
        }
        return null
    }

    private fun loop() {
        while (running) {
            var link = State.link
            if (link == null) {
                link = find()
                if (!running) break
                State.link = link
                State.connected = link != null
                State.changed()
                if (link == null) {
                    try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                    continue
                }
            }
            try {
                if (State.paused) Net.call(link, "GET", "/api/info") else poll(link)
                if (!State.connected) { State.connected = true; State.changed() }
                Thread.sleep(1500)
            } catch (e: UnpairedException) {
                val cur = State.activeLaptop ?: Prefs.laptops(this).find { it.laptopId == link.laptopId }
                if (cur != null) handleUnpaired(cur) else {
                    State.link = null; State.connected = false; State.changed()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                State.link = null; State.connected = false; State.changed()
                try { Thread.sleep(2000) } catch (ie: InterruptedException) { break }
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

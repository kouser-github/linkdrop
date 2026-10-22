package com.sentrocx.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sentrocx.app.databinding.ActivityMainBinding
import com.sentrocx.app.databinding.ItemFileBinding
import java.io.IOException
import java.net.URLConnection
import java.util.UUID

private fun View.show(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastAnnouncedUnpaired: String? = null
    private val adapter = Adapter()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { send(it) }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Prefs.setFolder(this, uri.toString())
            say("Save folder set")
        }
    }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startSync() }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        State.paused = Prefs.paused(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.fab.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        b.btnAddr.setOnClickListener {
            if (Prefs.laptops(this).isNotEmpty()) addressDialog() else startPairing()
        }
        b.btnPairNew.setOnClickListener { startPairing() }
        b.btnStop.setOnClickListener { handleStopButton() }

        b.toolbar.inflateMenu(R.menu.main_menu)
        b.toolbar.menu.findItem(R.id.action_pause).isChecked = State.paused
        b.toolbar.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.action_save_all -> saveAll(State.snapshot().filter { it.dir == Dir.RECEIVED && it.status == St.DONE })
                R.id.action_delete_all -> confirmDeleteAll()
                R.id.action_clear -> clearFinished()
                R.id.action_pause -> {
                    m.isChecked = !m.isChecked
                    State.paused = m.isChecked
                    Prefs.setPaused(this, m.isChecked)
                    say(if (m.isChecked) "Receiving paused" else "Receiving resumed")
                }
                R.id.action_folder -> pickFolder.launch(null)
                R.id.action_address -> addressDialog()
                R.id.action_pair -> startPairing()
                R.id.action_laptops -> savedLaptopsDialog()
                R.id.action_stop -> handleStopButton()
                R.id.action_about -> aboutDialog()
            }
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startSync()
    }

    private fun startSync() {
        State.serviceRunning = true
        ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
        refresh()
    }

    private fun handleStopButton() {
        if (State.cancelActiveTransfers()) {
            say("Cancelled active transfer(s)")
            refresh()
            return
        }
        if (State.serviceRunning) {
            startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
            say("Stopped service and scanning")
        } else {
            startSync()
            say("Started service")
        }
    }

    override fun onResume() {
        super.onResume()
        State.listener = { handler.post { schedule() } }
        refresh()
    }

    override fun onPause() { super.onPause(); State.listener = null }

    private fun schedule() {
        if (pending) return
        pending = true
        handler.postDelayed({ pending = false; refresh() }, 250)
    }

    private fun say(msg: String) { Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).setAnchorView(b.fab).show() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun refresh() {
        val list = State.snapshot()
        adapter.data = list
        adapter.notifyDataSetChanged()
        b.empty.show(list.isEmpty())

        val saved = Prefs.laptops(this)
        val active = State.activeLaptop ?: saved.find { it.laptopId == State.link?.laptopId }
        val removed = State.removedByLaptop
        if (removed != null && removed != lastAnnouncedUnpaired) {
            lastAnnouncedUnpaired = removed
            say("Removed by $removed. Pair again to connect.")
        }

        val hasActive = State.hasActiveTransfers()
        b.btnStop.text = if (hasActive || State.serviceRunning) "Stop" else "Start"
        b.toolbar.menu.findItem(R.id.action_stop)?.title = if (hasActive || State.serviceRunning) "Stop" else "Start"
        b.btnAddr.text = if (saved.isNotEmpty()) "Address" else "Pair"

        val autoCandidates = saved.filter { it.autoConnect || it.laptopId == State.targetLaptopId }
        b.statusText.text = when {
            !State.serviceRunning -> "Stopped. Tap Start to resume."
            State.connected && active != null -> "Connected securely to ${active.nickname}"
            removed != null && saved.isEmpty() -> "Removed by $removed. Tap Pair to reconnect."
            saved.isEmpty() -> "Not paired. Tap Pair, then compare the code with the laptop page."
            autoCandidates.isEmpty() -> "Auto-connect is off. Enable Auto or tap Connect on a saved laptop."
            else -> {
                val names = autoCandidates.joinToString(", ") { it.nickname }
                "Looking for $names. Is it on the same Wi-Fi with SentRocx running?"
            }
        }

        renderSavedLaptops(saved, active)
    }

    private fun renderSavedLaptops(saved: List<Pairing>, active: Pairing?) {
        b.savedEmpty.show(saved.isEmpty())
        b.savedList.removeAllViews()
        b.savedList.show(saved.isNotEmpty())
        for (p in saved) {
            b.savedList.addView(buildLaptopRow(p, active?.laptopId == p.laptopId && State.connected, null))
        }
    }

    private fun buildLaptopRow(p: Pairing, isConnected: Boolean, parentDialog: AlertDialog?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = p.nickname
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        val sub = TextView(this).apply {
            text = when {
                isConnected -> "Connected"
                p.autoConnect -> "Auto-connect on"
                else -> "Auto-connect off"
            }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            if (isConnected) setTextColor(themeColor(com.google.android.material.R.attr.colorTertiary))
        }
        textCol.addView(title)
        textCol.addView(sub)
        row.addView(textCol)

        val autoBox = MaterialCheckBox(this).apply {
            text = "Auto"
            isChecked = p.autoConnect
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoConnect(this@MainActivity, p.laptopId, checked)
                if (!checked && State.activeLaptop?.laptopId == p.laptopId && State.targetLaptopId != p.laptopId) {
                    State.link = null
                    State.activeLaptop = null
                    State.connected = false
                }
                refresh()
            }
        }
        row.addView(autoBox)

        if (!isConnected) {
            val btnConn = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Connect"
                setOnClickListener {
                    State.targetLaptopId = p.laptopId
                    State.link = null
                    State.connected = false
                    if (!State.serviceRunning) startSync() else State.changed()
                    say("Connecting to ${p.nickname}…")
                    parentDialog?.dismiss()
                }
            }
            row.addView(btnConn)
        }

        val btnForget = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Forget"
            setTextColor(themeColor(com.google.android.material.R.attr.colorError))
            setOnClickListener {
                parentDialog?.dismiss()
                confirmForget(p)
            }
        }
        row.addView(btnForget)
        return row
    }

    private fun savedLaptopsDialog() {
        val saved = Prefs.laptops(this)
        if (saved.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Saved laptops")
                .setMessage("No laptops saved yet. Pair with a laptop first.")
                .setPositiveButton("Pair") { _, _ -> startPairing() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Saved laptops")
            .setView(scroll)
            .setNeutralButton("Pair new") { _, _ -> startPairing() }
            .setPositiveButton("Close", null)
            .create()
        val activeId = if (State.connected) State.activeLaptop?.laptopId ?: State.link?.laptopId else null
        for (p in saved) {
            container.addView(buildLaptopRow(p, activeId == p.laptopId, dlg))
        }
        dlg.show()
    }

    // ---------- sending ----------
    private fun send(uri: Uri) {
        val base = State.link
        if (base == null) { say("Laptop not connected yet"); return }
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val item = Item(UUID.randomUUID().toString(), name, Dir.SENT, size)
        item.source = uri
        State.items.add(0, item)
        Thread { Transfer.upload(applicationContext, base, item) }.start()
        refresh()
    }

    private fun retry(item: Item) {
        val base = State.link
        if (base == null) { say("Laptop not connected yet"); return }
        Thread {
            if (item.dir == Dir.RECEIVED) Transfer.download(base, item, filesDir)
            else Transfer.upload(applicationContext, base, item)
        }.start()
    }

    // ---------- saving / deleting ----------
    private fun saveAll(list: List<Item>) {
        if (list.isEmpty()) { say("Nothing to save"); return }
        val folder = Prefs.folder(this)
        val tree = folder?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }
        if (tree == null || !tree.canWrite()) {
            Snackbar.make(b.root, "Choose a save folder first", Snackbar.LENGTH_LONG)
                .setAnchorView(b.fab).setAction("Choose") { pickFolder.launch(null) }.show()
            return
        }
        Thread {
            var ok = 0
            for (item in list) {
                try {
                    val f = item.file ?: continue
                    val mime = URLConnection.guessContentTypeFromName(item.name) ?: "application/octet-stream"
                    val out = tree.createFile(mime, item.name) ?: continue
                    contentResolver.openOutputStream(out.uri)?.use { o -> f.inputStream().use { i -> i.copyTo(o) } }
                    f.delete()
                    State.items.remove(item)
                    ok++
                } catch (e: Exception) { }
            }
            runOnUiThread { say("Saved $ok of ${list.size}") }
            State.changed()
        }.start()
    }

    private fun delete(item: Item) { item.file?.delete(); State.items.remove(item); refresh() }

    private fun confirmDeleteAll() {
        val list = State.snapshot().filter { it.dir == Dir.RECEIVED && it.status == St.DONE }
        if (list.isEmpty()) { say("Nothing to delete"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${list.size} file(s)?")
            .setMessage("They will be removed from this phone. Saved files in your folder are not touched.")
            .setPositiveButton("Delete") { _, _ -> list.forEach { delete(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun clearFinished() {
        State.snapshot().filter {
            it.status == St.FAILED || it.status == St.CANCELLED || it.status == St.DECLINED ||
                (it.dir == Dir.SENT && it.status == St.DONE)
        }.forEach { it.file?.delete(); State.items.remove(it) }
        refresh()
    }

    // ---------- dialogs ----------
    private fun addressDialog() {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = "Laptop address, like 192.168.1.5:3000"
        val et = TextInputEditText(til.context)
        et.setText(Prefs.manual(this))
        til.addView(et)
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(til) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Laptop address")
            .setMessage("Leave this empty to find the laptop automatically. The laptop page shows its address under About.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setManual(this, et.text.toString().trim())
                State.link = null; State.connected = false
                if (!State.serviceRunning) startSync() else refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun aboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_logo)
            .setTitle("SentRocx 3.0")
            .setMessage(
                "Move small files between your phone and laptop over the same Wi-Fi. No cloud, no accounts.\n\n" +
                "• Everything is encrypted (AES-256-GCM), and only laptops you paired can connect.\n" +
                "• Files sent from the laptop arrive here by themselves. Tap Save to keep one in your folder, or Delete.\n" +
                "• Files you send wait for you to press Accept on the laptop page.\n" +
                "• You can cancel any transfer while it runs.\n\n" +
                "To keep receiving in the background, turn off battery optimisation for SentRocx.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Battery settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .show()
    }

    // ---------- pairing ----------
    private fun startPairing() {
        if (!State.serviceRunning) startSync()
        say("Looking for your laptop…")
        Thread {
            try {
                val f = Net.locate(Prefs.manual(this)) ?: throw IOException("laptop not found")
                val s = PairSession(f.base, Build.MODEL ?: "Phone")
                s.start()
                val sas = s.reveal()
                runOnUiThread { codeDialog(s, sas, f.base) }
            } catch (e: Exception) {
                runOnUiThread { say(if (e.message?.contains("409") == true) "Another pairing is in progress on the laptop" else "Pairing failed: ${e.message}") }
            }
        }.start()
    }

    private fun codeDialog(s: PairSession, sas: String, base: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Check the code")
            .setMessage("The laptop page should show this code:\n\n${sas.substring(0, 3)} ${sas.substring(3)}\n\nTap Yes only if it is exactly the same.")
            .setCancelable(false)
            .setPositiveButton("Yes, same") { _, _ -> Thread { finishPairing(s, base) }.start() }
            .setNegativeButton("No") { _, _ -> Thread { try { s.cancel() } catch (e: Exception) { } }.start() }
            .show()
    }

    private fun finishPairing(s: PairSession, base: String) {
        try {
            s.confirm()
            runOnUiThread { say("Now press “Codes match” on the laptop page") }
            val end = System.currentTimeMillis() + 110_000
            while (System.currentTimeMillis() < end) {
                val st = s.status()
                when (st.getString("state")) {
                    "approved" -> {
                        val k = s.keys()
                        val lid = st.getString("laptop")
                        val did = st.getString("device")
                        val enc = k.copyOfRange(0, 32)
                        val mac = k.copyOfRange(32, 64)
                        runOnUiThread { promptNicknameDialog(lid, did, enc, mac, base) }
                        return
                    }
                    "rejected", "none" -> { runOnUiThread { say("Pairing was declined or expired") }; return }
                }
                Thread.sleep(1000)
            }
            try { s.cancel() } catch (e: Exception) { }
            runOnUiThread { say("Pairing timed out") }
        } catch (e: Exception) { runOnUiThread { say("Pairing failed: ${e.message}") } }
    }

    private fun promptNicknameDialog(lid: String, did: String, enc: ByteArray, mac: ByteArray, base: String) {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = "Laptop nickname"
        val et = TextInputEditText(til.context)
        til.addView(et)
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(til) }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Name this laptop")
            .setMessage("Enter a nickname to save this laptop:")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nick = et.text?.toString()?.trim() ?: ""
                if (nick.isEmpty()) {
                    til.error = "Nickname required"
                    return@setOnClickListener
                }
                val p = Pairing(lid, did, nick, enc, mac, true)
                Prefs.addOrUpdateLaptop(this, p)
                State.removedByLaptop = null
                State.activeLaptop = p
                State.targetLaptopId = null
                State.link = Link(base, p.deviceId, p.enc, p.mac, p.laptopId)
                State.connected = true
                State.changed()
                dlg.dismiss()
                say("Paired with ${p.nickname}")
                refresh()
            }
        }
        dlg.show()
    }

    private fun confirmForget(p: Pairing) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Forget ${p.nickname}?")
            .setMessage("You will need to pair again before sending or receiving with ${p.nickname}.")
            .setPositiveButton("Forget") { _, _ ->
                Prefs.removeLaptop(this, p.laptopId)
                if (State.activeLaptop?.laptopId == p.laptopId || State.link?.laptopId == p.laptopId) {
                    State.link = null
                    State.activeLaptop = null
                    State.connected = false
                }
                if (State.targetLaptopId == p.laptopId) State.targetLaptopId = null
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- list ----------
    inner class Adapter : RecyclerView.Adapter<VH>() {
        var data: List<Item> = emptyList()
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
    }

    inner class VH(private val v: ItemFileBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(item: Item) {
            val recv = item.dir == Dir.RECEIVED
            val arrow = if (recv) "From laptop" else "To laptop"
            val pct = if (item.size > 0) (item.done * 100 / item.size).toInt() else 0
            v.name.text = item.name
            v.sub.text = when (item.status) {
                St.QUEUED -> "$arrow · queued"
                St.WAITING_ACCEPT -> "$arrow · waiting for you to accept on the laptop"
                St.ACTIVE -> "$arrow · $pct% · ${fmtBytes(item.speed)}/s · ${fmtBytes(item.done)} of ${fmtBytes(item.size)}"
                St.DONE -> if (recv) "$arrow · ${fmtBytes(item.size)} · ready to save" else "$arrow · sent"
                St.FAILED -> "$arrow · failed. ${item.note}"
                St.CANCELLED -> "$arrow · cancelled"
                St.DECLINED -> "$arrow · declined on the laptop"
            }
            val busy = item.status == St.QUEUED || item.status == St.WAITING_ACCEPT || item.status == St.ACTIVE
            v.bar.visibility = View.GONE
            if (item.status == St.ACTIVE || item.status == St.WAITING_ACCEPT) {
                v.bar.isIndeterminate = item.status == St.WAITING_ACCEPT || item.size <= 0
                if (!v.bar.isIndeterminate) v.bar.progress = pct
                v.bar.visibility = View.VISIBLE
            }
            val ready = recv && item.status == St.DONE
            v.btnCancel.show(busy)
            v.btnSave.show(ready)
            v.btnDelete.show(ready)
            v.btnRetry.show(item.status == St.FAILED)
            v.btnRemove.show(!busy && !ready)
            v.btnCancel.setOnClickListener { item.cancel.set(true); say("Cancelling…") }
            v.btnSave.setOnClickListener { saveAll(listOf(item)) }
            v.btnDelete.setOnClickListener { delete(item) }
            v.btnRetry.setOnClickListener { retry(item) }
            v.btnRemove.setOnClickListener { item.file?.delete(); State.items.remove(item); refresh() }
        }
    }
}

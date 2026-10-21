package com.linkdrop.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.linkdrop.app.databinding.ActivityMainBinding
import com.linkdrop.app.databinding.ItemFileBinding
import java.net.URLConnection
import java.util.UUID

private fun View.show(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
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
        b.btnAddr.setOnClickListener { addressDialog() }

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
                R.id.action_about -> aboutDialog()
            }
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startSync()
    }

    private fun startSync() = ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))

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

    private fun refresh() {
        val list = State.snapshot()
        adapter.data = list
        adapter.notifyDataSetChanged()
        b.empty.show(list.isEmpty())
        b.statusText.text = if (State.connected)
            "Connected to ${State.baseUrl?.removePrefix("http://")}"
        else "Looking for your laptop. Is it on the same Wi-Fi with LinkDrop running?"
    }

    // ---------- sending ----------
    private fun send(uri: Uri) {
        val base = State.baseUrl
        if (base == null) { say("Laptop not found yet"); return }
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
        val base = State.baseUrl
        if (base == null) { say("Laptop not found yet"); return }
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
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, 8, pad, 0); addView(til) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Laptop address")
            .setMessage("Leave this empty to find the laptop automatically. The laptop page shows its address under About.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setManual(this, et.text.toString().trim())
                State.baseUrl = null; State.connected = false
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun aboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_logo)
            .setTitle("LinkDrop 2.0")
            .setMessage(
                "Move small files between your phone and laptop over the same Wi-Fi. No cloud, no accounts.\n\n" +
                "• Files sent from the laptop arrive here by themselves. Tap Save to keep one in your folder, or Delete.\n" +
                "• Files you send wait for you to press Accept on the laptop page.\n" +
                "• You can cancel any transfer while it runs.\n\n" +
                "There is no password, so anyone on your Wi-Fi can use LinkDrop. Use trusted networks.\n\n" +
                "To keep receiving in the background, turn off battery optimisation for LinkDrop.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Battery settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .show()
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

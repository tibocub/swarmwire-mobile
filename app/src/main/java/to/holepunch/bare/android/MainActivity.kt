package to.holepunch.bare.android

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.File

// One screen: the list of everything being downloaded or shared, and Send / Get / Bridge / Stop all
// along the bottom. The first three open popups that stay open, so several things can be done in a row.
// The worklet sends the whole list once a second; `onScan` and `onPicked` belong to the open popup.
class MainActivity : Activity() {
  private lateinit var worker: Worker
  private lateinit var speeds: TextView
  private lateinit var directBox: CheckBox
  private val rows = Rows()
  private var entries = listOf<JSONObject>()

  private var onScan: ((String) -> Unit)? = null
  private var onPicked: ((Uri) -> Unit)? = null
  private var onSaveTarget: ((Uri) -> Unit)? = null
  private var onPopup: ((JSONObject) -> Unit)? = null // worklet messages for the open popup
  private var status: TextView? = null
  private var open: AlertDialog? = null
  private var serving = false // the foreground service is up
  private var shownNote: String? = null
  private val held = mutableListOf<ParcelFileDescriptor>() // picked files stay open while they are served

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val list = ListView(this).apply {
      adapter = rows
      divider = null
      setOnItemClickListener { _, _, i, _ -> menu(entries[i]) }
    }
    val empty = TextView(this).apply { text = "Nothing yet.\nGet downloads a link, Send shares a file."; gravity = Gravity.CENTER }
    list.emptyView = empty

    speeds = TextView(this).apply { gravity = Gravity.END }
    directBox = CheckBox(this).apply {
      text = "Save straight to Downloads/swarmwire (needs all-files access)"
      isChecked = getPreferences(MODE_PRIVATE).getBoolean("direct", false)
      setOnCheckedChangeListener { _, on -> direct(on) }
    }

    val bar = LinearLayout(this).apply {
      button("Send") { showSend() }
      button("Get") { showGet() }
      button("Bridge") { showBridge() }
      button("Stop all") { stopAll() }
      for (i in 0 until childCount) getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(TextView(this@MainActivity).apply { text = "swarmwire"; textSize = 28f })
      addView(speeds)
      addView(directBox)
      addView(FrameLayout(this@MainActivity).apply { addView(list); addView(empty) }, LinearLayout.LayoutParams(-1, 0, 1f))
      addView(bar)
      setOnApplyWindowInsetsListener { v, insets -> // targetSdk 35 draws under the system bars
        v.setPadding(48, insets.systemWindowInsetTop + 24, 48, insets.systemWindowInsetBottom + 24)
        insets
      }
    }
    setContentView(root)

    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0) // without it the service still runs, just unseen

    worker = Worker(this) { onMessage(it) }
    handle(intent)
  }

  // Test hook, no camera or tapping needed:
  //   adb shell am start -n .../.MainActivity --es screen get|send|bridge --es scan '<scanned text>'
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

  private fun handle(intent: Intent) {
    when (intent.getStringExtra("screen")) {
      "get" -> showGet()
      "send" -> showSend()
      "bridge" -> showBridge()
    }
    intent.getStringExtra("scan")?.let { onScan?.invoke(it) }
  }

  private fun onMessage(m: JSONObject) {
    when (m.getString("type")) {
      "list" -> {
        val all = m.getJSONArray("entries")
        entries = List(all.length()) { all.getJSONObject(it) }
        speeds.text = "↓ ${human(m.getDouble("down").toLong())}/s   ↑ ${human(m.getDouble("up").toLong())}/s"
        rows.notifyDataSetChanged()
        keepAlive(m)
      }
      "ready" -> {}
      else -> {
        val popup = onPopup
        if (popup != null) popup(m)
        else if (m.getString("type") == "delivered") toast("Link sent.")
        else if (m.getString("type") == "error") toast("Failed: ${m.getString("error")}")
      }
    }
  }

  // ---- the list ----

  private inner class Rows : BaseAdapter() {
    override fun getCount() = entries.size
    override fun getItem(i: Int) = entries[i]
    override fun getItemId(i: Int) = i.toLong()

    override fun getView(i: Int, old: View?, parent: ViewGroup): View {
      val e = entries[i]
      val text = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
      text.addView(TextView(this@MainActivity).apply {
        this.text = e.getString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD)
        setSingleLine(); ellipsize = TextUtils.TruncateAt.END
      })
      if (e.getString("state") == "downloading" || e.getString("state") == "connecting" && e.getString("kind") == "download") {
        text.addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (fraction(e) * 1000).toInt() })
      }
      text.addView(TextView(this@MainActivity).apply { this.text = describe(e); textSize = 13f })

      return LinearLayout(this@MainActivity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 16, 0, 16)
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply { this.text = "✕"; textSize = 24f; setPadding(32, 16, 16, 16); setOnClickListener { remove(e) } })
      }
    }
  }

  private fun fraction(e: JSONObject): Double {
    val size = e.optLong("size")
    return if (size > 0) minOf(e.optLong("done").toDouble() / size, 1.0) else 0.0
  }

  private fun describe(e: JSONObject): String {
    val peers = "${e.getInt("seeders")} seeders, ${e.getInt("peers") - e.getInt("seeders")} peers"
    return when (e.getString("state")) {
      "error" -> "Failed: ${e.getString("error")}"
      "connecting" -> "Looking for peers…"
      "downloading" -> "↓ ${human(e.getDouble("down").toLong())}/s  ${(fraction(e) * 100).toInt()}%  ${human(e.optLong("done"))} of ${human(e.optLong("size"))}  $peers"
      else -> "Seeding  ↑ ${human(e.getDouble("up").toLong())}/s  $peers" +
        (if (e.optBoolean("hidden")) "  private" else "") + (if (e.optBoolean("temp")) "  in app storage" else "")
    }
  }

  private fun remove(e: JSONObject) {
    val stop = { worker.send("type" to "stop", "id" to e.getString("id")) }
    if (!e.optBoolean("temp")) return stop()

    AlertDialog.Builder(this).setTitle("Remove ${e.getString("name")}?")
      .setMessage("Its copy in app storage is deleted too. Tap it and choose Save to phone first to keep it.")
      .setPositiveButton("Remove") { _, _ -> stop() }.setNegativeButton("Cancel", null).show()
  }

  private fun stopAll() {
    if (entries.isEmpty()) return
    AlertDialog.Builder(this).setTitle("Stop everything?")
      .setMessage("All ${entries.size} downloads and shared files stop. Copies kept in app storage are deleted.")
      .setPositiveButton("Stop all") { _, _ -> for (e in entries) worker.send("type" to "stop", "id" to e.getString("id")) }
      .setNegativeButton("Cancel", null).show()
  }

  // What tapping an entry offers.
  private fun menu(e: JSONObject) {
    val link = e.getString("link")
    val saveable = e.optBoolean("temp") && e.getString("state") == "seeding"
    val items = mutableListOf("Copy link to clipboard", "Show QR code", "Send to a device (scan its “get --qr” code)")
    if (saveable) items.add("Save to phone…")

    AlertDialog.Builder(this).setTitle(e.getString("name")).setItems(items.toTypedArray()) { _, which ->
      when (which) {
        0 -> {
          getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("swarmwire link", link))
          toast("Link copied.")
        }
        1 -> showQr(e)
        2 -> {
          onScan = { text ->
            if (text.startsWith("swarmwire-recv://")) { toast("Sending the link…"); worker.send("type" to "deliver", "url" to text, "link" to link) }
            else toast("That is not a receiver's code. Use the one “swarmwire get --qr” shows.")
          }
          scan()
        }
        3 -> save(e)
      }
    }.show()
  }

  private fun showQr(e: JSONObject) {
    val link = e.getString("link")
    val body = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 16, 48, 0)
      addView(ImageView(this@MainActivity).apply { adjustViewBounds = true; setImageBitmap(qrBitmap(link)) })
      addView(TextView(this@MainActivity).apply { text = link; setTextIsSelectable(true) })
    }
    AlertDialog.Builder(this).setTitle(e.getString("name")).setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Close", null).show()
  }

  // ---- popups: Send, Get, Bridge ----

  private fun popup(title: String, build: LinearLayout.() -> Unit) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
    body.build()
    status = TextView(this).apply { setPadding(0, 32, 0, 0) }
    body.addView(status)

    open?.dismiss() // one popup at a time
    onScan = null; onPicked = null; onPopup = null
    open = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(body) })
      .setNegativeButton("Close", null).create().apply {
        setOnDismissListener { onScan = null; onPicked = null; onPopup = null; status = null }
        show()
      }
  }

  private fun showGet() {
    val link = EditText(this).apply { hint = "swarmwire://…"; setSingleLine() }
    val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    val name = EditText(this).apply { setSingleLine() }
    val info = TextView(this)
    var pending: String? = null

    fun inspect(text: String) {
      if (!text.startsWith("swarmwire://")) return say("That is not a swarmwire link.")
      preview.visibility = View.GONE
      pending = text
      say("Looking…")
      worker.send("type" to "inspect", "link" to text)
    }

    popup("Get") {
      button("Scan a code") { scan() }
      addView(link)
      button("Look at this link") { inspect(link.text.toString().trim()) }
      addView(preview.apply {
        addView(TextView(this@MainActivity).apply { text = "Save as:"; setPadding(0, 24, 0, 0) })
        addView(name)
        addView(info)
        button("Download") {
          val chosen = pending ?: return@button
          worker.send("type" to "get", "link" to chosen, "name" to name.text.toString().trim(), "dest" to destination())
          preview.visibility = View.GONE
          link.setText("")
          pending = null
          say("Started. Scan another code, or close.")
        }
      })
    }
    onScan = ::inspect
    onPopup = { m ->
      when (m.getString("type")) {
        "inspected" -> if (m.getString("link") == pending) {
          name.setText(if (m.isNull("name")) "" else m.getString("name"))
          info.text = inspected(m) + "\n" + (destination()?.let { "Saves to $it" } ?: "Saves to app storage; tap it in the list afterwards to Save to phone.")
          preview.visibility = View.VISIBLE
          say("Check the name, then download.")
        }
        "error" -> say("Failed: ${m.getString("error")}")
      }
    }
  }

  private fun inspected(m: JSONObject): String {
    val size = if (m.isNull("size")) "unknown size" else human(m.getLong("size"))
    val lock = if (m.optBoolean("private")) ", private" else ""
    if (!m.optBoolean("folder")) return "File, $size$lock"

    val files = m.getJSONArray("files")
    val shown = (0 until minOf(files.length(), 12)).joinToString("\n") { "  ${files.getJSONObject(it).getString("path")}  (${human(files.getJSONObject(it).getLong("size"))})" }
    val more = if (files.length() > 12) "\n  … and ${files.length() - 12} more" else ""
    return "Folder, ${files.length()} files, $size$lock\n$shown$more"
  }

  private fun showSend() {
    var link: String? = null
    val hidden = CheckBox(this).apply { text = "Private: only people with the link can find it" }
    val qr = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true }
    val shown = TextView(this).apply { setTextIsSelectable(true) }
    val receiver = Button(this).apply { text = "Scan a receiver's code"; visibility = View.GONE; setOnClickListener { scan() } }

    popup("Send") {
      addView(hidden)
      button("Choose a file") { pickFile() }
      addView(qr); addView(shown); addView(receiver)
    }
    onPicked = { uri ->
      val pfd = contentResolver.openFileDescriptor(uri, "r")
      if (pfd == null) say("Could not open that file.") else {
        held.add(pfd)
        say("Preparing…")
        worker.send("type" to "serve", "path" to "fd:${pfd.fd}", "name" to displayName(uri), "private" to hidden.isChecked)
      }
    }
    onPopup = { m ->
      when (m.getString("type")) {
        "link" -> {
          link = m.getString("link")
          qr.setImageBitmap(qrBitmap(link!!)); qr.visibility = View.VISIBLE
          shown.text = link
          receiver.visibility = View.VISIBLE
          say("Sharing. Scan this code to get it, or scan receivers' codes one after another.")
        }
        "delivered" -> say("Sent. Scan another receiver's code, or close.")
        "error" -> say("Failed: ${m.getString("error")}")
      }
    }
    onScan = { text ->
      if (!text.startsWith("swarmwire-recv://")) say("That is not a receiver's code. Use the one a getter shows.")
      else if (link == null) say("Choose a file first.")
      else { say("Sending the link…"); worker.send("type" to "deliver", "url" to text, "link" to link) }
    }
  }

  private fun showBridge() {
    var link: String? = null
    val step = TextView(this).apply { textSize = 18f }
    fun reset() { link = null; step.text = "1. Scan the sender's code" }
    val change = Button(this).apply { text = "Change the sender"; setOnClickListener { reset() } }
    popup("Bridge") { addView(step); button("Scan a code") { scan() }; addView(change) }
    reset()

    onScan = { text ->
      val sender = link
      if (sender == null) {
        if (text.startsWith("swarmwire://")) {
          link = text
          step.text = "2. Scan receivers' codes, as many as you like"
          say("Got the sender's link.")
        } else say("That is not a sender's code (swarmwire://…).")
      } else if (text.startsWith("swarmwire-recv://")) {
        say("Handing the link over…")
        worker.send("type" to "deliver", "url" to text, "link" to sender)
      } else say("That is not a receiver's code (shown by a getter).")
    }
    onPopup = { m ->
      when (m.getString("type")) {
        "delivered" -> say("Done: that receiver now downloads from the sender. Scan the next receiver, or change the sender.")
        "error" -> say("Failed: ${m.getString("error")}")
      }
    }
  }

  // ---- where downloads go ----

  private fun destination(): String? =
    if (directBox.isChecked) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "swarmwire").path else null

  // Writing into shared storage by path needs "all files access", which only the user can grant in Settings.
  private fun direct(on: Boolean) {
    getPreferences(MODE_PRIVATE).edit().putBoolean("direct", on).apply()
    if (on && !Environment.isExternalStorageManager())
      startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
  }

  // ---- saving a finished download out of app storage ----

  private fun save(e: JSONObject) {
    val name = e.getString("name")
    val source = File(e.getString("path"))
    val folder = e.getBoolean("folder")

    onSaveTarget = { target ->
      toast("Saving…")
      Thread {
        try {
          if (folder) copyTree(source, DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target)), name)
          else contentResolver.openOutputStream(target, "wt")!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }

          worker.send("type" to "stop", "id" to e.getString("id")) // stops sharing it and frees the app copy
          runOnUiThread { toast("Saved $name.") }
        } catch (ex: Exception) {
          runOnUiThread { toast("Saving failed: ${ex.message}") }
        }
      }.start()
    }

    if (folder) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SAVE_TREE)
    else startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_TITLE, name), SAVE_FILE)
  }

  private fun copyTree(src: File, parent: Uri, name: String) {
    val dir = DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)!!
    for (f in src.listFiles()!!.sortedBy { it.name }) {
      if (f.isDirectory) copyTree(f, dir, f.name)
      else {
        val target = DocumentsContract.createDocument(contentResolver, dir, "application/octet-stream", f.name)!!
        contentResolver.openOutputStream(target, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
      }
    }
  }

  // ---- the background service and its notification ----

  // The service (a notification) runs while anything is listed; it is what keeps Android from freezing us.
  private fun keepAlive(m: JSONObject) {
    if (entries.isEmpty()) {
      if (serving) stopService(Intent(this, KeepAlive::class.java))
      serving = false
      shownNote = null
      return
    }

    val active = entries.filter { it.getString("kind") == "download" && it.getString("state") != "error" }
    var percent = -1
    val note = if (active.isNotEmpty()) {
      val done = active.sumOf { it.optLong("done") }
      val total = active.sumOf { it.optLong("size") }
      percent = if (total > 0) (done * 100 / total).toInt() else 0
      "Downloading ${active.size} · $percent% · ↓ ${human(m.getDouble("down").toLong())}/s"
    } else "Seeding ${entries.size} · ↑ ${human(m.getDouble("up").toLong())}/s"

    if (!serving) { startForegroundService(Intent(this, KeepAlive::class.java).putExtra("text", note)); serving = true }
    else if (note != shownNote) getSystemService(NotificationManager::class.java).notify(KeepAlive.ID, KeepAlive.notification(this, note, percent))
    shownNote = note
  }

  // ---- plumbing ----

  private fun scan() {
    IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setBeepEnabled(false)
      .setOrientationLocked(false).setPrompt("").initiateScan()
  }

  private fun pickFile() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FILE)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents?.let { onScan?.invoke(it); return }
    val uri = data?.data ?: return
    if (resultCode != RESULT_OK) return
    when (requestCode) {
      PICK_FILE -> onPicked?.invoke(uri)
      SAVE_FILE, SAVE_TREE -> onSaveTarget?.invoke(uri)
    }
  }

  private fun displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

  private fun say(text: String) { status?.text = text }
  private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

  private fun LinearLayout.button(label: String, action: () -> Unit): Button =
    Button(this@MainActivity).apply { text = label; setOnClickListener { action() } }.also { addView(it) }

  private fun human(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
    n >= 1_000_000 -> "%.1f MB".format(n / 1e6)
    n >= 1_000 -> "%.1f KB".format(n / 1e3)
    else -> "$n B"
  }

  // Back leaves the app running in the background rather than ending transfers.
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() { moveTaskToBack(true) }

  override fun onPause() { super.onPause(); if (entries.isEmpty()) worker.suspend() }

  override fun onResume() {
    super.onResume()
    worker.resume()
    if (directBox.isChecked && !Environment.isExternalStorageManager()) directBox.isChecked = false // not granted in Settings
  }

  override fun onDestroy() { super.onDestroy(); stopService(Intent(this, KeepAlive::class.java)); worker.close() }

  companion object {
    const val PICK_FILE = 1
    const val SAVE_FILE = 2
    const val SAVE_TREE = 3
  }
}

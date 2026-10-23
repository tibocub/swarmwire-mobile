package to.holepunch.bare.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.File

// Four screens on one activity: home, send, get, bridge. Each screen sets `onWorker` (messages from the
// worklet) and `onScan` (a scanned QR code); the buttons just call into them.
class MainActivity : Activity() {
  private lateinit var worker: Worker
  private lateinit var root: LinearLayout
  private var onWorker: (JSONObject) -> Unit = {}
  private var onScan: ((String) -> Unit)? = null
  private var onPicked: ((Uri) -> Unit)? = null
  private var onSaveTarget: ((Uri) -> Unit)? = null
  private var status: TextView? = null
  private var onHome = true
  private var busy = false
  private val held = mutableListOf<ParcelFileDescriptor>() // picked files stay open while they are served

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(root) }
    scroll.setOnApplyWindowInsetsListener { v, insets -> // targetSdk 35 draws under the system bars
      v.setPadding(48, insets.systemWindowInsetTop + 48, 48, insets.systemWindowInsetBottom + 48)
      insets
    }
    setContentView(scroll)
    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0) // without it the service still runs, just unseen

    worker = Worker(this) { onWorker(it) }
    showHome()
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

  // ---- screens ----

  private fun showHome() = screen("swarmwire", home = true) {
    button("Send") { showSend() }
    button("Get") { showGet() }
    button("Bridge") { showBridge() }
  }

  private fun showGet() {
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    val link = EditText(this).apply { hint = "swarmwire://…"; setSingleLine() }
    val saveBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun start(text: String) {
      if (!text.startsWith("swarmwire://")) return say("That is not a swarmwire link.")
      saveBox.removeAllViews()
      say("Connecting…")
      busy(true)
      worker.send("type" to "get", "link" to text)
    }

    screen("Get") {
      button("Scan a code") { scan() }
      addView(link)
      button("Download this link") { start(link.text.toString().trim()) }
      addView(bar)
      addView(saveBox)
    }
    onScan = ::start
    onWorker = { m ->
      when (m.getString("type")) {
        "progress" -> {
          val done = m.getLong("done"); val total = m.getLong("total")
          bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
          say("${human(done)} of ${human(total)}")
        }
        "done" -> {
          bar.progress = 1000
          busy(false)
          say("Downloaded ${m.getString("name")}.")
          saveBox.button("Save to phone…") { save(m, saveBox) }
        }
        "error" -> { busy(false); say("Failed: ${m.getString("error")}") }
      }
    }
  }

  private fun showSend() {
    var link: String? = null
    val qr = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true }
    val shown = TextView(this).apply { setTextIsSelectable(true) }
    val receiver = Button(this).apply { text = "Scan a receiver's code"; visibility = View.GONE; setOnClickListener { scan() } }

    screen("Send") {
      button("Choose a file") { pickFile() }
      addView(qr); addView(shown); addView(receiver)
      button("Stop sharing in the background") { busy(false); say("It now pauses whenever you leave the app.") }
    }
    onPicked = { uri ->
      val pfd = contentResolver.openFileDescriptor(uri, "r")
      if (pfd == null) say("Could not open that file.") else {
        held.add(pfd)
        say("Preparing…")
        worker.send("type" to "serve", "path" to "fd:${pfd.fd}", "name" to displayName(uri))
      }
    }
    onWorker = { m ->
      when (m.getString("type")) {
        "link" -> {
          link = m.getString("link")
          busy(true)
          qr.setImageBitmap(qrBitmap(link!!)); qr.visibility = View.VISIBLE
          shown.text = link
          receiver.visibility = View.VISIBLE
          say("Ready. Scan this code to get it. Sharing continues in the background while the notification shows.")
        }
        "delivered" -> say("Sent to the receiver. Sharing continues while the notification shows.")
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
    val scanButton = Button(this).apply { setOnClickListener { scan() } }

    fun reset() { link = null; step.text = "1. Scan the sender's code"; scanButton.text = "Scan the sender's code" }
    screen("Bridge") { addView(step); addView(scanButton) }
    reset()

    onScan = { text ->
      val sender = link
      if (sender == null) {
        if (text.startsWith("swarmwire://")) {
          link = text
          step.text = "2. Now scan the receiver's code"; scanButton.text = "Scan the receiver's code"
          say("Got the sender's link.")
        } else say("That is not a sender's code (swarmwire://…).")
      } else if (text.startsWith("swarmwire-recv://")) {
        say("Handing the link over…")
        worker.send("type" to "deliver", "url" to text, "link" to sender)
      } else say("That is not a receiver's code (shown by a getter).")
    }
    onWorker = { m ->
      when (m.getString("type")) {
        "delivered" -> { say("Done: the receiver now downloads straight from the sender."); reset() }
        "error" -> say("Failed: ${m.getString("error")}")
      }
    }
  }

  // ---- saving a finished download out of app storage ----

  private fun save(done: JSONObject, saveBox: LinearLayout) {
    val name = done.getString("name")
    val source = File(done.getString("path"))

    onSaveTarget = { target ->
      say("Saving…")
      Thread {
        try {
          if (done.getBoolean("folder")) copyTree(source, DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target)), name)
          else contentResolver.openOutputStream(target, "wt")!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }

          worker.send("type" to "release", "root" to done.getString("root")) // stop serving it, then free the space
          source.parentFile?.deleteRecursively()
          runOnUiThread { say("Saved $name."); saveBox.removeAllViews() }
        } catch (e: Exception) {
          runOnUiThread { say("Saving failed: ${e.message}") }
        }
      }.start()
    }

    if (done.getBoolean("folder")) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SAVE_TREE)
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

  private fun screen(title: String, home: Boolean = false, build: LinearLayout.() -> Unit) {
    onHome = home
    onScan = null; onPicked = null; onWorker = {}
    root.removeAllViews()
    root.addView(TextView(this).apply { text = title; textSize = 28f; setPadding(0, 0, 0, 32) })
    root.build()
    status = TextView(this).apply { setPadding(0, 32, 0, 0) }
    root.addView(status)
  }

  private fun say(text: String) { status?.text = text }

  private fun LinearLayout.button(label: String, action: () -> Unit): Button =
    Button(this@MainActivity).apply { text = label; setOnClickListener { action() } }.also { addView(it) }

  private fun human(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
    n >= 1_000_000 -> "%.1f MB".format(n / 1e6)
    n >= 1_000 -> "%.1f KB".format(n / 1e3)
    else -> "$n B"
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() { if (onHome) super.onBackPressed() else showHome() }

  // While a transfer runs, a foreground service keeps the app alive and the worklet is not suspended.
  private fun busy(on: Boolean) {
    this.busy = on
    val service = Intent(this, KeepAlive::class.java)
    if (on) startForegroundService(service) else stopService(service)
  }

  override fun onPause() { super.onPause(); if (!busy) worker.suspend() }
  override fun onResume() { super.onResume(); worker.resume() }
  override fun onDestroy() { super.onDestroy(); busy(false); worker.close() }

  companion object {
    const val PICK_FILE = 1
    const val SAVE_FILE = 2
    const val SAVE_TREE = 3
  }
}

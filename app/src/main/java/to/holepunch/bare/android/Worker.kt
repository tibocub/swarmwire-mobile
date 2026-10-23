package to.holepunch.bare.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.nio.ByteBuffer

// The swarmwire worklet, spoken to in JSON lines (see app/src/main/js/app.js). Messages arrive on the main thread.
class Worker(context: Context, private val onMessage: (JSONObject) -> Unit) {
  private val worklet = Worklet(null)
  private val ipc: IPC
  private val main = Handler(Looper.getMainLooper())
  private var pending = ""
  private var closing = false

  init {
    worklet.start("/app.bundle", context.assets.open("app.bundle"), arrayOf(context.filesDir.path))
    ipc = IPC(worklet)
    read()
  }

  fun send(vararg fields: Pair<String, Any?>) {
    val line = JSONObject(mapOf(*fields)).toString() + "\n"
    ipc.write(ByteBuffer.wrap(line.toByteArray())) { _ -> }
  }

  // IPC.read is one-shot per call; re-arm it after every chunk.
  private fun read() {
    ipc.read { data, e ->
      if (data != null) {
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        pending += String(bytes, Charsets.UTF_8)
        while (true) {
          val i = pending.indexOf('\n')
          if (i < 0) break
          val line = pending.substring(0, i)
          pending = pending.substring(i + 1)
          main.post { if (!closing) onMessage(JSONObject(line)) }
        }
      }
      if (e == null) read()
    }
  }

  fun suspend() = worklet.suspend()
  fun resume() = worklet.resume()

  // Leave the swarm properly (peers notice at once, as with ctrl-c in the CLI) before ending the worklet.
  fun close() {
    closing = true
    send("type" to "quit")
    main.postDelayed({ worklet.terminate() }, 3000)
  }
}

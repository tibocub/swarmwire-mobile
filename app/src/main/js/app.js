// The worklet: swarmwire behind a JSON-lines pipe. The Kotlin side (Worker.kt) is the only client.
//
// Everything being downloaded or seeded is an entry in one table, keyed by the content's root, and
// the UI is shown the whole table once a second (`list`).
//
//   UI -> here   {type:'inspect', link}                      what a link holds, before anything is fetched
//                {type:'get', link, name, dest}              download; dest is a folder, or null for app storage
//                {type:'serve', path, name, private}         share a file (path may be `fd:N`)
//                {type:'deliver', url, link}                 hand a link to a `swarmwire-recv://` meeting point
//                {type:'stop', id}                           stop a download or a seed
//   here -> UI   ready | list | inspected | link | delivered | error
import b4a from 'b4a'
import fs from 'bare-fs'
import Hyperswarm from 'hyperswarm'
import { randomBytes } from 'hypercore-crypto'
import { Swarmwire, parseLink, discoveryTopic, groupLength, DEFAULT_GROUP_LOG } from 'swarmwire-lib'

const [dir] = Bare.argv // app files dir, passed by MainActivity
const swarm = new Hyperswarm()
const node = new Swarmwire({ swarm })

const entries = new Map() // root -> { id, kind, link, name, size, folder, path, temp, done, state, error, ... }
const counted = new WeakSet() // sources whose reads are counted for the upload speed

const send = (msg) => BareKit.IPC.write(b4a.from(JSON.stringify(msg) + '\n'))
const fail = (err, op) => send({ type: 'error', op, error: String(err.message || err) })
Bare.on('uncaughtException', (err) => fail(err))

let buffer = ''
BareKit.IPC.on('data', (chunk) => {
  buffer += b4a.toString(chunk)
  let i
  while ((i = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, i)
    buffer = buffer.slice(i + 1)
    let msg
    try { msg = JSON.parse(line) } catch (err) { fail(err); continue }
    handle(msg).catch((err) => fail(err, msg.type))
  }
})

async function handle (msg) {
  if (msg.type === 'inspect') await inspect(msg.link)
  else if (msg.type === 'get') await get(msg)
  else if (msg.type === 'serve') await serve(msg)
  else if (msg.type === 'deliver') { await deliver(msg.url, msg.link); send({ type: 'delivered' }) }
  else if (msg.type === 'stop') await stop(msg.id)
}

// ---- looking before downloading ----

async function inspect (link) {
  const parsed = parseLink(link)
  const out = { type: 'inspected', link, name: safeName(parsed.name), size: parsed.size, folder: parsed.type === 'collection', private: !!parsed.secret }

  if (out.folder) {
    // A folder's file list is the one thing that has to come from the network.
    const reading = node.readList(parsed)
    const timer = setTimeout(() => reading.stop(), 30000)
    const list = await reading.finally(() => clearTimeout(timer))
    if (!list) throw new Error('could not read the folder list (is anyone sharing it?)')
    out.size = list.size
    out.files = list.files.map((f) => ({ path: f.path, size: f.size }))
  }

  send(out)
}

// ---- downloading ----

async function get ({ link, name, dest }) {
  const parsed = parseLink(link)
  if (entries.has(parsed.root)) throw new Error('already in the list')

  const base = safeName(name) || safeName(parsed.name) || parsed.root.slice(0, 16)
  const temp = !dest
  const folder = temp ? `${dir}/incoming/${parsed.root.slice(0, 16)}` : dest
  await fs.promises.mkdir(folder, { recursive: true })

  // Straight into a shared folder, never over something already there.
  const out = temp ? `${folder}/${base}` : free(folder, base)
  const entry = add({ id: parsed.root, kind: 'download', link, name: base, size: parsed.size, folder: parsed.type === 'collection', path: out, temp, dir: folder })

  entry.run = (async () => {
    if (entry.folder) await downloadFolder(entry, parsed)
    else {
      entry.download = node.download(parsed, out)
      entry.download.on('progress', ({ have }) => { entry.done = Math.min(have * groupLength(DEFAULT_GROUP_LOG), parsed.size) })
      await entry.download
    }
  })()

  entry.run.then(() => {
    if (entry.stopped) return
    entry.done = entry.size
    entry.kind = 'seed' // a finished download is served on, from where it landed
    entry.finished = true
  }, (err) => {
    if (!entry.stopped) { entry.state = 'error'; entry.error = String(err.message || err) }
  })
}

// Same as the library's downloadCollection, but a stop ends it between files.
async function downloadFolder (entry, parsed) {
  await fs.promises.mkdir(entry.path, { recursive: true })
  entry.session = swarm.join(discoveryTopic(parsed.root, parsed.secret), { server: true, client: true })

  entry.reading = node.readList(parsed)
  const list = await entry.reading
  if (entry.stopped) return
  if (!list) throw new Error('the folder list could not be read')

  entry.size = list.size
  let bytes = 0
  for (const file of list.files) {
    if (entry.stopped) return
    const target = `${entry.path}/${file.path}`
    await fs.promises.mkdir(target.slice(0, target.lastIndexOf('/')), { recursive: true })

    entry.download = node.downloadFile(parsed, file, target)
    entry.download.on('progress', ({ have }) => { entry.done = bytes + Math.min(have * groupLength(DEFAULT_GROUP_LOG), file.size) })
    await entry.download
    bytes += file.size
  }
}

// ---- sharing ----

async function serve ({ path, name, private: hidden }) {
  const secret = hidden ? b4a.toString(randomBytes(32), 'hex') : null
  const { root, size, link } = await node.serve(path, { name, secret })
  add({ id: root, kind: 'seed', link, name, size, folder: false, path, done: size, finished: true, hidden: !!hidden })
  send({ type: 'link', link, name })
}

// ---- stopping ----

async function stop (id) {
  const entry = entries.get(id)
  if (!entry) return

  entry.stopped = true
  entries.delete(id)

  entry.reading?.stop()
  await entry.session?.destroy?.()
  await entry.download?.stop()
  if (entry.kind === 'seed' && entry.finished) {
    for (const file of node.lists.get(id) ?? []) await node.unserve(file.root)
    await node.unserve(id)
  }
  const rm = (path) => fs.promises.rm(path, { recursive: true, force: true }).catch(() => {})
  if (entry.temp) await rm(entry.dir)
  else if (entry.kind === 'download' && !entry.finished) { // an unfinished file in a shared folder is a full-size placeholder: do not leave it
    await rm(entry.path)
    await rm(entry.path + '.swarmwire-part')
  }
}

// ---- the table ----

function add (entry) {
  Object.assign(entry, { done: 0, state: 'connecting', error: null, finished: false, down: 0, up: 0, upBytes: 0, last: { done: 0, up: 0, at: Date.now() } }, entry)
  entries.set(entry.id, entry)
  return entry
}

setInterval(() => {
  const now = Date.now()
  const rows = []

  for (const e of entries.values()) {
    countUploads(e)
    const dt = Math.max((now - e.last.at) / 1000, 0.001)
    e.down = e.finished ? 0 : (e.done - e.last.done) / dt
    e.up = (e.upBytes - e.last.up) / dt
    e.last = { done: e.done, up: e.upBytes, at: now }

    if (e.state !== 'error') e.state = e.finished ? 'seeding' : e.done > 0 || e.down > 0 ? 'downloading' : 'connecting'
    const { peers, seeders } = node.counts(e.link)

    rows.push({ id: e.id, kind: e.kind, link: e.link, name: e.name, size: e.size, folder: e.folder, path: e.path, temp: e.temp, done: e.done, state: e.state, error: e.error, down: e.down, up: e.up, peers, seeders, hidden: e.hidden })
  }

  const rates = meter.rates()
  send({ type: 'list', entries: rows, up: rates.up, down: rates.down })
}, 1000)

// The library does not say how much it uploaded per item, so reads of its sources are counted here.
function countUploads (entry) {
  const roots = [entry.id, ...(node.lists.get(entry.id) ?? []).map((f) => f.root)]
  for (const root of roots) {
    const source = node.serving.get(root)
    if (!source || counted.has(source)) continue
    counted.add(source)

    const read = source.readGroup.bind(source)
    source.readGroup = async (index) => {
      const data = await read(index)
      entry.upBytes += data.length
      return data
    }
  }
}

// Bytes on the wire in both directions, over every connection, past and present (as in the CLI).
const meter = {
  closed: { up: 0, down: 0 },
  live: new Set(),
  samples: [],
  rates () {
    let { up, down } = this.closed
    for (const stream of this.live) { up += stream.rawBytesWritten; down += stream.rawBytesRead }

    const now = Date.now()
    this.samples.push({ at: now, up, down })
    while (this.samples.length > 5) this.samples.shift()

    const first = this.samples[0]
    const seconds = (now - first.at) / 1000
    return seconds > 0 ? { up: (up - first.up) / seconds, down: (down - first.down) / seconds } : { up: 0, down: 0 }
  }
}
swarm.on('connection', (stream) => {
  meter.live.add(stream)
  stream.once('close', () => {
    meter.live.delete(stream)
    meter.closed.up += stream.rawBytesWritten
    meter.closed.down += stream.rawBytesRead
  })
})

// ---- handing a link to a getter ----

// The same one-shot protocol as swarmwire-cli's lib/rendezvous.js: connect to the key, write the
// link and a newline, expect "ok". Kept in step by hand until it moves into swarmwire-lib.
async function deliver (url, link) {
  const match = /^swarmwire-recv:\/\/([0-9a-f]{64})$/.exec(url)
  if (!match) throw new Error('not a swarmwire receive code')
  parseLink(link)

  const socket = swarm.dht.connect(b4a.from(match[1], 'hex'))
  socket.on('error', () => {})
  const timer = setTimeout(() => socket.destroy(), 30000)

  try {
    const reply = await new Promise((resolve) => {
      let text = ''
      socket.on('data', (chunk) => { text += b4a.toString(chunk) })
      socket.on('end', () => { socket.end(); resolve(text.trim()) })
      socket.on('close', () => resolve(text.trim()))
      socket.write(b4a.from(link + '\n'))
    })
    if (reply !== 'ok') throw new Error(reply ? `receiver refused the link: ${reply}` : 'could not reach the receiver')
  } finally {
    clearTimeout(timer)
  }
}

/** A name from a link is a hint: never let it climb out of its folder. */
function safeName (name) {
  const base = name ? name.split(/[\\/]/).pop() : null
  return base && base !== '.' && base !== '..' ? base : null
}

/** `name`, or `name (1)`, `name (2)`... : the first that does not exist in `folder`. */
function free (folder, name) {
  const dot = name.lastIndexOf('.')
  const [stem, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, '']
  let candidate = name
  for (let n = 1; fs.existsSync(`${folder}/${candidate}`); n++) candidate = `${stem} (${n})${ext}`
  return `${folder}/${candidate}`
}

send({ type: 'ready' })

// The worklet: swarmwire behind a JSON-lines pipe. The Kotlin side (Worker.kt) is the only client.
//
//   UI -> here   {type:'serve', path, name}      share a file (path may be `fd:N`)
//                {type:'get', link}              download into <files>/incoming/<root>/
//                {type:'deliver', url, link}     hand a link to a `swarmwire-recv://` meeting point
//                {type:'release', root}          stop serving a finished download
//   here -> UI   ready | link | progress | done | delivered | released | error
import b4a from 'b4a'
import fs from 'bare-fs'
import Hyperswarm from 'hyperswarm'
import { Swarmwire, parseLink, groupLength, DEFAULT_GROUP_LOG } from 'swarmwire-lib'

const [dir] = Bare.argv // app files dir, passed by MainActivity
const swarm = new Hyperswarm()
const node = new Swarmwire({ swarm })

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
  if (msg.type === 'serve') {
    const { link } = await node.serve(msg.path, { name: msg.name })
    send({ type: 'link', link })
  } else if (msg.type === 'get') {
    await get(msg.link)
  } else if (msg.type === 'deliver') {
    await deliver(msg.url, msg.link)
    send({ type: 'delivered' })
  } else if (msg.type === 'release') {
    await node.unserve(msg.root)
    send({ type: 'released', root: msg.root })
  }
}

async function get (link) {
  const parsed = parseLink(link)
  // One folder per link, so asking for the same link again resumes the partial download.
  const folder = `${dir}/incoming/${parsed.root.slice(0, 16)}`
  const name = safeName(parsed.name) || parsed.root.slice(0, 16)
  const out = `${folder}/${name}`
  const progress = (done, total) => send({ type: 'progress', done, total })

  await fs.promises.mkdir(folder, { recursive: true })

  if (parsed.type === 'collection') {
    await node.downloadCollection(parsed, out, { onprogress: ({ bytes, total }) => progress(bytes, total) })
  } else {
    const download = node.download(parsed, out)
    download.on('progress', ({ have }) => progress(Math.min(have * groupLength(DEFAULT_GROUP_LOG), parsed.size), parsed.size))
    await download
  }

  send({ type: 'done', root: parsed.root, path: out, name, folder: parsed.type === 'collection' })
}

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

send({ type: 'ready' })

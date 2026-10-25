// Generates the Bedrock -> Java block state table used by the Bedrock world loader.
//
//   node generate-mappings.mjs <geyser blocks.nbt> <java reports/blocks.json> <geyser biomes.json> <out dir>
//
// Inputs:
//  - blocks.nbt / biomes.json from https://github.com/GeyserMC/mappings (MIT), generated for a pair
//    (Java version, Bedrock version), e.g. Java 26.3 <-> Bedrock 1.26.50.
//  - blocks.json from the Java server data generator of the same Java version:
//    java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports
//
// Output (gzip, one entry per line):
//  bedrock-blocks.txt.gz   <bedrock name> <tab> <sorted k=v,k=v bedrock states> <tab> <java state>
//  bedrock-biomes.txt.gz   <bedrock numeric id> <tab> <java biome key>
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { gunzipSync, gzipSync } from 'node:zlib'
import { join } from 'node:path'

const [blocksNbt, javaBlocks, biomesJson, outDir] = process.argv.slice(2)
if (!outDir) { console.error('usage: generate-mappings.mjs <blocks.nbt> <java blocks.json> <biomes.json> <out dir>'); process.exit(1) }

// ---- minimal big-endian NBT reader ------------------------------------------------------------
function readNbt (buf) {
  let o = 0
  const u8 = () => buf[o++]
  const i16 = () => { const v = buf.readInt16BE(o); o += 2; return v }
  const i32 = () => { const v = buf.readInt32BE(o); o += 4; return v }
  const str = () => { const n = buf.readUInt16BE(o); o += 2; const s = buf.toString('utf8', o, o + n); o += n; return s }
  const payload = (t) => {
    switch (t) {
      case 1: { const v = buf.readInt8(o); o += 1; return v }
      case 2: return i16()
      case 3: return i32()
      case 4: { const v = buf.readBigInt64BE(o); o += 8; return v }
      case 5: { const v = buf.readFloatBE(o); o += 4; return v }
      case 6: { const v = buf.readDoubleBE(o); o += 8; return v }
      case 7: { const n = i32(); const v = buf.subarray(o, o + n); o += n; return v }
      case 8: return str()
      case 9: { const et = u8(); const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(payload(et)); return a }
      case 10: { const c = {}; for (;;) { const ct = u8(); if (ct === 0) return c; const name = str(); c[name] = payload(ct) } }
      case 11: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i32()); return a }
      case 12: { const n = i32(); const a = []; for (let i = 0; i < n; i++) { a.push(buf.readBigInt64BE(o)); o += 8 } return a }
      default: throw new Error('bad tag ' + t)
    }
  }
  const t = u8(); str()
  return payload(t)
}

let raw = readFileSync(blocksNbt)
if (raw[0] === 0x1f && raw[1] === 0x8b) raw = gunzipSync(raw)
const bedrock = readNbt(raw).bedrock_mappings

// Java states in registry order.
const java = JSON.parse(readFileSync(javaBlocks, 'utf8'))
const javaStates = []
const defaults = new Set()
for (const [name, def] of Object.entries(java)) {
  for (const st of def.states) {
    const props = st.properties ? Object.entries(st.properties).map(([k, v]) => `${k}=${v}`).join(',') : ''
    javaStates[st.id] = { name, props, str: props ? `${name}[${props}]` : name }
    if (st.default) defaults.add(st.id)
  }
}
if (javaStates.length !== bedrock.length) {
  console.error(`state count mismatch: java ${javaStates.length}, geyser ${bedrock.length} (versions differ?)`)
  process.exit(1)
}

const ns = (n) => n.includes(':') ? n : 'minecraft:' + n
const table = new Map() // bedrock key -> { java, isDefault, id }
for (let id = 0; id < bedrock.length; id++) {
  const e = bedrock[id]
  const j = javaStates[id]
  const bName = ns(e.bedrock_identifier ?? j.name)
  const states = e.state ?? {}
  const sKey = Object.keys(states).sort().map(k => `${k}=${String(states[k])}`).join(',')
  const key = `${bName}\t${sKey}`
  const prev = table.get(key)
  // Several Java states can share one Bedrock state (Java stores more, e.g. waterlogging, which
  // Bedrock keeps in a second block layer): prefer the Java default state, then a non waterlogged
  // one, then the lowest id. The loader sets waterlogged=true itself from the water layer.
  const score = (defaults.has(id) ? 2 : 0) + (j.props.includes('waterlogged=true') ? 0 : 1)
  if (prev === undefined || score > prev.score) table.set(key, { java: j.str, score })
}

mkdirSync(outDir, { recursive: true })
const lines = [...table.entries()].map(([k, v]) => `${k}\t${v.java}`).sort()
writeFileSync(join(outDir, 'bedrock-blocks.txt.gz'), gzipSync(lines.join('\n') + '\n', { level: 9 }))

const biomes = JSON.parse(readFileSync(biomesJson, 'utf8'))
const blines = Object.entries(biomes).map(([k, v]) => `${v.bedrock_id}\t${k}`).sort((a, b) => parseInt(a) - parseInt(b))
writeFileSync(join(outDir, 'bedrock-biomes.txt.gz'), gzipSync(blines.join('\n') + '\n', { level: 9 }))
console.log(`${lines.length} bedrock block states, ${blines.length} biomes -> ${outDir}`)

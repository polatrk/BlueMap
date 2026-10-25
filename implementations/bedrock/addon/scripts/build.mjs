// Builds both variants of the BlueMap behavior pack and stores the .mcpack files in the jar resources:
//   npm install && npm run build
// Output: dist/bluemap-<variant>/ and ../src/main/resources/de/bluecolored/bluemap/bedrock/addon/bluemap-<variant>.mcpack
import { build } from 'esbuild'
import { readFileSync, writeFileSync, mkdirSync, rmSync, readdirSync, statSync } from 'node:fs'
import { join, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deflateRawSync, crc32 } from 'node:zlib'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const resources = join(root, '..', 'src', 'main', 'resources', 'de', 'bluecolored', 'bluemap', 'bedrock', 'addon')

// Fixed UUIDs: this is one published pack, updates keep the same identity.
// BDS reads per-pack settings (permissions, variables, secrets) from config/<SCRIPT module uuid>/.
const IDS = {
  header: '6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f01',
  data: '6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f02',
  script: '6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f03'
}
// Checked against the npm packages tagged 1.26.52-stable and loaded by BDS 1.26.52.3.
const MODULES = { server: '2.10.0', serverNet: '1.0.0-beta', serverAdmin: '1.0.0-beta' }

function manifest (variant, version) {
  const deps = [{ module_name: '@minecraft/server', version: MODULES.server }]
  if (variant === 'net') {
    deps.push({ module_name: '@minecraft/server-net', version: MODULES.serverNet })
    deps.push({ module_name: '@minecraft/server-admin', version: MODULES.serverAdmin })
  }
  return {
    format_version: 2,
    header: {
      name: 'BlueMap',
      description: `Live player markers and /bluemap commands for BlueMap (${variant} variant)`,
      uuid: IDS.header, version, min_engine_version: [1, 26, 50]
    },
    modules: [
      { type: 'data', uuid: IDS.data, version },
      { type: 'script', language: 'javascript', uuid: IDS.script, version, entry: 'scripts/main.js' }
    ],
    dependencies: deps
  }
}

function zip (dir) {
  const files = []
  const walk = d => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else files.push(p) } }
  walk(dir)
  const locals = []; const centrals = []; let offset = 0
  for (const f of files.sort()) {
    const name = Buffer.from(relative(dir, f).split('\\').join('/'))
    const data = readFileSync(f); const comp = deflateRawSync(data); const crc = crc32(data) >>> 0
    const lh = Buffer.alloc(30)
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(8, 8)
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(comp.length, 18); lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(name.length, 26)
    const ch = Buffer.alloc(46)
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(8, 10)
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(comp.length, 20); ch.writeUInt32LE(data.length, 24)
    ch.writeUInt16LE(name.length, 28); ch.writeUInt32LE(offset, 42)
    locals.push(lh, name, comp); centrals.push(ch, name)
    offset += lh.length + name.length + comp.length
  }
  const cd = Buffer.concat(centrals); const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, cd, end])
}

const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const version = pkg.version.split('.').map(Number)
mkdirSync(resources, { recursive: true })
for (const variant of ['console', 'net']) {
  const out = join(root, 'dist', `bluemap-${variant}`)
  rmSync(out, { recursive: true, force: true })
  mkdirSync(join(out, 'scripts'), { recursive: true })
  await build({
    entryPoints: [join(root, 'src', `main-${variant}.ts`)], bundle: true, format: 'esm', target: 'es2022',
    external: ['@minecraft/*'], outfile: join(out, 'scripts', 'main.js'), logLevel: 'warning', legalComments: 'none'
  })
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest(variant, version), null, 2) + '\n')
  writeFileSync(join(resources, `bluemap-${variant}.mcpack`), zip(out))
  console.log(`bluemap-${variant}.mcpack`)
}
writeFileSync(join(resources, 'pack-ids.json'), JSON.stringify({ ...IDS, version }, null, 2) + '\n')

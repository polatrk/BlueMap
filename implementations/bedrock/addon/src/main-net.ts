/**
 * server-net variant (Beta APIs experiment): messages are POSTed to BlueMap's add-on endpoint.
 * config/<script module uuid>/variables.json  {"bluemap_url": "http://127.0.0.1:8101"}
 * config/<script module uuid>/secrets.json    {"bluemap_token": "Bearer <addon-token from bedrock.conf>"}
 * The in-game /bluemap command needs a console link (BlueMap answers with tellraw).
 */
import { http, HttpRequest, HttpRequestMethod, HttpHeader } from '@minecraft/server-net'
import { variables, secrets } from '@minecraft/server-admin'
import { startCollector, registerCommand, Message } from './collector'

const url = String(variables.get('bluemap_url') ?? 'http://127.0.0.1:8101').replace(/\/+$/, '')
const token = secrets.get('bluemap_token')

let lastWarn = 0
function warn (what: string, e: unknown): void {
  if (Date.now() - lastWarn < 60000) return
  lastWarn = Date.now()
  console.warn(`[BlueMap] ${what}: ${String((e as Error)?.message ?? e)} (further errors silenced for 60s)`)
}

let inFlight = 0
function send (msg: Message): void {
  if (inFlight >= 2) return
  const req = new HttpRequest(url + '/players')
  req.method = HttpRequestMethod.Post
  req.body = JSON.stringify(msg)
  req.timeout = 5
  req.headers = [new HttpHeader('Content-Type', 'application/json')]
  if (token !== undefined) req.headers.push(new HttpHeader('Authorization', token))
  inFlight++
  http.request(req).then(res => {
    if (res.status >= 400) warn('BlueMap refused the report', `HTTP ${res.status}`)
  }).catch((e: unknown) => warn(`cannot reach ${url}`, e)).finally(() => { inFlight-- })
}

registerCommand(send)
startCollector(send)

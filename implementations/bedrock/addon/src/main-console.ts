/**
 * Console variant (stable APIs only): messages are printed as `[BEDMAP]{json}` on the BDS console,
 * read by BlueMap (console-mode wrapper or external). Needs content-log-console-output-enabled=true.
 */
import { startCollector, registerCommand, Message } from './collector'

const send = (msg: Message): void => console.log('[BEDMAP]' + JSON.stringify(msg))
registerCommand(send)
startCollector(send)

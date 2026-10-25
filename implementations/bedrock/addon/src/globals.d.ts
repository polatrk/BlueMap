// The Script API runtime provides a console object (output goes to the BDS content log / console).
declare const console: { log: (...a: unknown[]) => void, warn: (...a: unknown[]) => void, error: (...a: unknown[]) => void }

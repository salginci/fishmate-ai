/** Tagged console logging, so a live session can be read in the devtools. */
const enabled = import.meta.env.DEV

export const log = {
  i(tag: string, message: string, ...rest: unknown[]) {
    if (enabled) console.info(`[${tag}] ${message}`, ...rest)
  },
  d(tag: string, message: string, ...rest: unknown[]) {
    if (enabled) console.debug(`[${tag}] ${message}`, ...rest)
  },
  w(tag: string, message: string, ...rest: unknown[]) {
    console.warn(`[${tag}] ${message}`, ...rest)
  },
  e(tag: string, message: string, ...rest: unknown[]) {
    console.error(`[${tag}] ${message}`, ...rest)
  },
}

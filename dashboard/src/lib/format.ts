const SHORT_SHA_LENGTH = 7

export function shortSha(sha: string): string {
  return sha.slice(0, SHORT_SHA_LENGTH)
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB'] as const
const oneDecimal = new Intl.NumberFormat('en', { maximumFractionDigits: 1 })

/** Formats a byte count using binary multiples, e.g. `48213` → `47.1 KB`. */
export function formatBytes(bytes: number): string {
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024
    unit++
  }
  return `${oneDecimal.format(value)} ${BYTE_UNITS[unit]}`
}

/** Formats a duration as its two most significant units, e.g. `1h 4m` or `48s`. */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.round(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m ${seconds}s`
  return `${seconds}s`
}

const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

// Largest unit first; a unit is used once the elapsed time reaches its size.
const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * DAY],
  ['month', 30 * DAY],
  ['week', 7 * DAY],
  ['day', DAY],
  ['hour', HOUR],
  ['minute', MINUTE],
  ['second', SECOND],
]

const relativeFormat = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

/** Formats `date` relative to `now`, e.g. `2 minutes ago` or `yesterday`. */
export function relativeTime(date: Date, now: Date = new Date()): string {
  const diff = date.getTime() - now.getTime()
  const [unit, size] = RELATIVE_UNITS.find(([, size]) => Math.abs(diff) >= size) ?? ['second', SECOND]
  // Truncate so 59.6s stays "59 seconds ago"; `|| 0` normalises -0.
  return relativeFormat.format(Math.trunc(diff / size) || 0, unit)
}

const dateTimeFormat = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'medium' })

export function formatDateTime(date: Date): string {
  return dateTimeFormat.format(date)
}

/** Turns a GitHub conclusion like `timed_out` into `Timed out`. */
export function formatConclusion(conclusion: string): string {
  const words = conclusion.replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

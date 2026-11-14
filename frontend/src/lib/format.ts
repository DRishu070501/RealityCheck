const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'medium',
})

/** Formats an ISO-8601 instant in the viewer's locale and time zone. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : dateTimeFormat.format(date)
}

/** Formats an ISO-8601 instant as date and time in the given IANA zone, for example the factory's. */
export function formatDateTimeInZone(iso: string, timeZone: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return new Intl.DateTimeFormat('en-GB', {
    timeZone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(date)
}

const integerFormat = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 })

export function formatInteger(value: number): string {
  return integerFormat.format(value)
}

/**
 * Formats a decimal string for display with at most `maxFractionDigits` and no trailing zeros.
 * Display only: the string from the API remains the value of record.
 */
export function formatDecimal(value: string, maxFractionDigits = 2): string {
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: maxFractionDigits }).format(number)
}

/** Formats a decimal-string amount in the given ISO 4217 currency. Display only. */
export function formatMoney(value: string, currencyCode: string): string {
  const number = Number(value)
  if (!Number.isFinite(number)) return `${value} ${currencyCode}`
  try {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: currencyCode,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(number)
  } catch {
    return `${value} ${currencyCode}`
  }
}

/** Formats a fraction string such as "0.985000" as a percentage, "98.5%". */
export function formatYield(fraction: string): string {
  const number = Number(fraction)
  if (!Number.isFinite(number)) return fraction
  return `${new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(number * 100)}%`
}

/** Formats whole seconds as the largest sensible units: "45 s", "15 min", "1 h 30 min", "48 h". */
export function formatDuration(totalSeconds: number): string {
  if (totalSeconds < 60) return `${totalSeconds} s`
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const parts: string[] = []
  if (hours > 0) parts.push(`${formatInteger(hours)} h`)
  if (minutes > 0) parts.push(`${minutes} min`)
  if (hours === 0 && seconds > 0) parts.push(`${seconds} s`)
  return parts.join(' ')
}

/** Turns an upper-snake code such as "POLISHING_WHEEL" into "Polishing wheel". */
export function humanizeCode(code: string): string {
  const words = code.toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'] as const

/** Name of an ISO day number, 1 = Monday .. 7 = Sunday. */
export function dayName(isoDay: number): string {
  return DAY_NAMES[isoDay - 1] ?? `Day ${isoDay}`
}

/* ---- Wall-clock time in a named zone -------------------------------------------------------- */

function zoneOffsetMs(utcMs: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
  }).formatToParts(new Date(utcMs))
  const read = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((part) => part.type === type)?.value ?? '0')
  const asUtc = Date.UTC(read('year'), read('month') - 1, read('day'), read('hour'), read('minute'), read('second'))
  return asUtc - Math.floor(utcMs / 1000) * 1000
}

/**
 * Converts a `datetime-local` value ("2026-01-07T09:00"), read as wall-clock time in `timeZone`,
 * to an ISO-8601 UTC instant. Returns null for input that is not a valid date-time.
 *
 * A wall-clock time skipped by a daylight-saving jump resolves to the instant after the jump.
 */
export function zonedLocalToInstant(local: string, timeZone: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local)
  if (!match) return null
  const [, year, month, day, hour, minute, second] = match
  const wallAsUtc = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second ?? '0'),
  )
  if (Number.isNaN(wallAsUtc)) return null
  // The offset depends on the instant, which is what we are solving for: estimate, then correct once.
  const firstGuess = wallAsUtc - zoneOffsetMs(wallAsUtc, timeZone)
  const instant = wallAsUtc - zoneOffsetMs(firstGuess, timeZone)
  return new Date(instant).toISOString().replace('.000Z', 'Z')
}

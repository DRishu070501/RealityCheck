import { formatDecimal, formatInteger, formatMoney } from '@/lib/format'

/**
 * Display rules for simulated figures. Values stay the API's strings; these functions only format
 * them. A null value is never shown as zero: callers render {@link NOT_AVAILABLE} with a reason.
 */

export const NOT_AVAILABLE = '—'

/** Calendar time: "36 h" below two days, otherwise "14 d 11 h". */
export function formatElapsed(totalSeconds: number): string {
  const hours = Math.round(totalSeconds / 3600)
  if (Math.abs(hours) < 48) return `${formatInteger(hours)} h`
  const days = Math.trunc(hours / 24)
  const rest = Math.abs(hours % 24)
  return rest === 0 ? `${formatInteger(days)} d` : `${formatInteger(days)} d ${rest} h`
}

/** Working (factory-open) time, always in hours with one decimal: "54.3 working h". */
export function formatWorkingHours(totalSeconds: number | string): string {
  return `${formatDecimal(String(Number(totalSeconds) / 3600), 1)} working h`
}

/** A fraction such as "0.655172" as "65.5%". */
export function formatFraction(fraction: string, digits = 1): string {
  const number = Number(fraction)
  if (!Number.isFinite(number)) return fraction
  return `${new Intl.NumberFormat('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(number * 100)}%`
}

/** A percentage change such as "-33.17" as "−33.17%", with an explicit sign. */
export function formatSignedPercent(percent: string): string {
  const number = Number(percent)
  if (!Number.isFinite(number)) return percent
  const text = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(Math.abs(number))
  return `${number > 0 ? '+' : number < 0 ? '−' : ''}${text}%`
}

/**
 * Formats a value by the unit the comparison API names: seconds, stones/hour, stones, carats,
 * currency, currency/stone, fraction, orders.
 *
 * @param signed prefix positive values with "+", for differences
 */
export function formatByUnit(value: string | null, unit: string, currencyCode: string, signed = false): string {
  if (value === null) return NOT_AVAILABLE
  const number = Number(value)
  const sign = signed && number > 0 ? '+' : ''
  switch (unit) {
    case 'seconds':
      return `${sign}${formatElapsed(number)}`
    case 'stones/hour':
      return `${sign}${formatDecimal(value, 2)} stones/h`
    case 'stones':
      return `${sign}${formatDecimal(value, 1)} stones`
    case 'carats':
      return `${sign}${formatDecimal(value, 2)} ct`
    case 'currency':
      return `${sign}${formatMoney(value, currencyCode)}`
    case 'currency/stone':
      return `${sign}${formatMoney(value, currencyCode)} per stone`
    case 'fraction':
      return signed
        ? `${sign}${formatDecimal(String(number * 100), 1)} pts`
        : formatFraction(value)
    case 'orders':
      return `${sign}${formatInteger(number)} ${Math.abs(number) === 1 ? 'order' : 'orders'}`
    default:
      return `${sign}${formatDecimal(value, 2)}`
  }
}

/** Explains why a simulated value can be absent. */
export const NULL_REASON: Record<string, string> = {
  stoneYield: 'No stones entered the run, so there is no yield.',
  throughputStonesPerHour: 'The run had no working time, so there is no throughput.',
  machineUtilization: 'No machine capacity was available during the run.',
  employeeUtilization: 'No employee capacity was available during the run.',
  onTimeDeliveryRate: 'No order in the run has a due date.',
  perOutputStone: 'Nothing was produced, so there is no cost per stone.',
}

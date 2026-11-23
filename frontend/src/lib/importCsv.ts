import { zonedLocalToInstant } from '@/lib/format'

/**
 * Reads the two import files (orders and packet movements) into the rows `POST /api/imports` takes.
 * Only the format is checked here (columns, numbers, dates), for every row at once; the business rules
 * are checked by the backend, which runs the import through the same services as the live API.
 */

export interface OrderRow {
  row: number
  orderNumber: string
  customerRef?: string
  priority?: number
  placedAt?: string
  dueAt?: string
  dispatchedAt?: string
  packet?: string
  stones?: number
  carats?: string
  route?: string
}

export interface MovementRow {
  row: number
  orderNumber: string
  packet?: string
  stage: string
  startedAt?: string
  completedAt?: string
  stonesOut?: string
  caratsOut?: string
}

export interface RowProblem {
  row: number
  message: string
}

export interface Parsed<T> {
  rows: T[]
  problems: RowProblem[]
}

export const ORDER_COLUMNS = [
  'order_number',
  'customer_ref',
  'priority',
  'placed_at',
  'due_at',
  'dispatched_at',
  'packet',
  'stones',
  'carats',
  'route',
] as const
export const MOVEMENT_COLUMNS = [
  'order_number',
  'packet',
  'stage',
  'started_at',
  'completed_at',
  'stones_out',
  'carats_out',
] as const

const REQUIRED_ORDER_COLUMNS = ['order_number', 'placed_at', 'stones', 'carats']
const REQUIRED_MOVEMENT_COLUMNS = ['order_number', 'stage', 'started_at']

/** Splits CSV text into records: quoted fields, doubled quotes, commas and newlines inside quotes. */
export function parseCsv(text: string): string[][] {
  const records: string[][] = []
  let record: string[] = []
  let field = ''
  let quoted = false
  const source = text.startsWith('﻿') ? text.slice(1) : text
  for (let i = 0; i < source.length; i++) {
    const c = source.charAt(i)
    if (quoted) {
      if (c === '"' && source.charAt(i + 1) === '"') {
        field += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        field += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === ',') {
      record.push(field)
      field = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && source.charAt(i + 1) === '\n') i++
      record.push(field)
      records.push(record)
      record = []
      field = ''
    } else {
      field += c
    }
  }
  if (field !== '' || record.length > 0) {
    record.push(field)
    records.push(record)
  }
  return records
}

/** `Order Number`, `order-number` and `ORDER_NUMBER` all name the column `order_number`. */
function columnName(header: string): string {
  return header.trim().toLowerCase().replace(/[\s-]+/g, '_')
}

const DATE_TIME =
  /^(?:(\d{4})-(\d{1,2})-(\d{1,2})|(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}))(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$/

/**
 * A moment as written in a file, to an ISO instant. A value with an offset (`2026-10-01T09:30:00+05:30`,
 * or `Z`) is taken as is. Otherwise it is factory-local time: `2026-10-01 09:30`, `01-10-2026 09:30` or
 * `01/10/2026` (day first), with midnight when the time is left out.
 *
 * @returns null when the text is not a date
 */
export function parseMoment(text: string, timeZone: string): string | null {
  const value = text.trim()
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(value) && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    const millis = Date.parse(value)
    return Number.isNaN(millis) ? null : new Date(millis).toISOString().replace('.000Z', 'Z')
  }
  const match = DATE_TIME.exec(value)
  if (!match) return null
  const [, y1, m1, d1, d2, m2, y2, hh = '0', mm = '0', ss = '0'] = match
  const year = Number(y1 ?? y2)
  const month = Number(m1 ?? m2)
  const day = Number(d1 ?? d2)
  const [hour, minute, second] = [Number(hh), Number(mm), Number(ss)]
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return null
  if (new Date(Date.UTC(year, month - 1, day)).getUTCDate() !== day) return null
  const pad = (n: number) => String(n).padStart(2, '0')
  return zonedLocalToInstant(
    `${String(year)}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}:${pad(second)}`,
    timeZone,
  )
}

type Cells = Record<string, string>

function readTable(
  text: string,
  required: readonly string[],
): { cells: { row: number; values: Cells }[]; problems: RowProblem[] } {
  const records = parseCsv(text)
  const header = (records[0] ?? []).map(columnName)
  const missing = required.filter((column) => !header.includes(column))
  if (records.length === 0 || missing.length > 0) {
    return {
      cells: [],
      problems: [
        {
          row: 1,
          message:
            records.length === 0
              ? 'The file is empty'
              : `Missing column${missing.length > 1 ? 's' : ''}: ${missing.join(', ')}`,
        },
      ],
    }
  }
  const cells: { row: number; values: Cells }[] = []
  records.slice(1).forEach((record, index) => {
    if (record.every((value) => value.trim() === '')) return
    const values: Cells = {}
    header.forEach((column, i) => {
      values[column] = (record[i] ?? '').trim()
    })
    cells.push({ row: index + 2, values })
  })
  return { cells, problems: [] }
}

function blankToUndefined(value: string | undefined): string | undefined {
  return value === undefined || value === '' ? undefined : value
}

class RowReader {
  readonly problems: string[] = []

  constructor(
    private readonly values: Cells,
    private readonly timeZone: string,
  ) {}

  text(column: string, required = false): string | undefined {
    const value = blankToUndefined(this.values[column])
    if (required && value === undefined) this.problems.push(`${column} is required`)
    return value
  }

  moment(column: string, required = false): string | undefined {
    const value = this.text(column, required)
    if (value === undefined) return undefined
    const parsed = parseMoment(value, this.timeZone)
    if (parsed === null) {
      this.problems.push(`${column} "${value}" is not a date and time (use 2026-10-01 09:30)`)
      return undefined
    }
    return parsed
  }

  wholeNumber(column: string, required = false): number | undefined {
    const value = this.text(column, required)
    if (value === undefined) return undefined
    if (!/^\d+$/.test(value)) {
      this.problems.push(`${column} "${value}" is not a whole number`)
      return undefined
    }
    return Number(value)
  }

  decimal(column: string, required = false): string | undefined {
    const value = this.text(column, required)
    if (value === undefined) return undefined
    if (!/^\d+(\.\d+)?$/.test(value)) {
      this.problems.push(`${column} "${value}" is not a number`)
      return undefined
    }
    return value
  }
}

function read<T>(
  text: string,
  required: readonly string[],
  timeZone: string,
  build: (reader: RowReader, row: number) => T,
): Parsed<T> {
  const table = readTable(text, required)
  const rows: T[] = []
  const problems = [...table.problems]
  for (const { row, values } of table.cells) {
    const reader = new RowReader(values, timeZone)
    const built = build(reader, row)
    if (reader.problems.length > 0) {
      problems.push({ row, message: reader.problems.join('; ') })
    } else {
      rows.push(built)
    }
  }
  return { rows, problems }
}

/** One row per packet; rows of the same order repeat its order-level columns or leave them blank. */
export function readOrders(text: string, timeZone: string): Parsed<OrderRow> {
  return read(text, REQUIRED_ORDER_COLUMNS, timeZone, (r, row) => ({
    row,
    orderNumber: r.text('order_number', true) ?? '',
    customerRef: r.text('customer_ref'),
    priority: r.wholeNumber('priority'),
    placedAt: r.moment('placed_at'),
    dueAt: r.moment('due_at'),
    dispatchedAt: r.moment('dispatched_at'),
    packet: r.text('packet'),
    stones: r.wholeNumber('stones', true),
    carats: r.decimal('carats', true),
    route: r.text('route'),
  }))
}

/** One row per visit of a packet to a stage. */
export function readMovements(text: string, timeZone: string): Parsed<MovementRow> {
  return read(text, REQUIRED_MOVEMENT_COLUMNS, timeZone, (r, row) => ({
    row,
    orderNumber: r.text('order_number', true) ?? '',
    packet: r.text('packet'),
    stage: r.text('stage', true) ?? '',
    startedAt: r.moment('started_at', true),
    completedAt: r.moment('completed_at'),
    stonesOut: r.decimal('stones_out'),
    caratsOut: r.decimal('carats_out'),
  }))
}

/** Example files to start from, using the given stage codes. */
export function templates(stageCodes: readonly string[]): { orders: string; movements: string } {
  const [first = 'STAGE_1', second = 'STAGE_2'] = stageCodes
  return {
    orders: [
      ORDER_COLUMNS.join(','),
      'ORD-1001,Buyer A,3,2026-10-01 09:00,2026-10-20 18:00,,P1,40,52.5,',
      'ORD-1001,,,,,,P2,25,31.2,',
      'ORD-1002,Buyer B,5,2026-10-03 10:00,2026-10-12 18:00,,P1,60,80,',
    ].join('\n'),
    movements: [
      MOVEMENT_COLUMNS.join(','),
      `ORD-1001,P1,${first},2026-10-01 10:00,2026-10-02 16:00,40,30.1`,
      `ORD-1001,P1,${second},2026-10-03 09:30,2026-10-06 12:00,,`,
      `ORD-1001,P2,${first},2026-10-01 11:00,2026-10-02 18:00,25,18`,
      `ORD-1002,P1,${first},2026-10-03 11:00,,,`,
    ].join('\n'),
  }
}

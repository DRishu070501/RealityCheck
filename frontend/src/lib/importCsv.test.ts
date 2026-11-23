import { describe, expect, it } from 'vitest'

import { parseCsv, parseMoment, readMovements, readOrders, templates } from '@/lib/importCsv'

describe('parseCsv', () => {
  it('reads quoted fields with commas, quotes and newlines, and any line ending', () => {
    expect(parseCsv('a,b\r\n"x, y","say ""hi"""\n"two\nlines",\n')).toEqual([
      ['a', 'b'],
      ['x, y', 'say "hi"'],
      ['two\nlines', ''],
    ])
  })

  it('ignores a byte order mark and keeps a last line without a newline', () => {
    expect(parseCsv('﻿a\n1')).toEqual([['a'], ['1']])
  })
})

describe('parseMoment', () => {
  it('reads factory-local times in several layouts', () => {
    expect(parseMoment('2026-10-01 09:30', 'Asia/Kolkata')).toBe('2026-10-01T04:00:00Z')
    expect(parseMoment('01-10-2026 09:30', 'Asia/Kolkata')).toBe('2026-10-01T04:00:00Z')
    expect(parseMoment('01/10/2026', 'Asia/Kolkata')).toBe('2026-09-30T18:30:00Z')
    expect(parseMoment('2026-10-01T09:30:15', 'UTC')).toBe('2026-10-01T09:30:15Z')
  })

  it('takes a time with an offset as it is', () => {
    expect(parseMoment('2026-10-01T09:30:00+05:30', 'UTC')).toBe('2026-10-01T04:00:00Z')
    expect(parseMoment('2026-10-01T04:00:00Z', 'Asia/Kolkata')).toBe('2026-10-01T04:00:00Z')
  })

  it('refuses what is not a date', () => {
    expect(parseMoment('31-02-2026', 'UTC')).toBeNull()
    expect(parseMoment('2026-13-01', 'UTC')).toBeNull()
    expect(parseMoment('yesterday', 'UTC')).toBeNull()
    expect(parseMoment('2026-10-01 25:00', 'UTC')).toBeNull()
  })
})

describe('readOrders', () => {
  it('maps columns by name, in any order and case, and skips blank lines', () => {
    const parsed = readOrders('Carats,Order Number,STONES,placed-at,packet\n12.5,A-1,10,2026-10-01 09:00,P1\n,,,,\n', 'UTC')
    expect(parsed.problems).toEqual([])
    expect(parsed.rows).toEqual([
      {
        row: 2,
        orderNumber: 'A-1',
        carats: '12.5',
        stones: 10,
        placedAt: '2026-10-01T09:00:00Z',
        packet: 'P1',
        customerRef: undefined,
        priority: undefined,
        dueAt: undefined,
        dispatchedAt: undefined,
        route: undefined,
      },
    ])
  })

  it('reports every row with a problem, by its line in the file', () => {
    const parsed = readOrders(
      'order_number,placed_at,stones,carats\nA-1,2026-10-01,ten,12\n,2026-10-01,5,1\nA-2,soon,5,-1\n',
      'UTC',
    )
    expect(parsed.rows).toEqual([])
    expect(parsed.problems).toEqual([
      { row: 2, message: 'stones "ten" is not a whole number' },
      { row: 3, message: 'order_number is required' },
      {
        row: 4,
        message: 'placed_at "soon" is not a date and time (use 2026-10-01 09:30); carats "-1" is not a number',
      },
    ])
  })

  it('names missing required columns', () => {
    expect(readOrders('order_number,stones\nA,1', 'UTC').problems).toEqual([
      { row: 1, message: 'Missing columns: placed_at, carats' },
    ])
    expect(readOrders('', 'UTC').problems).toEqual([{ row: 1, message: 'The file is empty' }])
  })
})

describe('readMovements and templates', () => {
  it('reads the movements template it offers, with the given stage codes', () => {
    const { orders, movements } = templates(['CUT', 'POLISH'])
    expect(readOrders(orders, 'Asia/Kolkata').problems).toEqual([])
    const parsed = readMovements(movements, 'Asia/Kolkata')
    expect(parsed.problems).toEqual([])
    expect(parsed.rows.map((r) => r.stage)).toEqual(['CUT', 'POLISH', 'CUT', 'CUT'])
    expect(parsed.rows[3]?.completedAt).toBeUndefined()
  })
})

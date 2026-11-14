import { describe, expect, it } from 'vitest'

import {
  formatCarats,
  formatDay,
  formatShortDateTimeInZone,
  formatStones,
  formatTimeInZone,
  instantToZonedLocal,
  localDayKey,
  localDayRange,
  zonedLocalToInstant,
} from '@/lib/format'
import { eventSubject, priorityName } from '@/lib/production'
import {
  eventSchema,
  factoryOverviewSchema,
  factoryStateSchema,
  orderDetailSchema,
  orderSchema,
  pageOf,
  timelineSummarySchema,
} from '@/lib/schemas'
import { fixtures } from '@/test/backend'

describe('production schemas accept what the backend actually sends', () => {
  it('parses a page of orders, keeping carats as a string and stones as a whole number', () => {
    const page = pageOf(orderSchema).parse(fixtures.orders)

    expect(page.items).toHaveLength(25)
    expect(page.totalItems).toBe(47)
    expect(page.items[0]?.totalStones).toBe(120)
    expect(page.items[0]?.totalRoughCarats).toBe('144.0000')
    expect(page.items.filter((order) => order.delayed).map((order) => order.orderNumber)).toEqual([
      'SDW-0008',
      'SDW-0012',
      'SDW-0021',
      'SDW-0028',
    ])
  })

  it('parses an order with its batches and history, including a corrected event and its correction', () => {
    const detail = orderDetailSchema.parse(fixtures.orderDetail)

    expect(detail.batches[0]?.currentStageId).toBeNull()
    expect(detail.batches[0]?.currentStones).toBe('66.0000')
    const corrected = detail.events.find((event) => event.corrected)
    expect(corrected?.stoneQuantity).toBe('69.0000')
    expect(corrected?.original).toEqual({ stoneQuantity: '70.0000', caratQuantity: '60.7600', durationSeconds: 21312 })
    const correction = detail.events.find((event) => event.eventType === 'CORRECTION')
    expect(correction?.correctsEventId).toBe(corrected?.id)
    expect(correction?.payload).toEqual({ reason: 'Recount after cutting (synthetic)' })
  })

  it('parses a page of timeline events', () => {
    const page = pageOf(eventSchema).parse(fixtures.timeline)

    expect(page.totalItems).toBe(1376)
    expect(page.items).toHaveLength(14)
    expect(page.items[3]?.stoneQuantity).toBe('98.0000')
  })

  it('parses the timeline summary, where a bucket lists only the event types it has', () => {
    const summary = timelineSummarySchema.parse(fixtures.timelineSummary)

    expect(summary.bucket).toBe('DAY')
    expect(summary.buckets).toHaveLength(18)
    expect(summary.buckets[0]?.byType.STAGE_STARTED).toBe(11)
    expect(summary.buckets[0]?.byType.ORDER_DISPATCHED).toBeUndefined()
  })

  it('parses a reconstructed factory state', () => {
    const state = factoryStateSchema.parse(fixtures.state)

    expect(state.orders).toEqual({ PENDING: 0, IN_PROGRESS: 18, COMPLETED: 9, DISPATCHED: 20 })
    expect(state.stages).toHaveLength(8)
    expect(state.batches).toHaveLength(62)
    expect(state.resources).toHaveLength(14)
  })

  it('parses the overview', () => {
    const overview = factoryOverviewSchema.parse(fixtures.overview)

    expect(overview.orders.delayed).toBe(4)
    expect(overview.machines).toEqual({ inUse: 17, capacity: 21 })
    expect(overview.dailyOutput).toHaveLength(14)
    expect(overview.recentEvents).toHaveLength(10)
  })

  it('rejects a value that claims a source other than recorded history', () => {
    expect(orderSchema.safeParse({ ...fixtures.orders.items[0], source: 'SIMULATED' }).success).toBe(false)
  })

  it('rejects a number where a decimal string is expected', () => {
    expect(orderSchema.safeParse({ ...fixtures.orders.items[0], totalRoughCarats: 144 }).success).toBe(false)
  })
})

describe('dates in the factory zone', () => {
  it('finds the local day an instant falls on', () => {
    // 18:29 and 18:31 UTC straddle midnight in Surat (UTC+5:30).
    expect(localDayKey('2026-11-13T18:29:00Z', 'Asia/Kolkata')).toBe('2026-11-13')
    expect(localDayKey('2026-11-13T18:31:00Z', 'Asia/Kolkata')).toBe('2026-11-14')
    expect(localDayKey('2026-11-13T18:31:00Z', 'UTC')).toBe('2026-11-13')
  })

  it('gives the first and last second of a local day as instants', () => {
    expect(localDayRange('2026-11-10', 'Asia/Kolkata')).toEqual({
      from: '2026-11-09T18:30:00Z',
      to: '2026-11-10T18:29:59Z',
    })
    expect(localDayRange('not a day', 'Asia/Kolkata')).toBeNull()
  })

  it('turns an instant into a datetime-local value and back', () => {
    const local = instantToZonedLocal('2026-11-14T07:08:54Z', 'Asia/Kolkata')

    expect(local).toBe('2026-11-14T12:38')
    expect(zonedLocalToInstant(local, 'Asia/Kolkata')).toBe('2026-11-14T07:08:00Z')
    expect(instantToZonedLocal('nonsense', 'Asia/Kolkata')).toBe('')
  })

  it('formats times and days compactly', () => {
    expect(formatTimeInZone('2026-11-14T07:08:54Z', 'Asia/Kolkata')).toBe('12:38')
    expect(formatShortDateTimeInZone('2026-11-14T07:08:54Z', 'Asia/Kolkata')).toBe('14 Nov 2026, 12:38')
    expect(formatDay('2026-11-14')).toBe('Sat, 14 Nov 2026')
    expect(formatDay('2026-11-14', true)).toBe('14 Nov')
    expect(formatDay('soon')).toBe('soon')
  })
})

describe('quantities and names', () => {
  it('always names the unit, and never merges stones with carats', () => {
    expect(formatStones('97.0000')).toBe('97 stones')
    expect(formatStones('1.0000')).toBe('1 stone')
    expect(formatStones('97.2500')).toBe('97.25 stones')
    expect(formatCarats('66.1000')).toBe('66.1 ct')
    expect(formatCarats('129.7182')).toBe('129.72 ct')
  })

  it('names priorities from lowest to urgent', () => {
    expect([1, 2, 3, 4, 5].map(priorityName)).toEqual(['Lowest', 'Low', 'Normal', 'High', 'Urgent'])
    expect(priorityName(9)).toBe('Priority 9')
  })

  it('describes who an event is about', () => {
    const [batchEvent] = fixtures.timeline.items
    const base = eventSchema.parse(batchEvent)

    expect(eventSubject(base)).toBe('SDW-0042 · batch 1')
    expect(eventSubject({ ...base, batchNumber: null })).toBe('SDW-0042')
    expect(eventSubject({ ...base, orderNumber: null, batchNumber: null, resourceName: 'Polishing wheels' })).toBe(
      'Polishing wheels',
    )
  })
})

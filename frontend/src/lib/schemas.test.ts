import { describe, expect, it } from 'vitest'

import { systemInfoSchema } from '@/lib/api'
import {
  dayName,
  formatDateTimeInZone,
  formatDecimal,
  formatDuration,
  formatMoney,
  formatYield,
  humanizeCode,
  zonedLocalToInstant,
} from '@/lib/format'
import {
  capacityTimelineSchema,
  demoResetSchema,
  factorySchema,
  factorySummarySchema,
  pageOf,
  resourceSchema,
  stageSchema,
  workflowSchema,
  workflowSummarySchema,
} from '@/lib/schemas'
import { fixtures } from '@/test/backend'

describe('schemas accept what the backend actually sends', () => {
  it('parses the factory list and a factory with its calendar and counts', () => {
    expect(pageOf(factorySummarySchema).parse(fixtures.factories).items).toHaveLength(1)

    const factory = factorySchema.parse(fixtures.factory)
    expect(factory.calendar.shifts).toHaveLength(6)
    expect(factory.calendar.weeklyWorkingSeconds).toBe(172_800)
    expect(factory.counts).toEqual({ orders: 47, resources: 14, workflows: 1 })
  })

  it('parses workflows and keeps stage decimals as strings', () => {
    expect(pageOf(workflowSummarySchema).parse(fixtures.workflows).items[0]?.stageCount).toBe(8)

    const workflow = workflowSchema.parse(fixtures.workflow)
    const polishing = workflow.stages.find((stage) => stage.code === 'POLISHING')
    expect(polishing?.stonesPerHour).toBe('6.0000')
    expect(polishing?.caratYield).toBe('0.820000')
    expect(polishing?.fixedCostPerJob).toBe('150.00')
    expect(polishing?.maxSlots).toBe(6)
  })

  it('accepts a stage that needs no resources and is therefore unlimited', () => {
    const stage = stageSchema.parse({ ...fixtures.workflow.stages[0], requirements: [], maxSlots: null })

    expect(stage.maxSlots).toBeNull()
  })

  it('parses resource pools and keeps money as a string', () => {
    const pools = pageOf(resourceSchema).parse(fixtures.resources).items

    expect(pools).toHaveLength(14)
    expect(pools.find((pool) => pool.name === 'Polishing wheels')?.hourlyCost).toBe('140.00')
  })

  it('parses a capacity timeline, with and without windows', () => {
    expect(capacityTimelineSchema.parse(fixtures.availability).timeline[0]?.capacity).toBe(4)
    expect(
      capacityTimelineSchema.parse({
        resourceId: fixtures.availability.resourceId,
        baseCapacity: 3,
        from: null,
        to: null,
        windows: [],
        timeline: [],
      }).windows,
    ).toEqual([])
  })

  it('parses system info including the demo reset flag, and the reset result', () => {
    expect(systemInfoSchema.parse(fixtures.systemInfo).demo.resetEnabled).toBe(true)
    expect(
      demoResetSchema.parse({ factoryId: fixtures.factory.id, resetAt: '2026-11-14T06:40:00.123456Z' }).factoryId,
    ).toBe(fixtures.factory.id)
  })

  it('rejects a number where a decimal string is required', () => {
    const pool = { ...fixtures.resources.items[0], hourlyCost: 160 }

    expect(resourceSchema.safeParse(pool).success).toBe(false)
  })
})

describe('formatters', () => {
  it('formats durations in the largest sensible units', () => {
    expect(formatDuration(0)).toBe('0 s')
    expect(formatDuration(45)).toBe('45 s')
    expect(formatDuration(900)).toBe('15 min')
    expect(formatDuration(930)).toBe('15 min 30 s')
    expect(formatDuration(5400)).toBe('1 h 30 min')
    expect(formatDuration(172_800)).toBe('48 h')
  })

  it('formats yields as percentages and decimals without trailing zeros', () => {
    expect(formatYield('1.000000')).toBe('100%')
    expect(formatYield('0.985000')).toBe('98.5%')
    expect(formatYield('0.620000')).toBe('62%')
    expect(formatDecimal('240.0000', 4)).toBe('240')
    expect(formatDecimal('6.2500', 4)).toBe('6.25')
  })

  it('formats money in the factory currency with two decimals', () => {
    expect(formatMoney('140.00', 'INR')).toMatch(/^₹\s?140\.00$/)
    expect(formatMoney('150', 'NOT_A_CURRENCY')).toBe('150 NOT_A_CURRENCY')
  })

  it('names codes and days', () => {
    expect(humanizeCode('POLISHING_WHEEL')).toBe('Polishing wheel')
    expect(dayName(1)).toBe('Monday')
    expect(dayName(7)).toBe('Sunday')
  })

  it('shows an instant as wall-clock time in the factory zone', () => {
    expect(formatDateTimeInZone('2026-01-07T03:30:00Z', 'Asia/Kolkata')).toBe('Wed, 7 Jan 2026, 09:00')
  })
})

describe('zonedLocalToInstant', () => {
  it('reads a datetime-local value as wall-clock time in the given zone', () => {
    expect(zonedLocalToInstant('2026-01-07T09:00', 'Asia/Kolkata')).toBe('2026-01-07T03:30:00Z')
    expect(zonedLocalToInstant('2026-01-07T18:00', 'Asia/Kolkata')).toBe('2026-01-07T12:30:00Z')
    expect(zonedLocalToInstant('2026-01-07T09:00', 'UTC')).toBe('2026-01-07T09:00:00Z')
  })

  it('applies the offset in force on that date in a zone with daylight saving', () => {
    expect(zonedLocalToInstant('2026-01-15T09:00', 'America/New_York')).toBe('2026-01-15T14:00:00Z')
    expect(zonedLocalToInstant('2026-07-15T09:00', 'America/New_York')).toBe('2026-07-15T13:00:00Z')
  })

  it('is correct on either side of a daylight-saving change', () => {
    // US clocks go forward at 02:00 on 8 March 2026.
    expect(zonedLocalToInstant('2026-03-08T01:30', 'America/New_York')).toBe('2026-03-08T06:30:00Z')
    expect(zonedLocalToInstant('2026-03-08T03:30', 'America/New_York')).toBe('2026-03-08T07:30:00Z')
  })

  it('round-trips through the zone formatter', () => {
    const instant = zonedLocalToInstant('2026-01-10T14:45', 'Asia/Kolkata')

    expect(instant).not.toBeNull()
    expect(formatDateTimeInZone(instant ?? '', 'Asia/Kolkata')).toBe('Sat, 10 Jan 2026, 14:45')
  })

  it('returns null for input that is not a date-time', () => {
    expect(zonedLocalToInstant('', 'Asia/Kolkata')).toBeNull()
    expect(zonedLocalToInstant('tomorrow', 'Asia/Kolkata')).toBeNull()
  })
})

import { z } from 'zod'

/**
 * Zod schemas for the factory configuration API (Milestone 2). They mirror the backend DTOs in
 * `*Dtos.java`. Decimals (money, rates, yields) arrive as strings and stay strings here; they are
 * converted only for display, in `format.ts`.
 */

const uuid = z.uuid()
const instant = z.iso.datetime({ offset: true })
/** A decimal sent as a string so that no precision is lost. */
const decimalString = z.string().regex(/^\d+(\.\d+)?$/)
const localTime = z.string().regex(/^\d{2}:\d{2}$/)

/** The list envelope every collection endpoint returns. `page` is zero-based. */
export function pageOf<T extends z.ZodType>(item: T) {
  return z.object({
    items: z.array(item),
    page: z.number().int().nonnegative(),
    size: z.number().int().positive(),
    totalItems: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
  })
}

/* ---- Factory -------------------------------------------------------------------------------- */

export const factorySummarySchema = z.object({
  id: uuid,
  name: z.string(),
  timeZone: z.string(),
  currencyCode: z.string(),
  demo: z.boolean(),
})
export type FactorySummary = z.infer<typeof factorySummarySchema>

export const calendarBreakSchema = z.object({ startTime: localTime, endTime: localTime })

export const shiftSchema = z.object({
  id: uuid,
  /** ISO day number: 1 = Monday .. 7 = Sunday. */
  dayOfWeek: z.number().int().min(1).max(7),
  startTime: localTime,
  endTime: localTime,
  breaks: z.array(calendarBreakSchema),
})
export type Shift = z.infer<typeof shiftSchema>

export const factorySchema = factorySummarySchema.extend({
  description: z.string().nullable(),
  calendar: z.object({
    shifts: z.array(shiftSchema),
    weeklyWorkingSeconds: z.number().int().nonnegative(),
  }),
  /** How many of each configured thing the factory has; a key appears once its module exists. */
  counts: z.record(z.string(), z.number().int().nonnegative()),
  version: z.number().int(),
  createdAt: instant,
  updatedAt: instant,
})
export type Factory = z.infer<typeof factorySchema>

/* ---- Resources ------------------------------------------------------------------------------ */

export const resourceKindSchema = z.enum(['MACHINE', 'EMPLOYEE'])
export type ResourceKind = z.infer<typeof resourceKindSchema>

export const resourceStatusSchema = z.enum(['ACTIVE', 'INACTIVE'])
export type ResourceStatus = z.infer<typeof resourceStatusSchema>

export const requiredByStageSchema = z.object({
  stageId: uuid,
  stageCode: z.string(),
  stageName: z.string(),
  sequence: z.number().int(),
  unitsPerSlot: z.number().int().positive(),
})

export const resourceSchema = z.object({
  id: uuid,
  factoryId: uuid,
  kind: resourceKindSchema,
  category: z.string(),
  name: z.string(),
  /** Number of interchangeable units in the pool. */
  capacity: z.number().int().nonnegative(),
  /** Factory currency per unit per hour. */
  hourlyCost: decimalString,
  status: resourceStatusSchema,
  requiredByStages: z.array(requiredByStageSchema),
  version: z.number().int().nonnegative(),
  createdAt: instant,
  updatedAt: instant,
})
export type Resource = z.infer<typeof resourceSchema>

/** Body of `PUT /api/resources/{id}`. Every field is required; `version` is the one last read. */
export interface UpdateResourceInput {
  name: string
  category: string
  capacity: number
  hourlyCost: string
  status: ResourceStatus
  version: number
}

export const availabilityWindowSchema = z.object({
  id: uuid,
  startsAt: instant,
  endsAt: instant,
  /** Negative: units unavailable for the window. */
  capacityDelta: z.number().int(),
  reason: z.string(),
  createdAt: instant,
})
export type AvailabilityWindow = z.infer<typeof availabilityWindowSchema>

export const capacityTimelineSchema = z.object({
  resourceId: uuid,
  baseCapacity: z.number().int().nonnegative(),
  from: instant.nullable(),
  to: instant.nullable(),
  windows: z.array(availabilityWindowSchema),
  timeline: z.array(z.object({ from: instant, to: instant, capacity: z.number().int().nonnegative() })),
})
export type CapacityTimeline = z.infer<typeof capacityTimelineSchema>

/** Body of `POST /api/resources/{id}/availability`. Instants are ISO-8601 UTC. */
export interface CreateAvailabilityInput {
  startsAt: string
  endsAt: string
  capacityDelta: number
  reason: string
}

/* ---- Workflow ------------------------------------------------------------------------------- */

export const workflowStatusSchema = z.enum(['DRAFT', 'ACTIVE', 'ARCHIVED'])

export const workflowSummarySchema = z.object({
  id: uuid,
  factoryId: uuid,
  name: z.string(),
  status: workflowStatusSchema,
  stageCount: z.number().int().nonnegative(),
})
export type WorkflowSummary = z.infer<typeof workflowSummarySchema>

export const stageRequirementSchema = z.object({
  resourceId: uuid,
  resourceName: z.string(),
  kind: resourceKindSchema,
  unitsPerSlot: z.number().int().positive(),
  poolCapacity: z.number().int().nonnegative(),
})

export const stageSchema = z.object({
  id: uuid,
  code: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  sequence: z.number().int().positive(),
  /** Stones per hour per processing slot. */
  stonesPerHour: decimalString,
  /** Seconds per job. */
  setupSeconds: z.number().int().nonnegative(),
  /** Fractions in (0, 1]. */
  stoneYield: decimalString,
  caratYield: decimalString,
  /** Factory currency per job. */
  fixedCostPerJob: decimalString,
  requirements: z.array(stageRequirementSchema),
  /** Jobs the stage can run at once at base capacity; null when it needs no resources. */
  maxSlots: z.number().int().nonnegative().nullable(),
})
export type Stage = z.infer<typeof stageSchema>

export const workflowSchema = z.object({
  id: uuid,
  factoryId: uuid,
  name: z.string(),
  description: z.string().nullable(),
  status: workflowStatusSchema,
  stages: z.array(stageSchema),
  version: z.number().int(),
  createdAt: instant,
  updatedAt: instant,
})
export type Workflow = z.infer<typeof workflowSchema>

/* ---- Demo ----------------------------------------------------------------------------------- */

export const demoResetSchema = z.object({ factoryId: uuid, resetAt: instant })
export type DemoReset = z.infer<typeof demoResetSchema>

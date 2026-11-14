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

/* ---- Production: orders, events, timeline, state (Milestone 3) ------------------------------- */

/*
 * Everything below is recorded history ("Actual"). Stones and carats are separate quantities and
 * are never added to each other; both arrive as decimal strings.
 */

export const orderStatusSchema = z.enum(['PENDING', 'IN_PROGRESS', 'COMPLETED', 'DISPATCHED'])
export type OrderStatus = z.infer<typeof orderStatusSchema>

export const batchStatusSchema = z.enum(['QUEUED', 'IN_PROGRESS', 'COMPLETED'])
export type BatchStatus = z.infer<typeof batchStatusSchema>

export const eventTypeSchema = z.enum([
  'ORDER_CREATED',
  'STAGE_QUEUED',
  'STAGE_STARTED',
  'STAGE_COMPLETED',
  'RESOURCE_UNAVAILABLE',
  'ORDER_COMPLETED',
  'ORDER_DISPATCHED',
  'CORRECTION',
])
export type EventType = z.infer<typeof eventTypeSchema>

export const orderSchema = z.object({
  id: uuid,
  factoryId: uuid,
  orderNumber: z.string(),
  customerRef: z.string().nullable(),
  /** 1 to 5; 5 is the most urgent. */
  priority: z.number().int().min(1).max(5),
  status: orderStatusSchema,
  placedAt: instant,
  dueAt: instant.nullable(),
  completedAt: instant.nullable(),
  /** Finished after it was due, or unfinished and past due now. */
  delayed: z.boolean(),
  batchCount: z.number().int().nonnegative(),
  batchesCompleted: z.number().int().nonnegative(),
  /** Whole stones the order started with. */
  totalStones: z.number().int().nonnegative(),
  totalRoughCarats: decimalString,
  source: z.literal('ACTUAL'),
})
export type Order = z.infer<typeof orderSchema>

export const batchSchema = z.object({
  id: uuid,
  batchNumber: z.number().int().positive(),
  /** What the batch started with. */
  stoneCount: z.number().int().positive(),
  roughCarats: decimalString,
  /** What it holds now: the output of its last completed stage. */
  currentStones: decimalString,
  currentCarats: decimalString,
  /** Null once the batch has finished. */
  currentStageId: uuid.nullable(),
  currentStageCode: z.string().nullable(),
  currentStageName: z.string().nullable(),
  currentStageSequence: z.number().int().nullable(),
  status: batchStatusSchema,
})
export type Batch = z.infer<typeof batchSchema>

export const eventSchema = z.object({
  id: uuid,
  /** Recording order. */
  seq: z.number().int(),
  eventType: eventTypeSchema,
  /** When it happened. */
  occurredAt: instant,
  /** When the row was written. */
  recordedAt: instant,
  orderId: uuid.nullable(),
  orderNumber: z.string().nullable(),
  batchId: uuid.nullable(),
  batchNumber: z.number().int().nullable(),
  stageId: uuid.nullable(),
  stageCode: z.string().nullable(),
  stageName: z.string().nullable(),
  resourceId: uuid.nullable(),
  resourceName: z.string().nullable(),
  /** Effective values: those of the latest correction, if one was made. */
  stoneQuantity: decimalString.nullable(),
  caratQuantity: decimalString.nullable(),
  durationSeconds: z.number().int().nonnegative().nullable(),
  payload: z.record(z.string(), z.unknown()),
  correctsEventId: uuid.nullable(),
  /** True on a correction row that cancels its target. */
  voids: z.boolean(),
  /** A correction has replaced at least one value; `original` holds what was first recorded. */
  corrected: z.boolean(),
  /** A correction cancelled this event; it no longer counts. */
  voided: z.boolean(),
  original: z
    .object({
      stoneQuantity: decimalString.nullable(),
      caratQuantity: decimalString.nullable(),
      durationSeconds: z.number().int().nonnegative().nullable(),
    })
    .nullable(),
  source: z.literal('ACTUAL'),
})
export type OperationalEvent = z.infer<typeof eventSchema>

export const orderDetailSchema = z.object({
  order: orderSchema,
  batches: z.array(batchSchema),
  /** The full history of the order in recording order, correction rows included. */
  events: z.array(eventSchema),
})
export type OrderDetail = z.infer<typeof orderDetailSchema>

export const timelineSummarySchema = z.object({
  bucket: z.enum(['DAY', 'HOUR']),
  timeZone: z.string(),
  firstEventAt: instant.nullable(),
  lastEventAt: instant.nullable(),
  /** Oldest first; buckets with no events are absent. Corrections and voided events are not counted. */
  buckets: z.array(
    z.object({
      /** Start of the bucket in factory-local time, as a UTC instant. */
      start: instant,
      total: z.number().int().nonnegative(),
      byType: z.partialRecord(eventTypeSchema, z.number().int().nonnegative()),
    }),
  ),
  source: z.literal('ACTUAL'),
})
export type TimelineSummary = z.infer<typeof timelineSummarySchema>

export const stageStateSchema = z.object({
  stageId: uuid,
  code: z.string(),
  name: z.string(),
  sequence: z.number().int().positive(),
  /** Batches waiting at the stage. */
  queued: z.number().int().nonnegative(),
  /** Batches being worked on at the stage. */
  inProgress: z.number().int().nonnegative(),
  /** Batches that have finished the stage. */
  completed: z.number().int().nonnegative(),
})
export type StageState = z.infer<typeof stageStateSchema>

export const batchStateSchema = z.object({
  batchId: uuid,
  orderId: uuid,
  orderNumber: z.string(),
  batchNumber: z.number().int().positive(),
  priority: z.number().int().min(1).max(5),
  status: batchStatusSchema,
  stageId: uuid.nullable(),
  stageCode: z.string().nullable(),
  stageSequence: z.number().int().nullable(),
  /** What the batch holds on entering its current stage, or its final output when completed. */
  inputStones: decimalString,
  inputCarats: decimalString,
  /** When it entered the queue of its current stage; null once in progress or completed. */
  waitingSince: instant.nullable(),
  startedAt: instant.nullable(),
  completedAt: instant.nullable(),
})
export type BatchState = z.infer<typeof batchStateSchema>

export const resourceStateSchema = z.object({
  resourceId: uuid,
  name: z.string(),
  kind: resourceKindSchema,
  /** Configured units. */
  baseCapacity: z.number().int().nonnegative(),
  /** Units available at that instant, after availability windows. */
  capacity: z.number().int().nonnegative(),
  /** Units held by batches in progress then. */
  unitsInUse: z.number().int().nonnegative(),
})
export type ResourceState = z.infer<typeof resourceStateSchema>

/** The factory as it stood at `at`, rebuilt from the event history. */
export const factoryStateSchema = z.object({
  factoryId: uuid,
  at: instant,
  orders: z.record(orderStatusSchema, z.number().int().nonnegative()),
  stages: z.array(stageStateSchema),
  batches: z.array(batchStateSchema),
  resources: z.array(resourceStateSchema),
  source: z.literal('ACTUAL'),
})
export type FactoryState = z.infer<typeof factoryStateSchema>

const unitsInUseSchema = z.object({
  inUse: z.number().int().nonnegative(),
  capacity: z.number().int().nonnegative(),
})

export const dailyOutputSchema = z.object({
  /** Factory-local date, YYYY-MM-DD. */
  date: z.iso.date(),
  batchesCompleted: z.number().int().nonnegative(),
  stonesCompleted: decimalString,
  caratsCompleted: decimalString,
})
export type DailyOutput = z.infer<typeof dailyOutputSchema>

/** What the dashboard shows about the present, all from recorded history. */
export const factoryOverviewSchema = z.object({
  factoryId: uuid,
  asOf: instant,
  orders: z.object({
    total: z.number().int().nonnegative(),
    /** Pending plus in progress. */
    active: z.number().int().nonnegative(),
    pending: z.number().int().nonnegative(),
    inProgress: z.number().int().nonnegative(),
    completed: z.number().int().nonnegative(),
    dispatched: z.number().int().nonnegative(),
    /** Late orders of any status. */
    delayed: z.number().int().nonnegative(),
  }),
  stages: z.array(stageStateSchema),
  resources: z.array(resourceStateSchema),
  /** A snapshot of this instant, not utilization over time. */
  machines: unitsInUseSchema,
  employees: unitsInUseSchema,
  /** Oldest first, one entry per factory-local day. */
  dailyOutput: z.array(dailyOutputSchema),
  recentEvents: z.array(eventSchema),
  source: z.literal('ACTUAL'),
})
export type FactoryOverview = z.infer<typeof factoryOverviewSchema>

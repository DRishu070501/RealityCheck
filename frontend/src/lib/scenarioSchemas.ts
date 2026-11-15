import { z } from 'zod'

/**
 * Zod schemas for scenarios, simulation runs and comparisons (Milestone 5 API). They mirror
 * `ScenarioDtos`, `RunDtos`, `SimulationDtos` and `ComparisonDtos` on the backend. Decimals arrive
 * as strings and stay strings; they are converted only for display.
 */

const uuid = z.uuid()
const instant = z.iso.datetime({ offset: true })
/** A decimal as a string, possibly negative (differences are). */
const decimal = z.string().regex(/^-?\d+(\.\d+)?$/)

/* ---- Scenarios ------------------------------------------------------------------------------ */

export const changeTypeSchema = z.enum([
  'RESOURCE_CAPACITY',
  'RESOURCE_UNAVAILABLE',
  'RESOURCE_DOWNTIME',
  'DEMAND_VOLUME',
  'PROCESSING_RATE',
  'WORKING_SCHEDULE',
])
export type ChangeType = z.infer<typeof changeTypeSchema>

export const runStatusSchema = z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'])
export type RunStatus = z.infer<typeof runStatusSchema>

export const latestRunSchema = z.object({
  runId: uuid,
  status: runStatusSchema,
  finishedAt: instant.nullable(),
})
export type LatestRun = z.infer<typeof latestRunSchema>

export const scenarioChangeSchema = z.object({
  id: uuid,
  ordinal: z.number().int().positive(),
  changeType: changeTypeSchema,
  inherited: z.boolean(),
  /** The typed payload as stored; its shape depends on `changeType`. */
  payload: z.record(z.string(), z.unknown()),
  /** Plain-language description built by the server from the payload and names; no simulated numbers. */
  summary: z.string(),
})
export type ScenarioChange = z.infer<typeof scenarioChangeSchema>

export const scenarioSchema = z.object({
  id: uuid,
  factoryId: uuid,
  name: z.string(),
  description: z.string().nullable(),
  parentScenarioId: uuid.nullable(),
  forkAt: instant,
  includeFutureOrders: z.boolean(),
  baseline: z.boolean(),
  changes: z.array(scenarioChangeSchema),
  latestRun: latestRunSchema.nullable(),
  version: z.number().int(),
  createdAt: instant,
  updatedAt: instant,
})
export type Scenario = z.infer<typeof scenarioSchema>

export const scenarioSummarySchema = scenarioSchema.omit({ changes: true }).extend({
  changeCount: z.number().int().nonnegative(),
})
export type ScenarioSummary = z.infer<typeof scenarioSummarySchema>

export const validationProblemSchema = z.object({
  changeId: uuid.nullable(),
  code: z.string(),
  message: z.string(),
  subject: z.string().nullable(),
})

export const validationWarningSchema = z.object({
  changeId: uuid.nullable(),
  ordinal: z.number().int(),
  code: z.string(),
  message: z.string(),
})

export const scenarioValidationSchema = z.object({
  valid: z.boolean(),
  errors: z.array(validationProblemSchema),
  warnings: z.array(validationWarningSchema),
  preview: z
    .object({
      pools: z.array(
        z.object({
          resourceId: uuid,
          name: z.string(),
          kind: z.enum(['MACHINE', 'EMPLOYEE']),
          capacityBefore: z.number().int(),
          capacityAfter: z.number().int(),
          windowsAdded: z.number().int(),
        }),
      ),
      stages: z.array(
        z.object({
          stageId: uuid,
          code: z.string(),
          name: z.string(),
          stonesPerHourBefore: decimal,
          stonesPerHourAfter: decimal,
          maxSlotsBefore: z.number().int().nullable(),
          maxSlotsAfter: z.number().int().nullable(),
        }),
      ),
      weeklyWorkingSecondsBefore: z.number().int(),
      weeklyWorkingSecondsAfter: z.number().int(),
      unstartedStonesBefore: decimal,
      unstartedStonesAfter: decimal,
    })
    .nullable(),
  baseStateHash: z.string(),
  inputHash: z.string().nullable(),
})
export type ScenarioValidation = z.infer<typeof scenarioValidationSchema>

/* ---- Runs ----------------------------------------------------------------------------------- */

export const runSubmittedSchema = z.object({
  runId: uuid,
  scenarioId: uuid,
  status: runStatusSchema,
  reused: z.boolean(),
  submittedAt: instant,
})
export type RunSubmitted = z.infer<typeof runSubmittedSchema>

export const runSchema = z.object({
  runId: uuid,
  scenarioId: uuid,
  scenarioName: z.string().nullable(),
  factoryId: uuid,
  status: runStatusSchema,
  forkAt: instant,
  algorithmVersion: z.string(),
  inputHash: z.string(),
  baseStateHash: z.string(),
  terminationReason: z.string().nullable(),
  simulatedEndAt: instant.nullable(),
  eventsProcessed: z.number().int().nullable(),
  failureMessage: z.string().nullable(),
  submittedAt: instant,
  startedAt: instant.nullable(),
  finishedAt: instant.nullable(),
})
export type Run = z.infer<typeof runSchema>

const costSchema = z.object({
  employee: decimal,
  machine: decimal,
  fixed: decimal,
  total: decimal,
  perOutputStone: decimal.nullable(),
  currencyCode: z.string(),
})

export const summarySchema = z.object({
  elapsedSeconds: z.number().int(),
  workingSeconds: z.number().int(),
  processingSeconds: z.number().int(),
  waitingWorkingSeconds: z.number().int(),
  waitingCalendarSeconds: z.number().int(),
  inputStones: decimal,
  outputStones: decimal,
  outputStonesRounded: decimal,
  inputCarats: decimal,
  outputCarats: decimal,
  stoneYield: decimal.nullable(),
  caratYield: decimal.nullable(),
  throughputStonesPerHour: decimal.nullable(),
  machineUtilization: decimal.nullable(),
  employeeUtilization: decimal.nullable(),
  cost: costSchema,
  ordersTotal: z.number().int(),
  ordersCompleted: z.number().int(),
  ordersDelayed: z.number().int(),
  ordersWithDueDate: z.number().int(),
  onTimeDeliveryRate: decimal.nullable(),
})
export type RunSummary = z.infer<typeof summarySchema>

export const bottleneckEvidenceSchema = z.object({
  limitingPoolId: uuid.nullable(),
  limitingPoolName: z.string().nullable(),
  limitingPoolUtilization: decimal.nullable(),
  utilizationSignal: decimal,
  waitSignal: decimal,
  queueSignal: decimal,
  saturation: decimal,
  blockedShare: decimal,
  score: decimal,
})

export const stageResultSchema = z.object({
  stageId: uuid,
  code: z.string(),
  name: z.string(),
  sequence: z.number().int(),
  jobsCompleted: z.number().int(),
  inputStones: decimal,
  outputStones: decimal,
  lossStones: decimal,
  inputCarats: decimal,
  outputCarats: decimal,
  lossCarats: decimal,
  processingSeconds: z.number().int(),
  waitingWorkingSeconds: z.number().int(),
  waitingCalendarSeconds: z.number().int(),
  meanWaitWorkingSeconds: decimal,
  meanStageSeconds: decimal,
  maxStageSeconds: z.number().int(),
  meanQueueLength: decimal,
  maxQueueLength: z.number().int(),
  throughputStonesPerHour: decimal.nullable(),
  fixedCost: decimal,
  bottleneck: bottleneckEvidenceSchema,
})
export type StageResult = z.infer<typeof stageResultSchema>

export const poolResultSchema = z.object({
  resourceId: uuid,
  name: z.string(),
  kind: z.enum(['MACHINE', 'EMPLOYEE']),
  capacity: z.number().int(),
  busyUnitSeconds: z.number().int(),
  availableUnitSeconds: z.number().int(),
  idleUnitSeconds: z.number().int(),
  utilization: decimal.nullable(),
  utilizationNullReason: z.string().nullable(),
  overcommitted: z.boolean(),
  cost: decimal,
})
export type PoolResult = z.infer<typeof poolResultSchema>

export const orderResultSchema = z.object({
  orderId: uuid,
  orderNumber: z.string(),
  completesAt: instant.nullable(),
  dueAt: instant.nullable(),
  outcome: z.enum(['ON_TIME', 'LATE', 'UNFINISHED']),
  delayed: z.boolean(),
})
export type OrderResult = z.infer<typeof orderResultSchema>

export const bottleneckSchema = z.object({
  stageId: uuid.nullable(),
  stageCode: z.string().nullable(),
  stageName: z.string().nullable(),
  reasonCode: z.string(),
  explanation: z.string(),
  scoreThreshold: decimal,
  saturationThreshold: decimal,
})
export type Bottleneck = z.infer<typeof bottleneckSchema>

export const runResultsSchema = z.object({
  runId: uuid,
  scenarioId: uuid,
  source: z.literal('SIMULATED'),
  algorithmVersion: z.string(),
  currencyCode: z.string(),
  forkAt: instant,
  simulatedEndAt: instant,
  terminationReason: z.string(),
  eventsProcessed: z.number().int(),
  summary: summarySchema,
  bottleneck: bottleneckSchema,
  orders: z.array(orderResultSchema),
  resources: z.array(poolResultSchema),
  unfinished: z.array(
    z.object({ batchId: uuid, orderId: uuid, stageSequence: z.number().int(), state: z.string() }),
  ),
})
export type RunResults = z.infer<typeof runResultsSchema>

export const simulatedEventSchema = z.object({
  seq: z.number().int(),
  occurredAt: instant,
  eventType: z.enum(['JOB_QUEUED', 'JOB_STARTED', 'JOB_COMPLETED', 'CAPACITY_CHANGED', 'ORDER_COMPLETED']),
  batchId: uuid.nullable(),
  orderId: uuid.nullable(),
  stageId: uuid.nullable(),
  resourceId: uuid.nullable(),
  stones: decimal.nullable(),
  carats: decimal.nullable(),
  capacity: z.number().int().nullable(),
})
export type SimulatedEvent = z.infer<typeof simulatedEventSchema>

/* ---- Comparison ----------------------------------------------------------------------------- */

const deltaSchema = z.object({
  baseline: decimal.nullable(),
  alternative: decimal.nullable(),
  absolute: decimal.nullable(),
  percent: decimal.nullable(),
})
export type Delta = z.infer<typeof deltaSchema>

export const betterWhenSchema = z.enum(['LOWER', 'HIGHER'])
export const directionSchema = z.enum(['IMPROVED', 'WORSENED', 'UNCHANGED'])
export type Direction = z.infer<typeof directionSchema>

export const metricDeltaSchema = deltaSchema.extend({
  key: z.string(),
  label: z.string(),
  unit: z.string(),
  betterWhen: betterWhenSchema.nullable(),
  direction: directionSchema.nullable(),
})
export type MetricDelta = z.infer<typeof metricDeltaSchema>

const comparedRunSchema = z.object({
  runId: uuid,
  scenarioId: uuid,
  scenarioName: z.string().nullable(),
  algorithmVersion: z.string(),
  terminationReason: z.string().nullable(),
  appliedChanges: z.array(z.object({ ordinal: z.number().int(), changeType: z.string(), summary: z.string() })),
})
export type ComparedRun = z.infer<typeof comparedRunSchema>

const bottleneckStageSchema = z.object({
  stageId: uuid,
  code: z.string().nullable(),
  name: z.string().nullable(),
  baselineScore: decimal.nullable(),
  alternativeScore: decimal.nullable(),
})

export const bottleneckChangeSchema = z.object({
  change: z.enum(['SAME', 'MOVED', 'RESOLVED', 'EMERGED', 'NONE']),
  baselineStage: bottleneckStageSchema.nullable(),
  alternativeStage: bottleneckStageSchema.nullable(),
})
export type BottleneckChange = z.infer<typeof bottleneckChangeSchema>

export const alternativeComparisonSchema = z.object({
  run: comparedRunSchema,
  metrics: z.array(metricDeltaSchema),
  bottleneck: bottleneckChangeSchema,
  stages: z.array(
    z.object({
      stageId: uuid,
      code: z.string(),
      name: z.string(),
      sequence: z.number().int().nullable(),
      meanWaitWorkingSeconds: deltaSchema,
      meanQueueLength: deltaSchema,
      maxQueueLength: deltaSchema,
      throughputStonesPerHour: deltaSchema,
      limitingUtilization: deltaSchema,
      bottleneckScore: deltaSchema,
    }),
  ),
  resources: z.array(
    z.object({
      resourceId: uuid,
      name: z.string(),
      kind: z.string(),
      capacity: deltaSchema,
      utilization: deltaSchema,
      busyUnitSeconds: deltaSchema,
      idleUnitSeconds: deltaSchema,
      cost: deltaSchema,
    }),
  ),
  orders: z.array(
    z.object({
      orderId: uuid,
      orderNumber: z.string(),
      dueAt: instant.nullable(),
      baselineCompletionAt: instant.nullable(),
      alternativeCompletionAt: instant.nullable(),
      differenceSeconds: z.number().int().nullable(),
      baselineOutcome: z.string(),
      alternativeOutcome: z.string(),
    }),
  ),
})
export type AlternativeComparison = z.infer<typeof alternativeComparisonSchema>

export const comparisonSchema = z.object({
  source: z.literal('SIMULATED'),
  currencyCode: z.string().nullable(),
  sameStartingState: z.boolean(),
  forkAt: instant,
  warnings: z.array(z.object({ code: z.string(), runId: uuid, message: z.string() })),
  baseline: comparedRunSchema,
  alternatives: z.array(alternativeComparisonSchema),
  actual: z.null(),
})
export type Comparison = z.infer<typeof comparisonSchema>

export const scenarioComparisonsSchema = z.object({
  baselineMissing: z.boolean(),
  suggestedBaselineScenarioId: uuid.nullable(),
  items: z.array(comparisonSchema),
})
export type ScenarioComparisons = z.infer<typeof scenarioComparisonsSchema>

/* ---- Request bodies ------------------------------------------------------------------------- */

export interface CreateScenarioInput {
  factoryId: string
  name: string
  description?: string
  forkAt?: string
  parentScenarioId?: string
  includeFutureOrders?: boolean
}

export interface UpdateScenarioInput {
  name?: string
  description?: string
  version: number
}

export interface ChangeInput {
  changeType: ChangeType
  payload: Record<string, unknown>
}

export interface CompareInput {
  baselineRunId: string
  alternativeRunIds: string[]
  allowDifferentBaseline?: boolean
}

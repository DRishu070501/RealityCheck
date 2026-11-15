import { z } from 'zod'

import {
  availabilityWindowSchema,
  capacityTimelineSchema,
  demoResetSchema,
  eventSchema,
  factoryOverviewSchema,
  factorySchema,
  factoryStateSchema,
  factorySummarySchema,
  orderDetailSchema,
  orderSchema,
  pageOf,
  resourceSchema,
  stageSchema,
  timelineSummarySchema,
  workflowSchema,
  workflowSummarySchema,
  type CreateAvailabilityInput,
  type EventType,
  type OrderStatus,
  type ResourceKind,
  type UpdateResourceInput,
} from '@/lib/schemas'
import {
  comparisonSchema,
  runResultsSchema,
  runSchema,
  runSubmittedSchema,
  scenarioChangeSchema,
  scenarioComparisonsSchema,
  scenarioSchema,
  scenarioSummarySchema,
  scenarioValidationSchema,
  simulatedEventSchema,
  stageResultSchema,
  type ChangeInput,
  type CompareInput,
  type CreateScenarioInput,
  type UpdateScenarioInput,
} from '@/lib/scenarioSchemas'

/**
 * The single place the application talks to the backend. Every response is parsed with a Zod
 * schema, and every failure surfaces as an {@link ApiError} with a stable `kind`.
 */

const fieldErrorSchema = z.object({ field: z.string(), message: z.string() })

/**
 * RFC 9457 problem details, as produced by the backend's exception handler. Extra members (for
 * example `status` of a run, or `errors` that are scenario problems rather than field errors) are
 * kept in {@link ApiError.body}.
 */
const problemSchema = z.looseObject({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  errors: z.array(z.unknown()).optional(),
})

export type FieldError = z.infer<typeof fieldErrorSchema>

export type ApiErrorKind =
  /** The request never produced an HTTP response (backend down, DNS, offline). */
  | 'network'
  /** The backend answered with a non-2xx status. */
  | 'http'
  /** The backend answered 2xx but the body did not match the expected schema. */
  | 'schema'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status: number | undefined
  readonly title: string
  readonly detail: string | undefined
  /** Machine-readable reason from the backend, for example `STALE_VERSION`. */
  readonly code: string | undefined
  readonly fieldErrors: readonly FieldError[]
  /** The whole problem document, for members beyond the standard ones. */
  readonly body: Readonly<Record<string, unknown>>

  constructor(init: {
    kind: ApiErrorKind
    title: string
    status?: number
    detail?: string
    code?: string
    fieldErrors?: readonly FieldError[]
    body?: Record<string, unknown>
    cause?: unknown
  }) {
    super(init.detail ?? init.title, { cause: init.cause })
    this.name = 'ApiError'
    this.kind = init.kind
    this.title = init.title
    this.status = init.status
    this.detail = init.detail
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.body = init.body ?? {}
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text.trim() === '') return undefined
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

const GATEWAY_STATUSES: ReadonlySet<number> = new Set([502, 503, 504])

function toHttpError(response: Response, body: unknown): ApiError {
  const problem = problemSchema.safeParse(body)
  const fallbackTitle = response.statusText || `Request failed with status ${response.status}`
  if (!problem.success) {
    // A gateway error without a problem body comes from the proxy in front of the backend
    // (Vite in development, nginx in the container): the backend itself is not answering.
    if (GATEWAY_STATUSES.has(response.status)) {
      return new ApiError({
        kind: 'http',
        status: response.status,
        title: 'Backend unavailable',
        detail: 'The RealityCheck backend is not responding. Check that it is running.',
      })
    }
    return new ApiError({ kind: 'http', status: response.status, title: fallbackTitle })
  }
  return new ApiError({
    kind: 'http',
    status: response.status,
    title: problem.data.title ?? fallbackTitle,
    detail: problem.data.detail,
    code: problem.data.code,
    fieldErrors: (problem.data.errors ?? []).flatMap((entry) => {
      const parsed = fieldErrorSchema.safeParse(entry)
      return parsed.success ? [parsed.data] : []
    }),
    body: problem.data,
  })
}

/**
 * Sends a request and parses the JSON response with `schema`.
 *
 * @throws ApiError for network failures, non-2xx responses and schema mismatches.
 *         An aborted request rethrows the original `AbortError` so TanStack Query can ignore it.
 */
export async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  options: RequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, headers, signal } = options

  let response: Response
  try {
    response = await fetch(path, {
      method,
      signal,
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError({
      kind: 'network',
      title: 'Backend unreachable',
      detail: 'The RealityCheck backend did not respond. Check that it is running.',
      cause,
    })
  }

  const payload = await readJson(response)

  if (!response.ok) {
    throw toHttpError(response, payload)
  }

  if (response.status === 204) return undefined as T

  const parsed = schema.safeParse(payload)
  if (!parsed.success) {
    throw new ApiError({
      kind: 'schema',
      status: response.status,
      title: 'Unexpected response from the backend',
      detail: `The response for ${path} did not match the expected format.`,
      cause: parsed.error,
    })
  }
  return parsed.data
}

/* ---------------------------------------------------------------------------------------------
 * System endpoints (Milestone 1)
 * ------------------------------------------------------------------------------------------- */

export const systemInfoSchema = z.object({
  application: z.string(),
  version: z.string(),
  serverTime: z.iso.datetime({ offset: true }),
  database: z.object({
    status: z.string(),
    product: z.string().nullish(),
    version: z.string().nullish(),
  }),
  migrations: z.object({
    appliedCount: z.number().int().nonnegative(),
    currentVersion: z.string().nullish(),
  }),
  ai: z.object({
    enabled: z.boolean(),
    provider: z.string(),
  }),
  /** Whether this deployment allows the demonstration data to be reset from the UI. */
  demo: z.object({ resetEnabled: z.boolean() }),
})
export type SystemInfo = z.infer<typeof systemInfoSchema>

/** Spring Boot Actuator health. Extra fields (components, groups) are ignored. */
export const healthSchema = z.object({ status: z.string() })
export type Health = z.infer<typeof healthSchema>

/** Largest page the backend serves; configuration lists are far smaller, so one page is all of it. */
const ALL = 200

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value))
  }
  const text = search.toString()
  return text === '' ? '' : `?${text}`
}

const factoryPageSchema = pageOf(factorySummarySchema)
const workflowPageSchema = pageOf(workflowSummarySchema)
const stagePageSchema = pageOf(stageSchema)
const resourcePageSchema = pageOf(resourceSchema)
const orderPageSchema = pageOf(orderSchema)
const eventPageSchema = pageOf(eventSchema)
const scenarioPageSchema = pageOf(scenarioSummarySchema)
const runPageSchema = pageOf(runSchema)
const simulatedEventPageSchema = pageOf(simulatedEventSchema)

/** Filters of `GET /api/orders`. The backend sorts earliest due first. */
export interface OrderListParams {
  status?: OrderStatus
  /** When set, only orders that are (true) or are not (false) late. */
  delayed?: boolean
  page: number
  size: number
}

/** Filters of `GET /api/timeline`. `from` and `to` are inclusive ISO-8601 instants. */
export interface TimelineParams {
  orderId?: string
  batchId?: string
  stageId?: string
  type?: EventType
  from?: string
  to?: string
  includeVoided?: boolean
  order: 'asc' | 'desc'
  page: number
  size: number
}

export const api = {
  factories: {
    list: (signal?: AbortSignal) => request(`/api/factories${query({ size: ALL })}`, factoryPageSchema, { signal }),
    get: (id: string, signal?: AbortSignal) => request(`/api/factories/${id}`, factorySchema, { signal }),
    /** The factory rebuilt from its event history at `at`; now when omitted. */
    state: (id: string, at?: string, signal?: AbortSignal) =>
      request(`/api/factories/${id}/state${query({ at })}`, factoryStateSchema, { signal }),
    overview: (id: string, signal?: AbortSignal) =>
      request(`/api/factories/${id}/overview`, factoryOverviewSchema, { signal }),
  },
  orders: {
    list: (factoryId: string, params: OrderListParams, signal?: AbortSignal) =>
      request(`/api/orders${query({ factoryId, ...params })}`, orderPageSchema, { signal }),
    get: (id: string, signal?: AbortSignal) => request(`/api/orders/${id}`, orderDetailSchema, { signal }),
    /** Records that a completed order left the factory, now. */
    dispatch: (orderId: string) =>
      request('/api/events', eventSchema, { method: 'POST', body: { eventType: 'ORDER_DISPATCHED', orderId } }),
  },
  timeline: {
    /** What happened, as now known: corrections folded in, voided events left out unless asked for. */
    list: (factoryId: string, params: TimelineParams, signal?: AbortSignal) =>
      request(`/api/timeline${query({ factoryId, ...params })}`, eventPageSchema, { signal }),
    summary: (factoryId: string, bucket: 'DAY' | 'HOUR', signal?: AbortSignal) =>
      request(`/api/timeline/summary${query({ factoryId, bucket })}`, timelineSummarySchema, { signal }),
  },
  workflows: {
    list: (factoryId: string, signal?: AbortSignal) =>
      request(`/api/workflows${query({ factoryId, size: ALL })}`, workflowPageSchema, { signal }),
    get: (id: string, signal?: AbortSignal) => request(`/api/workflows/${id}`, workflowSchema, { signal }),
    stages: (id: string, signal?: AbortSignal) =>
      request(`/api/workflows/${id}/stages`, stagePageSchema, { signal }),
  },
  resources: {
    list: (factoryId: string, kind?: ResourceKind, signal?: AbortSignal) =>
      request(`/api/resources${query({ factoryId, kind, size: ALL })}`, resourcePageSchema, { signal }),
    get: (id: string, signal?: AbortSignal) => request(`/api/resources/${id}`, resourceSchema, { signal }),
    update: (id: string, body: UpdateResourceInput) =>
      request(`/api/resources/${id}`, resourceSchema, { method: 'PUT', body }),
    /** Without a range the backend reports the span covered by the pool's windows. */
    availability: (id: string, range?: { from: string; to: string }, signal?: AbortSignal) =>
      request(
        `/api/resources/${id}/availability${query({ from: range?.from, to: range?.to })}`,
        capacityTimelineSchema,
        { signal },
      ),
    addAvailability: (id: string, body: CreateAvailabilityInput) =>
      request(`/api/resources/${id}/availability`, availabilityWindowSchema, { method: 'POST', body }),
  },
  scenarios: {
    list: (factoryId: string, signal?: AbortSignal) =>
      request(`/api/scenarios${query({ factoryId, size: ALL })}`, scenarioPageSchema, { signal }),
    get: (id: string, signal?: AbortSignal) => request(`/api/scenarios/${id}`, scenarioSchema, { signal }),
    create: (body: CreateScenarioInput) => request('/api/scenarios', scenarioSchema, { method: 'POST', body }),
    update: (id: string, body: UpdateScenarioInput) =>
      request(`/api/scenarios/${id}`, scenarioSchema, { method: 'PATCH', body }),
    addChange: (id: string, body: ChangeInput) =>
      request(`/api/scenarios/${id}/changes`, scenarioChangeSchema, { method: 'POST', body }),
    replaceChange: (id: string, changeId: string, body: ChangeInput) =>
      request(`/api/scenarios/${id}/changes/${changeId}`, scenarioChangeSchema, { method: 'PUT', body }),
    deleteChange: (id: string, changeId: string) =>
      request(`/api/scenarios/${id}/changes/${changeId}`, z.undefined(), { method: 'DELETE' }),
    /** Assembles and checks the scenario without storing anything. */
    validate: (id: string, signal?: AbortSignal) =>
      request(`/api/scenarios/${id}/validate`, scenarioValidationSchema, { method: 'POST', signal }),
    /** Starts a run; a repeated `idempotencyKey` returns the run it first created. */
    simulate: (id: string, idempotencyKey: string, force = false) =>
      request(`/api/scenarios/${id}/simulate${query({ force: force ? true : undefined })}`, runSubmittedSchema, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    runs: (id: string, signal?: AbortSignal) =>
      request(`/api/scenarios/${id}/simulations${query({ size: ALL })}`, runPageSchema, { signal }),
    comparisons: (id: string, signal?: AbortSignal) =>
      request(`/api/scenarios/${id}/comparisons`, scenarioComparisonsSchema, { signal }),
  },
  simulations: {
    get: (runId: string, signal?: AbortSignal) => request(`/api/simulations/${runId}`, runSchema, { signal }),
    results: (runId: string, signal?: AbortSignal) =>
      request(`/api/simulations/${runId}/results`, runResultsSchema, { signal }),
    stages: (runId: string, signal?: AbortSignal) =>
      request(`/api/simulations/${runId}/stages`, z.array(stageResultSchema), { signal }),
    events: (runId: string, page: number, size: number, signal?: AbortSignal) =>
      request(`/api/simulations/${runId}/events${query({ page, size })}`, simulatedEventPageSchema, { signal }),
    compare: (body: CompareInput) => request('/api/simulations/compare', comparisonSchema, { method: 'POST', body }),
  },
  demo: {
    /** Erases all data and re-creates the demonstration factory. Answers 404 when disabled. */
    reset: () => request('/api/demo/reset', demoResetSchema, { method: 'POST' }),
  },
  system: {
    info: (signal?: AbortSignal) => request('/api/system/info', systemInfoSchema, { signal }),
    /**
     * Actuator answers 503 with a normal health body when a component is down, so that status
     * is read as data here rather than thrown.
     */
    health: async (signal?: AbortSignal): Promise<Health> => {
      try {
        return await request('/actuator/health', healthSchema, { signal })
      } catch (error) {
        if (error instanceof ApiError && error.status === 503) return { status: 'DOWN' }
        throw error
      }
    },
  },
} as const

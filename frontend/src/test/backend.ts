import { vi } from 'vitest'

import availability from '@/test/fixtures/availability.json'
import baselineResults from '@/test/fixtures/baselineResults.json'
import comparison from '@/test/fixtures/comparison.json'
import simulatedEvents from '@/test/fixtures/events.json'
import results from '@/test/fixtures/results.json'
import run from '@/test/fixtures/run.json'
import runStages from '@/test/fixtures/runStages.json'
import runs from '@/test/fixtures/runs.json'
import scenario from '@/test/fixtures/scenario.json'
import scenarioBaseline from '@/test/fixtures/scenarioBaseline.json'
import scenarioComparisons from '@/test/fixtures/scenarioComparisons.json'
import scenarios from '@/test/fixtures/scenarios.json'
import validation from '@/test/fixtures/validation.json'
import factories from '@/test/fixtures/factories.json'
import factory from '@/test/fixtures/factory.json'
import orderDetail from '@/test/fixtures/orderDetail.json'
import orders from '@/test/fixtures/orders.json'
import overview from '@/test/fixtures/overview.json'
import resources from '@/test/fixtures/resources.json'
import state from '@/test/fixtures/state.json'
import systemInfo from '@/test/fixtures/systemInfo.json'
import timeline from '@/test/fixtures/timeline.json'
import timelineSummary from '@/test/fixtures/timelineSummary.json'
import workflow from '@/test/fixtures/workflow.json'
import workflows from '@/test/fixtures/workflows.json'
import { jsonResponse } from '@/test/utils'

/**
 * Responses captured from the running backend with the demonstration seed loaded. The production
 * fixtures (orders, events, state, overview) were taken on 14 November 2026, so their history
 * runs from 26 October to that day; `timeline` holds only the newest 14 of its 1,376 events.
 */
export const fixtures = {
  availability,
  baselineResults,
  comparison,
  results,
  run,
  runStages,
  runs,
  scenario,
  scenarioBaseline,
  scenarioComparisons,
  scenarios,
  simulatedEvents,
  validation,
  factories,
  factory,
  orderDetail,
  orders,
  overview,
  resources,
  state,
  systemInfo,
  timeline,
  timelineSummary,
  workflow,
  workflows,
}

export const FACTORY_ID = factory.id
export const WORKFLOW_ID = workflow.id
/** SDW-0002: dispatched, one batch, with one corrected stage completion. */
export const ORDER_ID = orderDetail.order.id

/**
 * Scenario fixtures, captured on 15 November 2026 from the six seeded demonstration scenarios after
 * all six were run. `scenario` is "Introduce an additional shift" and `run` its completed run;
 * `comparison` compares it with the baseline run, whose results are `baselineResults`.
 * `simulatedEvents` is the first 200 of that run's 674 simulated events.
 */
export const SCENARIO_ID = scenario.id
export const BASELINE_SCENARIO_ID = scenarioBaseline.id
export const RUN_ID = run.runId
export const BASELINE_RUN_ID = comparison.baseline.runId

/** The scenario endpoints for the captured scenarios and runs. */
export function scenarioRoutes(): Routes {
  return {
    'GET /api/scenarios': () => jsonResponse(scenarios),
    [`GET /api/scenarios/${SCENARIO_ID}`]: () => jsonResponse(scenario),
    [`GET /api/scenarios/${BASELINE_SCENARIO_ID}`]: () => jsonResponse(scenarioBaseline),
    [`POST /api/scenarios/${SCENARIO_ID}/validate`]: () => jsonResponse(validation),
    [`GET /api/scenarios/${SCENARIO_ID}/simulations`]: () => jsonResponse(runs),
    [`GET /api/scenarios/${SCENARIO_ID}/comparisons`]: () => jsonResponse(scenarioComparisons),
    [`GET /api/simulations/${RUN_ID}`]: () => jsonResponse(run),
    [`GET /api/simulations/${RUN_ID}/results`]: () => jsonResponse(results),
    [`GET /api/simulations/${RUN_ID}/stages`]: () => jsonResponse(runStages),
    [`GET /api/simulations/${RUN_ID}/events`]: () => jsonResponse(simulatedEvents),
    [`GET /api/simulations/${BASELINE_RUN_ID}/results`]: () => jsonResponse(baselineResults),
    'POST /api/simulations/compare': () => jsonResponse(comparison),
  }
}

export function poolNamed(name: string) {
  const pool = resources.items.find((item) => item.name === name)
  if (!pool) throw new Error(`No fixture pool named ${name}`)
  return pool
}

export function stageNamed(name: string) {
  const stage = workflow.stages.find((item) => item.name === name)
  if (!stage) throw new Error(`No fixture stage named ${name}`)
  return stage
}

export interface MockRequest {
  method: string
  path: string
  query: URLSearchParams
  body: unknown
  /** Request headers as sent; names are lower-case. */
  headers: Record<string, string>
}

type Handler = (request: MockRequest) => Response | Promise<Response>

/** Keys are "METHOD /path". A key ending in "*" matches any path with that prefix. */
export type Routes = Record<string, Handler>

export function problem(
  status: number,
  title: string,
  detail: string,
  errors?: { field: string; message: string }[],
  code?: string,
) {
  return jsonResponse(
    { type: 'about:blank', title, status, detail, ...(errors ? { errors } : {}), ...(code ? { code } : {}) },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

export const emptyPage = { items: [], page: 0, size: 200, totalItems: 0, totalPages: 0 }

/** The seeded demonstration factory, as the backend serves it. */
export function demoRoutes(): Routes {
  return {
    'GET /api/system/info': () => jsonResponse(systemInfo),
    'GET /actuator/health': () => jsonResponse({ status: 'UP' }),
    'GET /api/factories': () => jsonResponse(factories),
    [`GET /api/factories/${FACTORY_ID}`]: () => jsonResponse(factory),
    [`GET /api/factories/${FACTORY_ID}/state`]: () => jsonResponse(state),
    [`GET /api/factories/${FACTORY_ID}/overview`]: () => jsonResponse(overview),
    'GET /api/workflows': () => jsonResponse(workflows),
    [`GET /api/workflows/${WORKFLOW_ID}`]: () => jsonResponse(workflow),
    'GET /api/resources': ({ query }) => {
      const kind = query.get('kind')
      const items = resources.items.filter((item) => kind === null || item.kind === kind)
      return jsonResponse({ ...resources, items, totalItems: items.length })
    },
    'GET /api/resources/*': ({ path }) =>
      path.endsWith('/availability')
        ? jsonResponse(availability)
        : jsonResponse(resources.items.find((item) => path.endsWith(item.id)) ?? null),
    // The first page of orders as captured; the filters are applied to that page.
    'GET /api/orders': ({ query }) => {
      const status = query.get('status')
      const delayed = query.get('delayed')
      const items = orders.items.filter(
        (item) => (status === null || item.status === status) && (delayed === null || String(item.delayed) === delayed),
      )
      const filtered = status !== null || delayed !== null
      return jsonResponse({
        ...orders,
        items,
        page: Number(query.get('page') ?? '0'),
        totalItems: filtered ? items.length : orders.totalItems,
        totalPages: filtered ? 1 : orders.totalPages,
      })
    },
    [`GET /api/orders/${ORDER_ID}`]: () => jsonResponse(orderDetail),
    'GET /api/timeline': () => jsonResponse(timeline),
    'GET /api/timeline/summary': () => jsonResponse(timelineSummary),
  }
}

/**
 * Replaces `fetch` with a router over `routes`. A request no route matches rejects, so a test
 * fails loudly if the page calls something it did not expect.
 */
export function mockBackend(routes: Routes) {
  const requests: MockRequest[] = []
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(raw, 'http://localhost')
    const method = init?.method ?? 'GET'
    const request: MockRequest = {
      method,
      path: url.pathname,
      query: url.searchParams,
      body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined,
      headers: Object.fromEntries(new Headers(init?.headers).entries()),
    }
    requests.push(request)
    const exact = routes[`${method} ${url.pathname}`]
    if (exact) return exact(request)
    const prefixed = Object.entries(routes).find(
      ([key]) => key.endsWith('*') && `${method} ${url.pathname}`.startsWith(key.slice(0, -1)),
    )
    if (prefixed) return prefixed[1](request)
    throw new Error(`Unexpected request: ${method} ${url.pathname}`)
  })
  vi.stubGlobal('fetch', spy)
  return {
    requests,
    /** Requests made with the given method to the given path. */
    sent: (method: string, path: string) => requests.filter((r) => r.method === method && r.path === path),
    /** The most recent request with the given method to the given path. */
    last: (method: string, path: string): MockRequest => {
      const matching = requests.filter((r) => r.method === method && r.path === path)
      const request = matching[matching.length - 1]
      if (!request) throw new Error(`No ${method} ${path} request was made`)
      return request
    },
  }
}

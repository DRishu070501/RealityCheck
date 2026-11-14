import { vi } from 'vitest'

import availability from '@/test/fixtures/availability.json'
import factories from '@/test/fixtures/factories.json'
import factory from '@/test/fixtures/factory.json'
import resources from '@/test/fixtures/resources.json'
import systemInfo from '@/test/fixtures/systemInfo.json'
import workflow from '@/test/fixtures/workflow.json'
import workflows from '@/test/fixtures/workflows.json'
import { jsonResponse } from '@/test/utils'

/** Responses captured from the running backend with the demonstration seed loaded. */
export const fixtures = { availability, factories, factory, resources, systemInfo, workflow, workflows }

export const FACTORY_ID = factory.id
export const WORKFLOW_ID = workflow.id

export function poolNamed(name: string) {
  const pool = resources.items.find((item) => item.name === name)
  if (!pool) throw new Error(`No fixture pool named ${name}`)
  return pool
}

export interface MockRequest {
  method: string
  path: string
  query: URLSearchParams
  body: unknown
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
  }
}

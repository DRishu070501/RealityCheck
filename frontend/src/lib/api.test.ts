import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'

import { api, ApiError, request } from '@/lib/api'
import { jsonResponse, systemInfoFixture } from '@/test/utils'

const thingSchema = z.object({ id: z.string(), count: z.number() })

function mockFetch(impl: () => Promise<Response>) {
  const spy = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() => impl())
  vi.stubGlobal('fetch', spy)
  return spy
}

async function captureError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
  throw new Error('Expected the request to fail')
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('returns the parsed body on success', async () => {
    const fetchSpy = mockFetch(() => Promise.resolve(jsonResponse({ id: 'a', count: 2, extra: true })))

    await expect(request('/api/things/a', thingSchema)).resolves.toEqual({ id: 'a', count: 2 })

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/things/a',
      expect.objectContaining({ method: 'GET', body: undefined }),
    )
  })

  it('serialises a JSON body and sets the content type', async () => {
    const fetchSpy = mockFetch(() => Promise.resolve(jsonResponse({ id: 'b', count: 1 }, { status: 201 })))

    await request('/api/things', thingSchema, { method: 'POST', body: { count: 1 } })

    const init = fetchSpy.mock.calls[0]?.[1]
    expect(init?.body).toBe('{"count":1}')
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' })
  })

  it('maps a problem+json response to a typed ApiError with field errors', async () => {
    mockFetch(() =>
      Promise.resolve(
        jsonResponse(
          {
            type: 'about:blank',
            title: 'Validation failed',
            status: 400,
            detail: 'The request has 1 invalid field.',
            errors: [{ field: 'name', message: 'must not be blank' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await captureError(request('/api/things', thingSchema, { method: 'POST', body: {} }))

    expect(error.kind).toBe('http')
    expect(error.status).toBe(400)
    expect(error.title).toBe('Validation failed')
    expect(error.detail).toBe('The request has 1 invalid field.')
    expect(error.fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }])
  })

  it('falls back to the status when an error response has no usable body', async () => {
    mockFetch(() => Promise.resolve(new Response('<html>Not Found</html>', { status: 404 })))

    const error = await captureError(request('/api/things/a', thingSchema))

    expect(error.kind).toBe('http')
    expect(error.status).toBe(404)
    expect(error.title).toBe('Request failed with status 404')
    expect(error.fieldErrors).toEqual([])
  })

  it('explains a gateway error from the proxy as an unavailable backend', async () => {
    mockFetch(() => Promise.resolve(new Response('', { status: 502 })))

    const error = await captureError(request('/api/things/a', thingSchema))

    expect(error.kind).toBe('http')
    expect(error.status).toBe(502)
    expect(error.title).toBe('Backend unavailable')
  })

  it('reports a network failure as an unreachable backend', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')))

    const error = await captureError(request('/api/things/a', thingSchema))

    expect(error.kind).toBe('network')
    expect(error.status).toBeUndefined()
    expect(error.title).toBe('Backend unreachable')
  })

  it('rejects a 2xx response that does not match the schema', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ id: 'a', count: 'two' })))

    const error = await captureError(request('/api/things/a', thingSchema))

    expect(error.kind).toBe('schema')
    expect(error.status).toBe(200)
  })

  it('rethrows an abort unchanged so callers can ignore cancelled requests', async () => {
    mockFetch(() => Promise.reject(new DOMException('Aborted', 'AbortError')))

    await expect(request('/api/things/a', thingSchema)).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('api.system', () => {
  it('parses system info', async () => {
    mockFetch(() => Promise.resolve(jsonResponse(systemInfoFixture)))

    await expect(api.system.info()).resolves.toEqual(systemInfoFixture)
  })

  it('rejects system info with a malformed server time', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ ...systemInfoFixture, serverTime: 'yesterday' })))

    const error = await captureError(api.system.info())

    expect(error.kind).toBe('schema')
  })

  it('reads a 503 health response as DOWN instead of throwing', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ status: 'DOWN' }, { status: 503 })))

    await expect(api.system.health()).resolves.toEqual({ status: 'DOWN' })
  })
})

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'

/** Renders with a fresh QueryClient (no retries, no shared cache) and an in-memory router. */
export function renderWithProviders(ui: ReactElement, { route = '/' } = {}): RenderResult {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

export function jsonResponse(
  body: unknown,
  init: { status?: number; headers?: Record<string, string> } = {},
): Response {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  })
}

export const systemInfoFixture = {
  application: 'RealityCheck',
  version: '0.1.0',
  serverTime: '2026-11-14T06:30:00Z',
  database: { status: 'UP', product: 'PostgreSQL', version: '17.2' },
  migrations: { appliedCount: 1, currentVersion: '1' },
  ai: { enabled: false, provider: 'none' },
}

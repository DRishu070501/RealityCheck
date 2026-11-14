import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { OverviewPage } from '@/features/overview/OverviewPage'
import { jsonResponse, renderWithProviders, systemInfoFixture } from '@/test/utils'

type Handler = () => Promise<Response>

/** Routes mocked fetch calls by path so each endpoint can succeed or fail independently. */
function mockBackend(handlers: { health: Handler; info: Handler }) {
  const spy = vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname : input.url
    if (url === '/actuator/health') return handlers.health()
    if (url === '/api/system/info') return handlers.info()
    return Promise.reject(new Error(`Unexpected request to ${url}`))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

const never: Handler = () => new Promise<Response>(() => undefined)
const networkDown: Handler = () => Promise.reject(new TypeError('Failed to fetch'))

function row(label: string): HTMLElement {
  const term = screen.getByText(label, { selector: 'dt' })
  if (!term.parentElement) throw new Error(`Row ${label} has no container`)
  return term.parentElement
}

/** Waits for text inside a row. Rows are re-created when loading finishes, so the row is looked up on every attempt. */
async function expectInRow(label: string, text: string): Promise<void> {
  await waitFor(() => {
    expect(within(row(label)).getByText(text)).toBeInTheDocument()
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('OverviewPage', () => {
  it('shows a busy placeholder for every row while loading, and no values', () => {
    mockBackend({ health: never, info: never })

    renderWithProviders(<OverviewPage />)

    expect(screen.getByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByText('Backend API')).toBeInTheDocument()
    expect(screen.getByText('Database')).toBeInTheDocument()
    expect(screen.getByText('Schema migrations')).toBeInTheDocument()
    expect(screen.getByText('Backend API').closest('dl')).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByText('UP')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('renders the values reported by the backend', async () => {
    mockBackend({
      health: () => Promise.resolve(jsonResponse({ status: 'UP', groups: ['liveness'] })),
      info: () => Promise.resolve(jsonResponse(systemInfoFixture)),
    })

    renderWithProviders(<OverviewPage />)

    await expectInRow('Database', 'UP')
    await expectInRow('Backend API', 'UP')
    expect(within(row('Database')).getByText('PostgreSQL 17.2')).toBeInTheDocument()
    expect(within(row('Schema migrations')).getByText('1 applied, current version 1')).toBeInTheDocument()
    expect(within(row('AI layer')).getByText('Not configured')).toBeInTheDocument()
    expect(within(row('Application version')).getByText('RealityCheck 0.1.0')).toBeInTheDocument()
    expect(row('Server time').querySelector('time')).toHaveAttribute('dateTime', '2026-11-14T06:30:00Z')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('names the AI provider when the AI layer is enabled', async () => {
    mockBackend({
      health: () => Promise.resolve(jsonResponse({ status: 'UP' })),
      info: () => Promise.resolve(jsonResponse({ ...systemInfoFixture, ai: { enabled: true, provider: 'anthropic' } })),
    })

    renderWithProviders(<OverviewPage />)

    await expectInRow('AI layer', 'Enabled')
    expect(within(row('AI layer')).getByText('Provider: anthropic')).toBeInTheDocument()
  })

  it('reports an unreachable backend and recovers on retry', async () => {
    const backend = { health: networkDown, info: networkDown }
    mockBackend({ health: () => backend.health(), info: () => backend.info() })

    renderWithProviders(<OverviewPage />)

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Backend unreachable')).toBeInTheDocument()
    await expectInRow('Backend API', 'Unreachable')
    expect(screen.queryByText('Database')).not.toBeInTheDocument()

    backend.health = () => Promise.resolve(jsonResponse({ status: 'UP' }))
    backend.info = () => Promise.resolve(jsonResponse(systemInfoFixture))
    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }))

    await expectInRow('Database', 'PostgreSQL 17.2')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the problem title and status when the API returns an error', async () => {
    mockBackend({
      health: () => Promise.resolve(jsonResponse({ status: 'UP' })),
      info: () =>
        Promise.resolve(
          jsonResponse(
            { title: 'Internal Server Error', status: 500, detail: 'An unexpected error occurred.' },
            { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
    })

    renderWithProviders(<OverviewPage />)

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Internal Server Error (HTTP 500)')).toBeInTheDocument()
    expect(within(alert).getByText('An unexpected error occurred.')).toBeInTheDocument()
    await expectInRow('Backend API', 'UP')
  })
})

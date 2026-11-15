import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ScenariosPage } from '@/features/scenarios/ScenariosPage'
import {
  BASELINE_SCENARIO_ID,
  demoRoutes,
  FACTORY_ID,
  fixtures,
  mockBackend,
  problem,
  SCENARIO_ID,
  scenarioRoutes,
} from '@/test/backend'
import { LocationProbe } from '@/test/LocationProbe'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderPage() {
  return renderWithProviders(
    <>
      <ScenariosPage />
      <LocationProbe />
    </>,
    { route: '/scenarios' },
  )
}

function routes(extra: Parameters<typeof mockBackend>[0] = {}) {
  return {
    ...demoRoutes(),
    ...scenarioRoutes(),
    'POST /api/scenarios': () => jsonResponse(fixtures.scenario, { status: 201 }),
    ...extra,
  }
}

describe('ScenariosPage', () => {
  it('lists the scenarios with their start, change count and latest run', async () => {
    const backend = mockBackend(routes())
    renderPage()

    const table = await screen.findByRole('table', { name: 'Scenarios' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(6)
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'Current factory operations' })).toHaveAttribute(
      'href',
      `/scenarios/${BASELINE_SCENARIO_ID}`,
    )
    expect(within(rows[0] as HTMLElement).getByText('Baseline')).toBeInTheDocument()
    // 18:03:14 UTC on 9 November is 23:33 in Surat.
    expect(within(rows[0] as HTMLElement).getByText('9 Nov 2026, 23:33')).toBeInTheDocument()
    expect(within(rows[5] as HTMLElement).getByText('Introduce an additional shift')).toBeInTheDocument()
    expect(within(table).getAllByText('Completed')).toHaveLength(6)
    expect(backend.last('GET', '/api/scenarios').query.get('factoryId')).toBe(FACTORY_ID)
  })

  it('creates a scenario at a factory-local moment and opens it', async () => {
    const user = userEvent.setup()
    const backend = mockBackend(routes())
    renderPage()
    await screen.findByRole('table', { name: 'Scenarios' })

    await user.click(screen.getByRole('button', { name: 'New scenario' }))
    const dialog = await screen.findByRole('dialog', { name: 'New scenario' })
    await user.type(within(dialog).getByLabelText('Name'), 'Night shift trial')
    fireEvent.change(within(dialog).getByLabelText('Start from (Asia/Kolkata)'), {
      target: { value: '2026-11-10T09:00' },
    })
    await user.click(within(dialog).getByRole('button', { name: 'Create scenario' }))

    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent(`/scenarios/${SCENARIO_ID}`)
    })
    expect(backend.last('POST', '/api/scenarios').body).toEqual({
      factoryId: FACTORY_ID,
      name: 'Night shift trial',
      forkAt: '2026-11-10T03:30:00Z',
      includeFutureOrders: true,
    })
  })

  it('refuses a start in the future before asking the server', async () => {
    const user = userEvent.setup()
    const backend = mockBackend(routes())
    renderPage()
    await screen.findByRole('table', { name: 'Scenarios' })

    await user.click(screen.getByRole('button', { name: 'New scenario' }))
    const dialog = await screen.findByRole('dialog', { name: 'New scenario' })
    await user.type(within(dialog).getByLabelText('Name'), 'Tomorrow')
    fireEvent.change(within(dialog).getByLabelText('Start from (Asia/Kolkata)'), {
      target: { value: '2099-01-01T09:00' },
    })
    await user.click(within(dialog).getByRole('button', { name: 'Create scenario' }))

    expect(await within(dialog).findByText(/Choose a moment in the past/)).toBeInTheDocument()
    expect(backend.sent('POST', '/api/scenarios')).toHaveLength(0)
  })

  it('branches an existing scenario, keeping its start unless one is given', async () => {
    const user = userEvent.setup()
    const backend = mockBackend(routes())
    renderPage()
    await screen.findByRole('table', { name: 'Scenarios' })

    await user.click(screen.getByRole('button', { name: 'Branch' }))
    const dialog = await screen.findByRole('dialog', { name: 'Branch a scenario' })
    await user.selectOptions(within(dialog).getByLabelText('Branch from'), SCENARIO_ID)
    await user.type(within(dialog).getByLabelText('Name'), 'Evening shift plus two wheels')
    await user.click(within(dialog).getByRole('button', { name: 'Create branch' }))

    await waitFor(() => {
      expect(backend.sent('POST', '/api/scenarios')).toHaveLength(1)
    })
    expect(backend.last('POST', '/api/scenarios').body).toEqual({
      factoryId: FACTORY_ID,
      name: 'Evening shift plus two wheels',
      parentScenarioId: SCENARIO_ID,
    })
  })

  it('says when the name is already taken', async () => {
    const user = userEvent.setup()
    mockBackend(
      routes({
        'POST /api/scenarios': () =>
          problem(409, 'Conflict', 'A scenario named "Current factory operations" already exists in this factory'),
      }),
    )
    renderPage()
    await screen.findByRole('table', { name: 'Scenarios' })

    await user.click(screen.getByRole('button', { name: 'New scenario' }))
    const dialog = await screen.findByRole('dialog', { name: 'New scenario' })
    await user.type(within(dialog).getByLabelText('Name'), 'Current factory operations')
    fireEvent.change(within(dialog).getByLabelText('Start from (Asia/Kolkata)'), {
      target: { value: '2026-11-10T09:00' },
    })
    await user.click(within(dialog).getByRole('button', { name: 'Create scenario' }))

    expect(await within(dialog).findByText('Another scenario of this factory already has this name')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
  })

  it('offers to create the first scenario when there are none', async () => {
    mockBackend(routes({ 'GET /api/scenarios': () => jsonResponse({ ...fixtures.scenarios, items: [], totalItems: 0, totalPages: 0 }) }))
    renderPage()

    expect(await screen.findByText('No scenarios yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Branch' })).toBeDisabled()
  })
})

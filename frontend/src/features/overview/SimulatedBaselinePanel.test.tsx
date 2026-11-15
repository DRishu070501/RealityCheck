import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SimulatedBaselinePanel } from '@/features/overview/SimulatedBaselinePanel'
import { formatFraction } from '@/lib/metrics'
import {
  BASELINE_RUN_ID,
  BASELINE_SCENARIO_ID,
  demoRoutes,
  fixtures,
  mockBackend,
  problem,
  scenarioRoutes,
  type Routes,
} from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

function routes(overrides: Routes = {}): Routes {
  return { ...demoRoutes(), ...scenarioRoutes(), ...overrides }
}

const notRun = {
  ...fixtures.scenarios,
  items: fixtures.scenarios.items.map((item) => ({ ...item, latestRun: null })),
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SimulatedBaselinePanel', () => {
  it("shows the baseline run's simulated figures, tagged Simulated, with a link to the run", async () => {
    mockBackend(routes())

    renderWithProviders(<SimulatedBaselinePanel />)

    const summary = fixtures.baselineResults.summary
    expect(await screen.findByText(formatFraction(summary.machineUtilization))).toBeInTheDocument()
    expect(screen.getByText(formatFraction(summary.employeeUtilization))).toBeInTheDocument()
    expect(screen.getByText('33.18 stones/h')).toBeInTheDocument()
    expect(screen.getByText('Polishing')).toBeInTheDocument()
    expect(screen.getByText(formatFraction(summary.onTimeDeliveryRate))).toBeInTheDocument()
    expect(screen.getByText('Simulated')).toBeInTheDocument()
    expect(screen.getByText(': Current factory operations')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /View the full results/ })).toHaveAttribute(
      'href',
      `/results?run=${BASELINE_RUN_ID}`,
    )
  })

  it('offers to run the baseline when it has never been run, and follows the run until it completes', async () => {
    const runId = '11111111-1111-4111-8111-111111111111'
    const backend = mockBackend(
      routes({
        'GET /api/scenarios': () => jsonResponse(notRun),
        [`POST /api/scenarios/${BASELINE_SCENARIO_ID}/simulate`]: () =>
          jsonResponse(
            { runId, scenarioId: BASELINE_SCENARIO_ID, status: 'PENDING', reused: false, submittedAt: '2026-11-15T07:00:00Z' },
            { status: 202 },
          ),
        [`GET /api/simulations/${runId}`]: () => jsonResponse({ ...fixtures.run, runId, status: 'COMPLETED' }),
      }),
    )

    renderWithProviders(<SimulatedBaselinePanel />)

    expect(await screen.findByText(/shown only once the baseline scenario/)).toBeInTheDocument()
    expect(screen.queryByText('33.18 stones/h')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Run baseline simulation' }))

    expect(await screen.findByText('Completed')).toBeInTheDocument()
    expect(backend.sent('POST', `/api/scenarios/${BASELINE_SCENARIO_ID}/simulate`)).toHaveLength(1)
  })

  it('says the simulator is busy when the server answers 429', async () => {
    mockBackend(
      routes({
        'GET /api/scenarios': () => jsonResponse(notRun),
        [`POST /api/scenarios/${BASELINE_SCENARIO_ID}/simulate`]: () =>
          problem(429, 'Too many requests', 'Too many simulations are running or queued. Try again shortly.'),
      }),
    )

    renderWithProviders(<SimulatedBaselinePanel />)

    await userEvent.click(await screen.findByRole('button', { name: 'Run baseline simulation' }))

    expect(await screen.findByText('The simulator is busy. Try again in a few seconds.')).toBeInTheDocument()
  })

  it('points to Scenario Lab when no baseline scenario exists', async () => {
    const noBaseline = {
      ...fixtures.scenarios,
      items: fixtures.scenarios.items.filter((item) => !item.baseline),
    }
    mockBackend(routes({ 'GET /api/scenarios': () => jsonResponse(noBaseline) }))

    renderWithProviders(<SimulatedBaselinePanel />)

    expect(await screen.findByText(/no baseline scenario/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Scenario Lab' })).toHaveAttribute('href', '/scenarios')
  })
})

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ScenarioDetailPage } from '@/features/scenarios/ScenarioDetailPage'
import {
  BASELINE_RUN_ID,
  BASELINE_SCENARIO_ID,
  demoRoutes,
  fixtures,
  mockBackend,
  problem,
  RUN_ID,
  SCENARIO_ID,
  scenarioRoutes,
  type Routes as MockRoutes,
} from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

const PATH = `/api/scenarios/${SCENARIO_ID}`
const neverRun = { ...fixtures.scenario, latestRun: null }

function renderPage(routes: MockRoutes) {
  const backend = mockBackend({ ...demoRoutes(), ...scenarioRoutes(), ...routes })
  renderWithProviders(
    <Routes>
      <Route path="/scenarios/:id" element={<ScenarioDetailPage />} />
    </Routes>,
    { route: `/scenarios/${SCENARIO_ID}` },
  )
  return { backend, user: userEvent.setup() }
}

function submitted(runId: string, reused = false) {
  return jsonResponse(
    { runId, scenarioId: SCENARIO_ID, status: reused ? 'COMPLETED' : 'PENDING', reused, submittedAt: '2026-11-15T07:00:00Z' },
    { status: 202 },
  )
}

function runIn(status: string, extra: object = {}) {
  return jsonResponse({ ...fixtures.run, status, ...extra })
}

describe('ScenarioDetailPage', () => {
  it('shows the scenario, its changes, the check and its completed run with links to results', async () => {
    renderPage({})

    expect(await screen.findByRole('heading', { name: 'Introduce an additional shift' })).toBeInTheDocument()
    const changes = screen.getByRole('list', { name: 'Changes in order' })
    expect(within(changes).getByText('Add shifts: Mon, Tue, Wed, Thu, Fri, Sat 18:00-22:00')).toBeInTheDocument()
    expect(await screen.findByText('Ready to run')).toBeInTheDocument()
    expect(screen.getByText('Simulated')).toBeInTheDocument()

    expect(await screen.findByRole('link', { name: 'View results' })).toHaveAttribute('href', `/results?run=${RUN_ID}`)
    expect(await screen.findByRole('link', { name: 'Compare with baseline' })).toHaveAttribute(
      'href',
      `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}`,
    )
    const history = await screen.findByRole('table', { name: 'Runs, newest first' })
    expect(within(history).getByText('All work finished')).toBeInTheDocument()
  })

  it('previews only the configuration the changes affect, with everything on request', async () => {
    const { user } = renderPage({})
    const check = await screen.findByRole('region', { name: 'Check before running' })

    // Adding an evening shift changes working time, not pools or stages.
    expect(await within(check).findByText('No pool or stage is changed.')).toBeInTheDocument()
    expect(within(check).getByText('48 working h')).toBeInTheDocument()
    expect(within(check).getByText('72 working h')).toBeInTheDocument()
    await user.click(within(check).getByRole('button', { name: 'Show all' }))
    const pools = within(check).getByRole('table', { name: 'Resource pools before and after' })
    const firstRow = within(pools).getAllByRole('row')[1] as HTMLElement
    expect(within(firstRow).getByText('Bruting lathes')).toBeInTheDocument()
  })

  it('cannot run an invalid scenario, and says why', async () => {
    renderPage({
      [`POST ${PATH}/validate`]: () =>
        jsonResponse({
          ...fixtures.validation,
          valid: false,
          inputHash: null,
          errors: [
            {
              changeId: null,
              code: 'REQUIREMENT_EXCEEDS_CAPACITY',
              message: 'Stage Polishing needs 1 unit of Polishing wheels per slot, but the pool never has that many',
              subject: 'POLISHING',
            },
          ],
        }),
    })

    expect(await screen.findByText('Cannot run')).toBeInTheDocument()
    expect(screen.getByText(/Stage Polishing needs 1 unit of Polishing wheels/)).toBeInTheDocument()
    const run = screen.getByRole('button', { name: 'Run simulation' })
    expect(run).toBeDisabled()
    expect(run).toHaveAccessibleDescription('Fix the problems listed above before running.')
  })

  it('runs with a fresh idempotency key and polls until the run completes', async () => {
    let polls = 0
    const { backend, user } = renderPage({
      [`GET ${PATH}`]: () => jsonResponse(neverRun),
      [`POST ${PATH}/simulate`]: () => submitted(RUN_ID),
      [`GET /api/simulations/${RUN_ID}`]: () => {
        polls += 1
        return polls === 1 ? runIn('RUNNING', { finishedAt: null, terminationReason: null }) : runIn('COMPLETED')
      },
    })
    expect(await screen.findByText('This scenario has not been run yet.')).toBeInTheDocument()
    const run = screen.getByRole('button', { name: 'Run simulation' })
    await waitFor(() => {
      expect(run).toBeEnabled()
    })

    await user.click(run)

    expect(await screen.findByText(/Simulating\. The status is checked every second\./)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'View results' }, { timeout: 4000 })).toBeInTheDocument()
    expect(polls).toBeGreaterThanOrEqual(2)
    const key = backend.last('POST', `${PATH}/simulate`).headers['idempotency-key']
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('shows the reason when the run fails', async () => {
    const { user } = renderPage({
      [`GET ${PATH}`]: () => jsonResponse(neverRun),
      [`POST ${PATH}/simulate`]: () => submitted(RUN_ID),
      [`GET /api/simulations/${RUN_ID}`]: () =>
        runIn('FAILED', { failureMessage: 'The simulation could not be completed. Reference: x', terminationReason: null }),
    })
    const run = await screen.findByRole('button', { name: 'Run simulation' })
    await waitFor(() => {
      expect(run).toBeEnabled()
    })

    await user.click(run)

    expect(await screen.findByText('The simulation failed')).toBeInTheDocument()
    expect(screen.getByText('The simulation could not be completed. Reference: x')).toBeInTheDocument()
  })

  it('says the simulator is busy when the server refuses with 429', async () => {
    const { user } = renderPage({
      [`GET ${PATH}`]: () => jsonResponse(neverRun),
      [`POST ${PATH}/simulate`]: () =>
        problem(429, 'Too many requests', 'Too many simulations are running or queued. Try again shortly.'),
    })
    const run = await screen.findByRole('button', { name: 'Run simulation' })
    await waitFor(() => {
      expect(run).toBeEnabled()
    })

    await user.click(run)

    expect(await screen.findByText('The simulator is busy')).toBeInTheDocument()
    expect(screen.getByText(/Try again in a few seconds/)).toBeInTheDocument()
  })

  it('says when an identical completed run was reused', async () => {
    const { user } = renderPage({
      [`GET ${PATH}`]: () => jsonResponse(neverRun),
      [`POST ${PATH}/simulate`]: () => submitted(RUN_ID, true),
    })
    const run = await screen.findByRole('button', { name: 'Run simulation' })
    await waitFor(() => {
      expect(run).toBeEnabled()
    })

    await user.click(run)

    expect(await screen.findByText(/its stored results were reused/)).toBeInTheDocument()
  })

  it('offers to run the baseline when there is nothing to compare with yet', async () => {
    const { backend, user } = renderPage({
      [`GET ${PATH}/comparisons`]: () =>
        jsonResponse({ baselineMissing: true, suggestedBaselineScenarioId: BASELINE_SCENARIO_ID, items: [] }),
      [`POST /api/scenarios/${BASELINE_SCENARIO_ID}/simulate`]: () => submitted(BASELINE_RUN_ID),
      [`GET /api/simulations/${BASELINE_RUN_ID}`]: () =>
        runIn('RUNNING', { runId: BASELINE_RUN_ID, scenarioId: BASELINE_SCENARIO_ID, finishedAt: null }),
    })

    await user.click(await screen.findByRole('button', { name: 'Run baseline' }))

    await waitFor(() => {
      expect(backend.sent('POST', `/api/scenarios/${BASELINE_SCENARIO_ID}/simulate`)).toHaveLength(1)
    })
    expect(screen.queryByRole('link', { name: 'Compare with baseline' })).not.toBeInTheDocument()
    expect(await screen.findByText('Running')).toBeInTheDocument()
  })

  it('creates and runs a baseline from the same moment when none starts there', async () => {
    const created = { ...fixtures.scenarioBaseline, id: BASELINE_SCENARIO_ID, name: `Baseline for "${fixtures.scenario.name}"` }
    const { backend, user } = renderPage({
      [`GET ${PATH}/comparisons`]: () =>
        jsonResponse({ baselineMissing: true, suggestedBaselineScenarioId: null, items: [] }),
      'POST /api/scenarios': () => jsonResponse(created, { status: 201 }),
      [`POST /api/scenarios/${BASELINE_SCENARIO_ID}/simulate`]: () => submitted(BASELINE_RUN_ID),
      [`GET /api/simulations/${BASELINE_RUN_ID}`]: () =>
        runIn('RUNNING', { runId: BASELINE_RUN_ID, scenarioId: BASELINE_SCENARIO_ID, finishedAt: null }),
    })

    expect(await screen.findByText(/No baseline starts at this scenario's moment yet/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Create and run baseline' }))

    await waitFor(() => {
      expect(backend.sent('POST', `/api/scenarios/${BASELINE_SCENARIO_ID}/simulate`)).toHaveLength(1)
    })
    const body = backend.last('POST', '/api/scenarios').body as Record<string, unknown>
    expect(body).toMatchObject({
      factoryId: fixtures.scenario.factoryId,
      name: `Baseline for "${fixtures.scenario.name}"`,
      forkAt: fixtures.scenario.forkAt,
      includeFutureOrders: fixtures.scenario.includeFutureOrders,
    })
    expect(await screen.findByText('Running')).toBeInTheDocument()
  })

  it('renames with the current version and explains a conflicting edit', async () => {
    const { backend, user } = renderPage({
      [`PATCH ${PATH}`]: () =>
        problem(409, 'Conflict', 'The record was changed by someone else. Reload it and try again.', undefined, 'STALE_VERSION'),
    })
    await user.click(await screen.findByRole('button', { name: 'Rename' }))
    const name = screen.getByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Evening shift')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('This scenario was changed after you opened it')).toBeInTheDocument()
    expect(backend.last('PATCH', PATH).body).toEqual({
      name: 'Evening shift',
      description: fixtures.scenario.description,
      version: fixtures.scenario.version,
    })
  })

  it('deletes a change after confirmation', async () => {
    const change = fixtures.scenario.changes[0]
    if (!change) throw new Error('The fixture scenario has a change')
    const { backend, user } = renderPage({
      [`DELETE ${PATH}/changes/${change.id}`]: () => new Response(null, { status: 204 }),
    })

    await user.click(await screen.findByRole('button', { name: 'Delete change 1' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete change 1?' })
    await user.click(within(dialog).getByRole('button', { name: 'Delete change' }))

    await waitFor(() => {
      expect(backend.sent('DELETE', `${PATH}/changes/${change.id}`)).toHaveLength(1)
    })
  })
})

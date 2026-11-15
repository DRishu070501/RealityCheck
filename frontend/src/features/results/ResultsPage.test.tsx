import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ResultsPage } from '@/features/results/ResultsPage'
import { formatMoney } from '@/lib/format'
import { formatByUnit, formatElapsed, formatFraction } from '@/lib/metrics'
import {
  BASELINE_RUN_ID,
  demoRoutes,
  fixtures,
  mockBackend,
  problem,
  RUN_ID,
  scenarioRoutes,
  type Routes,
} from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

const ORDER_A = '22222222-2222-4222-8222-222222222222'
const ORDER_B = '33333333-3333-4333-8333-333333333333'

/** The single alternative of the captured comparison. */
function capturedAlternative() {
  const alternative = fixtures.comparison.alternatives[0]
  if (!alternative) throw new Error('The comparison fixture has no alternative')
  return alternative
}

const baselineRun = {
  ...fixtures.run,
  runId: BASELINE_RUN_ID,
  scenarioId: fixtures.scenarioBaseline.id,
  scenarioName: 'Current factory operations',
}

/** One page of events, so the timeline draws exactly the captured 200. */
const onePageOfEvents = { ...fixtures.simulatedEvents, totalItems: 200, totalPages: 1 }

function routes(overrides: Routes = {}): Routes {
  return {
    ...demoRoutes(),
    ...scenarioRoutes(),
    [`GET /api/simulations/${BASELINE_RUN_ID}`]: () => jsonResponse(baselineRun),
    [`GET /api/simulations/${RUN_ID}/events`]: () => jsonResponse(onePageOfEvents),
    ...overrides,
  }
}

/** Finds a table by its caption without computing the accessibility tree of the whole page (slow on large pages). */
function tableByCaption(caption: string): HTMLElement {
  const match = [...document.querySelectorAll('caption')].find((element) => element.textContent === caption)
  const table = match?.closest('table')
  if (!table) throw new Error(`No table captioned ${caption}`)
  return table
}

function metricRow(key: string): HTMLElement {
  const row = document.querySelector<HTMLElement>(`[data-metric="${key}"]`)
  if (!row) throw new Error(`No metric row ${key}`)
  return row
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ResultsPage, one run', () => {
  it('shows the run as simulated, with figures equal to its stored results and the bottleneck explanation', async () => {
    mockBackend(routes())

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=none` })

    const summary = fixtures.results.summary
    expect(await screen.findByRole('heading', { name: 'Introduce an additional shift' })).toBeInTheDocument()
    expect(screen.getAllByText('Simulated').length).toBeGreaterThan(0)
    expect(await screen.findByText(formatElapsed(summary.elapsedSeconds))).toBeInTheDocument()
    expect(screen.getByText('31.87 stones/h')).toBeInTheDocument()
    expect(screen.getByText(formatMoney(summary.cost.total, 'INR'))).toBeInTheDocument()
    expect(screen.getByText('3,228 stones')).toHaveAttribute('title', 'Exactly 3227.8149 stones (expected value)')
    expect(screen.getByText(formatFraction(summary.machineUtilization))).toBeInTheDocument()
    expect(screen.getByText('29 of 29')).toBeInTheDocument()
    expect(screen.getByText(fixtures.results.bottleneck.explanation)).toBeInTheDocument()
    expect(screen.getByText('All work finished')).toBeInTheDocument()
    expect(screen.queryByText('The simulation did not finish all work')).not.toBeInTheDocument()
  })

  it('warns when the run stopped on a limit and counts the unfinished batches', async () => {
    const stopped = {
      ...fixtures.results,
      terminationReason: 'HORIZON_REACHED',
      unfinished: [
        { batchId: ORDER_A, orderId: ORDER_A, stageSequence: 6, state: 'QUEUED' },
        { batchId: ORDER_B, orderId: ORDER_B, stageSequence: 1, state: 'NOT_ARRIVED' },
      ],
    }
    mockBackend(routes({ [`GET /api/simulations/${RUN_ID}/results`]: () => jsonResponse(stopped) }))

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=none` })

    const warning = await screen.findByText('The simulation did not finish all work')
    const panel = warning.closest('[role="alert"]') as HTMLElement
    expect(within(panel).getByText(/Unfinished batches: 2 \(1 waiting in a queue, 1 not yet arrived\)/)).toBeInTheDocument()
  })

  it('draws one production-timeline lane per stage, with a table alternative', async () => {
    mockBackend(routes())

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=none` })

    expect(await screen.findByTestId('production-gantt')).toBeInTheDocument()
    expect(screen.getAllByTestId('gantt-lane')).toHaveLength(fixtures.runStages.length)
    const table = tableByCaption('Jobs per stage in the production timeline')
    expect(within(table).getByText('Rough Intake')).toBeInTheDocument()
  })

  it('offers Scenario Lab when no run has completed', async () => {
    const none = {
      ...fixtures.scenarios,
      items: fixtures.scenarios.items.map((item) => ({ ...item, latestRun: null })),
    }
    mockBackend(routes({ 'GET /api/scenarios': () => jsonResponse(none) }))

    renderWithProviders(<ResultsPage />, { route: '/results' })

    expect(await screen.findByText('No completed simulation yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Scenario Lab' })).toHaveAttribute('href', '/scenarios')
  })
})

describe('ResultsPage, comparison', () => {
  it('picks the baseline run with the same starting state and shows the comparison values verbatim', async () => {
    const backend = mockBackend(routes())

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}` })

    await screen.findByText('Headline metrics')
    expect(backend.last('POST', '/api/simulations/compare').body).toEqual({
      baselineRunId: BASELINE_RUN_ID,
      alternativeRunIds: [RUN_ID],
      allowDifferentBaseline: false,
      includeActual: true,
    })
    expect(screen.getByText(/Chosen automatically/)).toBeInTheDocument()

    const elapsed = metricRow('elapsedSeconds')
    expect(within(elapsed).getByText(formatByUnit('1248248', 'seconds', 'INR'))).toBeInTheDocument()
    expect(within(elapsed).getByText(formatByUnit('834247', 'seconds', 'INR'))).toBeInTheDocument()
    expect(within(elapsed).getByText('improved')).toBeInTheDocument()

    const cost = metricRow('cost.total')
    expect(within(cost).getByText(formatByUnit('19117.83', 'currency', 'INR', true))).toBeInTheDocument()
    expect(within(cost).getByText('(+2.23%)')).toBeInTheDocument()
    expect(within(cost).getByText('worsened')).toBeInTheDocument()

    const utilization = metricRow('machineUtilization')
    expect(within(utilization).queryByText(/improved|worsened|unchanged/)).not.toBeInTheDocument()

    expect(screen.getByText('The bottleneck stays at Polishing.')).toBeInTheDocument()
    expect(screen.getByText('Add shifts: Mon, Tue, Wed, Thu, Fri, Sat 18:00-22:00')).toBeInTheDocument()
    expect(screen.getByText('No changes: the factory as recorded.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Explain with AI Analyst' })).toHaveAttribute(
      'href',
      `/analyst?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}`,
    )
  })

  it('gives every chart a table with the same numbers', async () => {
    mockBackend(routes())

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    await screen.findByText('Headline metrics')
    for (const caption of [
      'Completion time in hours',
      'Total cost',
      'Cost per finished stone',
      'Throughput in stones per working hour',
      'Machine and employee utilization',
      'Mean waiting time by stage in working hours',
    ]) {
      expect(tableByCaption(caption)).toBeInTheDocument()
    }
    const completion = tableByCaption('Completion time in hours')
    expect(within(completion).getByText('346.7 h')).toBeInTheDocument() // 1248248 s
    expect(within(completion).getByText('231.7 h')).toBeInTheDocument() // 834247 s
  })

  it('shows what recorded history says happened after the fork beside the simulated baseline, without differences', async () => {
    mockBackend(routes())

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    const heading = await screen.findByText('What actually happened after the fork')
    const card = heading.closest('[aria-labelledby="actual-title"]') as HTMLElement
    expect(within(card).getByText('Actual')).toBeInTheDocument()
    const table = tableByCaption("Recorded outcome beside the baseline's simulated outcome")
    const actual = fixtures.comparison.actual
    expect(within(table).getByText(`${actual.ordersCompleted} of ${actual.ordersInScope}`)).toBeInTheDocument()
    expect(within(table).getByText(formatByUnit(actual.outputStones, 'stones', ''))).toBeInTheDocument()
    expect(within(table).getByText(formatByUnit(actual.outputCarats, 'carats', ''))).toBeInTheDocument()
    // The baseline's simulated figures, verbatim from its metrics.
    const outputStones = capturedAlternative().metrics.find((m) => m.key === 'outputStones')
    expect(within(table).getByText(formatByUnit(outputStones?.baseline ?? null, 'stones', ''))).toBeInTheDocument()
    expect(within(table).queryByText(/Difference/)).not.toBeInTheDocument()
    const completions = tableByCaption('Orders completed in recorded history after the fork')
    expect(within(completions).getAllByRole('row')).toHaveLength(actual.ordersCompleted + 1)
  })

  it('shows no recorded outcome when the comparison has none', async () => {
    mockBackend(
      routes({ 'POST /api/simulations/compare': () => jsonResponse({ ...fixtures.comparison, actual: null }) }),
    )

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    await screen.findByText('Headline metrics')
    expect(screen.queryByText('What actually happened after the fork')).not.toBeInTheDocument()
  })

  it('describes a moved bottleneck with both stages and their scores', async () => {
    const alternative = capturedAlternative()
    const moved = {
      ...fixtures.comparison,
      alternatives: [
        {
          ...alternative,
          bottleneck: {
            change: 'MOVED',
            baselineStage: { ...alternative.bottleneck.baselineStage, alternativeScore: '0.401000' },
            alternativeStage: {
              stageId: alternative.stages[3]?.stageId ?? alternative.stages[0]?.stageId,
              code: 'CUTTING',
              name: 'Cutting',
              baselineScore: '0.120000',
              alternativeScore: '0.655000',
            },
          },
        },
      ],
    }
    mockBackend(routes({ 'POST /api/simulations/compare': () => jsonResponse(moved) }))

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    expect(await screen.findByText('The bottleneck moves from Polishing to Cutting.')).toBeInTheDocument()
    const scores = tableByCaption('Bottleneck scores of the stages involved')
    expect(within(scores).getByText('0.655000')).toBeInTheDocument()
    expect(within(scores).getByText('0.401000')).toBeInTheDocument()
  })

  it('asks for consent before comparing runs from different starting states, then keeps the warning visible', async () => {
    let calls = 0
    const warned = {
      ...fixtures.comparison,
      sameStartingState: false,
      warnings: [
        {
          code: 'DIFFERENT_STARTING_STATE',
          runId: RUN_ID,
          message: 'This run started from a different factory state.',
        },
      ],
    }
    const backend = mockBackend(
      routes({
        'POST /api/simulations/compare': () => {
          calls += 1
          return calls === 1
            ? problem(
                422,
                'Request cannot be processed',
                'These runs did not start from the same factory state.',
                undefined,
                'DIFFERENT_STARTING_STATE',
              )
            : jsonResponse(warned)
        },
      }),
    )

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    expect(await screen.findByText('These runs started from different factory states')).toBeInTheDocument()
    expect(screen.getByText('These runs did not start from the same factory state.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Compare anyway' }))

    await screen.findByText('Headline metrics')
    expect(backend.last('POST', '/api/simulations/compare').body).toMatchObject({ allowDifferentBaseline: true })
    expect(screen.getByText('This run started from a different factory state.')).toBeInTheDocument()
  })

  it('says a run is not finished when the comparison answers 409', async () => {
    mockBackend(
      routes({
        'POST /api/simulations/compare': () =>
          problem(409, 'Conflict', `Run ${BASELINE_RUN_ID} is RUNNING; only COMPLETED runs can be compared.`),
      }),
    )

    renderWithProviders(<ResultsPage />, { route: `/results?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}` })

    expect(await screen.findByText('A run is not finished (HTTP 409)')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText(/is RUNNING; only COMPLETED runs can be compared/)).toBeInTheDocument()
    })
  })
})

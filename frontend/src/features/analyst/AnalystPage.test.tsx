import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AnalystPage } from '@/features/analyst/AnalystPage'
import { exampleQuestions } from '@/features/analyst/analystShared'
import { insightSchema } from '@/lib/aiSchemas'
import {
  BASELINE_RUN_ID,
  demoRoutes,
  fixtures,
  mockBackend,
  pageOfItems,
  problem,
  RUN_ID,
  scenarioRoutes,
  type Routes,
} from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

const FIVE_AWAY_RUN = fixtures.aiFallbackInsight.runId

function routes(overrides: Routes = {}): Routes {
  return { ...demoRoutes(), ...scenarioRoutes(), ...overrides }
}

/** The shift run, compared with the baseline: the captured Claude insight is in its history. */
const SHIFT = `/analyst?run=${RUN_ID}&baseline=${BASELINE_RUN_ID}`

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AI Analyst', () => {
  it('parses every captured insight', () => {
    for (const insight of [fixtures.aiInsight, fixtures.aiAnswer, fixtures.aiFallbackInsight]) {
      expect(insightSchema.parse(insight).source).toBe('AI_INTERPRETATION')
    }
  })

  it('shows a stored insight in sections, tagged AI interpretation, with evidence tagged Simulated', async () => {
    mockBackend(
      routes({ [`GET /api/ai/insights/${RUN_ID}`]: () => jsonResponse(pageOfItems([fixtures.aiInsight])) }),
    )
    const user = userEvent.setup()

    renderWithProviders(<AnalystPage />, { route: SHIFT })

    const card = await screen.findByTestId('insight-card')
    expect(within(card).getByRole('heading', { name: 'Comparison with the baseline' })).toBeInTheDocument()
    expect(within(card).getByText('AI interpretation')).toBeInTheDocument()
    expect(within(card).getByText(/Claude · claude-haiku-5-5/)).toBeInTheDocument()
    for (const section of ['Summary', 'Key changes', 'Risks', 'Bottleneck', 'Recommendations', 'Assumptions', 'Limitations']) {
      expect(within(card).getByRole('heading', { level: 3, name: section })).toBeInTheDocument()
    }
    expect(within(card).getByText(fixtures.aiInsight.content.summary)).toBeInTheDocument()

    // Evidence beside the first recommendation: label, quoted value, source run, Simulated tag.
    const firstRecommendation = within(card).getByText(fixtures.aiInsight.content.recommendations[0]?.title ?? '')
      .closest('li')
    if (!firstRecommendation) throw new Error('recommendation not rendered')
    await user.click(within(firstRecommendation).getByText(/^Evidence \(/))
    const evidence = within(firstRecommendation)
    expect(evidence.getByText('Simulated')).toBeInTheDocument()
    const onTime = fixtures.aiInsight.evidence.find((e) => e.key === 'delta.summary.onTimeDeliveryRate.absolute')
    expect(evidence.getByText(onTime?.label ?? '')).toBeInTheDocument()
    expect(evidence.getByText(onTime?.display[0] ?? '')).toBeInTheDocument()
    expect(evidence.getAllByText('Introduce an additional shift').length).toBeGreaterThan(0)
    expect(within(card).queryByRole('status')).not.toBeInTheDocument()
  })

  it('says when the daily spending cap made the built-in analyst answer', async () => {
    const capped = { ...fixtures.aiFallbackInsight, fallbackReason: 'DAILY_BUDGET' }
    mockBackend(
      routes({ [`GET /api/ai/insights/${FIVE_AWAY_RUN}`]: () => jsonResponse(pageOfItems([capped])) }),
    )

    renderWithProviders(<AnalystPage />, { route: `/analyst?run=${FIVE_AWAY_RUN}` })

    const card = await screen.findByTestId('insight-card')
    expect(within(card).getByRole('status')).toHaveTextContent("Today's spending cap")
  })

  it('says when the built-in analyst answered instead of the language model', async () => {
    mockBackend(
      routes({
        [`GET /api/ai/insights/${FIVE_AWAY_RUN}`]: () => jsonResponse(pageOfItems([fixtures.aiFallbackInsight])),
      }),
    )

    renderWithProviders(<AnalystPage />, { route: `/analyst?run=${FIVE_AWAY_RUN}` })

    const card = await screen.findByTestId('insight-card')
    expect(within(card).getByRole('status')).toHaveTextContent(
      "The language model's answer did not pass the evidence check",
    )
    expect(within(card).getByText(/Built-in analyst \(fixed rules, no language model\)/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Print' })).toBeInTheDocument()
  })

  it('asks a question with the chosen run and baseline and shows the answer first', async () => {
    // Like the backend: an answer is stored, so the history includes it afterwards.
    const answer = { ...fixtures.aiAnswer, runId: RUN_ID }
    const stored: unknown[] = [fixtures.aiInsight]
    const backend = mockBackend(
      routes({
        [`GET /api/ai/insights/${RUN_ID}`]: () => jsonResponse(pageOfItems(stored)),
        'POST /api/ai/ask': () => {
          stored.unshift(answer)
          return jsonResponse(answer)
        },
      }),
    )
    const user = userEvent.setup()

    renderWithProviders(<AnalystPage />, { route: SHIFT })
    await screen.findByTestId('insight-card')
    await user.type(screen.getByLabelText('Ask about this run'), 'Which resource is underused?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    await waitFor(() => { expect(screen.getAllByTestId('insight-card')).toHaveLength(2); })
    expect(backend.last('POST', '/api/ai/ask').body).toEqual({
      runId: RUN_ID,
      baselineRunId: BASELINE_RUN_ID,
      question: 'Which resource is underused?',
    })
    const [first] = screen.getAllByTestId('insight-card')
    expect(within(first as HTMLElement).getByRole('heading', { name: 'Which resource is underused?' })).toBeInTheDocument()
    expect(screen.getByLabelText('Ask about this run')).toHaveValue('')
  })

  it('requests a report type, and needs a baseline only for the comparison', async () => {
    const backend = mockBackend(
      routes({ 'POST /api/ai/insights': () => jsonResponse(fixtures.aiInsight) }),
    )
    const user = userEvent.setup()

    renderWithProviders(<AnalystPage />, { route: `/analyst?run=${RUN_ID}&baseline=none` })

    expect(await screen.findByRole('button', { name: 'Compare with baseline' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Executive report' }))
    await waitFor(() => { expect(backend.sent('POST', '/api/ai/insights')).toHaveLength(1); })
    expect(backend.last('POST', '/api/ai/insights').body).toEqual({ runId: RUN_ID, type: 'EXECUTIVE_REPORT' })
  })

  it('explains a rate limit', async () => {
    mockBackend(
      routes({
        'POST /api/ai/insights': () => problem(429, 'Too many requests', 'Too many AI requests this minute (limit 20).'),
      }),
    )
    const user = userEvent.setup()

    renderWithProviders(<AnalystPage />, { route: SHIFT })
    await user.click(await screen.findByRole('button', { name: 'Explain this run' }))

    expect(await screen.findByText('Too many AI requests (HTTP 429)')).toBeInTheDocument()
    expect(screen.getByText('Too many AI requests this minute (limit 20).')).toBeInTheDocument()
  })

  it('shows an empty state linking to Scenario Lab when no run is completed', async () => {
    mockBackend(routes({ 'GET /api/scenarios': () => jsonResponse(pageOfItems([])) }))

    renderWithProviders(<AnalystPage />, { route: '/analyst' })

    expect(await screen.findByText('No completed simulation to analyse')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Scenario Lab' })).toHaveAttribute('href', '/scenarios')
  })

  it('suggests comparison questions only with a baseline', () => {
    expect(exampleQuestions(false)).not.toContain('What changed compared with the baseline?')
    expect(exampleQuestions(true)).toContain('What changed compared with the baseline?')
  })
})

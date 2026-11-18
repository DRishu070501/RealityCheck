import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AccuracyPage } from '@/features/accuracy/AccuracyPage'
import { demoRoutes, fixtures, mockBackend, problem } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function storedBacktest() {
  return { ...demoRoutes(), 'GET /api/backtests': () => jsonResponse([fixtures.backtest]) }
}

describe('AccuracyPage', () => {
  it('offers a backtest when none has been run, and shows its result once run', async () => {
    const calls: unknown[] = []
    mockBackend({
      ...demoRoutes(),
      'POST /api/backtests': ({ body }) => {
        calls.push(body)
        return jsonResponse(fixtures.backtest, { status: 201 })
      },
    })
    const user = userEvent.setup()

    renderWithProviders(<AccuracyPage />)

    expect(await screen.findByText('No backtest has been run for this factory.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Run a backtest' }))

    expect(await screen.findByRole('region', { name: 'Typical error' })).toBeInTheDocument()
    expect(calls).toEqual([{ factoryId: fixtures.factory.id }])
    expect(screen.getByRole('button', { name: 'Run again' })).toBeInTheDocument()
  })

  it('shows the typical error across dates, tagged simulated and actual', async () => {
    mockBackend(storedBacktest())

    renderWithProviders(<AccuracyPage />)

    const overall = await screen.findByRole('region', { name: 'Typical error' })
    // Captured: finished 0.193548, lead time 0.042129, work in progress 0.403846.
    expect(within(overall).getByText('Packets finished', { selector: 'dt' }).nextElementSibling).toHaveTextContent('19.4%')
    expect(within(overall).getByText('Median lead time', { selector: 'dt' }).nextElementSibling).toHaveTextContent('4.2%')
    expect(within(overall).getByText('Work in progress by stage', { selector: 'dt' }).nextElementSibling)
      .toHaveTextContent('40.4%')
    expect(within(overall).getByText('Simulated')).toBeInTheDocument()
    expect(within(overall).getByText('Actual')).toBeInTheDocument()
  })

  it('compares each date with recorded history in a chart and a table', async () => {
    mockBackend(storedBacktest())

    renderWithProviders(<AccuracyPage />)

    const forks = await screen.findByRole('region', { name: 'Each date, predicted against recorded' })
    expect(within(forks).getByText(/the comparison stops at the last recorded event/)).toBeInTheDocument()
    const bars = within(forks).getByRole('list', { name: 'Packets finished, simulated and recorded' })
    expect(within(bars).getAllByRole('listitem')).toHaveLength(4)
    expect(within(bars).getAllByText(/: simulated 24, recorded 31\.$/)).toHaveLength(1)

    const rows = within(within(forks).getByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(5)
    // First fork: 24 simulated against 31 recorded is −22.58%; median lead 603,850 s against 590,958 s is +2.18%.
    expect(rows[1]).toHaveTextContent('(last recorded event)')
    expect(rows[1]).toHaveTextContent('−22.58%')
    expect(rows[1]).toHaveTextContent('+2.18%')
    expect(rows[1]).toHaveTextContent('39.4%')
  })

  it('shows work in progress by stage for the chosen date', async () => {
    mockBackend(storedBacktest())
    const user = userEvent.setup()

    renderWithProviders(<AccuracyPage />)

    const card = await screen.findByRole('region', { name: 'Work in progress by stage at the end of the window' })
    const bars = within(card).getByRole('list', { name: 'Work in progress per stage, simulated and recorded' })
    expect(within(bars).getAllByRole('listitem')).toHaveLength(11)
    expect(within(card).getByText('41 misplaced across stages; error 39.4%.')).toBeInTheDocument()

    await user.selectOptions(within(card).getByRole('combobox', { name: 'Date simulated from' }), '3')
    expect(within(card).getByText('57 misplaced across stages; error 54.8%.')).toBeInTheDocument()
    await user.click(within(card).getByText('Show as table'))
    expect(within(card).getAllByRole('row')).toHaveLength(12)
  })

  it('reports a failed backtest', async () => {
    mockBackend({
      ...demoRoutes(),
      'POST /api/backtests': () =>
        problem(422, 'Unprocessable', 'The factory has no recorded history to test the simulation against.'),
    })
    const user = userEvent.setup()

    renderWithProviders(<AccuracyPage />)

    await user.click(await screen.findByRole('button', { name: 'Run a backtest' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('no recorded history')
  })
})

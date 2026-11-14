import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { OperationsSummary } from '@/features/overview/OperationsSummary'
import { demoRoutes, emptyPage, FACTORY_ID, fixtures, mockBackend, problem } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

const OVERVIEW_PATH = `/api/factories/${FACTORY_ID}/overview`

function figure(label: string): HTMLElement {
  const term = screen.getByText(label, { selector: 'dt' })
  if (!term.parentElement) throw new Error(`Figure ${label} has no container`)
  return term.parentElement
}

describe('OperationsSummary', () => {
  it('shows order counts from recorded history, with late orders apart', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    await screen.findByRole('heading', { name: 'Production now' })
    expect(figure('Active orders')).toHaveTextContent('1818 in progress')
    expect(figure('Pending orders')).toHaveTextContent('0')
    // Nine completed and waiting, twenty already dispatched.
    expect(figure('Completed orders')).toHaveTextContent('2920 of them dispatched')
    expect(figure('Late orders')).toHaveTextContent('4')
    expect(within(figure('Late orders')).getByRole('link', { name: /Late orders/ })).toHaveAttribute(
      'href',
      '/orders?late=1',
    )
  })

  it('reports units in use now, and says this is not utilization over time', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    await screen.findByRole('heading', { name: 'Production now' })
    expect(figure('Machines in use now')).toHaveTextContent('17units, of 21 available now')
    expect(figure('Employees in use now')).toHaveTextContent('23units, of 31 available now')
    expect(screen.getByText(/It is not utilization over time/)).toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('tags every production card as Actual and states the instant in factory time', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    await screen.findByRole('heading', { name: 'Production now' })
    expect(screen.getAllByText('Actual')).toHaveLength(3)
    // 07:21 UTC is 12:51 in Surat.
    expect(screen.getByText('Sat, 14 Nov 2026, 12:51')).toBeInTheDocument()
  })

  it('shows the work at each stage and marks the longest queue without calling it a bottleneck', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    const stages = await screen.findByRole('list', { name: 'Work at each stage now' })
    expect(within(stages).getAllByRole('listitem').map((item) => item.getAttribute('aria-label'))).toEqual([
      'Rough Intake',
      'Planning',
      'Marking',
      'Cutting',
      'Bruting',
      'Polishing',
      'Quality Inspection',
      'Dispatch',
    ])
    const polishing = within(stages).getByRole('listitem', { name: 'Polishing' })
    expect(polishing).toHaveTextContent('Queued6')
    expect(polishing).toHaveTextContent('In progress6')
    expect(within(polishing).getByText('Longest queue')).toBeInTheDocument()
    expect(within(stages).getAllByText('Longest queue')).toHaveLength(1)
    expect(within(stages).queryByText(/bottleneck/i)).not.toBeInTheDocument()
  })

  it('marks no stage when nothing is waiting anywhere', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${OVERVIEW_PATH}`]: () =>
        jsonResponse({
          ...fixtures.overview,
          stages: fixtures.overview.stages.map((stage) => ({ ...stage, queued: 0 })),
        }),
    })

    renderWithProviders(<OperationsSummary />)

    await screen.findByRole('list', { name: 'Work at each stage now' })
    expect(screen.queryByText('Longest queue')).not.toBeInTheDocument()
  })

  it('gives the daily output as stones and carats for each of the fourteen days', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    const table = await screen.findByRole('table', { name: 'Finished output per day' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(15)
    const last = within(rows[14] as HTMLElement)
    expect(last.getByRole('rowheader')).toHaveTextContent('Sat, 14 Nov 2026')
    expect(last.getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['198 stones', '129.72 ct', '2'])
  })

  it('says so when nothing was finished in the period', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${OVERVIEW_PATH}`]: () =>
        jsonResponse({
          ...fixtures.overview,
          dailyOutput: fixtures.overview.dailyOutput.map((day) => ({
            ...day,
            batchesCompleted: 0,
            stonesCompleted: '0.0000',
            caratsCompleted: '0.0000',
          })),
        }),
    })

    renderWithProviders(<OperationsSummary />)

    expect(await screen.findByText('No batch finished the last stage in this period.')).toBeInTheDocument()
  })

  it('lists the latest events and links to the timeline and to each order', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<OperationsSummary />)

    const events = await screen.findByRole('list', { name: 'Recent events' })
    const items = within(events).getAllByRole('listitem')
    expect(items).toHaveLength(10)
    expect(items[0]).toHaveTextContent('14 Nov 2026, 12:38')
    expect(items[0]).toHaveTextContent('Stage started')
    expect(within(items[0] as HTMLElement).getByRole('link', { name: 'SDW-0042 · batch 1' })).toHaveAttribute(
      'href',
      '/orders/082d46e1-0baf-3659-bdf2-c80ae6a1986e',
    )
    expect(screen.getByRole('link', { name: 'Open the timeline' })).toHaveAttribute('href', '/timeline')
  })

  it('says so when no production has been recorded', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${OVERVIEW_PATH}`]: () =>
        jsonResponse({
          ...fixtures.overview,
          orders: { total: 0, active: 0, pending: 0, inProgress: 0, completed: 0, dispatched: 0, delayed: 0 },
          recentEvents: [],
        }),
    })

    renderWithProviders(<OperationsSummary />)

    expect(await screen.findByText('No production has been recorded yet')).toBeInTheDocument()
    expect(screen.queryByText('Production now')).not.toBeInTheDocument()
  })

  it('shows a failure with a retry', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${OVERVIEW_PATH}`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderWithProviders(<OperationsSummary />)

    expect(within(await screen.findByRole('alert')).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('renders nothing when there is no factory, leaving that to the factory summary', async () => {
    const backend = mockBackend({ 'GET /api/factories': () => jsonResponse(emptyPage) })

    const { container } = renderWithProviders(<OperationsSummary />)

    await vi.waitFor(() => {
      expect(backend.sent('GET', '/api/factories')).toHaveLength(1)
    })
    expect(container).toBeEmptyDOMElement()
  })
})

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { TimelinePage } from '@/features/timeline/TimelinePage'
import {
  demoRoutes,
  FACTORY_ID,
  fixtures,
  mockBackend,
  ORDER_ID,
  problem,
  stageNamed,
} from '@/test/backend'
import { LocationProbe } from '@/test/LocationProbe'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

const STATE_PATH = `/api/factories/${FACTORY_ID}/state`

function renderPage(route = '/timeline') {
  return renderWithProviders(
    <>
      <TimelinePage />
      <LocationProbe />
    </>,
    { route },
  )
}

async function eventsCard(): Promise<HTMLElement> {
  await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })
  return screen.getByRole('region', { name: 'Events' })
}

function location(): string {
  return screen.getByTestId('location').textContent
}

describe('TimelinePage event list', () => {
  it('groups events by factory-local day, newest first, with time, type, subject and stage', async () => {
    const backend = mockBackend(demoRoutes())

    renderPage()

    const day = await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })
    const rows = within(day).getAllByRole('button')
    expect(rows).toHaveLength(14)
    // 07:08:54 UTC is 12:38 in Surat.
    expect(rows[0]).toHaveAccessibleName('12:38 Stage started, SDW-0042 · batch 1')
    expect(within(rows[0] as HTMLElement).getByText('Bruting')).toBeInTheDocument()
    expect(screen.getByText('1,376 events')).toBeInTheDocument()
    expect(screen.getAllByText('Actual').length).toBeGreaterThan(0)

    const request = backend.last('GET', '/api/timeline')
    expect(request.query.get('factoryId')).toBe(FACTORY_ID)
    expect(request.query.get('order')).toBe('desc')
    expect(request.query.get('page')).toBe('0')
    expect(request.query.get('size')).toBe('50')
    expect(request.query.has('includeVoided')).toBe(false)
  })

  it('starts a new group where the local day changes, even inside one UTC day', async () => {
    // 18:29 and 18:31 UTC straddle midnight in Surat (UTC+5:30).
    const [first, second] = fixtures.timeline.items
    mockBackend({
      ...demoRoutes(),
      'GET /api/timeline': () =>
        jsonResponse({
          ...fixtures.timeline,
          items: [
            { ...first, occurredAt: '2026-11-13T18:31:00Z' },
            { ...second, occurredAt: '2026-11-13T18:29:00Z' },
          ],
        }),
    })

    renderPage()

    const later = await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })
    const earlier = screen.getByRole('region', { name: 'Fri, 13 Nov 2026' })
    expect(within(later).getByRole('button')).toHaveAccessibleName(/^00:01 /)
    expect(within(earlier).getByRole('button')).toHaveAccessibleName(/^23:59 /)
  })

  it('shows quantities with their units on completions only', async () => {
    mockBackend(demoRoutes())
    renderPage()
    const day = await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })

    const completion = within(day).getByRole('button', { name: '12:38 Stage completed, SDW-0042 · batch 1' })
    const start = within(day).getByRole('button', { name: '12:38 Stage started, SDW-0042 · batch 1' })

    expect(within(completion).getByText('98 stones')).toBeInTheDocument()
    expect(within(completion).getByText('80.6 ct')).toBeInTheDocument()
    expect(within(start).queryByText(/stones/)).not.toBeInTheDocument()
  })

  it.each([
    ['Stage', 'stage', () => stageNamed('Polishing').id, '6. Polishing', 'stageId'],
    ['Event type', 'type', () => 'STAGE_COMPLETED', 'Stage completed', 'type'],
  ])('filters by %s on the backend and keeps the choice in the address', async (label, param, value, option, query) => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()

    await userEvent.selectOptions(await screen.findByRole('combobox', { name: label }), option)

    await waitFor(() => {
      expect(backend.last('GET', '/api/timeline').query.get(query)).toBe(value())
    })
    expect(location()).toBe(`/timeline?${param}=${value()}`)
  })

  it('filters by order when an order number is entered', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()
    await waitFor(() => {
      expect(backend.sent('GET', '/api/orders')).toHaveLength(1)
    })

    await userEvent.type(screen.getByRole('combobox', { name: 'Order number' }), 'sdw-0002')

    await waitFor(() => {
      expect(backend.last('GET', '/api/timeline').query.get('orderId')).toBe(ORDER_ID)
    })
    expect(location()).toBe(`/timeline?order=${ORDER_ID}`)
  })

  it('turns a day range into the first and last instant of those days in factory time', async () => {
    const backend = mockBackend(demoRoutes())

    renderPage('/timeline?from=2026-11-10&to=2026-11-12')
    await eventsCard()

    const request = backend.last('GET', '/api/timeline')
    // Midnight in Surat is 18:30 UTC the day before.
    expect(request.query.get('from')).toBe('2026-11-09T18:30:00Z')
    expect(request.query.get('to')).toBe('2026-11-12T18:29:59Z')
    expect(screen.getByLabelText('From day')).toHaveValue('2026-11-10')
    expect(screen.getByLabelText('To day')).toHaveValue('2026-11-12')
  })

  it('does not ask the backend for an inverted range, and says what is wrong', async () => {
    const backend = mockBackend(demoRoutes())

    renderPage('/timeline?from=2026-11-12&to=2026-11-10')

    expect(await screen.findByText(/“To day” is before “From day”/)).toBeInTheDocument()
    expect(backend.sent('GET', '/api/timeline')).toHaveLength(0)
  })

  it('can show the oldest events first and include voided ones', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()

    await userEvent.click(screen.getByRole('button', { name: 'Oldest first' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Include voided events' }))

    await waitFor(() => {
      const request = backend.last('GET', '/api/timeline')
      expect(request.query.get('order')).toBe('asc')
      expect(request.query.get('includeVoided')).toBe('true')
    })
    expect(location()).toBe('/timeline?sort=asc&voided=1')
  })

  it('clears every filter at once', async () => {
    mockBackend(demoRoutes())
    renderPage(`/timeline?type=STAGE_STARTED&from=2026-11-10&to=2026-11-10&sort=asc`)
    await eventsCard()

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(location()).toBe('/timeline?sort=asc')
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument()
  })

  it('fetches further pages from the backend', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()
    expect(screen.getByText('1–50 of 1,376 events')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      expect(backend.last('GET', '/api/timeline').query.get('page')).toBe('1')
    })
    expect(location()).toBe('/timeline?page=1')
  })

  it('says so when the filters match nothing', async () => {
    mockBackend({
      ...demoRoutes(),
      'GET /api/timeline': () => jsonResponse({ ...fixtures.timeline, items: [], totalItems: 0, totalPages: 0 }),
    })

    renderPage('/timeline?type=ORDER_DISPATCHED')

    expect(await screen.findByText('No events match these filters')).toBeInTheDocument()
  })

  it('says so when nothing has ever been recorded', async () => {
    mockBackend({
      ...demoRoutes(),
      'GET /api/timeline/summary': () =>
        jsonResponse({ ...fixtures.timelineSummary, firstEventAt: null, lastEventAt: null, buckets: [] }),
      'GET /api/timeline': () => jsonResponse({ ...fixtures.timeline, items: [], totalItems: 0, totalPages: 0 }),
    })

    renderPage()

    expect(await screen.findByText('Nothing has been recorded yet')).toBeInTheDocument()
  })

  it('shows a failure to load events with a retry, and keeps the rest of the page', async () => {
    mockBackend({
      ...demoRoutes(),
      'GET /api/timeline': () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity by day' })).toBeInTheDocument()
  })
})

describe('TimelinePage activity by day', () => {
  it('offers every day of the history with its event count', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const days = within(await screen.findByRole('list', { name: 'Activity by day' })).getAllByRole('button')

    expect(days).toHaveLength(18)
    // The first bucket starts at 18:30 UTC on the 25th, which is midnight on the 26th in Surat.
    expect(days[0]).toHaveTextContent('Mon, 26 Oct 2026: 32 events. Show this day.')
    expect(screen.getByText(/History runs from/)).toHaveTextContent('Mon, 26 Oct 2026, 09:00')
  })

  it('filters the events to a day when that day is chosen', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    const days = within(await screen.findByRole('list', { name: 'Activity by day' })).getAllByRole('button')

    await userEvent.click(days[0] as HTMLElement)

    await waitFor(() => {
      const request = backend.last('GET', '/api/timeline')
      expect(request.query.get('from')).toBe('2026-10-25T18:30:00Z')
      expect(request.query.get('to')).toBe('2026-10-26T18:29:59Z')
    })
    expect(location()).toBe('/timeline?from=2026-10-26&to=2026-10-26')
    expect(days[0]).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('TimelinePage event detail', () => {
  it('opens every field of the selected event, with occurred and recorded times apart', async () => {
    const user = userEvent.setup()
    mockBackend(demoRoutes())
    renderPage()
    const day = await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })

    await user.click(within(day).getByRole('button', { name: '12:38 Stage completed, SDW-0042 · batch 1' }))

    const dialog = await screen.findByRole('dialog', { name: 'Stage completed' })
    expect(within(dialog).getByText('Occurred')).toBeInTheDocument()
    expect(within(dialog).getByText('Recorded')).toBeInTheDocument()
    expect(within(dialog).getAllByText('Sat, 14 Nov 2026, 12:38')).toHaveLength(2)
    expect(within(dialog).getByRole('link', { name: 'SDW-0042' })).toHaveAttribute(
      'href',
      '/orders/082d46e1-0baf-3659-bdf2-c80ae6a1986e',
    )
    expect(within(dialog).getByText('Cutting')).toBeInTheDocument()
    expect(within(dialog).getByText('98 stones')).toBeInTheDocument()
    expect(within(dialog).getByText('80.6 ct')).toBeInTheDocument()
    expect(within(dialog).getByText('9 h 42 min')).toBeInTheDocument()
  })

  it('shows what a corrected event first recorded', async () => {
    const user = userEvent.setup()
    const corrected = fixtures.orderDetail.events.find((event) => event.corrected)
    mockBackend({
      ...demoRoutes(),
      'GET /api/timeline': () => jsonResponse({ ...fixtures.timeline, items: [corrected], totalItems: 1, totalPages: 1 }),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Stage completed, SDW-0002 · batch 1/ }))

    const dialog = await screen.findByRole('dialog', { name: 'Stage completed' })
    expect(within(dialog).getByText('69 stones')).toBeInTheDocument()
    expect(within(dialog).getByText('First recorded as 70 stones')).toBeInTheDocument()
    expect(within(dialog).getByText('Corrected')).toBeInTheDocument()
  })
})

describe('TimelinePage point-in-time inspector', () => {
  it('asks for nothing and explains itself until a moment is chosen', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()

    const inspector = screen.getByRole('region', { name: 'Factory at a point in time' })

    expect(within(inspector).getByText(/No moment is selected/)).toBeInTheDocument()
    expect(backend.sent('GET', STATE_PATH)).toHaveLength(0)
  })

  it('cannot branch a scenario until a moment is chosen', async () => {
    mockBackend(demoRoutes())
    renderPage()
    await eventsCard()

    const button = screen.getByRole('button', { name: 'Create scenario from this point' })

    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription('Choose a moment first.')
  })

  it('cannot branch a scenario before the recorded history begins', async () => {
    mockBackend(demoRoutes())
    renderPage('/timeline?at=2026-10-01T00:00:00Z')
    await eventsCard()

    const button = screen.getByRole('button', { name: 'Create scenario from this point' })

    await waitFor(() => {
      expect(button).toHaveAccessibleDescription(/before the recorded history begins/)
    })
    expect(button).toBeDisabled()
  })

  it('creates a scenario at the inspected moment and opens it', async () => {
    const user = userEvent.setup()
    const backend = mockBackend({
      ...demoRoutes(),
      'POST /api/scenarios': () => jsonResponse(fixtures.scenario, { status: 201 }),
    })
    renderPage('/timeline?at=2026-11-10T03:30:00Z')
    await eventsCard()

    const button = screen.getByRole('button', { name: 'Create scenario from this point' })
    await waitFor(() => {
      expect(button).toBeEnabled()
    })
    await user.click(button)
    const dialog = await screen.findByRole('dialog', { name: 'New scenario' })
    expect(within(dialog).queryByLabelText(/Start from/)).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Name'), 'From Tuesday morning')
    await user.click(within(dialog).getByRole('button', { name: 'Create scenario' }))

    await waitFor(() => {
      expect(location()).toBe(`/scenarios/${fixtures.scenario.id}`)
    })
    expect(backend.last('POST', '/api/scenarios').body).toEqual({
      factoryId: FACTORY_ID,
      name: 'From Tuesday morning',
      forkAt: '2026-11-10T03:30:00Z',
      includeFutureOrders: true,
    })
  })

  it('rebuilds the factory at the moment of an event and keeps that moment in the address', async () => {
    const user = userEvent.setup()
    const backend = mockBackend(demoRoutes())
    renderPage()
    const day = await screen.findByRole('region', { name: 'Sat, 14 Nov 2026' })

    await user.click(within(day).getByRole('button', { name: '12:38 Stage completed, SDW-0042 · batch 1' }))
    await user.click(await screen.findByRole('button', { name: 'Inspect factory at this moment' }))

    await waitFor(() => {
      expect(backend.last('GET', STATE_PATH).query.get('at')).toBe('2026-11-14T07:08:54Z')
    })
    expect(location()).toBe('/timeline?at=2026-11-14T07%3A08%3A54Z')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Moment to inspect/)).toHaveValue('2026-11-14T12:38')
  })

  it('shows the reconstructed orders, stages, pools and batches', async () => {
    mockBackend(demoRoutes())

    renderPage('/timeline?at=2026-11-14T07:21:39Z')

    const inspector = await screen.findByRole('region', { name: 'Factory at a point in time' })
    const orders = await within(inspector).findByLabelText('Orders by status at that moment')
    expect(orders).toHaveTextContent('Pending orders0')
    expect(orders).toHaveTextContent('In progress orders18')
    expect(orders).toHaveTextContent('Completed orders9')
    expect(orders).toHaveTextContent('Dispatched orders20')

    const stages = within(inspector).getByRole('list', { name: 'Stages at that moment' })
    const polishing = within(stages).getByRole('listitem', { name: 'Polishing' })
    expect(polishing).toHaveTextContent('Queued6')
    expect(polishing).toHaveTextContent('In progress6')
    expect(polishing).toHaveTextContent('Completed42')
    expect(within(polishing).getByText('Longest queue')).toBeInTheDocument()
    expect(within(stages).getAllByText('Longest queue')).toHaveLength(1)

    const pools = within(inspector).getByRole('table', { name: 'Resource pools at that moment' })
    const wheels = within(pools).getByText('Polishing wheels').closest('tr')
    expect(wheels).toHaveTextContent('Polishing wheelsMachine66')

    const inProgress = within(inspector).getByRole('table', { name: 'Batches in progress' })
    const queued = within(inspector).getByRole('table', { name: 'Batches queued' })
    expect(within(inProgress).getAllByRole('row')).toHaveLength(18)
    expect(within(queued).getAllByRole('row')).toHaveLength(7)
    const waiting = within(queued).getByRole('link', { name: 'SDW-0036' }).closest('tr')
    expect(waiting).toHaveTextContent('Polishing')
    expect(waiting).toHaveTextContent('117 stones97.09 ct')
    expect(waiting).toHaveTextContent('13 Nov 2026, 12:35')
    expect(within(inspector).getByText(/batches had already finished every stage/)).toHaveTextContent('39')
    expect(within(inspector).getByText('Actual')).toBeInTheDocument()
  })

  it('reads a moment typed in factory time and inspects it', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await eventsCard()

    const input = screen.getByLabelText(/Moment to inspect \(Asia\/Kolkata\)/)
    await userEvent.type(input, '2026-11-10T09:00')

    await waitFor(() => {
      // 09:00 in Surat is 03:30 UTC.
      expect(backend.last('GET', STATE_PATH).query.get('at')).toBe('2026-11-10T03:30:00Z')
    })
  })

  it('says the factory was empty before the first order', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${STATE_PATH}`]: () =>
        jsonResponse({
          ...fixtures.state,
          at: '2026-10-01T00:00:00Z',
          orders: { PENDING: 0, IN_PROGRESS: 0, COMPLETED: 0, DISPATCHED: 0 },
          batches: [],
        }),
    })

    renderPage('/timeline?at=2026-10-01T00:00:00Z')

    expect(await screen.findByText(/No order had been placed by then/)).toBeInTheDocument()
  })

  it('clears the moment again', async () => {
    mockBackend(demoRoutes())
    renderPage('/timeline?at=2026-11-14T07:21:39Z')
    await screen.findByLabelText('Orders by status at that moment')

    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))

    expect(location()).toBe('/timeline')
    expect(screen.getByText(/No moment is selected/)).toBeInTheDocument()
  })

  it('shows a failed reconstruction with a retry', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET ${STATE_PATH}`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderPage('/timeline?at=2026-11-14T07:21:39Z')

    const inspector = await screen.findByRole('region', { name: 'Factory at a point in time' })
    expect(within(await within(inspector).findByRole('alert')).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})

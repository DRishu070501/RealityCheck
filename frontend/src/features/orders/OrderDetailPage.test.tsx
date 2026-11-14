import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { OrderDetailPage } from '@/features/orders/OrderDetailPage'
import { demoRoutes, fixtures, mockBackend, ORDER_ID, problem, type Routes as MockRoutes } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderPage(id = ORDER_ID) {
  return renderWithProviders(
    <Routes>
      <Route path="/orders/:id" element={<OrderDetailPage />} />
    </Routes>,
    { route: `/orders/${id}` },
  )
}

/** The fixture order as it was before it was dispatched. */
function completedOrder() {
  return { ...fixtures.orderDetail, order: { ...fixtures.orderDetail.order, status: 'COMPLETED' } }
}

function completedRoutes(extra: MockRoutes = {}): MockRoutes {
  return { ...demoRoutes(), [`GET /api/orders/${ORDER_ID}`]: () => jsonResponse(completedOrder()), ...extra }
}

function eventRows(): HTMLElement[] {
  return within(screen.getByRole('table', { name: 'Event history' })).getAllByRole('row').slice(1)
}

describe('OrderDetailPage', () => {
  it('shows the order header from recorded history, in factory time', async () => {
    mockBackend(demoRoutes())

    renderPage()

    expect(await screen.findByRole('heading', { name: 'SDW-0002' })).toBeInTheDocument()
    expect(screen.getByText('Demo buyer C')).toBeInTheDocument()
    expect(screen.getByText('Dispatched')).toBeInTheDocument()
    expect(screen.getByText('Actual')).toBeInTheDocument()
    expect(screen.queryByText('Late')).not.toBeInTheDocument()
    expect(screen.getByText(/Urgent/)).toBeInTheDocument()
    // 06:30 UTC is 12:00 in Surat.
    expect(screen.getByText('Mon, 26 Oct 2026, 12:00')).toBeInTheDocument()
    expect(screen.getByText('Wed, 4 Nov 2026, 12:00')).toBeInTheDocument()
    const header = within(screen.getByRole('region', { name: 'SDW-0002' }))
    expect(header.getByText('70 stones')).toBeInTheDocument()
    expect(header.getByText('98 ct')).toBeInTheDocument()
    expect(header.getByText('Asia/Kolkata')).toBeInTheDocument()
  })

  it('shows what each batch started with and holds now, stones and carats apart', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const batches = await screen.findByRole('table', { name: 'Batches' })
    const cells = within(within(batches).getAllByRole('row')[1] as HTMLElement)
      .getAllByRole('cell')
      .map((cell) => cell.textContent)

    expect(cells).toEqual(['Batch 1', '70 stones98 ct', '66 stones44.84 ct', 'Finished', 'Finished'])
  })

  it('lists the whole history oldest first', async () => {
    mockBackend(demoRoutes())
    renderPage()
    await screen.findByRole('table', { name: 'Event history' })

    const rows = eventRows()

    expect(rows).toHaveLength(28)
    expect(within(rows[0] as HTMLElement).getByText('Order placed')).toBeInTheDocument()
    expect(within(rows[0] as HTMLElement).getByText('26 Oct 2026, 12:00')).toBeInTheDocument()
    expect(within(rows[rows.length - 1] as HTMLElement).getByText('Order dispatched')).toBeInTheDocument()
  })

  it('shows a corrected value beside what was first recorded, struck through', async () => {
    mockBackend(demoRoutes())
    renderPage()
    await screen.findByRole('table', { name: 'Event history' })

    const corrected = eventRows().find((row) => within(row).queryByText('Corrected'))
    if (!corrected) throw new Error('No corrected event row')

    expect(within(corrected).getByText('Stage completed')).toBeInTheDocument()
    expect(within(corrected).getByText('Cutting')).toBeInTheDocument()
    const original = within(corrected).getByText('70 stones')
    expect(original.tagName).toBe('S')
    expect(within(corrected).getByText('69 stones')).toBeInTheDocument()
    // The carats were not corrected, so they are shown once.
    expect(within(corrected).getByText('60.76 ct')).toBeInTheDocument()
    expect(within(corrected).getByText('5 h 55 min')).toBeInTheDocument()
  })

  it('shows the correction itself as its own entry, with its reason', async () => {
    mockBackend(demoRoutes())
    renderPage()
    await screen.findByRole('table', { name: 'Event history' })

    const correction = eventRows().find((row) => within(row).queryByText('Correction'))
    if (!correction) throw new Error('No correction row')

    expect(within(correction).getByText('Replaces a recorded value')).toBeInTheDocument()
    expect(within(correction).getByText('Recount after cutting (synthetic)')).toBeInTheDocument()
  })

  it('links to the same order in the timeline', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const link = await screen.findByRole('link', { name: 'Open in the timeline' })

    expect(link).toHaveAttribute('href', `/timeline?order=${ORDER_ID}`)
  })

  it('offers no dispatch for an order that is already dispatched', async () => {
    mockBackend(demoRoutes())
    renderPage()
    await screen.findByRole('heading', { name: 'SDW-0002' })

    expect(screen.queryByRole('button', { name: 'Record dispatch' })).not.toBeInTheDocument()
  })

  it('records the dispatch of a completed order after a confirmation', async () => {
    const user = userEvent.setup()
    const backend = mockBackend(
      completedRoutes({ 'POST /api/events': () => jsonResponse(fixtures.timeline.items[0], { status: 201 }) }),
    )
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Record dispatch' }))
    expect(backend.sent('POST', '/api/events')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm dispatch' }))

    await waitFor(() => {
      expect(backend.sent('POST', '/api/events')).toHaveLength(1)
    })
    expect(backend.last('POST', '/api/events').body).toEqual({ eventType: 'ORDER_DISPATCHED', orderId: ORDER_ID })
    // The order is read again afterwards.
    await waitFor(() => {
      expect(backend.sent('GET', `/api/orders/${ORDER_ID}`).length).toBeGreaterThan(1)
    })
  })

  it('shows why the backend refused a dispatch', async () => {
    const user = userEvent.setup()
    mockBackend(
      completedRoutes({
        'POST /api/events': () =>
          problem(422, 'Request cannot be processed', 'Order SDW-0002 has already been dispatched'),
      }),
    )
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Record dispatch' }))
    await user.click(screen.getByRole('button', { name: 'Confirm dispatch' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Order SDW-0002 has already been dispatched')
  })

  it('says so when the order does not exist', async () => {
    mockBackend({
      ...demoRoutes(),
      'GET /api/orders/*': () => problem(404, 'Not found', 'Order 00000000-0000-0000-0000-000000000001 was not found'),
    })

    renderPage('00000000-0000-0000-0000-000000000001')

    expect(await screen.findByText('This order does not exist')).toBeInTheDocument()
  })

  it('shows other failures with a retry', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET /api/orders/${ORDER_ID}`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderPage()

    expect(within(await screen.findByRole('alert')).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})

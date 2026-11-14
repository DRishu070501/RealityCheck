import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { OrdersPage } from '@/features/orders/OrdersPage'
import { demoRoutes, emptyPage, FACTORY_ID, mockBackend, problem } from '@/test/backend'
import { LocationProbe } from '@/test/LocationProbe'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderPage(route = '/orders') {
  return renderWithProviders(
    <>
      <OrdersPage />
      <LocationProbe />
    </>,
    { route },
  )
}

async function table(): Promise<HTMLElement> {
  return screen.findByRole('table', { name: 'Production orders' })
}

function rowOf(orders: HTMLElement, orderNumber: string): HTMLElement {
  const row = within(orders).getByRole('link', { name: orderNumber }).closest('tr')
  if (!row) throw new Error(`No row for ${orderNumber}`)
  return row
}

describe('OrdersPage', () => {
  it('lists the first page of orders, earliest due first, as recorded history', async () => {
    const backend = mockBackend(demoRoutes())

    renderPage()

    const orders = await table()
    expect(within(orders).getAllByRole('row')).toHaveLength(26)
    expect(screen.getByText('47 orders')).toBeInTheDocument()
    expect(screen.getByText('Actual')).toBeInTheDocument()
    expect(screen.getByText('Synthetic demo data')).toBeInTheDocument()
    expect(screen.getByText(/factory local time \(Asia\/Kolkata\)/)).toBeInTheDocument()

    const request = backend.last('GET', '/api/orders')
    expect(request.query.get('factoryId')).toBe(FACTORY_ID)
    expect(request.query.get('page')).toBe('0')
    expect(request.query.get('size')).toBe('25')
    expect(request.query.has('status')).toBe(false)
    expect(request.query.has('delayed')).toBe(false)
  })

  it('shows every fact of an order with its unit, in factory time', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const cells = within(rowOf(await table(), 'SDW-0001'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent)

    expect(cells[0]).toBe('SDW-0001Demo buyer B')
    expect(cells[1]).toBe('3 Normal')
    expect(cells[2]).toBe('Dispatched')
    // 03:30 UTC is 09:00 in Surat.
    expect(cells[3]).toBe('26 Oct 2026 09:00')
    expect(cells[4]).toBe('3 Nov 2026 09:00')
    expect(cells[5]).toBe('2 Nov 2026 11:09')
    expect(cells[6]).toBe('1 of 1')
    // Stones and carats are shown apart, each with its unit.
    expect(cells[7]).toBe('120 stones 144 ct')
  })

  it('marks late orders, and only those', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const orders = await table()

    expect(within(rowOf(orders, 'SDW-0008')).getByText('Late')).toBeInTheDocument()
    expect(within(rowOf(orders, 'SDW-0001')).queryByText('Late')).not.toBeInTheDocument()
    expect(within(orders).getAllByText('Late')).toHaveLength(4)
  })

  it('names the urgency of a priority rather than showing a bare number', async () => {
    mockBackend(demoRoutes())
    renderPage()

    expect(within(rowOf(await table(), 'SDW-0002')).getByText(/Urgent/)).toBeInTheDocument()
  })

  it('asks the backend for one status when a status filter is chosen', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await table()

    await userEvent.click(screen.getByRole('button', { name: 'Completed' }))

    await waitFor(() => {
      expect(backend.last('GET', '/api/orders').query.get('status')).toBe('COMPLETED')
    })
    await waitFor(() => {
      expect(screen.getByText('7 orders')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'Completed' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('location')).toHaveTextContent('/orders?status=COMPLETED')
  })

  it('asks the backend for late orders only when that is ticked', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await table()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Late only' }))

    await waitFor(() => {
      expect(backend.last('GET', '/api/orders').query.get('delayed')).toBe('true')
    })
    await waitFor(() => {
      expect(screen.getByText('4 orders')).toBeInTheDocument()
    })
    expect(screen.getByTestId('location')).toHaveTextContent('/orders?late=1')
  })

  it('reads its filters from the address, so a filtered list can be linked to', async () => {
    const backend = mockBackend(demoRoutes())

    renderPage('/orders?status=DISPATCHED&late=1')
    await screen.findByRole('checkbox', { name: 'Late only', checked: true })

    await waitFor(() => {
      const request = backend.last('GET', '/api/orders')
      expect(request.query.get('status')).toBe('DISPATCHED')
      expect(request.query.get('delayed')).toBe('true')
    })
  })

  it('fetches the next page from the backend and returns to the first when a filter changes', async () => {
    const backend = mockBackend(demoRoutes())
    renderPage()
    await table()
    expect(screen.getByText('1–25 of 47 orders')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => {
      expect(backend.last('GET', '/api/orders').query.get('page')).toBe('1')
    })
    expect(screen.getByTestId('location')).toHaveTextContent('/orders?page=1')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Late only' }))

    await waitFor(() => {
      expect(backend.last('GET', '/api/orders').query.get('page')).toBe('0')
    })
  })

  it('links each order to its detail page', async () => {
    mockBackend(demoRoutes())
    renderPage()

    const link = within(await table()).getByRole('link', { name: 'SDW-0001' })

    expect(link).toHaveAttribute('href', '/orders/7c7621b4-9f01-377c-bcec-e2e1aeec1d5b')
  })

  it('says so when no order has been placed', async () => {
    mockBackend({ ...demoRoutes(), 'GET /api/orders': () => jsonResponse(emptyPage) })

    renderPage()

    expect(await screen.findByText('No production orders have been placed')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('says so when the filters match nothing', async () => {
    mockBackend({ ...demoRoutes(), 'GET /api/orders': () => jsonResponse(emptyPage) })

    renderPage('/orders?status=PENDING')

    expect(await screen.findByText('No orders match these filters')).toBeInTheDocument()
  })

  it('shows the backend error and offers a retry', async () => {
    mockBackend({
      ...demoRoutes(),
      'GET /api/orders': () => problem(500, 'Internal server error', 'Something went wrong on the server.'),
    })

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Internal server error (HTTP 500)')).toBeInTheDocument()
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})

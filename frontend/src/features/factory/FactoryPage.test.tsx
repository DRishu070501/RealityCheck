import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { FactoryPage } from '@/features/factory/FactoryPage'
import { demoRoutes, emptyPage, FACTORY_ID, fixtures, mockBackend, problem } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('FactoryPage', () => {
  it('shows the factory profile, marked as synthetic demo data', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<FactoryPage />)

    expect(await screen.findByRole('heading', { name: 'Surat Diamond Works' })).toBeInTheDocument()
    expect(screen.getByText('Synthetic demo data')).toBeInTheDocument()
    expect(screen.getByText('Asia/Kolkata')).toBeInTheDocument()
    expect(screen.getByText('INR')).toBeInTheDocument()
    expect(screen.getByText('14 resource pools')).toBeInTheDocument()
    expect(screen.getByText('1 workflow')).toBeInTheDocument()
  })

  it('renders every day of the week with its shift, break and hours, and Sunday closed', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<FactoryPage />)

    const monday = await screen.findByRole('listitem', { name: 'Monday' })
    expect(within(monday).getByText('09:00 – 18:00')).toBeInTheDocument()
    expect(within(monday).getByText('break 13:00 – 14:00')).toBeInTheDocument()
    expect(within(monday).getByText('8 h')).toBeInTheDocument()

    const sunday = screen.getByRole('listitem', { name: 'Sunday' })
    expect(within(sunday).getByText('Closed')).toBeInTheDocument()
    expect(within(sunday).getByText('0 h')).toBeInTheDocument()

    expect(screen.getByText('48 h')).toBeInTheDocument()
    for (const day of ['Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']) {
      expect(screen.getByRole('listitem', { name: day })).toBeInTheDocument()
    }
  })

  it('does not tag a factory that is not demo data', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET /api/factories/${FACTORY_ID}`]: () => jsonResponse({ ...fixtures.factory, demo: false }),
    })

    renderWithProviders(<FactoryPage />)

    await screen.findByRole('heading', { name: 'Surat Diamond Works' })
    expect(screen.queryByText('Synthetic demo data')).not.toBeInTheDocument()
  })

  it('explains how to load a factory when the backend has none', async () => {
    mockBackend({ 'GET /api/factories': () => jsonResponse(emptyPage) })

    renderWithProviders(<FactoryPage />)

    expect(await screen.findByText('No factory is configured yet')).toBeInTheDocument()
    expect(screen.getByText('REALITYCHECK_DEMO_SEED=true')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings')
  })

  it('shows a loading state, then an error with a working retry', async () => {
    let fail = true
    mockBackend({
      ...demoRoutes(),
      'GET /api/factories': () =>
        fail ? problem(500, 'Internal server error', 'Something went wrong on the server.') : jsonResponse(fixtures.factories),
    })

    renderWithProviders(<FactoryPage />)

    expect(screen.getByRole('status', { name: 'Loading factory' })).toBeInTheDocument()
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Internal server error (HTTP 500)')).toBeInTheDocument()

    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('heading', { name: 'Surat Diamond Works' })).toBeInTheDocument()
  })
})

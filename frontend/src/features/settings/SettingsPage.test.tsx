import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SettingsPage } from '@/features/settings/SettingsPage'
import { FACTORY_ID, fixtures, mockBackend, problem } from '@/test/backend'
import { LocationProbe } from '@/test/LocationProbe'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  delete document.documentElement.dataset.theme
})

const info = (resetEnabled: boolean) => () => jsonResponse({ ...fixtures.systemInfo, demo: { resetEnabled } })
const RESET = 'POST /api/demo/reset'

describe('SettingsPage', () => {
  it('shows how the deployment is configured', async () => {
    mockBackend({ 'GET /api/system/info': info(true) })

    renderWithProviders(<SettingsPage />)

    expect(await screen.findByText('RealityCheck 0.1.0')).toBeInTheDocument()
    expect(screen.getByText('PostgreSQL 17.12')).toBeInTheDocument()
    expect(screen.getByText('4 applied, current version 4')).toBeInTheDocument()
    expect(screen.getByText('Built-in analyst')).toBeInTheDocument()
    expect(screen.getByText('1.0.0')).toBeInTheDocument()
  })

  it('switches to the Halloween theme at once, remembers it, and switches back', async () => {
    mockBackend({ 'GET /api/system/info': info(true) })
    const user = userEvent.setup()

    renderWithProviders(<SettingsPage />)

    const appearance = screen.getByRole('region', { name: 'Appearance' })
    expect(within(appearance).getByRole('radio', { name: /Professional/ })).toBeChecked()
    expect(document.documentElement.dataset.theme).toBeUndefined()

    await user.click(within(appearance).getByRole('radio', { name: /Halloween/ }))

    expect(document.documentElement.dataset.theme).toBe('halloween')
    expect(window.localStorage.getItem('realitycheck.theme')).toBe('halloween')

    await user.click(within(appearance).getByRole('radio', { name: /Professional/ }))

    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(window.localStorage.getItem('realitycheck.theme')).toBe('professional')
  })

  it('offers no reset when it is disabled by configuration', async () => {
    mockBackend({ 'GET /api/system/info': info(false) })

    renderWithProviders(<SettingsPage />)

    expect(await screen.findByText(/disabled by configuration/)).toBeInTheDocument()
    expect(screen.getByText('REALITYCHECK_DEMO_RESET_ENABLED=true')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reset demo data' })).not.toBeInTheDocument()
  })

  it('needs the confirmation typed, then resets, discards cached data and returns to Overview', async () => {
    const user = userEvent.setup()
    const backend = mockBackend({
      'GET /api/system/info': info(true),
      [RESET]: () => jsonResponse({ factoryId: FACTORY_ID, resetAt: '2026-11-14T06:40:00.123456Z' }),
    })
    renderWithProviders(
      <>
        <SettingsPage />
        <LocationProbe />
      </>,
      { route: '/settings' },
    )

    await user.click(await screen.findByRole('button', { name: 'Reset demo data' }))

    const dialog = await screen.findByRole('dialog', { name: 'Erase all data and reset the demo?' })
    expect(within(dialog).getByText('This cannot be undone.')).toBeInTheDocument()
    expect(within(dialog).getByText(/will be deleted, including your own\s+changes/)).toBeInTheDocument()
    const erase = within(dialog).getByRole('button', { name: 'Erase and reset' })
    expect(erase).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/to confirm/), 'reset')
    expect(erase).toBeDisabled()
    await user.clear(within(dialog).getByLabelText(/to confirm/))
    await user.type(within(dialog).getByLabelText(/to confirm/), 'RESET')
    expect(backend.sent('POST', '/api/demo/reset')).toHaveLength(0)

    await user.click(erase)

    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    })
    expect(backend.sent('POST', '/api/demo/reset')).toHaveLength(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('resets nothing when the confirmation is cancelled', async () => {
    const user = userEvent.setup()
    const backend = mockBackend({ 'GET /api/system/info': info(true) })
    renderWithProviders(<SettingsPage />)

    await user.click(await screen.findByRole('button', { name: 'Reset demo data' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(backend.sent('POST', '/api/demo/reset')).toHaveLength(0)
  })

  it('keeps the confirmation open and reports a failed reset', async () => {
    const user = userEvent.setup()
    mockBackend({
      'GET /api/system/info': info(true),
      [RESET]: () => problem(500, 'Internal server error', 'Something went wrong on the server. The error has been logged.'),
    })
    renderWithProviders(<SettingsPage />)

    await user.click(await screen.findByRole('button', { name: 'Reset demo data' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/to confirm/), 'RESET')
    await user.click(within(dialog).getByRole('button', { name: 'Erase and reset' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Something went wrong on the server.')
    expect(screen.queryByText(/Demo data was reset at/)).not.toBeInTheDocument()
  })

  it('reports an unreachable backend', async () => {
    mockBackend({ 'GET /api/system/info': () => Promise.reject(new TypeError('Failed to fetch')) })

    renderWithProviders(<SettingsPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Backend unreachable')
  })
})

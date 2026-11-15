import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AppShell } from '@/app/AppShell'
import { RouteErrorPage } from '@/app/RouteErrorPage'
import { demoRoutes, mockBackend } from '@/test/backend'

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderBroken(error: Error) {
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  mockBackend(demoRoutes())
  const Thrower = () => {
    throw error
  }
  const router = createMemoryRouter(
    [
      {
        element: <AppShell />,
        children: [{ errorElement: <RouteErrorPage />, children: [{ path: '/', element: <Thrower /> }] }],
      },
    ],
    { initialEntries: ['/'] },
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('RouteErrorPage', () => {
  it('replaces a screen that fails to render, inside the shell, with a way out', async () => {
    renderBroken(new Error('render failed'))

    expect(await screen.findByRole('heading', { name: 'This screen could not be shown' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Overview' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()
    expect(screen.queryByText('render failed')).not.toBeInTheDocument()
  })

  it('asks for a reload when a screen built before an update can no longer be fetched', async () => {
    renderBroken(new TypeError('Failed to fetch dynamically imported module: /assets/ResultsPage-abc.js'))

    expect(await screen.findByRole('heading', { name: 'A newer version is available' })).toBeInTheDocument()
  })
})

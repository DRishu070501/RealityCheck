import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { routes } from '@/app/routes'
import { demoRoutes, mockBackend, ORDER_ID, scenarioRoutes } from '@/test/backend'

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('routes', () => {
  it.each([
    ['/', 'Overview'],
    ['/factory', 'Factory'],
    ['/workflow', 'Workflow'],
    ['/resources', 'Resources'],
    ['/orders', 'Production Orders'],
    [`/orders/${ORDER_ID}`, 'Order'],
    ['/timeline', 'Operational Timeline'],
    ['/insights', 'Where Time Goes'],
    ['/scenarios', 'Scenario Lab'],
    ['/results', 'Simulation Results'],
    ['/analyst', 'AI Analyst'],
    ['/settings', 'Settings'],
  ])('loads the screen for %s on demand', async (path, heading) => {
    mockBackend({ ...demoRoutes(), ...scenarioRoutes() })

    renderAt(path)

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
  })

})

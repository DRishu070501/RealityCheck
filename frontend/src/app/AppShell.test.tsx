import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { NAV_ITEMS } from '@/app/nav'
import { Sidebar } from '@/app/Sidebar'
import { AppShell } from '@/app/AppShell'
import { ComingSoon } from '@/components/ComingSoon'
import { renderWithProviders } from '@/test/utils'

const EXPECTED_LABELS = [
  'Overview',
  'Factory',
  'Workflow',
  'Resources',
  'Production Orders',
  'Operational Timeline',
  'Scenario Lab',
  'Simulation Results',
  'AI Analyst',
  'Settings',
]

describe('Sidebar', () => {
  it('renders the ten navigation links in the specified order', () => {
    renderWithProviders(<Sidebar />)

    const nav = screen.getByRole('navigation', { name: 'Primary' })
    const links = within(nav).getAllByRole('link')

    expect(links.map((link) => link.textContent)).toEqual(EXPECTED_LABELS)
  })

  it.each([
    ['/', 'Overview'],
    ['/scenarios', 'Scenario Lab'],
    ['/timeline', 'Operational Timeline'],
  ])('marks only the link for %s as the current page', (route, label) => {
    renderWithProviders(<Sidebar />, { route })

    const current = screen.getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')

    expect(current).toHaveLength(1)
    expect(current[0]).toHaveTextContent(label)
  })
})

describe('navigation config', () => {
  it('marks exactly the screens that are not built yet as planned', () => {
    const planned = NAV_ITEMS.filter((item) => item.planned).map((item) => item.label)

    expect(planned).toEqual([
      'Production Orders',
      'Operational Timeline',
      'Scenario Lab',
      'Simulation Results',
      'AI Analyst',
    ])
  })
})

describe('AppShell', () => {
  function renderShell() {
    const router = createMemoryRouter(
      [
        {
          element: <AppShell />,
          children: [
            { index: true, element: <p>overview page</p> },
            {
              path: '/factory',
              element: <ComingSoon title="Factory" summary="Factory profile." milestone="Milestone 2" />,
            },
          ],
        },
      ],
      { initialEntries: ['/'] },
    )
    return render(<RouterProvider router={router} />)
  }

  it('opens the navigation drawer, navigates, and closes it again', async () => {
    const user = userEvent.setup()
    renderShell()

    const toggle = screen.getByRole('button', { name: 'Open navigation' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getAllByRole('navigation', { name: 'Primary' })).toHaveLength(1)

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const [, drawerNav] = screen.getAllByRole('navigation', { name: 'Primary' })
    if (!drawerNav) throw new Error('Drawer navigation did not open')

    await user.click(within(drawerNav).getByRole('link', { name: 'Factory' }))

    expect(screen.getByRole('heading', { level: 1, name: 'Factory' })).toBeInTheDocument()
    expect(screen.getByText('Not implemented yet')).toBeInTheDocument()
    expect(screen.getByText('Planned for Milestone 2')).toBeInTheDocument()
    expect(screen.getAllByRole('navigation', { name: 'Primary' })).toHaveLength(1)
  })

  it('closes the drawer with Escape', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    expect(screen.getByRole('button', { name: 'Close navigation' })).toBeInTheDocument()

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('button', { name: 'Close navigation' })).not.toBeInTheDocument()
  })
})

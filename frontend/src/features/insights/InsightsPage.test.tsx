import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { InsightsPage } from '@/features/insights/InsightsPage'
import { demoRoutes, FACTORY_ID, mockBackend, problem } from '@/test/backend'
import { renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function region(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('InsightsPage', () => {
  it('shows lead time with and without parking, from recorded history', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<InsightsPage />)

    const lead = await screen.findByRole('region', { name: 'Lead time' })
    // 734,400 s is 204 h; 486,000 s is 135 h.
    expect(within(lead).getByText('Median lead time', { selector: 'dt' }).nextElementSibling).toHaveTextContent('8 d 12 h')
    expect(within(lead).getByText('Median without parking', { selector: 'dt' }).nextElementSibling).toHaveTextContent('5 d 15 h')
    // The fixture has no parking stage; finished packets' own parking share is in the route table.
    expect(within(lead).getByText('Time parked, all packets', { selector: 'dt' }).nextElementSibling).toHaveTextContent('0.0%')
    expect(within(within(lead).getByRole('table')).getByText('31.2%')).toBeInTheDocument()
    expect(within(lead).getByText('Packets finished', { selector: 'dt' }).nextElementSibling).toHaveTextContent('25')
    expect(within(lead).getByText(/Packets still on their way are not counted/)).toBeInTheDocument()
    expect(within(lead).getByText('Actual')).toBeInTheDocument()
  })

  it('splits each stage’s time into waiting and worked, with the same figures as a table', async () => {
    mockBackend(demoRoutes())
    const user = userEvent.setup()

    renderWithProviders(<InsightsPage />)

    const card = await screen.findByRole('region', { name: 'Where packet time goes, by stage' })
    const bars = within(card).getByRole('list', { name: 'Packet time per stage' })
    expect(within(bars).getAllByRole('listitem')).toHaveLength(8)
    // Polishing: 900,000 s waiting (250 h) and 300,000 s worked (83 h) of 2,172,800 s in all.
    expect(within(bars).getByText(/^Polishing: 55\.2% of all packet time; waiting 10 d 10 h, worked 3 d 11 h, held 0 h/))
      .toBeInTheDocument()

    await user.click(within(card).getByText('Show as table'))
    expect(within(card).getAllByRole('row')).toHaveLength(9)
  })

  it('shows the haunted stages, recorded yields and the wait for dispatch', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<InsightsPage />)

    await screen.findByRole('region', { name: 'Haunted stages' })
    const yields = within(region('Yield by stage')).getByRole('table')
    const polishing = within(yields).getByText('6. Polishing').closest('tr') as HTMLElement
    expect(polishing).toHaveTextContent('99.0%')
    expect(polishing).toHaveTextContent('82.0%')

    const dispatch = region('Waiting for dispatch')
    expect(within(dispatch).getByText('Lots dispatched', { selector: 'dt' }).nextElementSibling).toHaveTextContent('12')
    expect(within(dispatch).getByText('Median wait to dispatch', { selector: 'dt' }).nextElementSibling).toHaveTextContent('2 h')
    expect(within(dispatch).getByText('Finished, not yet dispatched', { selector: 'dt' }).nextElementSibling).toHaveTextContent('4')
  })

  it('reports a failure to read recorded history', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET /api/factories/${FACTORY_ID}/insights`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderWithProviders(<InsightsPage />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Lead time' })).not.toBeInTheDocument()
  })
})

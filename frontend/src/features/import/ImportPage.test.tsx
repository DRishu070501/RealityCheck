import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ImportPage } from '@/features/import/ImportPage'
import { demoRoutes, mockBackend } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

const ORDERS = 'order_number,placed_at,stones,carats\nA-1,2026-10-01 09:00,10,12.5\n'
const MOVEMENTS = 'order_number,stage,started_at,completed_at\nA-1,INWARD,2026-10-01 10:00,2026-10-01 12:00\n'

function csv(name: string, text: string) {
  return new File([text], name, { type: 'text/csv' })
}

const accepted = {
  dryRun: true,
  saved: false,
  orders: 1,
  packets: 1,
  movements: 1,
  dispatched: 0,
  firstEventAt: '2026-10-01T03:30:00Z',
  lastEventAt: '2026-10-01T06:30:00Z',
  error: null,
}

describe('ImportPage', () => {
  it('checks the files without saving, then imports them', async () => {
    const bodies: { dryRun: boolean; orders: unknown[]; movements: unknown[] }[] = []
    mockBackend({
      ...demoRoutes(),
      'POST /api/imports': ({ body }) => {
        const sent = body as (typeof bodies)[number]
        bodies.push(sent)
        return jsonResponse({ ...accepted, dryRun: sent.dryRun, saved: !sent.dryRun })
      },
    })
    const user = userEvent.setup()
    renderWithProviders(<ImportPage />)

    await user.upload(await screen.findByLabelText('Orders file'), csv('orders.csv', ORDERS))
    await user.upload(screen.getByLabelText('Movements file'), csv('moves.csv', MOVEMENTS))
    expect(await screen.findByText('orders.csv: 1 row read')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(await screen.findByText(/Ready to import/)).toHaveTextContent('1 order, 1 packet, 1 stage visit and 0 dispatches')

    await user.click(screen.getByRole('button', { name: 'Import' }))
    expect(await screen.findByText(/^Imported/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Where Time Goes' })).toHaveAttribute('href', '/insights')

    expect(bodies.map((b) => b.dryRun)).toEqual([true, false])
    expect(bodies[0]?.orders).toEqual([expect.objectContaining({ row: 2, orderNumber: 'A-1', stones: 10 })])
    expect(bodies[0]?.movements).toEqual([expect.objectContaining({ row: 2, stage: 'INWARD' })])
  })

  it('lists format problems by row and does not offer a check', async () => {
    mockBackend(demoRoutes())
    const user = userEvent.setup()
    renderWithProviders(<ImportPage />)

    await user.upload(
      await screen.findByLabelText('Orders file'),
      csv('orders.csv', 'order_number,placed_at,stones,carats\nA-1,someday,10,12\n'),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Row 2: placed_at "someday" is not a date')
    expect(screen.getByRole('button', { name: 'Check' })).toBeDisabled()
  })

  it('shows the file and row of the rule the backend refused', async () => {
    mockBackend({
      ...demoRoutes(),
      'POST /api/imports': () =>
        jsonResponse({
          ...accepted,
          orders: 0,
          packets: 0,
          movements: 0,
          firstEventAt: null,
          lastEventAt: null,
          error: { file: 'movements', row: 2, message: 'stage SAWING is not in the active workflow' },
        }),
    })
    const user = userEvent.setup()
    renderWithProviders(<ImportPage />)

    await user.upload(await screen.findByLabelText('Orders file'), csv('orders.csv', ORDERS))
    await user.upload(screen.getByLabelText('Movements file'), csv('moves.csv', MOVEMENTS))
    await user.click(await screen.findByRole('button', { name: 'Check' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'moves.csv, row 2: stage SAWING is not in the active workflow. Nothing was saved.',
    )
    expect(screen.getByRole('button', { name: 'Import' })).toBeDisabled()
  })
})

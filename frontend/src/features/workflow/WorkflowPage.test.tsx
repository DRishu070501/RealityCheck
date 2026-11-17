import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { WorkflowPage } from '@/features/workflow/WorkflowPage'
import { demoRoutes, emptyPage, FACTORY_ID, fixtures, mockBackend, problem, WORKFLOW_ID } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function pipeline(): HTMLElement {
  return screen.getByRole('list', { name: 'Production stages in sequence' })
}

describe('WorkflowPage', () => {
  it('renders the eight stages in sequence order', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<WorkflowPage />)

    await screen.findByRole('heading', { name: 'Rough to polished' })
    const names = within(pipeline())
      .getAllByRole('button')
      .map((button) => button.textContent)

    expect(names).toHaveLength(8)
    expect(names.map((text) => /Stage (\d)/.exec(text)?.[1])).toEqual(['1', '2', '3', '4', '5', '6', '7', '8'])
    for (const [index, name] of [
      'Rough Intake',
      'Planning',
      'Marking',
      'Cutting',
      'Bruting',
      'Polishing',
      'Quality Inspection',
      'Dispatch',
    ].entries()) {
      expect(names[index]).toContain(name)
    }
  })

  it('shows the first stage in detail until another is selected', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<WorkflowPage />)

    expect(await screen.findByRole('heading', { name: '1. Rough Intake' })).toBeInTheDocument()
    expect(within(pipeline()).getByRole('button', { name: /Rough Intake/ })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows every configured parameter of the selected stage, with units', async () => {
    mockBackend(demoRoutes())
    renderWithProviders(<WorkflowPage />)
    await screen.findByRole('heading', { name: 'Rough to polished' })

    await userEvent.click(within(pipeline()).getByRole('button', { name: /Polishing/ }))

    expect(screen.getByRole('heading', { name: '6. Polishing' })).toBeInTheDocument()
    expect(screen.getByText('POLISHING')).toBeInTheDocument()
    expect(screen.getByText('6 stones/hour per slot')).toBeInTheDocument()
    expect(screen.getByText('15 min per job')).toBeInTheDocument()
    expect(screen.getByText('Up to 6 jobs at once')).toBeInTheDocument()
    expect(screen.getByText('99%')).toBeInTheDocument()
    expect(screen.getByText('82%')).toBeInTheDocument()
    expect(screen.getByText(/^₹\s?150\.00 per job$/)).toBeInTheDocument()

    const requirements = screen.getByRole('table', { name: 'Resources one Polishing slot needs' })
    const [, wheels, polishers] = within(requirements).getAllByRole('row')
    if (!wheels || !polishers) throw new Error('Expected two requirement rows')
    expect(within(wheels).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'Polishing wheels',
      'Machine',
      '1',
      '6 units',
    ])
    expect(within(polishers).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'Polishers',
      'Employee',
      '2',
      '12 units',
    ])
  })

  it('describes a stage without requirements as unlimited', async () => {
    const [first, ...rest] = fixtures.workflow.stages
    mockBackend({
      ...demoRoutes(),
      [`GET /api/workflows/${WORKFLOW_ID}`]: () =>
        jsonResponse({ ...fixtures.workflow, stages: [{ ...first, requirements: [], maxSlots: null }, ...rest] }),
    })

    renderWithProviders(<WorkflowPage />)

    expect(await screen.findByText('Unlimited jobs at once')).toBeInTheDocument()
    expect(screen.getByText('Unlimited slots')).toBeInTheDocument()
    expect(screen.getByText(/This stage needs no resources/)).toBeInTheDocument()
    const detail = screen.getByRole('region', { name: '1. Rough Intake' })
    expect(within(detail).queryByRole('table')).not.toBeInTheDocument()
  })

  it('calls no stage a bottleneck and says which figures are not available yet', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<WorkflowPage />)

    expect(
      await screen.findByText(/Utilization, average duration and bottleneck evidence come from simulation runs/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/bottleneck$/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/utilization:/i)).not.toBeInTheDocument()
  })

  it('shows how many batches are queued and in progress at each stage right now, as actual', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<WorkflowPage />)

    await screen.findByRole('heading', { name: 'Rough to polished' })
    const polishing = await within(pipeline()).findByRole('button', { name: /Polishing/ })
    await within(polishing).findByText('6 queued · 6 in progress')
    expect(within(pipeline()).getByRole('button', { name: /Rough Intake/ })).toHaveTextContent(
      '0 queued · 0 in progress',
    )
    expect(screen.getAllByText('Actual').length).toBeGreaterThan(0)

    await userEvent.click(polishing)

    const now = screen.getByLabelText('At this stage right now')
    expect(now).toHaveTextContent('6 batches queued, 6 in progress, 42 through this stage.')
    expect(within(now).getByText('Actual')).toBeInTheDocument()
  })

  it('still shows the configuration when the live counts cannot be read', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET /api/factories/${FACTORY_ID}/state`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderWithProviders(<WorkflowPage />)

    expect(await screen.findByRole('heading', { name: '1. Rough Intake' })).toBeInTheDocument()
    expect(within(pipeline()).getAllByRole('button')).toHaveLength(8)
    expect(screen.queryByText(/queued ·/)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('ranks the haunted stages from recorded time and marks them in the pipeline', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<WorkflowPage />)

    const card = await screen.findByRole('region', { name: 'Haunted stages' })
    const ranked = within(card).getAllByRole('listitem')
    expect(ranked.map((item) => item.textContent)).toEqual([
      expect.stringContaining('#1 Polishing'),
      expect.stringContaining('#2 Cutting'),
      expect.stringContaining('#3 Quality Inspection'),
    ])
    expect(ranked[0]).toHaveTextContent('41.42% of all packet time was spent waiting to be worked on here.')
    expect(ranked[0]).toHaveTextContent('40 visits · waiting 250 h · worked 83 h')
    expect(within(card).getByText('Actual')).toBeInTheDocument()
    expect(within(pipeline()).getByRole('button', { name: /Polishing/ })).toHaveTextContent('Haunted #1')
    expect(within(pipeline()).getByRole('button', { name: /Rough Intake/ })).not.toHaveTextContent('Haunted')

    await userEvent.click(within(card).getByText('Show every stage as a table'))
    const table = within(card).getByRole('table', { name: 'Packet time at every stage, from recorded history' })
    expect(within(table).getAllByRole('row')).toHaveLength(9)
  })

  it('shows no haunted stages when recorded time cannot be read', async () => {
    mockBackend({
      ...demoRoutes(),
      [`GET /api/factories/${FACTORY_ID}/insights`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    renderWithProviders(<WorkflowPage />)

    expect(await screen.findByRole('heading', { name: '1. Rough Intake' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Haunted stages' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Haunted #/)).not.toBeInTheDocument()
  })

  it('says so when the factory has no workflow', async () => {
    mockBackend({ ...demoRoutes(), 'GET /api/workflows': () => jsonResponse(emptyPage) })

    renderWithProviders(<WorkflowPage />)

    expect(await screen.findByText('No workflow is configured')).toBeInTheDocument()
  })
})

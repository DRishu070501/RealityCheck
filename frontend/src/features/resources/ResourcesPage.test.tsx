import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ResourcesPage } from '@/features/resources/ResourcesPage'
import { demoRoutes, emptyPage, FACTORY_ID, mockBackend, poolNamed, problem, type Routes } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

const wheels = poolNamed('Polishing wheels')
const WHEELS_PATH = `/api/resources/${wheels.id}`

afterEach(() => {
  vi.unstubAllGlobals()
})

async function renderPage(routes: Routes = demoRoutes()) {
  const backend = mockBackend(routes)
  renderWithProviders(<ResourcesPage />, { route: '/resources' })
  await screen.findByRole('table', { name: 'Machines' })
  return backend
}

function poolNames(table: HTMLElement): (string | null | undefined)[] {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[0]?.querySelector('span')?.textContent)
}

async function openEdit(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
  await user.click(screen.getByRole('button', { name: 'Edit Polishing wheels' }))
  return screen.findByRole('dialog', { name: 'Edit Polishing wheels' })
}

async function openAvailability(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
  await user.click(screen.getByRole('button', { name: 'Availability of Polishing wheels' }))
  const dialog = await screen.findByRole('dialog', { name: 'Availability of Polishing wheels' })
  await within(dialog).findByText('Scheduled wheel maintenance (synthetic)')
  return dialog
}

describe('ResourcesPage list', () => {
  it('lists machine and employee pools in separate groups with units and cost', async () => {
    await renderPage()

    const machines = screen.getByRole('table', { name: 'Machines' })
    const employees = screen.getByRole('table', { name: 'Employees' })
    expect(poolNames(machines)).toEqual([
      'Bruting lathes',
      'Grading microscopes',
      'Laser marking units',
      'Laser saws',
      'Planning scanners',
      'Polishing wheels',
    ])
    expect(poolNames(employees)).toHaveLength(8)
    expect(screen.getByText('6 pools, 21 units')).toBeInTheDocument()
    expect(screen.getByText('8 pools, 31 units')).toBeInTheDocument()

    const row = within(machines).getByText('Polishing wheels').closest('tr')
    if (!row) throw new Error('No row for Polishing wheels')
    const cells = within(row)
      .getAllByRole('cell')
      .map((cell) => cell.textContent)
    expect(cells[0]).toBe('Polishing wheelsPolishing wheel')
    expect(cells[1]).toBe('6 units')
    expect(cells[3]).toMatch(/^₹\s?140\.00$/)
    expect(cells[4]).toBe('Active')
    expect(cells[5]).toBe('Polishing (1 per slot)')
  })

  it('asks the backend for one kind when a filter is chosen', async () => {
    const backend = await renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Employees' }))

    await waitFor(() => {
      expect(screen.queryByRole('table', { name: 'Machines' })).not.toBeInTheDocument()
    })
    expect(poolNames(screen.getByRole('table', { name: 'Employees' }))).toContain('Polishers')
    expect(screen.getByRole('button', { name: 'Employees' })).toHaveAttribute('aria-pressed', 'true')
    expect(backend.sent('GET', '/api/resources').map((request) => request.query.get('kind'))).toEqual([
      null,
      'EMPLOYEE',
    ])
  })

  it('says so when no pools are configured', async () => {
    mockBackend({ ...demoRoutes(), 'GET /api/resources': () => jsonResponse(emptyPage) })

    renderWithProviders(<ResourcesPage />)

    expect(await screen.findByText('No resource pools are configured')).toBeInTheDocument()
  })

  it('shows the units of each pool in use right now, as actual, and no utilization', async () => {
    await renderPage()

    const machines = screen.getByRole('table', { name: 'Machines' })
    expect(within(machines).getByRole('columnheader', { name: /In use now/ })).toBeInTheDocument()
    const scanners = within(machines).getByText('Planning scanners').closest('tr')
    if (!scanners) throw new Error('No row for Planning scanners')
    expect(await within(scanners).findByText('1 of 3')).toBeInTheDocument()
    const polishers = within(screen.getByRole('table', { name: 'Employees' })).getByText('Polishers').closest('tr')
    expect(polishers).toHaveTextContent('12 of 12')
    expect(screen.getByText('Actual')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: /utilization/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('still lists the pools when the units in use cannot be read', async () => {
    await renderPage({
      ...demoRoutes(),
      [`GET /api/factories/${FACTORY_ID}/state`]: () => problem(500, 'Internal server error', 'Something went wrong.'),
    })

    expect(poolNames(screen.getByRole('table', { name: 'Machines' }))).toHaveLength(6)
    expect(screen.queryByText('Actual')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('editing a pool', () => {
  it('starts from the current values and sends every field with the version it read', async () => {
    const user = userEvent.setup()
    const backend = await renderPage({
      ...demoRoutes(),
      [`PUT ${WHEELS_PATH}`]: ({ body }) => jsonResponse({ ...wheels, ...(body as object), version: 1 }),
    })
    const dialog = await openEdit(user)

    expect(within(dialog).getByLabelText('Name')).toHaveValue('Polishing wheels')
    expect(within(dialog).getByLabelText('Category')).toHaveValue('POLISHING_WHEEL')
    expect(within(dialog).getByLabelText('Capacity (units)')).toHaveValue(6)
    expect(within(dialog).getByLabelText('Hourly cost (INR)')).toHaveValue('140.00')
    expect(within(dialog).getByLabelText('Status')).toHaveValue('ACTIVE')

    await user.clear(within(dialog).getByLabelText('Capacity (units)'))
    await user.type(within(dialog).getByLabelText('Capacity (units)'), '8')
    await user.clear(within(dialog).getByLabelText('Hourly cost (INR)'))
    await user.type(within(dialog).getByLabelText('Hourly cost (INR)'), '155.5')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(backend.sent('PUT', WHEELS_PATH).map((request) => request.body)).toEqual([
      {
        name: 'Polishing wheels',
        category: 'POLISHING_WHEEL',
        capacity: 8,
        hourlyCost: '155.5',
        status: 'ACTIVE',
        version: 0,
      },
    ])
    // The list is re-read after the change.
    expect(backend.sent('GET', '/api/resources').length).toBeGreaterThan(1)
  })

  it('validates before sending anything', async () => {
    const user = userEvent.setup()
    const backend = await renderPage()
    const dialog = await openEdit(user)

    await user.clear(within(dialog).getByLabelText('Name'))
    await user.clear(within(dialog).getByLabelText('Category'))
    await user.type(within(dialog).getByLabelText('Category'), 'polishing wheel')
    await user.clear(within(dialog).getByLabelText('Capacity (units)'))
    await user.type(within(dialog).getByLabelText('Capacity (units)'), '-1')
    await user.clear(within(dialog).getByLabelText('Hourly cost (INR)'))
    await user.type(within(dialog).getByLabelText('Hourly cost (INR)'), '12.345')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText('Enter a name')).toBeInTheDocument()
    expect(within(dialog).getByText(/Use upper-case letters, digits and underscores/)).toBeInTheDocument()
    expect(within(dialog).getByText('Capacity cannot be negative')).toBeInTheDocument()
    expect(within(dialog).getByText(/at most two decimals/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
    expect(backend.sent('PUT', WHEELS_PATH)).toHaveLength(0)
  })

  it('rejects an empty or fractional capacity', async () => {
    const user = userEvent.setup()
    const backend = await renderPage()
    const dialog = await openEdit(user)
    const capacity = within(dialog).getByLabelText('Capacity (units)')

    await user.clear(capacity)
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(await within(dialog).findByText('Enter a whole number of units')).toBeInTheDocument()

    await user.type(capacity, '2.5')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(await within(dialog).findByText('Enter a whole number of units')).toBeInTheDocument()
    expect(backend.sent('PUT', WHEELS_PATH)).toHaveLength(0)
  })

  it('puts field errors from the API on the matching fields', async () => {
    const user = userEvent.setup()
    await renderPage({
      ...demoRoutes(),
      [`PUT ${WHEELS_PATH}`]: () =>
        problem(400, 'Validation failed', 'One or more fields are invalid.', [
          { field: 'capacity', message: 'must be less than or equal to 100000' },
          { field: 'name', message: 'must not be blank' },
        ]),
    })
    const dialog = await openEdit(user)

    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText('must be less than or equal to 100000')).toBeInTheDocument()
    expect(within(dialog).getByText('must not be blank')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Capacity (units)')).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).queryByText('Validation failed')).not.toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('explains a stale version and reloads the latest values on request', async () => {
    const user = userEvent.setup()
    const latest = { ...wheels, capacity: 9, version: 4 }
    let stale = true
    const backend = await renderPage({
      ...demoRoutes(),
      [`PUT ${WHEELS_PATH}`]: ({ body }) =>
        stale
          ? problem(409, 'Conflict', 'The record was changed.', undefined, 'STALE_VERSION')
          : jsonResponse({ ...latest, ...(body as object), version: 5 }),
      [`GET ${WHEELS_PATH}`]: () => jsonResponse(latest),
    })
    const dialog = await openEdit(user)

    await user.clear(within(dialog).getByLabelText('Capacity (units)'))
    await user.type(within(dialog).getByLabelText('Capacity (units)'), '7')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    const alert = await within(dialog).findByRole('alert')
    expect(within(alert).getByText('This pool was changed after you opened it')).toBeInTheDocument()

    await user.click(within(alert).getByRole('button', { name: 'Reload latest values' }))
    await waitFor(() => {
      expect(within(dialog).getByLabelText('Capacity (units)')).toHaveValue(9)
    })
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()

    stale = false
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(backend.sent('PUT', WHEELS_PATH).map((request) => (request.body as { version: number }).version)).toEqual(
      [0, 4],
    )
  })

  it('shows why a required pool cannot be deactivated, without offering a reload', async () => {
    const user = userEvent.setup()
    const guard = 'This pool is required by stage Polishing of the active workflow and cannot be deactivated'
    await renderPage({ ...demoRoutes(), [`PUT ${WHEELS_PATH}`]: () => problem(409, 'Conflict', guard) })
    const dialog = await openEdit(user)

    await user.selectOptions(within(dialog).getByLabelText('Status'), 'INACTIVE')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText(guard)).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Reload latest values' })).not.toBeInTheDocument()
  })

  it('closes without saving on Cancel', async () => {
    const user = userEvent.setup()
    const backend = await renderPage()
    const dialog = await openEdit(user)

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(backend.sent('PUT', WHEELS_PATH)).toHaveLength(0)
  })
})

describe('availability of a pool', () => {
  it('lists recorded windows and effective capacity in factory time', async () => {
    const user = userEvent.setup()
    await renderPage()

    const dialog = await openAvailability(user)

    expect(within(dialog).getByText('−2 units')).toBeInTheDocument()
    // 03:30Z to 12:30Z is 09:00 to 18:00 in Asia/Kolkata.
    expect(within(dialog).getAllByText(/Wed, 7 Jan 2026, 09:00/).length).toBeGreaterThan(0)
    expect(within(dialog).getAllByText(/Wed, 7 Jan 2026, 18:00/).length).toBeGreaterThan(0)
    expect(within(dialog).getByText('4 of 6 units')).toBeInTheDocument()
  })

  it('says the pool is at base capacity when it has no windows', async () => {
    const user = userEvent.setup()
    await renderPage({
      ...demoRoutes(),
      [`GET ${WHEELS_PATH}/availability`]: () =>
        jsonResponse({ resourceId: wheels.id, baseCapacity: 6, from: null, to: null, windows: [], timeline: [] }),
    })

    await user.click(screen.getByRole('button', { name: 'Availability of Polishing wheels' }))

    expect(await screen.findByText(/No capacity windows are recorded/)).toBeInTheDocument()
  })

  it('requires every field, a non-zero change, and an end after the start', async () => {
    const user = userEvent.setup()
    const backend = await renderPage()
    const dialog = await openAvailability(user)

    await user.click(within(dialog).getByRole('button', { name: 'Record window' }))
    expect(await within(dialog).findByText('Enter when the window starts')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter when the window ends')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a whole number of units')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a reason')).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('Starts'), { target: { value: '2026-01-10T14:00' } })
    fireEvent.change(within(dialog).getByLabelText('Ends'), { target: { value: '2026-01-10T09:00' } })
    await user.type(within(dialog).getByLabelText('Capacity change (units)'), '0')
    await user.type(within(dialog).getByLabelText('Reason'), 'Backwards')
    await user.click(within(dialog).getByRole('button', { name: 'Record window' }))

    expect(await within(dialog).findByText('The end must be after the start')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a change other than zero')).toBeInTheDocument()
    expect(backend.sent('POST', `${WHEELS_PATH}/availability`)).toHaveLength(0)
  })

  it('sends the window as UTC instants converted from factory local time', async () => {
    const user = userEvent.setup()
    const backend = await renderPage({
      ...demoRoutes(),
      [`POST ${WHEELS_PATH}/availability`]: ({ body }) =>
        jsonResponse(
          { id: '11111111-1111-4111-8111-111111111111', createdAt: '2026-11-14T06:40:00Z', ...(body as object) },
          { status: 201 },
        ),
    })
    const dialog = await openAvailability(user)

    fireEvent.change(within(dialog).getByLabelText('Starts'), { target: { value: '2026-01-10T09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Ends'), { target: { value: '2026-01-10T13:00' } })
    await user.type(within(dialog).getByLabelText('Capacity change (units)'), '-1')
    await user.type(within(dialog).getByLabelText('Reason'), ' Wheel repair ')
    await user.click(within(dialog).getByRole('button', { name: 'Record window' }))

    await waitFor(() => {
      expect(backend.sent('POST', `${WHEELS_PATH}/availability`)).toHaveLength(1)
    })
    expect(backend.sent('POST', `${WHEELS_PATH}/availability`)[0]?.body).toEqual({
      startsAt: '2026-01-10T03:30:00Z',
      endsAt: '2026-01-10T07:30:00Z',
      capacityDelta: -1,
      reason: 'Wheel repair',
    })
    // The form is cleared and the windows are re-read.
    await waitFor(() => {
      expect(within(dialog).getByLabelText('Reason')).toHaveValue('')
    })
    expect(backend.sent('GET', `${WHEELS_PATH}/availability`).length).toBeGreaterThan(1)
  })

  it('shows an API failure without losing what was typed', async () => {
    const user = userEvent.setup()
    await renderPage({
      ...demoRoutes(),
      [`POST ${WHEELS_PATH}/availability`]: () =>
        problem(400, 'Validation failed', 'One or more fields are invalid.', [
          { field: 'endsAt', message: 'must be after startsAt' },
        ]),
    })
    const dialog = await openAvailability(user)

    fireEvent.change(within(dialog).getByLabelText('Starts'), { target: { value: '2026-01-10T09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Ends'), { target: { value: '2026-01-10T13:00' } })
    await user.type(within(dialog).getByLabelText('Capacity change (units)'), '-1')
    await user.type(within(dialog).getByLabelText('Reason'), 'Repair')
    await user.click(within(dialog).getByRole('button', { name: 'Record window' }))

    expect(await within(dialog).findByText('must be after startsAt')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Reason')).toHaveValue('Repair')
  })
})

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ChangeDialog } from '@/features/scenarios/ChangeDialog'
import { factorySchema } from '@/lib/schemas'
import { scenarioSchema } from '@/lib/scenarioSchemas'
import { demoRoutes, fixtures, mockBackend, poolNamed, problem, SCENARIO_ID, stageNamed } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

const factory = factorySchema.parse(fixtures.factory)
const scenario = scenarioSchema.parse(fixtures.scenario)
const CHANGES = `/api/scenarios/${SCENARIO_ID}/changes`
const savedChange = fixtures.scenario.changes[0]

function setup(extra: Parameters<typeof mockBackend>[0] = {}) {
  const backend = mockBackend({
    ...demoRoutes(),
    [`POST ${CHANGES}`]: () => jsonResponse(savedChange, { status: 201 }),
    ...extra,
  })
  const onClose = vi.fn()
  renderWithProviders(<ChangeDialog factory={factory} scenario={scenario} onClose={onClose} />)
  return { backend, onClose, user: userEvent.setup() }
}

async function chooseType(user: ReturnType<typeof userEvent.setup>, type: string) {
  const dialog = await screen.findByRole('dialog', { name: 'Add a change' })
  await user.selectOptions(await within(dialog).findByLabelText('Kind of change'), type)
  return dialog
}

async function submit(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) {
  await user.click(within(dialog).getByRole('button', { name: 'Add change' }))
}

describe('ChangeDialog resource capacity', () => {
  it('blocks a negative absolute capacity', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_CAPACITY')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Polishing wheels').id)
    await user.selectOptions(within(dialog).getByLabelText('How to change it'), 'ABSOLUTE')
    await user.type(within(dialog).getByLabelText('Units'), '-1')
    await submit(user, dialog)

    expect(await within(dialog).findByText('Capacity cannot be negative')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Units')).toHaveAttribute('aria-invalid', 'true')
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })

  it('blocks a percentage below -100', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_CAPACITY')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Laser saws').id)
    await user.selectOptions(within(dialog).getByLabelText('How to change it'), 'PERCENT')
    await user.type(within(dialog).getByLabelText('Percentage'), '-150')
    await submit(user, dialog)

    expect(await within(dialog).findByText('A pool cannot shrink by more than 100%')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })

  it('posts a valid change and closes', async () => {
    const { backend, onClose, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_CAPACITY')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Polishing wheels').id)
    await user.type(within(dialog).getByLabelText('Units to add (negative removes)'), '2')
    await submit(user, dialog)

    await waitFor(() => {
      expect(onClose).toHaveBeenCalled()
    })
    expect(backend.last('POST', CHANGES).body).toEqual({
      changeType: 'RESOURCE_CAPACITY',
      payload: { resourceId: poolNamed('Polishing wheels').id, mode: 'DELTA', value: 2 },
    })
  })

  it('shows a server error on the field it names, and a broken later change as a banner', async () => {
    let call = 0
    const { user } = setup({
      [`POST ${CHANGES}`]: () => {
        call += 1
        return call === 1
          ? problem(400, 'Validation failed', 'One or more fields are invalid.', [
              { field: 'payload.value', message: 'must be at most 100000 units' },
            ])
          : problem(400, 'Validation failed', 'One or more fields are invalid.', [
              { field: 'changes[2].payload.shifts', message: 'must not overlap the factory’s shifts' },
            ])
      },
    })
    const dialog = await chooseType(user, 'RESOURCE_CAPACITY')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Polishing wheels').id)
    await user.type(within(dialog).getByLabelText('Units to add (negative removes)'), '200000')
    await submit(user, dialog)
    expect(await within(dialog).findByText('must be at most 100000 units')).toBeInTheDocument()

    await submit(user, dialog)
    const banner = await within(dialog).findByText(/changes\[2\]\.payload\.shifts: must not overlap/)
    expect(banner.closest('[role="alert"]')).not.toBeNull()
  })
})

describe('ChangeDialog other change types', () => {
  it('needs at least one unavailable unit', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_UNAVAILABLE')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Polishers').id)
    await user.type(within(dialog).getByLabelText('Units unavailable'), '0')
    await submit(user, dialog)

    expect(await within(dialog).findByText('Enter a whole number of at least 1')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })

  it('posts unavailability from the start to the end of the simulation when no dates are given', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_UNAVAILABLE')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Polishers').id)
    await user.type(within(dialog).getByLabelText('Units unavailable'), '5')
    await submit(user, dialog)

    await waitFor(() => {
      expect(backend.sent('POST', CHANGES)).toHaveLength(1)
    })
    expect(backend.last('POST', CHANGES).body).toEqual({
      changeType: 'RESOURCE_UNAVAILABLE',
      payload: { resourceId: poolNamed('Polishers').id, units: 5 },
    })
  })

  it('refuses downtime that ends before it starts, and converts factory time when valid', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_DOWNTIME')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Laser saws').id)
    fireEvent.change(within(dialog).getByLabelText('From (Asia/Kolkata)'), { target: { value: '2026-11-12T14:00' } })
    fireEvent.change(within(dialog).getByLabelText('Until (Asia/Kolkata)'), { target: { value: '2026-11-12T10:00' } })
    await submit(user, dialog)
    expect(await within(dialog).findByText('The end must be after the start')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)

    fireEvent.change(within(dialog).getByLabelText('Until (Asia/Kolkata)'), { target: { value: '2026-11-12T18:00' } })
    await submit(user, dialog)
    await waitFor(() => {
      expect(backend.sent('POST', CHANGES)).toHaveLength(1)
    })
    expect(backend.last('POST', CHANGES).body).toEqual({
      changeType: 'RESOURCE_DOWNTIME',
      payload: { resourceId: poolNamed('Laser saws').id, from: '2026-11-12T08:30:00Z', to: '2026-11-12T12:30:00Z' },
    })
  })

  it('refuses a window that ends before the scenario starts', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'RESOURCE_DOWNTIME')
    await user.selectOptions(within(dialog).getByLabelText('Pool'), poolNamed('Laser saws').id)
    fireEvent.change(within(dialog).getByLabelText('From (Asia/Kolkata)'), { target: { value: '2026-11-01T10:00' } })
    fireEvent.change(within(dialog).getByLabelText('Until (Asia/Kolkata)'), { target: { value: '2026-11-01T12:00' } })
    await submit(user, dialog)

    expect(await within(dialog).findByText(/must be after the scenario’s start/)).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })

  it('refuses a demand change of zero and posts a valid one', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'DEMAND_VOLUME')
    await user.type(within(dialog).getByLabelText('Change in demand (%)'), '0')
    await submit(user, dialog)
    expect(await within(dialog).findByText('A change of 0% has no effect')).toBeInTheDocument()

    await user.clear(within(dialog).getByLabelText('Change in demand (%)'))
    await user.type(within(dialog).getByLabelText('Change in demand (%)'), '20')
    await submit(user, dialog)
    await waitFor(() => {
      expect(backend.sent('POST', CHANGES)).toHaveLength(1)
    })
    expect(backend.last('POST', CHANGES).body).toEqual({ changeType: 'DEMAND_VOLUME', payload: { percent: 20 } })
  })

  it('refuses a rate of zero or less', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'PROCESSING_RATE')
    await user.selectOptions(within(dialog).getByLabelText('Stage'), stageNamed('Polishing').id)
    await user.selectOptions(within(dialog).getByLabelText('How to change it'), 'ABSOLUTE')
    await user.type(within(dialog).getByLabelText('Stones per hour per slot'), '0')
    await submit(user, dialog)

    expect(await within(dialog).findByText('The rate must be greater than zero')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })

  it('needs at least one day for a schedule, then posts one shift per chosen day', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'WORKING_SCHEDULE')
    await submit(user, dialog)
    expect(await within(dialog).findByText('Choose at least one day')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)

    await user.click(within(dialog).getByLabelText('Saturday'))
    await user.click(within(dialog).getByLabelText('Monday'))
    await submit(user, dialog)
    await waitFor(() => {
      expect(backend.sent('POST', CHANGES)).toHaveLength(1)
    })
    expect(backend.last('POST', CHANGES).body).toEqual({
      changeType: 'WORKING_SCHEDULE',
      payload: {
        mode: 'ADD',
        shifts: [
          { dayOfWeek: 1, startTime: '18:00', endTime: '22:00', breaks: [] },
          { dayOfWeek: 6, startTime: '18:00', endTime: '22:00', breaks: [] },
        ],
      },
    })
  })

  it('refuses a shift that ends before it starts', async () => {
    const { backend, user } = setup()
    const dialog = await chooseType(user, 'WORKING_SCHEDULE')
    await user.click(within(dialog).getByLabelText('Sunday'))
    fireEvent.change(within(dialog).getByLabelText('Shift ends'), { target: { value: '17:00' } })
    await submit(user, dialog)

    expect(await within(dialog).findByText('The shift must end after it starts, on the same day')).toBeInTheDocument()
    expect(backend.sent('POST', CHANGES)).toHaveLength(0)
  })
})

describe('ChangeDialog editing', () => {
  it('opens an existing change with its values and its type locked, and replaces it', async () => {
    const user = userEvent.setup()
    const change = scenario.changes[0]
    if (!change) throw new Error('The fixture scenario has a change')
    const backend = mockBackend({
      ...demoRoutes(),
      [`PUT ${CHANGES}/${change.id}`]: () => jsonResponse(savedChange),
    })
    renderWithProviders(<ChangeDialog factory={factory} scenario={scenario} change={change} onClose={vi.fn()} />)

    const dialog = await screen.findByRole('dialog', { name: 'Edit change 1' })
    expect(await within(dialog).findByLabelText('Kind of change')).toBeDisabled()
    expect(within(dialog).getByLabelText('Monday')).toBeChecked()
    expect(within(dialog).getByLabelText('Sunday')).not.toBeChecked()
    expect(within(dialog).getByLabelText('Shift starts')).toHaveValue('18:00')

    await user.click(within(dialog).getByLabelText('Sunday'))
    await user.click(within(dialog).getByRole('button', { name: 'Save change' }))
    await waitFor(() => {
      expect(backend.sent('PUT', `${CHANGES}/${change.id}`)).toHaveLength(1)
    })
    const body = backend.last('PUT', `${CHANGES}/${change.id}`).body as { payload: { shifts: unknown[] } }
    expect(body.payload.shifts).toHaveLength(7)
  })
})

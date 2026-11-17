import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ComponentProps, type ReactNode, type SubmitEvent } from 'react'
import { useForm, useWatch, type FieldValues, type Path, type UseFormSetError } from 'react-hook-form'
import { z } from 'zod'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/form'
import { ProblemBanner } from '@/features/scenarios/ProblemBanner'
import {
  applyServerErrors,
  asApiError,
  CHANGE_TYPE_HINTS,
  CHANGE_TYPE_LABELS,
} from '@/features/scenarios/scenarioShared'
import { api, type ApiError } from '@/lib/api'
import { DAY_NAMES, formatDateTimeInZone, formatDecimal, instantToZonedLocal, zonedLocalToInstant } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, Resource, Stage } from '@/lib/schemas'
import { changeTypeSchema, type ChangeInput, type ChangeType, type Scenario, type ScenarioChange } from '@/lib/scenarioSchemas'
import { describedBy } from '@/lib/utils'

/* ---- Shared ------------------------------------------------------------------------------------ */

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/

function issue(context: z.RefinementCtx, path: string, message: string) {
  context.addIssue({ code: 'custom', path: [path], message })
}

function isWhole(value: number): boolean {
  return Number.isInteger(value)
}

/** `aria-*` attributes that tie a control to its `Field` hint and error. */
function aria(id: string, error: string | undefined, hint = false) {
  return {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy(id, hint, Boolean(error)),
  } as const
}

/** A payload value for a text input: numbers and strings as written, anything else empty. */
function text(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : ''
}

interface FormContext {
  factory: Factory
  scenario: Scenario
  change: ScenarioChange | undefined
  pools: readonly Resource[]
  stages: readonly Stage[]
  /** The change-type picker, rendered at the top of the form body. */
  typePicker: ReactNode
  onDone: () => void
}

/**
 * Saves a change (add, or replace when editing) and maps server errors: `payload.<field>` onto the
 * form field it names, anything else (including a later change it would break) into a banner.
 */
function useSaveChange<T extends FieldValues>(
  context: FormContext,
  setError: UseFormSetError<T>,
  fieldMap: Record<string, Path<T>>,
) {
  const queryClient = useQueryClient()
  const [failure, setFailure] = useState<{ error: ApiError; messages: string[] } | null>(null)
  const mutation = useMutation({
    mutationFn: (body: ChangeInput) =>
      context.change
        ? api.scenarios.replaceChange(context.scenario.id, context.change.id, body)
        : api.scenarios.addChange(context.scenario.id, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
      context.onDone()
    },
    onError: (error) => {
      const apiError = asApiError(error, 'The change could not be saved')
      const prefixed = Object.fromEntries(
        Object.entries(fieldMap).map(([field, target]) => [`payload.${field}`, target]),
      ) as Record<string, Path<T>>
      const { banner, messages } = applyServerErrors(apiError, setError, prefixed)
      setFailure(banner ? { error: banner, messages } : null)
    },
  })
  const save = (changeType: ChangeType, payload: Record<string, unknown>) => {
    setFailure(null)
    mutation.mutate({ changeType, payload })
  }
  return { save, saving: mutation.isPending, failure }
}

function FormShell({
  context,
  onSubmit,
  saving,
  failure,
  children,
}: {
  context: FormContext
  onSubmit: (event: SubmitEvent<HTMLFormElement>) => void
  saving: boolean
  failure: { error: ApiError; messages: string[] } | null
  children: ReactNode
}) {
  return (
    <form onSubmit={onSubmit} noValidate className="flex min-h-0 flex-col">
      <DialogBody className="flex flex-col gap-4">
        {context.typePicker}
        {failure ? (
          <ProblemBanner error={failure.error}>
            {failure.messages.length > 0 ? (
              <ul className="mt-1 list-disc pl-4 text-muted-foreground">
                {failure.messages.map((message) => (
                  <li key={message}>{message}</li>
                ))}
              </ul>
            ) : null}
          </ProblemBanner>
        ) : null}
        {children}
      </DialogBody>
      <DialogFooter>
        <DialogClose asChild>
          <Button variant="outline">Cancel</Button>
        </DialogClose>
        <Button type="submit" disabled={saving}>
          {saving ? 'Saving' : context.change ? 'Save change' : 'Add change'}
        </Button>
      </DialogFooter>
    </form>
  )
}

function PoolSelect({ pools, ...props }: { pools: readonly Resource[] } & ComponentProps<'select'>) {
  const machines = pools.filter((pool) => pool.kind === 'MACHINE')
  const employees = pools.filter((pool) => pool.kind === 'EMPLOYEE')
  return (
    <Select {...props}>
      <option value="">Choose a pool</option>
      <optgroup label="Machines">
        {machines.map((pool) => (
          <option key={pool.id} value={pool.id}>
            {pool.name} ({pool.capacity})
          </option>
        ))}
      </optgroup>
      <optgroup label="Employees">
        {employees.map((pool) => (
          <option key={pool.id} value={pool.id}>
            {pool.name} ({pool.capacity})
          </option>
        ))}
      </optgroup>
    </Select>
  )
}

/** Converts a factory wall-clock value to an instant, or null when empty or invalid. */
function toInstant(local: string, timeZone: string): string | null {
  return local === '' ? null : zonedLocalToInstant(local, timeZone)
}

/* ---- Resource capacity -------------------------------------------------------------------------- */

const capacitySchema = z
  .object({
    resourceId: z.string().min(1, 'Choose a pool'),
    mode: z.enum(['ABSOLUTE', 'DELTA', 'PERCENT']),
    value: z.string().trim().min(1, 'Enter a value'),
  })
  .superRefine((values, context) => {
    const value = Number(values.value)
    if (!Number.isFinite(value)) {
      issue(context, 'value', 'Enter a number')
      return
    }
    if (values.mode === 'ABSOLUTE') {
      if (value < 0) issue(context, 'value', 'Capacity cannot be negative')
      else if (!isWhole(value)) issue(context, 'value', 'Enter a whole number of units')
    } else if (values.mode === 'DELTA') {
      if (!isWhole(value)) issue(context, 'value', 'Enter a whole number of units')
      else if (value === 0) issue(context, 'value', 'A change of zero units has no effect')
    } else if (value < -100) {
      issue(context, 'value', 'A pool cannot shrink by more than 100%')
    } else if (value === 0) {
      issue(context, 'value', 'A change of 0% has no effect')
    }
  })

function CapacityForm({ context }: { context: FormContext }) {
  const initial = context.change?.payload
  const form = useForm<z.infer<typeof capacitySchema>>({
    resolver: zodResolver(capacitySchema),
    defaultValues: {
      resourceId: text(initial?.resourceId),
      mode: (initial?.mode as 'ABSOLUTE' | 'DELTA' | 'PERCENT' | undefined) ?? 'DELTA',
      value: text(initial?.value),
    },
  })
  const { errors } = form.formState
  const mode = useWatch({ control: form.control, name: 'mode' })
  const { save, saving, failure } = useSaveChange(context, form.setError, {
    resourceId: 'resourceId',
    mode: 'mode',
    value: 'value',
  })
  const submit = form.handleSubmit((values) => {
    save('RESOURCE_CAPACITY', { resourceId: values.resourceId, mode: values.mode, value: Number(values.value) })
  })
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      <Field htmlFor="change-pool" label="Pool" error={errors.resourceId?.message}>
        <PoolSelect pools={context.pools} {...aria('change-pool', errors.resourceId?.message)} {...form.register('resourceId')} />
      </Field>
      <Field htmlFor="change-mode" label="How to change it" error={errors.mode?.message}>
        <Select {...aria('change-mode', errors.mode?.message)} {...form.register('mode')}>
          <option value="DELTA">Add or remove units</option>
          <option value="ABSOLUTE">Set the number of units</option>
          <option value="PERCENT">Change by a percentage</option>
        </Select>
      </Field>
      <Field
        htmlFor="change-value"
        label={mode === 'PERCENT' ? 'Percentage' : mode === 'ABSOLUTE' ? 'Units' : 'Units to add (negative removes)'}
        hint={
          mode === 'PERCENT'
            ? 'Rounded down to whole units, so capacity is never overstated: 4 machines at −30% become 2.'
            : 'Whole units. Capacity never goes below zero.'
        }
        error={errors.value?.message}
      >
        <Input inputMode="decimal" autoComplete="off" {...aria('change-value', errors.value?.message, true)} {...form.register('value')} />
      </Field>
    </FormShell>
  )
}

/* ---- Resource unavailable ----------------------------------------------------------------------- */

function windowSchema(timeZone: string, forkAt: string, bothRequired: boolean) {
  return z
    .object({
      resourceId: z.string().min(1, 'Choose a pool'),
      units: z.string(),
      fromLocal: z.string(),
      toLocal: z.string(),
    })
    .superRefine((values, context) => {
      if (!bothRequired) {
        const units = Number(values.units)
        if (values.units.trim() === '') issue(context, 'units', 'Enter how many units are unavailable')
        else if (!Number.isFinite(units) || !isWhole(units) || units < 1) issue(context, 'units', 'Enter a whole number of at least 1')
      }
      const from = toInstant(values.fromLocal, timeZone)
      const to = toInstant(values.toLocal, timeZone)
      if (bothRequired && values.fromLocal === '') issue(context, 'fromLocal', 'Enter when it starts')
      if (bothRequired && values.toLocal === '') issue(context, 'toLocal', 'Enter when it ends')
      if (values.fromLocal !== '' && from === null) issue(context, 'fromLocal', 'Enter a valid date and time')
      if (values.toLocal !== '' && to === null) issue(context, 'toLocal', 'Enter a valid date and time')
      if (from !== null && to !== null && Date.parse(to) <= Date.parse(from)) {
        issue(context, 'toLocal', 'The end must be after the start')
      } else if (to !== null && Date.parse(to) <= Date.parse(forkAt)) {
        issue(context, 'toLocal', 'The end must be after the scenario’s start, or it could not affect the simulation')
      }
    })
}

type WindowValues = z.infer<ReturnType<typeof windowSchema>>

function WindowForm({ context, kind }: { context: FormContext; kind: 'RESOURCE_UNAVAILABLE' | 'RESOURCE_DOWNTIME' }) {
  const { timeZone } = context.factory
  const initial = context.change?.payload
  const downtime = kind === 'RESOURCE_DOWNTIME'
  const local = (value: unknown) => (typeof value === 'string' ? instantToZonedLocal(value, timeZone) : '')
  const form = useForm<WindowValues>({
    resolver: zodResolver(windowSchema(timeZone, context.scenario.forkAt, downtime)),
    defaultValues: {
      resourceId: text(initial?.resourceId),
      units: text(initial?.units),
      fromLocal: local(initial?.from),
      toLocal: local(initial?.to),
    },
  })
  const { errors } = form.formState
  const { save, saving, failure } = useSaveChange(context, form.setError, {
    resourceId: 'resourceId',
    units: 'units',
    from: 'fromLocal',
    to: 'toLocal',
  })
  const submit = form.handleSubmit((values) => {
    const from = toInstant(values.fromLocal, timeZone)
    const to = toInstant(values.toLocal, timeZone)
    save(kind, {
      resourceId: values.resourceId,
      ...(downtime ? {} : { units: Number(values.units) }),
      ...(from === null ? {} : { from }),
      ...(to === null ? {} : { to }),
    })
  })
  const fork = formatDateTimeInZone(context.scenario.forkAt, timeZone)
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      <Field htmlFor="change-pool" label="Pool" error={errors.resourceId?.message}>
        <PoolSelect pools={context.pools} {...aria('change-pool', errors.resourceId?.message)} {...form.register('resourceId')} />
      </Field>
      {downtime ? null : (
        <Field htmlFor="change-units" label="Units unavailable" hint="If more than the pool has, none are left." error={errors.units?.message}>
          <Input inputMode="numeric" autoComplete="off" {...aria('change-units', errors.units?.message, true)} {...form.register('units')} />
        </Field>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field
          htmlFor="change-from"
          label={`From (${timeZone})`}
          hint={downtime ? undefined : `Empty: from the scenario’s start, ${fork}.`}
          error={errors.fromLocal?.message}
        >
          <Input type="datetime-local" {...aria('change-from', errors.fromLocal?.message, !downtime)} {...form.register('fromLocal')} />
        </Field>
        <Field
          htmlFor="change-to"
          label={`Until (${timeZone})`}
          hint={downtime ? undefined : 'Empty: until the end of the simulation.'}
          error={errors.toLocal?.message}
        >
          <Input type="datetime-local" {...aria('change-to', errors.toLocal?.message, !downtime)} {...form.register('toLocal')} />
        </Field>
      </div>
    </FormShell>
  )
}

/* ---- Demand volume ------------------------------------------------------------------------------ */

const demandSchema = z
  .object({ percent: z.string().trim().min(1, 'Enter a percentage') })
  .superRefine((values, context) => {
    const percent = Number(values.percent)
    if (!Number.isFinite(percent)) issue(context, 'percent', 'Enter a number')
    else if (percent <= -100) issue(context, 'percent', 'Demand cannot fall by 100% or more')
    else if (percent === 0) issue(context, 'percent', 'A change of 0% has no effect')
    else if (percent > 500) issue(context, 'percent', 'At most +500%')
  })

function DemandForm({ context }: { context: FormContext }) {
  const form = useForm<z.infer<typeof demandSchema>>({
    resolver: zodResolver(demandSchema),
    defaultValues: { percent: text(context.change?.payload.percent) },
  })
  const { errors } = form.formState
  const { save, saving, failure } = useSaveChange(context, form.setError, { percent: 'percent' })
  const submit = form.handleSubmit((values) => {
    save('DEMAND_VOLUME', { percent: Number(values.percent) })
  })
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      <Field
        htmlFor="change-percent"
        label="Change in demand (%)"
        hint="Applies to every batch not yet started on its first stage, including orders that arrive later. Stones are not rounded."
        error={errors.percent?.message}
      >
        <Input inputMode="decimal" autoComplete="off" {...aria('change-percent', errors.percent?.message, true)} {...form.register('percent')} />
      </Field>
    </FormShell>
  )
}

/* ---- Processing rate ---------------------------------------------------------------------------- */

const rateSchema = z
  .object({
    stageId: z.string().min(1, 'Choose a stage'),
    mode: z.enum(['ABSOLUTE', 'PERCENT']),
    value: z.string().trim().min(1, 'Enter a value'),
  })
  .superRefine((values, context) => {
    const value = Number(values.value)
    if (!Number.isFinite(value)) issue(context, 'value', 'Enter a number')
    else if (values.mode === 'ABSOLUTE' && value <= 0) issue(context, 'value', 'The rate must be greater than zero')
    else if (values.mode === 'PERCENT' && value <= -100) issue(context, 'value', 'A rate cannot fall by 100% or more')
    else if (values.mode === 'PERCENT' && value === 0) issue(context, 'value', 'A change of 0% has no effect')
  })

function RateForm({ context }: { context: FormContext }) {
  const initial = context.change?.payload
  const form = useForm<z.infer<typeof rateSchema>>({
    resolver: zodResolver(rateSchema),
    defaultValues: {
      stageId: text(initial?.stageId),
      mode: (initial?.mode as 'ABSOLUTE' | 'PERCENT' | undefined) ?? 'PERCENT',
      value: text(initial?.value),
    },
  })
  const { errors } = form.formState
  const mode = useWatch({ control: form.control, name: 'mode' })
  const { save, saving, failure } = useSaveChange(context, form.setError, {
    stageId: 'stageId',
    mode: 'mode',
    value: 'value',
  })
  const submit = form.handleSubmit((values) => {
    save('PROCESSING_RATE', { stageId: values.stageId, mode: values.mode, value: Number(values.value) })
  })
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      <Field htmlFor="change-stage" label="Stage" error={errors.stageId?.message}>
        <Select {...aria('change-stage', errors.stageId?.message)} {...form.register('stageId')}>
          <option value="">Choose a stage</option>
          {context.stages.map((stage) => (
            <option key={stage.id} value={stage.id}>
              {stage.sequence}. {stage.name} ({formatDecimal(stage.stonesPerHour, 4)} stones/h per slot)
            </option>
          ))}
        </Select>
      </Field>
      <Field htmlFor="change-mode" label="How to change it" error={errors.mode?.message}>
        <Select {...aria('change-mode', errors.mode?.message)} {...form.register('mode')}>
          <option value="PERCENT">Change by a percentage</option>
          <option value="ABSOLUTE">Set the rate</option>
        </Select>
      </Field>
      <Field
        htmlFor="change-value"
        label={mode === 'PERCENT' ? 'Percentage' : 'Stones per hour per slot'}
        error={errors.value?.message}
      >
        <Input inputMode="decimal" autoComplete="off" {...aria('change-value', errors.value?.message)} {...form.register('value')} />
      </Field>
    </FormShell>
  )
}

/* ---- Stage delay -------------------------------------------------------------------------------- */

const delaySchema = z
  .object({
    stageId: z.string().min(1, 'Choose a stage'),
    percent: z.string().trim().min(1, 'Enter a percentage'),
  })
  .superRefine((values, context) => {
    const percent = Number(values.percent)
    if (!Number.isFinite(percent)) issue(context, 'percent', 'Enter a number')
    else if (percent <= -100) issue(context, 'percent', 'A waiting time cannot fall by 100% or more')
    else if (percent === 0) issue(context, 'percent', 'A change of 0% has no effect')
    else if (percent > 500) issue(context, 'percent', 'At most +500%')
  })

const KIND_LABELS = { PARKING: 'parking', WAITING: 'waiting', JOB_WORK: 'outside job work', WORKING: 'after processing' }

function DelayForm({ context }: { context: FormContext }) {
  const initial = context.change?.payload
  const form = useForm<z.infer<typeof delaySchema>>({
    resolver: zodResolver(delaySchema),
    defaultValues: { stageId: text(initial?.stageId), percent: text(initial?.percent) },
  })
  const { errors } = form.formState
  const { save, saving, failure } = useSaveChange(context, form.setError, { stageId: 'stageId', percent: 'percent' })
  const submit = form.handleSubmit((values) => {
    save('STAGE_DELAY', { stageId: values.stageId, percent: Number(values.percent) })
  })
  const delayed = context.stages.filter((stage) => stage.kind !== 'WORKING' || (stage.handlingDeciles?.length ?? 0) > 0)
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      <Field
        htmlFor="change-stage"
        label="Stage"
        hint={delayed.length === 0 ? 'No stage of this workflow has a measured waiting time.' : undefined}
        error={errors.stageId?.message}
      >
        <Select {...aria('change-stage', errors.stageId?.message)} {...form.register('stageId')}>
          <option value="">Choose a stage</option>
          {delayed.map((stage) => (
            <option key={stage.id} value={stage.id}>
              {stage.sequence}. {stage.name} ({KIND_LABELS[stage.kind]})
            </option>
          ))}
        </Select>
      </Field>
      <Field
        htmlFor="change-percent"
        label="Change in time held (%)"
        hint="Scales every measured duration at the stage: -50 halves it, 30 makes it 30% longer. Capacity is unchanged."
        error={errors.percent?.message}
      >
        <Input inputMode="decimal" autoComplete="off" {...aria('change-percent', errors.percent?.message, true)} {...form.register('percent')} />
      </Field>
    </FormShell>
  )
}

/* ---- Working schedule --------------------------------------------------------------------------- */

const scheduleSchema = z
  .object({
    mode: z.enum(['ADD', 'REPLACE']),
    days: z.array(z.string()).min(1, 'Choose at least one day'),
    startTime: z.string().regex(TIME, 'Enter a time such as 18:00'),
    endTime: z.string().regex(TIME, 'Enter a time such as 22:00'),
    breakStart: z.string(),
    breakEnd: z.string(),
  })
  .superRefine((values, context) => {
    if (TIME.test(values.startTime) && TIME.test(values.endTime) && values.endTime <= values.startTime) {
      issue(context, 'endTime', 'The shift must end after it starts, on the same day')
    }
    const hasBreak = values.breakStart !== '' || values.breakEnd !== ''
    if (!hasBreak) return
    if (!TIME.test(values.breakStart)) issue(context, 'breakStart', 'Enter both break times, or neither')
    else if (!TIME.test(values.breakEnd)) issue(context, 'breakEnd', 'Enter both break times, or neither')
    else if (values.breakEnd <= values.breakStart) issue(context, 'breakEnd', 'The break must end after it starts')
    else if (values.breakStart < values.startTime || values.breakEnd > values.endTime) {
      issue(context, 'breakStart', 'The break must lie inside the shift')
    }
  })

interface ShiftPayload {
  dayOfWeek: number
  startTime: string
  endTime: string
  breaks?: { startTime: string; endTime: string }[] | null
}

function ScheduleForm({ context }: { context: FormContext }) {
  const initial = context.change?.payload
  const shifts = (Array.isArray(initial?.shifts) ? initial.shifts : []) as ShiftPayload[]
  const first = shifts[0]
  const uniform = shifts.every(
    (shift) => shift.startTime === first?.startTime && shift.endTime === first.endTime && JSON.stringify(shift.breaks ?? []) === JSON.stringify(first.breaks ?? []),
  )
  const form = useForm<z.infer<typeof scheduleSchema>>({
    resolver: zodResolver(scheduleSchema),
    defaultValues: {
      mode: (initial?.mode as 'ADD' | 'REPLACE' | undefined) ?? 'ADD',
      days: shifts.map((shift) => String(shift.dayOfWeek)),
      startTime: first?.startTime ?? '18:00',
      endTime: first?.endTime ?? '22:00',
      breakStart: first?.breaks?.[0]?.startTime ?? '',
      breakEnd: first?.breaks?.[0]?.endTime ?? '',
    },
  })
  const { errors } = form.formState
  const { save, saving, failure } = useSaveChange(context, form.setError, {
    mode: 'mode',
    shifts: 'days',
  })
  const submit = form.handleSubmit((values) => {
    const breaks = values.breakStart === '' ? [] : [{ startTime: values.breakStart, endTime: values.breakEnd }]
    save('WORKING_SCHEDULE', {
      mode: values.mode,
      shifts: values.days
        .map(Number)
        .sort((a, b) => a - b)
        .map((day) => ({ dayOfWeek: day, startTime: values.startTime, endTime: values.endTime, breaks })),
    })
  })
  return (
    <FormShell context={context} onSubmit={(event) => void submit(event)} saving={saving} failure={failure}>
      {!uniform ? (
        <p role="note" className="rounded-md border border-status-warn/30 bg-status-warn-surface px-3 py-2 text-sm text-foreground">
          This change has shifts with different hours on different days. Saving here gives every chosen day the hours
          below.
        </p>
      ) : null}
      <Field htmlFor="change-mode" label="How to apply the shifts" error={errors.mode?.message}>
        <Select {...aria('change-mode', errors.mode?.message)} {...form.register('mode')}>
          <option value="ADD">Add them to the factory calendar</option>
          <option value="REPLACE">Replace the factory calendar with them</option>
        </Select>
      </Field>
      <fieldset className="flex flex-col gap-1.5" aria-describedby={errors.days ? 'change-days-error' : undefined}>
        <legend className="text-sm font-medium text-foreground">Days</legend>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {DAY_NAMES.map((day, index) => (
            <label key={day} className="flex items-center gap-1.5 text-sm text-foreground">
              <input type="checkbox" className="size-4" value={String(index + 1)} {...form.register('days')} />
              {day}
            </label>
          ))}
        </div>
        {errors.days?.message ? (
          <p id="change-days-error" role="alert" className="text-xs text-status-error">
            {errors.days.message}
          </p>
        ) : null}
      </fieldset>
      <div className="grid grid-cols-2 gap-4">
        <Field htmlFor="change-start" label="Shift starts" error={errors.startTime?.message}>
          <Input type="time" {...aria('change-start', errors.startTime?.message)} {...form.register('startTime')} />
        </Field>
        <Field htmlFor="change-end" label="Shift ends" error={errors.endTime?.message}>
          <Input type="time" {...aria('change-end', errors.endTime?.message)} {...form.register('endTime')} />
        </Field>
        <Field htmlFor="change-break-start" label="Break starts (optional)" error={errors.breakStart?.message}>
          <Input type="time" {...aria('change-break-start', errors.breakStart?.message)} {...form.register('breakStart')} />
        </Field>
        <Field htmlFor="change-break-end" label="Break ends (optional)" error={errors.breakEnd?.message}>
          <Input type="time" {...aria('change-break-end', errors.breakEnd?.message)} {...form.register('breakEnd')} />
        </Field>
      </div>
      <p className="text-xs text-subtle-foreground">
        Shifts apply to the whole factory, with every pool at its normal capacity. Added shifts may not overlap
        existing ones. Times are factory local time ({context.factory.timeZone}).
      </p>
    </FormShell>
  )
}

/* ---- Dialog ------------------------------------------------------------------------------------- */

function useActivePools(factory: Factory) {
  return useQuery({
    queryKey: queryKeys.resources.list(factory.id),
    queryFn: ({ signal }) => api.resources.list(factory.id, undefined, signal),
    select: (page) => page.items.filter((pool) => pool.status === 'ACTIVE'),
  })
}

function useActiveStages(factory: Factory) {
  const list = useQuery({
    queryKey: queryKeys.workflows.list(factory.id),
    queryFn: ({ signal }) => api.workflows.list(factory.id, signal),
  })
  const active = list.data?.items.find((workflow) => workflow.status === 'ACTIVE')
  const workflow = useQuery({
    queryKey: queryKeys.workflows.detail(active?.id ?? 'none'),
    queryFn: ({ signal }) => api.workflows.get(active?.id ?? '', signal),
    enabled: active !== undefined,
  })
  return {
    stages: workflow.data?.stages ?? [],
    pending: list.isPending || (active !== undefined && workflow.isPending),
  }
}

interface ChangeDialogProps {
  factory: Factory
  scenario: Scenario
  /** The change to edit; absent to add one. */
  change?: ScenarioChange
  onClose: () => void
}

/** Adds a change to a scenario, or edits one. Each change type has its own form and rules. */
export function ChangeDialog({ factory, scenario, change, onClose }: ChangeDialogProps) {
  const [type, setType] = useState<ChangeType>(change?.changeType ?? 'RESOURCE_CAPACITY')
  const pools = useActivePools(factory)
  const { stages, pending } = useActiveStages(factory)

  const typePicker = (
    <Field htmlFor="change-type" label="Kind of change" hint={CHANGE_TYPE_HINTS[type]}>
      <Select
        id="change-type"
        value={type}
        disabled={change !== undefined}
        aria-describedby="change-type-hint"
        onChange={(event) => {
          setType(changeTypeSchema.parse(event.target.value))
        }}
      >
        {changeTypeSchema.options.map((option) => (
          <option key={option} value={option}>
            {CHANGE_TYPE_LABELS[option]}
          </option>
        ))}
      </Select>
    </Field>
  )
  const context: FormContext = {
    factory,
    scenario,
    change,
    pools: pools.data ?? [],
    stages,
    typePicker,
    onDone: onClose,
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{change ? `Edit change ${change.ordinal}` : 'Add a change'}</DialogTitle>
          <DialogDescription>
            {change
              ? 'The kind of a change cannot be altered; delete it and add a new one instead.'
              : `Changes apply in order, after the ones already in “${scenario.name}”.`}
          </DialogDescription>
        </DialogHeader>
        {pools.isPending || pending ? (
          <DialogBody>
            <p role="status" className="text-sm text-muted-foreground">
              Loading pools and stages
            </p>
          </DialogBody>
        ) : (
          <TypeForm key={type} type={type} context={context} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function TypeForm({ type, context }: { type: ChangeType; context: FormContext }) {
  switch (type) {
    case 'RESOURCE_CAPACITY':
      return <CapacityForm context={context} />
    case 'RESOURCE_UNAVAILABLE':
      return <WindowForm context={context} kind="RESOURCE_UNAVAILABLE" />
    case 'RESOURCE_DOWNTIME':
      return <WindowForm context={context} kind="RESOURCE_DOWNTIME" />
    case 'DEMAND_VOLUME':
      return <DemandForm context={context} />
    case 'PROCESSING_RATE':
      return <RateForm context={context} />
    case 'WORKING_SCHEDULE':
      return <ScheduleForm context={context} />
    case 'STAGE_DELAY':
      return <DelayForm context={context} />
  }
}

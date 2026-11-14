import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'

import { ErrorState } from '@/components/ErrorState'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, Input } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { api, ApiError } from '@/lib/api'
import { formatDateTimeInZone, formatInteger, zonedLocalToInstant } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { CapacityTimeline, Resource } from '@/lib/schemas'
import { describedBy } from '@/lib/utils'

/** `datetime-local` values are wall-clock times in the factory's zone; see {@link zonedLocalToInstant}. */
function buildFormSchema(timeZone: string) {
  return z
    .object({
      startsAt: z.string().min(1, 'Enter when the window starts'),
      endsAt: z.string().min(1, 'Enter when the window ends'),
      capacityDelta: z
        .number({ error: 'Enter a whole number of units' })
        .int('Enter a whole number of units')
        .min(-100_000, 'At least -100,000')
        .max(100_000, 'At most 100,000')
        .refine((value) => value !== 0, 'Enter a change other than zero'),
      reason: z.string().trim().min(1, 'Enter a reason').max(200, 'At most 200 characters'),
    })
    .superRefine((values, context) => {
      const start = zonedLocalToInstant(values.startsAt, timeZone)
      const end = zonedLocalToInstant(values.endsAt, timeZone)
      if (values.startsAt !== '' && start === null) {
        context.addIssue({ code: 'custom', path: ['startsAt'], message: 'Enter a valid date and time' })
      }
      if (values.endsAt !== '' && end === null) {
        context.addIssue({ code: 'custom', path: ['endsAt'], message: 'Enter a valid date and time' })
      }
      if (start !== null && end !== null && end <= start) {
        context.addIssue({ code: 'custom', path: ['endsAt'], message: 'The end must be after the start' })
      }
    })
}
type FormValues = z.infer<ReturnType<typeof buildFormSchema>>
const FIELDS: readonly (keyof FormValues)[] = ['startsAt', 'endsAt', 'capacityDelta', 'reason']

function signed(value: number): string {
  return value > 0 ? `+${formatInteger(value)}` : `−${formatInteger(Math.abs(value))}`
}

function Windows({ data, timeZone }: { data: CapacityTimeline; timeZone: string }) {
  if (data.windows.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No capacity windows are recorded. The pool is at its base capacity of{' '}
        <span className="metric text-foreground">{formatInteger(data.baseCapacity)} units</span> at all times.
      </p>
    )
  }
  return (
    <div className="flex flex-col gap-4">
      <section aria-labelledby="windows-title">
        <h3 id="windows-title" className="text-xs font-medium text-muted-foreground">
          Recorded windows
        </h3>
        <ul className="mt-2 flex flex-col divide-y divide-border rounded-md border border-border">
          {data.windows.map((window) => (
            <li key={window.id} className="flex flex-col gap-0.5 px-3 py-2 text-sm">
              <span className="flex flex-wrap items-baseline justify-between gap-x-3">
                <span className="font-medium text-foreground">{window.reason}</span>
                <span className={window.capacityDelta < 0 ? 'metric text-status-warn' : 'metric text-status-ok'}>
                  {signed(window.capacityDelta)} units
                </span>
              </span>
              <span className="metric text-xs text-muted-foreground">
                <time dateTime={window.startsAt}>{formatDateTimeInZone(window.startsAt, timeZone)}</time>
                {' to '}
                <time dateTime={window.endsAt}>{formatDateTimeInZone(window.endsAt, timeZone)}</time>
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="timeline-title">
        <h3 id="timeline-title" className="text-xs font-medium text-muted-foreground">
          Effective capacity over the recorded period
        </h3>
        <ol className="mt-2 flex flex-col divide-y divide-border rounded-md border border-border">
          {data.timeline.map((segment) => (
            <li key={segment.from} className="flex flex-wrap items-baseline justify-between gap-x-3 px-3 py-2 text-sm">
              <span className="metric text-xs text-muted-foreground">
                {formatDateTimeInZone(segment.from, timeZone)} to {formatDateTimeInZone(segment.to, timeZone)}
              </span>
              <span className="metric text-foreground">
                {formatInteger(segment.capacity)} of {formatInteger(data.baseCapacity)} units
              </span>
            </li>
          ))}
        </ol>
        <p className="mt-1.5 text-xs text-subtle-foreground">
          Outside these periods the pool is at its base capacity of {formatInteger(data.baseCapacity)} units.
        </p>
      </section>
    </div>
  )
}

interface AvailabilityDialogProps {
  resource: Resource
  /** IANA zone of the factory; all times in this dialog are shown and entered in it. */
  timeZone: string
  onClose: () => void
}

export function AvailabilityDialog({ resource, timeZone, onClose }: AvailabilityDialogProps) {
  const queryClient = useQueryClient()
  const [failure, setFailure] = useState<ApiError | null>(null)

  const availability = useQuery({
    queryKey: queryKeys.resources.availability(resource.id),
    queryFn: ({ signal }) => api.resources.availability(resource.id, undefined, signal),
  })

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(buildFormSchema(timeZone)),
    defaultValues: { startsAt: '', endsAt: '', reason: '' },
  })

  const add = useMutation({
    mutationFn: (values: FormValues) =>
      api.resources.addAvailability(resource.id, {
        // The schema has already established that both convert.
        startsAt: zonedLocalToInstant(values.startsAt, timeZone) ?? '',
        endsAt: zonedLocalToInstant(values.endsAt, timeZone) ?? '',
        capacityDelta: values.capacityDelta,
        reason: values.reason,
      }),
    onSuccess: async () => {
      reset()
      await queryClient.invalidateQueries({ queryKey: queryKeys.resources.all })
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        setFailure(new ApiError({ kind: 'network', title: 'The window could not be recorded' }))
        return
      }
      const unmatched = error.fieldErrors.filter((fieldError) => {
        const field = FIELDS.find((name) => name === fieldError.field)
        if (!field) return true
        setError(field, { type: 'server', message: fieldError.message })
        return false
      })
      setFailure(error.fieldErrors.length > 0 && unmatched.length === 0 ? null : error)
    },
  })

  const submit = handleSubmit((values) => {
    setFailure(null)
    add.mutate(values)
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Availability of {resource.name}</DialogTitle>
          <DialogDescription>
            Dated changes to the pool&apos;s capacity, such as downtime or absence. Times are in factory local time (
            {timeZone}).
          </DialogDescription>
        </DialogHeader>

        <DialogBody className="flex flex-col gap-6">
          {availability.isPending ? (
            <div role="status" aria-label="Loading availability">
              <Skeleton className="h-20 w-full" />
            </div>
          ) : availability.isError ? (
            <ErrorState
              error={availability.error}
              onRetry={() => void availability.refetch()}
              retrying={availability.isFetching}
            />
          ) : (
            <Windows data={availability.data} timeZone={timeZone} />
          )}

          <form
            onSubmit={(event) => void submit(event)}
            noValidate
            aria-labelledby="record-window-title"
            className="flex flex-col gap-4 border-t border-border pt-4"
          >
            <div>
              <h3 id="record-window-title" className="text-sm font-semibold text-foreground">
                Record a capacity window
              </h3>
              <p className="mt-0.5 text-xs text-muted-foreground">
                Windows cannot be edited or removed afterwards. To undo one, record the opposite change over the same
                period.
              </p>
            </div>

            {failure ? (
              <div
                role="alert"
                className="flex items-start gap-2 rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5 text-sm"
              >
                <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
                <div className="min-w-0">
                  <p className="font-medium text-foreground">{failure.title}</p>
                  <p className="text-muted-foreground">{failure.detail ?? 'The request could not be completed.'}</p>
                </div>
              </div>
            ) : null}

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field htmlFor="window-start" label="Starts" hint="Factory local time." error={errors.startsAt?.message}>
                <Input
                  id="window-start"
                  type="datetime-local"
                  aria-invalid={errors.startsAt ? true : undefined}
                  aria-describedby={describedBy('window-start', true, Boolean(errors.startsAt))}
                  {...register('startsAt')}
                />
              </Field>
              <Field htmlFor="window-end" label="Ends" hint="Factory local time." error={errors.endsAt?.message}>
                <Input
                  id="window-end"
                  type="datetime-local"
                  aria-invalid={errors.endsAt ? true : undefined}
                  aria-describedby={describedBy('window-end', true, Boolean(errors.endsAt))}
                  {...register('endsAt')}
                />
              </Field>
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-[10rem_1fr]">
              <Field
                htmlFor="window-delta"
                label="Capacity change (units)"
                hint="Negative for units unavailable, for example -2."
                error={errors.capacityDelta?.message}
              >
                <Input
                  id="window-delta"
                  type="number"
                  step={1}
                  aria-invalid={errors.capacityDelta ? true : undefined}
                  aria-describedby={describedBy('window-delta', true, Boolean(errors.capacityDelta))}
                  {...register('capacityDelta', { valueAsNumber: true })}
                />
              </Field>
              <Field htmlFor="window-reason" label="Reason" error={errors.reason?.message}>
                <Input
                  id="window-reason"
                  autoComplete="off"
                  className="font-sans"
                  aria-invalid={errors.reason ? true : undefined}
                  aria-describedby={describedBy('window-reason', false, Boolean(errors.reason))}
                  {...register('reason')}
                />
              </Field>
            </div>

            <div className="flex justify-end">
              <Button type="submit" disabled={add.isPending}>
                {add.isPending ? 'Recording' : 'Record window'}
              </Button>
            </div>
          </form>
        </DialogBody>
      </DialogContent>
    </Dialog>
  )
}

import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
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
import { applyServerErrors, asApiError } from '@/features/scenarios/scenarioShared'
import { api, type ApiError } from '@/lib/api'
import { formatDateTimeInZone, instantToZonedLocal, zonedLocalToInstant } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'
import type { CreateScenarioInput, ScenarioSummary } from '@/lib/scenarioSchemas'
import { describedBy } from '@/lib/utils'

export type CreateMode =
  /** A new scenario; the user enters the fork. */
  | { kind: 'new' }
  /** A copy of an existing scenario's changes; the fork defaults to the parent's. */
  | { kind: 'branch'; parents: readonly ScenarioSummary[]; parentId?: string }
  /** A new scenario at an instant chosen elsewhere (the timeline). */
  | { kind: 'fixedFork'; forkAt: string }

interface CreateScenarioDialogProps {
  factory: Factory
  mode: CreateMode
  onClose: () => void
}

function schemaFor(mode: CreateMode, timeZone: string) {
  return z
    .object({
      name: z.string().trim().min(1, 'Enter a name').max(120, 'At most 120 characters'),
      description: z.string().max(2000, 'At most 2,000 characters'),
      forkLocal: z.string(),
      parentId: z.string(),
      includeFutureOrders: z.boolean(),
    })
    .superRefine((values, context) => {
      if (mode.kind === 'branch' && values.parentId === '') {
        context.addIssue({ code: 'custom', path: ['parentId'], message: 'Choose the scenario to branch from' })
      }
      const forkRequired = mode.kind === 'new'
      if (values.forkLocal === '') {
        if (forkRequired) context.addIssue({ code: 'custom', path: ['forkLocal'], message: 'Enter the moment to start from' })
        return
      }
      const instant = zonedLocalToInstant(values.forkLocal, timeZone)
      if (instant === null) {
        context.addIssue({ code: 'custom', path: ['forkLocal'], message: 'Enter a valid date and time' })
      } else if (Date.parse(instant) > Date.now()) {
        context.addIssue({
          code: 'custom',
          path: ['forkLocal'],
          message: 'Choose a moment in the past: there is no recorded state to start from in the future',
        })
      }
    })
}

type FormValues = z.infer<ReturnType<typeof schemaFor>>

/**
 * Creates a scenario from scratch, by branching, or at an instant picked on the timeline, then
 * opens it in Scenario Lab.
 */
export function CreateScenarioDialog({ factory, mode, onClose }: CreateScenarioDialogProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [failure, setFailure] = useState<ApiError | null>(null)
  const initialParent = mode.kind === 'branch' ? (mode.parentId ?? '') : ''

  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schemaFor(mode, factory.timeZone)),
    defaultValues: { name: '', description: '', forkLocal: '', parentId: initialParent, includeFutureOrders: true },
  })

  const create = useMutation({
    mutationFn: (body: CreateScenarioInput) => api.scenarios.create(body),
    onSuccess: async (scenario) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
      onClose()
      void navigate(`/scenarios/${scenario.id}`)
    },
    onError: (error) => {
      const apiError = asApiError(error, 'The scenario could not be created')
      if (apiError.status === 409) {
        setError('name', { type: 'server', message: 'Another scenario of this factory already has this name' })
        setFailure(null)
        return
      }
      const { banner } = applyServerErrors(apiError, setError, {
        name: 'name',
        description: 'description',
        forkAt: 'forkLocal',
        parentScenarioId: 'parentId',
      })
      setFailure(banner)
    },
  })

  const submit = handleSubmit((values) => {
    setFailure(null)
    const forkAt =
      mode.kind === 'fixedFork'
        ? mode.forkAt
        : values.forkLocal === ''
          ? undefined
          : (zonedLocalToInstant(values.forkLocal, factory.timeZone) ?? undefined)
    create.mutate({
      factoryId: factory.id,
      name: values.name.trim(),
      ...(values.description.trim() === '' ? {} : { description: values.description.trim() }),
      ...(forkAt === undefined ? {} : { forkAt }),
      ...(mode.kind === 'branch' ? { parentScenarioId: values.parentId } : { includeFutureOrders: values.includeFutureOrders }),
    })
  })

  const parentId = useWatch({ control, name: 'parentId' })
  const parent = mode.kind === 'branch' ? mode.parents.find((candidate) => candidate.id === parentId) : undefined
  const title = mode.kind === 'branch' ? 'Branch a scenario' : 'New scenario'
  const description =
    mode.kind === 'branch'
      ? 'The new scenario starts with a copy of the chosen scenario’s changes. Later edits to either one do not affect the other.'
      : mode.kind === 'fixedFork'
        ? `The scenario starts from the factory as recorded at ${formatDateTimeInZone(mode.forkAt, factory.timeZone)}.`
        : 'A scenario starts from the factory as recorded at a moment you choose. Add changes to it afterwards.'

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <form onSubmit={(event) => void submit(event)} noValidate className="flex min-h-0 flex-col">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>

          <DialogBody className="flex flex-col gap-4">
            <ProblemBanner error={failure} />

            {mode.kind === 'branch' ? (
              <Field htmlFor="scenario-parent" label="Branch from" error={errors.parentId?.message}>
                <Select
                  id="scenario-parent"
                  aria-invalid={errors.parentId ? true : undefined}
                  aria-describedby={describedBy('scenario-parent', false, Boolean(errors.parentId))}
                  {...register('parentId')}
                >
                  <option value="">Choose a scenario</option>
                  {mode.parents.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.name}
                    </option>
                  ))}
                </Select>
              </Field>
            ) : null}

            <Field htmlFor="scenario-name" label="Name" error={errors.name?.message}>
              <Input
                id="scenario-name"
                autoComplete="off"
                aria-invalid={errors.name ? true : undefined}
                aria-describedby={describedBy('scenario-name', false, Boolean(errors.name))}
                {...register('name')}
              />
            </Field>

            <Field htmlFor="scenario-description" label="Description (optional)" error={errors.description?.message}>
              <Input
                id="scenario-description"
                autoComplete="off"
                aria-invalid={errors.description ? true : undefined}
                aria-describedby={describedBy('scenario-description', false, Boolean(errors.description))}
                {...register('description')}
              />
            </Field>

            {mode.kind === 'fixedFork' ? null : (
              <Field
                htmlFor="scenario-fork"
                label={`Start from (${factory.timeZone})`}
                hint={
                  mode.kind === 'branch'
                    ? parent
                      ? `Leave empty to use the parent’s: ${formatDateTimeInZone(parent.forkAt, factory.timeZone)}.`
                      : 'Leave empty to use the parent’s.'
                    : 'Factory local time. Must lie within the recorded history.'
                }
                error={errors.forkLocal?.message}
              >
                <Input
                  id="scenario-fork"
                  type="datetime-local"
                  aria-invalid={errors.forkLocal ? true : undefined}
                  aria-describedby={describedBy('scenario-fork', true, Boolean(errors.forkLocal))}
                  placeholder={parent ? instantToZonedLocal(parent.forkAt, factory.timeZone) : undefined}
                  {...register('forkLocal')}
                />
              </Field>
            )}

            {mode.kind === 'branch' ? null : (
              <label className="flex items-start gap-2 text-sm text-foreground">
                <input type="checkbox" className="mt-0.5 size-4" {...register('includeFutureOrders')} />
                <span>
                  Include orders placed after the start
                  <span className="block text-xs text-subtle-foreground">
                    They arrive when they really did, so the simulation can be compared with what happened.
                  </span>
                </span>
              </label>
            )}
          </DialogBody>

          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Creating' : mode.kind === 'branch' ? 'Create branch' : 'Create scenario'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

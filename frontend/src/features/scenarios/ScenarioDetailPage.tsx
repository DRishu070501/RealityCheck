import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, GitBranch, Pencil, Plus, RotateCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router-dom'
import { z } from 'zod'

import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { RunStatusBadge } from '@/components/RunStatusBadge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
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
import { Field, Input } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { ChangeDialog } from '@/features/scenarios/ChangeDialog'
import { CreateScenarioDialog } from '@/features/scenarios/CreateScenarioDialog'
import { ProblemBanner } from '@/features/scenarios/ProblemBanner'
import { RunPanel } from '@/features/scenarios/RunPanel'
import { applyServerErrors, asApiError, CHANGE_TYPE_LABELS } from '@/features/scenarios/scenarioShared'
import { ValidationPanel } from '@/features/scenarios/ValidationPanel'
import { api, type ApiError } from '@/lib/api'
import { formatDateTimeInZone, formatShortDateTimeInZone } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'
import type { Scenario, ScenarioChange } from '@/lib/scenarioSchemas'
import { describedBy } from '@/lib/utils'

const detailsSchema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(120, 'At most 120 characters'),
  description: z.string().max(2000, 'At most 2,000 characters'),
})
type DetailsValues = z.infer<typeof detailsSchema>

/** Name and description, edited in place with the scenario's version guarding against lost updates. */
function DetailsEditor({ scenario, onDone }: { scenario: Scenario; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [failure, setFailure] = useState<ApiError | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<DetailsValues>({
    resolver: zodResolver(detailsSchema),
    defaultValues: { name: scenario.name, description: scenario.description ?? '' },
  })
  const update = useMutation({
    mutationFn: (values: DetailsValues) =>
      api.scenarios.update(scenario.id, {
        name: values.name.trim(),
        description: values.description.trim(),
        version: scenario.version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
      onDone()
    },
    onError: (error) => {
      const apiError = asApiError(error, 'The scenario could not be saved')
      if (apiError.status === 409 && apiError.code !== 'STALE_VERSION') {
        setError('name', { type: 'server', message: 'Another scenario of this factory already has this name' })
        return
      }
      setFailure(applyServerErrors(apiError, setError, { name: 'name', description: 'description' }).banner)
    },
  })
  const stale = failure?.status === 409 && failure.code === 'STALE_VERSION'

  return (
    <form
      noValidate
      aria-label="Edit scenario details"
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        void handleSubmit((values) => {
          setFailure(null)
          update.mutate(values)
        })(event)
      }}
    >
      {stale ? (
        <ProblemBanner title="This scenario was changed after you opened it">
          <p className="text-muted-foreground">Your edit was not saved. Reload the latest version and try again.</p>
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            onClick={() => {
              void queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.detail(scenario.id) })
              onDone()
            }}
          >
            <RotateCw aria-hidden="true" />
            Reload latest
          </Button>
        </ProblemBanner>
      ) : (
        <ProblemBanner error={failure} />
      )}
      <Field htmlFor="scenario-edit-name" label="Name" error={errors.name?.message}>
        <Input
          id="scenario-edit-name"
          autoComplete="off"
          aria-invalid={errors.name ? true : undefined}
          aria-describedby={describedBy('scenario-edit-name', false, Boolean(errors.name))}
          {...register('name')}
        />
      </Field>
      <Field htmlFor="scenario-edit-description" label="Description" error={errors.description?.message}>
        <Input
          id="scenario-edit-description"
          autoComplete="off"
          aria-invalid={errors.description ? true : undefined}
          aria-describedby={describedBy('scenario-edit-description', false, Boolean(errors.description))}
          {...register('description')}
        />
      </Field>
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={update.isPending}>
          {update.isPending ? 'Saving' : 'Save'}
        </Button>
        <Button variant="outline" size="sm" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

function DeleteChangeDialog({ scenario, change, onClose }: { scenario: Scenario; change: ScenarioChange; onClose: () => void }) {
  const queryClient = useQueryClient()
  const remove = useMutation({
    mutationFn: () => api.scenarios.deleteChange(scenario.id, change.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
      onClose()
    },
  })
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete change {change.ordinal}?</DialogTitle>
          <DialogDescription>{change.summary}</DialogDescription>
        </DialogHeader>
        <DialogBody className="flex flex-col gap-3 text-sm text-muted-foreground">
          <p>Later changes move up one place. Runs already made keep the changes they were run with.</p>
          {remove.isError ? <ProblemBanner error={asApiError(remove.error, 'The change could not be deleted')} /> : null}
        </DialogBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Keep it</Button>
          </DialogClose>
          <Button
            disabled={remove.isPending}
            onClick={() => {
              remove.mutate()
            }}
          >
            {remove.isPending ? 'Deleting' : 'Delete change'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ChangesCard({ factory, scenario }: { factory: Factory; scenario: Scenario }) {
  const [editing, setEditing] = useState<ScenarioChange | 'new' | null>(null)
  const [deleting, setDeleting] = useState<ScenarioChange | null>(null)
  return (
    <Card aria-labelledby="changes-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="changes-title">Changes</CardTitle>
          <CardDescription>Applied in order to a copy of the factory. Live configuration is never touched.</CardDescription>
        </div>
        <Button
          size="sm"
          onClick={() => {
            setEditing('new')
          }}
        >
          <Plus aria-hidden="true" />
          Add change
        </Button>
      </CardHeader>
      {scenario.changes.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">
          No changes: this scenario simulates the factory as recorded. Add a change to test a decision.
        </CardContent>
      ) : (
        <ol aria-label="Changes in order" className="divide-y divide-border">
          {scenario.changes.map((change) => (
            <li key={change.id} className="flex flex-wrap items-start gap-3 px-5 py-3">
              <span className="metric mt-0.5 text-sm text-subtle-foreground">{change.ordinal}.</span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-foreground">
                  {CHANGE_TYPE_LABELS[change.changeType]}
                  {change.inherited ? (
                    <Badge variant="neutral" className="ml-2" title="Copied from the parent scenario when this one was created.">
                      Inherited
                    </Badge>
                  ) : null}
                </p>
                <p className="text-sm text-muted-foreground">{change.summary}</p>
              </div>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Edit change ${change.ordinal}`}
                  onClick={() => {
                    setEditing(change)
                  }}
                >
                  <Pencil aria-hidden="true" />
                  Edit
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Delete change ${change.ordinal}`}
                  onClick={() => {
                    setDeleting(change)
                  }}
                >
                  <Trash2 aria-hidden="true" />
                  Delete
                </Button>
              </div>
            </li>
          ))}
        </ol>
      )}
      {editing ? (
        <ChangeDialog
          factory={factory}
          scenario={scenario}
          change={editing === 'new' ? undefined : editing}
          onClose={() => {
            setEditing(null)
          }}
        />
      ) : null}
      {deleting ? (
        <DeleteChangeDialog
          scenario={scenario}
          change={deleting}
          onClose={() => {
            setDeleting(null)
          }}
        />
      ) : null}
    </Card>
  )
}

function RunHistory({ factory, scenario }: { factory: Factory; scenario: Scenario }) {
  const runs = useQuery({
    queryKey: queryKeys.scenarios.runs(scenario.id),
    queryFn: ({ signal }) => api.scenarios.runs(scenario.id, signal),
  })
  return (
    <Card aria-labelledby="history-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="history-title">Run history</CardTitle>
          <CardDescription>Every run keeps the exact input it was given, so edits made later do not change it.</CardDescription>
        </div>
      </CardHeader>
      {runs.isPending ? (
        <CardContent role="status" aria-label="Loading runs">
          <Skeleton className="h-16 w-full" />
        </CardContent>
      ) : runs.isError ? (
        <CardContent>
          <ErrorState error={runs.error} onRetry={() => void runs.refetch()} retrying={runs.isFetching} />
        </CardContent>
      ) : runs.data.items.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">No runs yet.</CardContent>
      ) : (
        <Table aria-label="Runs, newest first" className="min-w-[36rem]">
          <thead>
            <tr>
              <TableHead>Submitted</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Outcome</TableHead>
              <TableHead>Algorithm</TableHead>
              <TableHead>
                <span className="sr-only">Results</span>
              </TableHead>
            </tr>
          </thead>
          <tbody>
            {runs.data.items.map((run) => (
              <TableRow key={run.runId}>
                <TableCell>
                  <time dateTime={run.submittedAt} className="metric whitespace-nowrap">
                    {formatShortDateTimeInZone(run.submittedAt, factory.timeZone)}
                  </time>
                </TableCell>
                <TableCell>
                  <RunStatusBadge status={run.status} />
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {run.status === 'FAILED'
                    ? (run.failureMessage ?? 'Failed')
                    : run.terminationReason === null
                      ? '—'
                      : run.terminationReason === 'COMPLETED'
                        ? 'All work finished'
                        : `Stopped at a limit (${run.terminationReason})`}
                </TableCell>
                <TableCell className="metric text-muted-foreground">{run.algorithmVersion}</TableCell>
                <TableCell>
                  {run.status === 'COMPLETED' ? (
                    <Link to={`/results?run=${run.runId}`} className="text-sm text-primary underline-offset-4 hover:underline">
                      Results
                    </Link>
                  ) : null}
                </TableCell>
              </TableRow>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  )
}

function ScenarioView({ factory, id }: { factory: Factory; id: string }) {
  const [editingDetails, setEditingDetails] = useState(false)
  const [branching, setBranching] = useState(false)
  const scenario = useQuery({
    queryKey: queryKeys.scenarios.detail(id),
    queryFn: ({ signal }) => api.scenarios.get(id, signal),
  })
  const version = scenario.data?.version ?? -1
  const validation = useQuery({
    queryKey: queryKeys.scenarios.validation(id, version),
    queryFn: ({ signal }) => api.scenarios.validate(id, signal),
    enabled: scenario.data !== undefined,
  })
  const parent = useQuery({
    queryKey: queryKeys.scenarios.detail(scenario.data?.parentScenarioId ?? 'none'),
    queryFn: ({ signal }) => api.scenarios.get(scenario.data?.parentScenarioId ?? '', signal),
    enabled: Boolean(scenario.data?.parentScenarioId),
  })

  if (scenario.isPending) {
    return (
      <div role="status" aria-label="Loading scenario" className="flex flex-col gap-3">
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    )
  }
  if (scenario.isError) {
    return <ErrorState error={scenario.error} onRetry={() => void scenario.refetch()} retrying={scenario.isFetching} />
  }

  const data = scenario.data
  const summary = { ...data, changeCount: data.changes.length }

  return (
    <>
      <div className="mb-6 flex flex-col gap-3">
        <Link to="/scenarios" className="flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft aria-hidden="true" className="size-4" />
          Scenario Lab
        </Link>
        {editingDetails ? (
          <DetailsEditor
            scenario={data}
            onDone={() => {
              setEditingDetails(false)
            }}
          />
        ) : (
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold tracking-tight text-foreground">
                {data.name}
                {data.baseline ? <Badge variant="info">Baseline</Badge> : null}
              </h1>
              {data.description ? <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{data.description}</p> : null}
              <p className="mt-2 text-sm text-muted-foreground">
                Starts from the factory as recorded at{' '}
                <time dateTime={data.forkAt} className="metric text-foreground">
                  {formatDateTimeInZone(data.forkAt, factory.timeZone)}
                </time>
                {data.includeFutureOrders ? ', with orders placed later arriving when they really did.' : ', without later orders.'}
              </p>
              {data.parentScenarioId ? (
                <p className="mt-1 text-sm text-muted-foreground">
                  Branched from{' '}
                  <Link to={`/scenarios/${data.parentScenarioId}`} className="text-primary underline-offset-4 hover:underline">
                    {parent.data?.name ?? 'its parent scenario'}
                  </Link>
                  .
                </p>
              ) : null}
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setEditingDetails(true)
                }}
              >
                <Pencil aria-hidden="true" />
                Rename
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setBranching(true)
                }}
              >
                <GitBranch aria-hidden="true" />
                Branch
              </Button>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col gap-6">
        <ChangesCard factory={factory} scenario={data} />
        <ValidationPanel
          validation={validation.data}
          pending={validation.isPending}
          error={validation.error}
          onRetry={() => void validation.refetch()}
          retrying={validation.isFetching}
        />
        <RunPanel
          key={data.id}
          factory={factory}
          scenario={data}
          valid={validation.data ? validation.data.valid : null}
          checkFailed={validation.isError}
        />
        <RunHistory factory={factory} scenario={data} />
      </div>

      {branching ? (
        <CreateScenarioDialog
          factory={factory}
          mode={{ kind: 'branch', parents: [summary], parentId: data.id }}
          onClose={() => {
            setBranching(false)
          }}
        />
      ) : null}
    </>
  )
}

/** One scenario: its changes, a check of what they do, running it and its run history. */
export function ScenarioDetailPage() {
  const { id = '' } = useParams()
  return <FactoryGate>{(factory) => <ScenarioView factory={factory} id={id} />}</FactoryGate>
}

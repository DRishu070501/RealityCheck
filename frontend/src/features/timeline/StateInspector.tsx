import { useQuery } from '@tanstack/react-query'
import { GitBranch, Info, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { PriorityIndicator } from '@/components/production'
import { StageStrip } from '@/components/StageStrip'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input, Label } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { CreateScenarioDialog } from '@/features/scenarios/CreateScenarioDialog'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { api } from '@/lib/api'
import {
  formatCarats,
  formatDateTimeInZone,
  formatInteger,
  formatShortDateTimeInZone,
  formatStones,
  instantToZonedLocal,
  zonedLocalToInstant,
} from '@/lib/format'
import { ORDER_STATUS_LABELS } from '@/lib/production'
import { queryKeys } from '@/lib/queryKeys'
import { orderStatusSchema, type BatchState, type Factory, type FactoryState } from '@/lib/schemas'

function BatchTable({
  title,
  batches,
  since,
  stageNames,
  timeZone,
}: {
  title: string
  batches: readonly BatchState[]
  /** Stage name by stage id. */
  stageNames: ReadonlyMap<string, string>
  /** Which instant the "since" column shows. */
  since: 'waitingSince' | 'startedAt'
  timeZone: string
}) {
  if (batches.length === 0) return null
  return (
    <section aria-label={title} className="border-t border-border">
      <h3 className="px-5 pt-4 text-xs font-semibold text-foreground">
        {title} <span className="metric font-normal text-muted-foreground">({formatInteger(batches.length)})</span>
      </h3>
      <Table aria-label={title} className="min-w-[44rem]">
        <thead>
          <tr>
            <TableHead>Order and batch</TableHead>
            <TableHead>Priority</TableHead>
            <TableHead>Stage</TableHead>
            <TableHead className="text-right">Holds</TableHead>
            <TableHead>{since === 'startedAt' ? 'Started' : 'Waiting since'}</TableHead>
          </tr>
        </thead>
        <tbody>
          {batches.map((batch) => {
            const when = batch[since]
            return (
              <TableRow key={batch.batchId}>
                <TableCell>
                  <Link
                    to={`/orders/${batch.orderId}`}
                    className="metric font-medium text-foreground underline-offset-4 hover:underline"
                  >
                    {batch.orderNumber}
                  </Link>{' '}
                  <span className="metric text-muted-foreground">· batch {batch.batchNumber}</span>
                </TableCell>
                <TableCell>
                  <PriorityIndicator priority={batch.priority} />
                </TableCell>
                <TableCell className="text-muted-foreground">
                  <span className="metric text-subtle-foreground">{batch.stageSequence}.</span>{' '}
                  {(batch.stageId === null ? undefined : stageNames.get(batch.stageId)) ?? '—'}
                </TableCell>
                <TableCell className="metric text-right whitespace-nowrap">
                  <span className="block">{formatStones(batch.inputStones)}</span>
                  <span className="block text-muted-foreground">{formatCarats(batch.inputCarats)}</span>
                </TableCell>
                <TableCell>
                  {when === null ? (
                    '—'
                  ) : (
                    <time dateTime={when} className="metric whitespace-nowrap">
                      {formatShortDateTimeInZone(when, timeZone)}
                    </time>
                  )}
                </TableCell>
              </TableRow>
            )
          })}
        </tbody>
      </Table>
    </section>
  )
}

/** The reconstructed factory: orders by status, every stage, every pool, and the batches in play. */
function StateView({ state, timeZone }: { state: FactoryState; timeZone: string }) {
  const inProgress = state.batches.filter((batch) => batch.status === 'IN_PROGRESS')
  const queued = state.batches.filter((batch) => batch.status === 'QUEUED')
  const finished = state.batches.length - inProgress.length - queued.length
  const stageNames = new Map(state.stages.map((stage) => [stage.stageId, stage.name]))
  const placed = orderStatusSchema.options.reduce((total, status) => total + state.orders[status], 0)

  if (placed === 0) {
    return (
      <CardContent className="border-t border-border text-sm text-muted-foreground">
        No order had been placed by then, so the factory was empty.
      </CardContent>
    )
  }

  return (
    <>
      <dl
        aria-label="Orders by status at that moment"
        className="grid grid-cols-2 border-t border-border sm:grid-cols-4"
      >
        {orderStatusSchema.options.map((status) => (
          <div key={status} className="flex flex-col gap-0.5 px-5 py-3">
            <dt className="text-xs text-muted-foreground">{ORDER_STATUS_LABELS[status]} orders</dt>
            <dd className="metric text-xl font-semibold text-foreground">{formatInteger(state.orders[status])}</dd>
          </div>
        ))}
      </dl>

      <div className="border-t border-border">
        <StageStrip stages={state.stages} label="Stages at that moment" />
      </div>

      <section aria-label="Resource pools at that moment" className="border-t border-border">
        <h3 className="px-5 pt-4 text-xs font-semibold text-foreground">Resource pools</h3>
        <Table aria-label="Resource pools at that moment" className="min-w-[36rem]">
          <thead>
            <tr>
              <TableHead>Pool</TableHead>
              <TableHead>Kind</TableHead>
              <TableHead className="text-right">Units in use</TableHead>
              <TableHead className="text-right">Units available</TableHead>
              <TableHead>Note</TableHead>
            </tr>
          </thead>
          <tbody>
            {state.resources.map((pool) => (
              <TableRow key={pool.resourceId}>
                <TableCell className="font-medium">{pool.name}</TableCell>
                <TableCell className="text-muted-foreground">{pool.kind === 'MACHINE' ? 'Machine' : 'Employee'}</TableCell>
                <TableCell className="metric text-right">{formatInteger(pool.unitsInUse)}</TableCell>
                <TableCell className="metric text-right">{formatInteger(pool.capacity)}</TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {pool.capacity !== pool.baseCapacity
                    ? `Configured ${formatInteger(pool.baseCapacity)}; an availability window applied.`
                    : null}
                  {pool.unitsInUse > pool.capacity ? ' More in use than available: work begun earlier kept its units.' : null}
                </TableCell>
              </TableRow>
            ))}
          </tbody>
        </Table>
      </section>

      <BatchTable
        title="Batches in progress"
        batches={inProgress}
        since="startedAt"
        stageNames={stageNames}
        timeZone={timeZone}
      />
      <BatchTable
        title="Batches queued"
        batches={queued}
        since="waitingSince"
        stageNames={stageNames}
        timeZone={timeZone}
      />
      <p className="border-t border-border px-5 py-3 text-xs text-subtle-foreground">
        <span className="metric">{formatInteger(finished)}</span> batches had already finished every stage and are not
        listed.
      </p>
    </>
  )
}

interface StateInspectorProps {
  factory: Factory
  /** The instant being inspected, ISO-8601 UTC, or null when none is selected. */
  at: string | null
  onAt: (at: string | null) => void
  /** When the factory's recorded history begins; null when it has none or it is not known yet. */
  firstEventAt?: string | null
}

/** Why a scenario cannot start at `at`, or null when it can. */
function forkProblem(at: string | null, firstEventAt: string | null, timeZone: string): string | null {
  if (at === null || Number.isNaN(Date.parse(at))) return 'Choose a moment first.'
  if (Date.parse(at) > Date.now()) return 'This moment is in the future: there is no recorded state to start from.'
  if (firstEventAt !== null && Date.parse(at) < Date.parse(firstEventAt)) {
    return `This moment is before the recorded history begins (${formatDateTimeInZone(firstEventAt, timeZone)}).`
  }
  return null
}

/**
 * The time machine's first half: pick any instant and see the factory as it stood then, rebuilt
 * from the event history, then branch a scenario from it.
 */
export function StateInspector({ factory, at, onAt, firstEventAt = null }: StateInspectorProps) {
  const [creating, setCreating] = useState(false)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [draft, setDraft] = useState(at === null ? '' : instantToZonedLocal(at, factory.timeZone))
  const [syncedAt, setSyncedAt] = useState(at)
  if (syncedAt !== at) {
    // The instant changed from outside (an event's "inspect" action, or Back): show it in the input.
    setSyncedAt(at)
    setDraft(at === null ? '' : instantToZonedLocal(at, factory.timeZone))
  }

  useEffect(() => {
    if (at !== null && typeof headingRef.current?.scrollIntoView === 'function') {
      headingRef.current.scrollIntoView({ block: 'start', behavior: 'auto' })
    }
  }, [at])

  const state = useQuery({
    queryKey: queryKeys.factories.state(factory.id, at ?? 'none'),
    queryFn: ({ signal }) => api.factories.state(factory.id, at ?? undefined, signal),
    enabled: at !== null,
  })

  const valid = at !== null && !Number.isNaN(Date.parse(at))
  const problem = forkProblem(at, firstEventAt, factory.timeZone)

  return (
    <Card aria-labelledby="inspector-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="inspector-title" ref={headingRef} className="scroll-mt-4">
            Factory at a point in time
          </CardTitle>
          <CardDescription>
            Choose any moment to see where every order, batch and resource stood then, rebuilt from the recorded
            events. Later corrections are applied: this is what is now known about that moment.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>

      <CardContent className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="inspect-at">Moment to inspect ({factory.timeZone})</Label>
          <Input
            id="inspect-at"
            type="datetime-local"
            className="w-56"
            value={draft}
            onChange={(event) => {
              const value = event.target.value
              setDraft(value)
              const instant = zonedLocalToInstant(value, factory.timeZone)
              if (instant !== null) onAt(instant)
            }}
          />
        </div>
        <Button
          variant="outline"
          onClick={() => {
            onAt(new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'))
          }}
        >
          Now
        </Button>
        {at !== null ? (
          <Button
            variant="ghost"
            onClick={() => {
              onAt(null)
            }}
          >
            <X aria-hidden="true" />
            Clear
          </Button>
        ) : null}
        <div className="ml-auto flex flex-col items-end gap-1">
          <Button
            variant="outline"
            disabled={problem !== null}
            aria-describedby={problem !== null ? 'scenario-note' : undefined}
            onClick={() => {
              setCreating(true)
            }}
          >
            <GitBranch aria-hidden="true" />
            Create scenario from this point
          </Button>
          {problem !== null ? (
            <p id="scenario-note" className="flex items-center gap-1.5 text-xs text-subtle-foreground">
              <Info aria-hidden="true" className="size-3.5 shrink-0" />
              {problem}
            </p>
          ) : null}
        </div>
      </CardContent>

      {at === null ? (
        <CardContent className="border-t border-border text-sm text-muted-foreground">
          No moment is selected. Enter a date and time above, or open an event below and choose “Inspect factory at
          this moment”.
        </CardContent>
      ) : !valid ? (
        <CardContent className="border-t border-border text-sm text-status-error" role="alert">
          The moment in the address is not a valid date and time.
        </CardContent>
      ) : state.isPending ? (
        <CardContent className="border-t border-border" role="status" aria-label="Rebuilding factory state">
          <Skeleton className="h-64 w-full" />
        </CardContent>
      ) : state.isError ? (
        <CardContent className="border-t border-border">
          <ErrorState error={state.error} onRetry={() => void state.refetch()} retrying={state.isFetching} />
        </CardContent>
      ) : (
        <>
          <p className="border-t border-border px-5 py-3 text-sm text-foreground" aria-live="polite">
            State at{' '}
            <time dateTime={state.data.at} className="metric font-medium">
              {formatDateTimeInZone(state.data.at, factory.timeZone)}
            </time>
          </p>
          <StateView state={state.data} timeZone={factory.timeZone} />
        </>
      )}
      {creating && at !== null ? (
        <CreateScenarioDialog
          factory={factory}
          mode={{ kind: 'fixedFork', forkAt: at }}
          onClose={() => {
            setCreating(false)
          }}
        />
      ) : null}
    </Card>
  )
}

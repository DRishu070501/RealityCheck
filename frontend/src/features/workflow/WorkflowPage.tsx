import { useQuery } from '@tanstack/react-query'
import { ChevronRight, Info } from 'lucide-react'
import { useState, type ReactNode } from 'react'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { api } from '@/lib/api'
import { formatDecimal, formatDuration, formatInteger, formatMoney, formatYield } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, Stage, StageState } from '@/lib/schemas'
import { cn } from '@/lib/utils'

function slotsLabel(maxSlots: number | null): string {
  if (maxSlots === null) return 'Unlimited jobs at once'
  return maxSlots === 1 ? 'Up to 1 job at once' : `Up to ${formatInteger(maxSlots)} jobs at once`
}

function rateLabel(stage: Stage): string {
  return `${formatDecimal(stage.stonesPerHour, 4)} stones/hour per slot`
}

/** "4 queued · 6 in progress", from recorded history. */
function liveLabel(live: StageState): string {
  return `${formatInteger(live.queued)} queued · ${formatInteger(live.inProgress)} in progress`
}

function Pipeline({
  stages,
  live,
  selectedId,
  onSelect,
}: {
  stages: readonly Stage[]
  /** What is at each stage right now, by stage id; empty while unknown. */
  live: ReadonlyMap<string, StageState>
  selectedId: string
  onSelect: (id: string) => void
}) {
  return (
    <ol
      aria-label="Production stages in sequence"
      className="grid grid-cols-1 gap-y-2 px-5 py-4 min-[26rem]:grid-cols-2 md:grid-cols-4 2xl:grid-cols-8"
    >
      {stages.map((stage, index) => {
        const selected = stage.id === selectedId
        const now = live.get(stage.id)
        return (
          <li key={stage.id} className="flex min-w-0 items-center">
            <button
              type="button"
              aria-pressed={selected}
              onClick={() => {
                onSelect(stage.id)
              }}
              className={cn(
                'flex h-full min-w-0 flex-1 flex-col gap-1.5 rounded-md border px-3 py-2.5 text-left transition-colors',
                selected
                  ? 'border-primary bg-surface-hover'
                  : 'border-border-strong bg-surface-raised hover:bg-surface-hover',
              )}
            >
              <span className="metric text-xs text-subtle-foreground">Stage {stage.sequence}</span>
              <span className="text-sm font-medium text-foreground">{stage.name}</span>
              <span className="metric mt-auto text-xs text-muted-foreground">
                {formatDecimal(stage.stonesPerHour, 4)} stones/h per slot
              </span>
              <span className="metric text-xs text-muted-foreground">
                {stage.maxSlots === null ? 'Unlimited slots' : `${formatInteger(stage.maxSlots)} slots`}
              </span>
              {now ? (
                <span className="metric border-t border-border pt-1.5 text-xs text-source-actual">{liveLabel(now)}</span>
              ) : null}
            </button>
            <ChevronRight
              aria-hidden="true"
              className={cn('size-4 shrink-0 text-subtle-foreground', index === stages.length - 1 && 'invisible')}
            />
          </li>
        )
      })}
    </ol>
  )
}

function Parameter({ label, hint, children }: { label: string; hint: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 px-5 py-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="metric text-base font-medium text-foreground">{children}</dd>
      <dd className="text-xs text-subtle-foreground">{hint}</dd>
    </div>
  )
}

function StageDetail({ stage, factory, live }: { stage: Stage; factory: Factory; live: StageState | undefined }) {
  return (
    <Card aria-labelledby="stage-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="stage-title" className="text-base">
            {stage.sequence}. {stage.name}
          </CardTitle>
          {stage.description ? <CardDescription>{stage.description}</CardDescription> : null}
        </div>
        <Badge variant="neutral" className="metric">
          {stage.code}
        </Badge>
      </CardHeader>

      <dl className="grid grid-cols-1 divide-y divide-border border-b border-border sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-3">
        <Parameter label="Processing rate" hint="One slot is one complete set of the resources below.">
          {rateLabel(stage)}
        </Parameter>
        <Parameter label="Setup time" hint="Added once per job, before processing starts.">
          {formatDuration(stage.setupSeconds)} per job
        </Parameter>
        <Parameter label="Parallel capacity" hint="At base pool capacity, before any downtime.">
          {slotsLabel(stage.maxSlots)}
        </Parameter>
        <Parameter label="Stone yield" hint="Share of stones that leave the stage.">
          {formatYield(stage.stoneYield)}
        </Parameter>
        <Parameter label="Carat yield" hint="Share of carat weight that leaves the stage.">
          {formatYield(stage.caratYield)}
        </Parameter>
        <Parameter label="Fixed cost" hint="Charged once for each job at this stage.">
          {formatMoney(stage.fixedCostPerJob, factory.currencyCode)} per job
        </Parameter>
      </dl>

      {live ? (
        <div
          aria-label="At this stage right now"
          className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3"
        >
          <p className="text-sm text-foreground">
            Right now:{' '}
            <span className="metric font-medium">
              {formatInteger(live.queued)} {live.queued === 1 ? 'batch' : 'batches'} queued
            </span>
            ,{' '}
            <span className="metric font-medium">{formatInteger(live.inProgress)} in progress</span>,{' '}
            <span className="metric text-muted-foreground">{formatInteger(live.completed)} through this stage</span>.
          </p>
          <SourceTag source="Actual" />
        </div>
      ) : null}

      {stage.requirements.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">
          This stage needs no resources, so any number of jobs can run at once.
        </CardContent>
      ) : (
        <Table aria-label={`Resources one ${stage.name} slot needs`}>
          <thead>
            <tr>
              <TableHead>Resource pool</TableHead>
              <TableHead>Kind</TableHead>
              <TableHead className="text-right">Units per slot</TableHead>
              <TableHead className="text-right">Pool capacity</TableHead>
            </tr>
          </thead>
          <tbody>
            {stage.requirements.map((requirement) => (
              <TableRow key={requirement.resourceId}>
                <TableCell className="font-medium">{requirement.resourceName}</TableCell>
                <TableCell className="text-muted-foreground">
                  {requirement.kind === 'MACHINE' ? 'Machine' : 'Employee'}
                </TableCell>
                <TableCell className="metric text-right">{formatInteger(requirement.unitsPerSlot)}</TableCell>
                <TableCell className="metric text-right">{formatInteger(requirement.poolCapacity)} units</TableCell>
              </TableRow>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  )
}

function WorkflowExplorer({ factory }: { factory: Factory }) {
  const list = useQuery({
    queryKey: queryKeys.workflows.list(factory.id),
    queryFn: ({ signal }) => api.workflows.list(factory.id, signal),
  })
  // The backend lists the active workflow first.
  const workflowId = list.data?.items[0]?.id
  const workflow = useQuery({
    queryKey: queryKeys.workflows.detail(workflowId ?? 'none'),
    queryFn: ({ signal }) => api.workflows.get(workflowId ?? '', signal),
    enabled: workflowId !== undefined,
  })
  // What is at each stage now. The page works without it, so its failure only hides the counts.
  const state = useQuery({
    queryKey: queryKeys.factories.state(factory.id, 'now'),
    queryFn: ({ signal }) => api.factories.state(factory.id, undefined, signal),
  })
  const [selectedId, setSelectedId] = useState<string | null>(null)

  if (list.isError) {
    return <ErrorState error={list.error} onRetry={() => void list.refetch()} retrying={list.isFetching} />
  }
  if (workflow.isError) {
    return <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} retrying={workflow.isFetching} />
  }
  if (list.isPending || (workflowId !== undefined && workflow.isPending)) {
    return (
      <div role="status" aria-label="Loading workflow" className="flex flex-col gap-3">
        <Skeleton className="h-36 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    )
  }
  if (!workflow.data || workflow.data.stages.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 text-sm text-muted-foreground">
        <p className="text-base font-medium text-foreground">No workflow is configured</p>
        <p className="mt-2 max-w-xl">
          {factory.name} has no workflow yet. Create one with <code className="metric">POST /api/workflows</code>, or
          reset the demo data in Settings.
        </p>
      </div>
    )
  }

  const { stages } = workflow.data
  const live = new Map((state.data?.stages ?? []).map((stage) => [stage.stageId, stage]))
  const selected = stages.find((stage) => stage.id === selectedId) ?? stages[0]
  if (!selected) return null

  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="pipeline-title">
        <CardHeader>
          <div className="min-w-0 flex-1 basis-72">
            <CardTitle id="pipeline-title">{workflow.data.name}</CardTitle>
            <CardDescription>
              {formatInteger(stages.length)} stages, each feeding the next. Select a stage for its details.
              {state.data ? ' Queue and in-progress counts are batches at each stage right now, from recorded history.' : ''}
            </CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={workflow.data.status === 'ACTIVE' ? 'ok' : 'neutral'}>
              {workflow.data.status === 'ACTIVE' ? 'Active' : workflow.data.status === 'DRAFT' ? 'Draft' : 'Archived'}
            </Badge>
            {factory.demo ? <DemoTag /> : null}
            <SourceTag />
            {state.data ? <SourceTag source="Actual" /> : null}
          </div>
        </CardHeader>
        <Pipeline stages={stages} live={live} selectedId={selected.id} onSelect={setSelectedId} />
      </Card>

      <StageDetail stage={selected} factory={factory} live={live.get(selected.id)} />

      <p className="flex items-start gap-2 text-xs text-subtle-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span>
          Rates, setup, yields, costs and resources are configuration; the queued and in-progress counts are
          recorded history. Utilization, average duration and bottleneck evidence come from simulation runs and are
          not shown until they can be read from simulated data.
        </span>
      </p>
    </div>
  )
}

export function WorkflowPage() {
  return (
    <>
      <PageHeader
        title="Workflow"
        description="The production stages in order, with the rate, setup, yield, cost and resources configured for each."
      />
      <FactoryGate>{(factory) => <WorkflowExplorer factory={factory} />}</FactoryGate>
    </>
  )
}

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
import type { Factory, Stage } from '@/lib/schemas'
import { cn } from '@/lib/utils'

function slotsLabel(maxSlots: number | null): string {
  if (maxSlots === null) return 'Unlimited jobs at once'
  return maxSlots === 1 ? 'Up to 1 job at once' : `Up to ${formatInteger(maxSlots)} jobs at once`
}

function rateLabel(stage: Stage): string {
  return `${formatDecimal(stage.stonesPerHour, 4)} stones/hour per slot`
}

function Pipeline({
  stages,
  selectedId,
  onSelect,
}: {
  stages: readonly Stage[]
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

function StageDetail({ stage, factory }: { stage: Stage; factory: Factory }) {
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
  const selected = stages.find((stage) => stage.id === selectedId) ?? stages[0]
  if (!selected) return null

  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="pipeline-title">
        <CardHeader>
          <div className="min-w-0">
            <CardTitle id="pipeline-title">{workflow.data.name}</CardTitle>
            <CardDescription>
              {formatInteger(stages.length)} stages, each feeding the next. Select a stage for its details.
            </CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={workflow.data.status === 'ACTIVE' ? 'ok' : 'neutral'}>
              {workflow.data.status === 'ACTIVE' ? 'Active' : workflow.data.status === 'DRAFT' ? 'Draft' : 'Archived'}
            </Badge>
            {factory.demo ? <DemoTag /> : null}
            <SourceTag />
          </div>
        </CardHeader>
        <Pipeline stages={stages} selectedId={selected.id} onSelect={setSelectedId} />
      </Card>

      <StageDetail stage={selected} factory={factory} />

      <p className="flex items-start gap-2 text-xs text-subtle-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span>
          Everything on this page is configuration. Live queue lengths and stage status arrive with production
          tracking, and utilization, average duration and bottleneck evidence with simulation runs. None of those
          figures are shown until they can be read from recorded or simulated data.
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

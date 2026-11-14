import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { NoFactoryState } from '@/components/FactoryGate'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { api } from '@/lib/api'
import { formatDecimal, formatInteger } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'
import { useCurrentFactory } from '@/lib/useCurrentFactory'

function Figure({ label, to, linkLabel, children }: { label: string; to: string; linkLabel: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 px-5 py-4">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="metric text-2xl font-semibold tracking-tight text-foreground">{children}</dd>
      <dd>
        <Link to={to} className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline">
          {linkLabel}
          <ArrowRight aria-hidden="true" className="size-3" />
        </Link>
      </dd>
    </div>
  )
}

function Pending() {
  return <Skeleton className="h-8 w-16" />
}

function Figures({ factory }: { factory: Factory }) {
  const workflows = useQuery({
    queryKey: queryKeys.workflows.list(factory.id),
    queryFn: ({ signal }) => api.workflows.list(factory.id, signal),
  })
  const resources = useQuery({
    queryKey: queryKeys.resources.list(factory.id),
    queryFn: ({ signal }) => api.resources.list(factory.id, undefined, signal),
  })

  const failed = workflows.isError ? workflows : resources.isError ? resources : null
  if (failed) {
    return (
      <CardContent>
        <ErrorState error={failed.error} onRetry={() => void failed.refetch()} retrying={failed.isFetching} />
      </CardContent>
    )
  }

  // The backend lists the active workflow first.
  const stageCount = workflows.data?.items[0]?.stageCount
  const pools = resources.data?.items
  const units = (kind: 'MACHINE' | 'EMPLOYEE'): number =>
    (pools ?? []).filter((pool) => pool.kind === kind).reduce((total, pool) => total + pool.capacity, 0)

  return (
    <dl className="grid grid-cols-2 divide-border lg:grid-cols-5 lg:divide-x [&>div]:border-b [&>div]:border-border lg:[&>div]:border-b-0">
      <Figure label="Workflow stages" to="/workflow" linkLabel="Workflow">
        {workflows.isPending ? <Pending /> : formatInteger(stageCount ?? 0)}
      </Figure>
      <Figure label="Resource pools" to="/resources" linkLabel="Resources">
        {pools ? formatInteger(pools.length) : <Pending />}
      </Figure>
      <Figure label="Machine units" to="/resources" linkLabel="Resources">
        {pools ? formatInteger(units('MACHINE')) : <Pending />}
      </Figure>
      <Figure label="Employee units" to="/resources" linkLabel="Resources">
        {pools ? formatInteger(units('EMPLOYEE')) : <Pending />}
      </Figure>
      <Figure label="Working time per week" to="/factory" linkLabel="Factory calendar">
        {formatDecimal(String(factory.calendar.weeklyWorkingSeconds / 3600), 2)} h
      </Figure>
    </dl>
  )
}

/** What is configured for the current factory. Counts only: no operational or simulated metric. */
export function FactorySummary() {
  const current = useCurrentFactory()

  if (current.status === 'empty') return <NoFactoryState />
  // The system status card above already reports an unreachable backend.
  if (current.status === 'error') {
    return <ErrorState error={current.error} onRetry={current.retry} retrying={current.retrying} />
  }

  return (
    <Card aria-labelledby="factory-summary-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="factory-summary-title">
            {current.status === 'ready' ? current.factory.name : 'Factory'}
          </CardTitle>
          <CardDescription>What is configured for this factory.</CardDescription>
        </div>
        {current.status === 'ready' ? (
          <div className="flex flex-wrap items-center gap-2">
            {current.factory.demo ? <DemoTag /> : null}
            <SourceTag />
          </div>
        ) : null}
      </CardHeader>
      {current.status === 'ready' ? (
        <Figures factory={current.factory} />
      ) : (
        <CardContent role="status" aria-label="Loading factory">
          <Skeleton className="h-16 w-full" />
        </CardContent>
      )}
    </Card>
  )
}

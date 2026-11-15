import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { lazy, Suspense, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { EventQuantities, EventTypeLabel } from '@/components/production'
import { StageStrip } from '@/components/StageStrip'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { api } from '@/lib/api'
import {
  formatCarats,
  formatDateTimeInZone,
  formatDay,
  formatInteger,
  formatShortDateTimeInZone,
  formatStones,
} from '@/lib/format'
import { eventSubject } from '@/lib/production'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, FactoryOverview } from '@/lib/schemas'
import { useCurrentFactory } from '@/lib/useCurrentFactory'
import { cn } from '@/lib/utils'

/** Recharts is large; it is fetched only when a chart is actually drawn. */
const DailyOutputChart = lazy(() => import('@/features/overview/DailyOutputChart'))

function Figure({
  label,
  hint,
  to,
  linkLabel,
  tone,
  children,
}: {
  label: string
  hint: string
  to: string
  linkLabel: string
  tone?: 'error'
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1 px-5 py-4">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('metric text-2xl font-semibold tracking-tight', tone === 'error' ? 'text-status-error' : 'text-foreground')}>
        {children}
      </dd>
      <dd className="text-xs text-subtle-foreground">{hint}</dd>
      <dd>
        <Link to={to} className="inline-flex items-center gap-1 text-xs text-primary underline-offset-4 hover:underline">
          {linkLabel}
          <ArrowRight aria-hidden="true" className="size-3" />
        </Link>
      </dd>
    </div>
  )
}

function ProductionNow({ overview, factory }: { overview: FactoryOverview; factory: Factory }) {
  const { orders, machines, employees } = overview
  return (
    <Card aria-labelledby="production-now-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="production-now-title">Production now</CardTitle>
          <CardDescription>
            From recorded orders and events, as of{' '}
            <time dateTime={overview.asOf} className="metric">
              {formatDateTimeInZone(overview.asOf, factory.timeZone)}
            </time>{' '}
            ({factory.timeZone}).
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <dl className="grid grid-cols-2 divide-border lg:grid-cols-6 lg:divide-x [&>div]:border-b [&>div]:border-border lg:[&>div]:border-b-0">
        <Figure
          label="Active orders"
          hint={`${formatInteger(orders.inProgress)} in progress`}
          to="/orders?status=IN_PROGRESS"
          linkLabel="Orders in progress"
        >
          {formatInteger(orders.active)}
        </Figure>
        <Figure label="Pending orders" hint="Placed, not started" to="/orders?status=PENDING" linkLabel="Pending orders">
          {formatInteger(orders.pending)}
        </Figure>
        <Figure
          label="Completed orders"
          hint={`${formatInteger(orders.dispatched)} of them dispatched`}
          to="/orders?status=COMPLETED"
          linkLabel="Awaiting dispatch"
        >
          {formatInteger(orders.completed + orders.dispatched)}
        </Figure>
        <Figure
          label="Late orders"
          hint="Finished late or past due"
          to="/orders?late=1"
          linkLabel="Late orders"
          tone={orders.delayed > 0 ? 'error' : undefined}
        >
          {formatInteger(orders.delayed)}
        </Figure>
        <Figure
          label="Machines in use now"
          hint={`units, of ${formatInteger(machines.capacity)} available now`}
          to="/resources"
          linkLabel="Resources"
        >
          {formatInteger(machines.inUse)}
        </Figure>
        <Figure
          label="Employees in use now"
          hint={`units, of ${formatInteger(employees.capacity)} available now`}
          to="/resources"
          linkLabel="Resources"
        >
          {formatInteger(employees.inUse)}
        </Figure>
      </dl>
      <p className="border-t border-border px-5 py-3 text-xs text-subtle-foreground">
        “In use now” counts units held by batches being worked on at this instant. It is not utilization over time;
        that, throughput and bottleneck evidence come from a simulation run, shown in the simulated baseline below.
      </p>
    </Card>
  )
}

function StageQueues({ overview }: { overview: FactoryOverview }) {
  return (
    <Card aria-labelledby="stage-queues-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="stage-queues-title">Work at each stage</CardTitle>
          <CardDescription>Batches waiting, being worked on, and through each stage right now.</CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <StageStrip stages={overview.stages} label="Work at each stage now" />
    </Card>
  )
}

function DailyOutput({ overview, factory }: { overview: FactoryOverview; factory: Factory }) {
  const days = overview.dailyOutput
  const any = days.some((day) => day.batchesCompleted > 0)
  return (
    <Card aria-labelledby="daily-output-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="daily-output-title">Finished output per day</CardTitle>
          <CardDescription>
            Stones that completed the last stage on each of the last {formatInteger(days.length)} days ({factory.timeZone}
            ). Weight in carats is in the tooltip.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      {any ? (
        <>
          <Suspense
            fallback={
              <CardContent role="status" aria-label="Loading chart">
                <Skeleton className="h-56 w-full" />
              </CardContent>
            }
          >
            <DailyOutputChart days={days} />
          </Suspense>
          <table className="sr-only">
            <caption>Finished output per day</caption>
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">Stones finished</th>
                <th scope="col">Weight finished</th>
                <th scope="col">Batches finished</th>
              </tr>
            </thead>
            <tbody>
              {days.map((day) => (
                <tr key={day.date}>
                  <th scope="row">{formatDay(day.date)}</th>
                  <td>{formatStones(day.stonesCompleted)}</td>
                  <td>{formatCarats(day.caratsCompleted)}</td>
                  <td>{formatInteger(day.batchesCompleted)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pb-3" />
        </>
      ) : (
        <CardContent className="text-sm text-muted-foreground">
          No batch finished the last stage in this period.
        </CardContent>
      )}
    </Card>
  )
}

function RecentEvents({ overview, factory }: { overview: FactoryOverview; factory: Factory }) {
  return (
    <Card aria-labelledby="recent-events-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="recent-events-title">Recent events</CardTitle>
          <CardDescription>The latest entries in the operational history.</CardDescription>
        </div>
        <Link
          to="/timeline"
          className="inline-flex items-center gap-1 text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          Open the timeline
          <ArrowRight aria-hidden="true" className="size-3" />
        </Link>
      </CardHeader>
      {overview.recentEvents.length === 0 ? (
        <CardContent className="text-sm text-muted-foreground">Nothing has been recorded yet.</CardContent>
      ) : (
        <ul aria-label="Recent events" className="divide-y divide-border">
          {overview.recentEvents.map((event) => (
            <li
              key={event.id}
              className="grid grid-cols-[1fr] items-start gap-x-4 gap-y-0.5 px-5 py-2.5 text-sm sm:grid-cols-[10rem_12rem_1fr_auto]"
            >
              <time dateTime={event.occurredAt} className="metric text-muted-foreground">
                {formatShortDateTimeInZone(event.occurredAt, factory.timeZone)}
              </time>
              <EventTypeLabel type={event.eventType} className="font-medium text-foreground" />
              <span className="min-w-0">
                {event.orderId !== null ? (
                  <Link
                    to={`/orders/${event.orderId}`}
                    className="metric text-foreground underline-offset-4 hover:underline"
                  >
                    {eventSubject(event)}
                  </Link>
                ) : (
                  <span className="metric text-foreground">{eventSubject(event)}</span>
                )}
                {event.stageName !== null ? <span className="text-muted-foreground"> · {event.stageName}</span> : null}
              </span>
              <span className="sm:text-right">
                {event.stoneQuantity !== null || event.caratQuantity !== null ? <EventQuantities event={event} /> : null}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Operations({ factory }: { factory: Factory }) {
  const overview = useQuery({
    queryKey: queryKeys.factories.overview(factory.id),
    queryFn: ({ signal }) => api.factories.overview(factory.id, signal),
  })

  if (overview.isPending) {
    return (
      <div role="status" aria-label="Loading production figures" className="flex flex-col gap-6">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-44 w-full" />
      </div>
    )
  }
  if (overview.isError) {
    return <ErrorState error={overview.error} onRetry={() => void overview.refetch()} retrying={overview.isFetching} />
  }
  if (overview.data.orders.total === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-8 text-sm text-muted-foreground">
        <p className="text-base font-medium text-foreground">No production has been recorded yet</p>
        <p className="mt-2 max-w-xl">
          Order counts, work at each stage and daily output appear here once orders are placed and events are recorded.
        </p>
      </div>
    )
  }
  return (
    <>
      <ProductionNow overview={overview.data} factory={factory} />
      <StageQueues overview={overview.data} />
      <DailyOutput overview={overview.data} factory={factory} />
      <RecentEvents overview={overview.data} factory={factory} />
    </>
  )
}

/**
 * What the recorded history says about the present. Renders nothing until a factory exists; the
 * factory summary above it already reports loading, errors and the empty state.
 */
export function OperationsSummary() {
  const current = useCurrentFactory()
  if (current.status !== 'ready') return null
  return <Operations factory={current.factory} />
}

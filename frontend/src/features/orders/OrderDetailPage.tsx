import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, History, Truck } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import {
  BatchStatusBadge,
  CorrectionMarkers,
  EventQuantities,
  EventTypeLabel,
  LateBadge,
  OrderStatusBadge,
  PriorityIndicator,
} from '@/components/production'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { api, ApiError } from '@/lib/api'
import {
  formatCarats,
  formatDateTimeInZone,
  formatDuration,
  formatInteger,
  formatShortDateTimeInZone,
  formatStones,
} from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Batch, Factory, OperationalEvent, OrderDetail } from '@/lib/schemas'
import { cn } from '@/lib/utils'

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 px-5 py-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm text-foreground">{children}</dd>
    </div>
  )
}

function When({ iso, timeZone }: { iso: string | null; timeZone: string }) {
  if (iso === null) return <span className="text-subtle-foreground">—</span>
  return (
    <time dateTime={iso} className="metric">
      {formatDateTimeInZone(iso, timeZone)}
    </time>
  )
}

/** Lets a completed order be recorded as dispatched. The backend refuses anything else. */
function DispatchAction({ detail }: { detail: OrderDetail }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const dispatch = useMutation({
    mutationFn: () => api.orders.dispatch(detail.order.id),
    onSuccess: async () => {
      setConfirming(false)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.orders.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.timeline.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.factories.all }),
      ])
    },
  })

  if (detail.order.status !== 'COMPLETED') return null
  const message =
    dispatch.error instanceof ApiError
      ? (dispatch.error.detail ?? dispatch.error.title)
      : dispatch.isError
        ? 'The dispatch could not be recorded.'
        : null

  return (
    <CardContent className="flex flex-col gap-3 border-t border-border">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-xl text-sm text-muted-foreground">
          Every batch has finished the last stage. Recording the dispatch adds an event to the history, dated now; it
          cannot be removed afterwards.
        </p>
        {confirming ? (
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={dispatch.isPending}
              onClick={() => {
                setConfirming(false)
                dispatch.reset()
              }}
            >
              Cancel
            </Button>
            <Button
              size="sm"
              disabled={dispatch.isPending}
              onClick={() => {
                dispatch.mutate()
              }}
            >
              <Truck aria-hidden="true" />
              {dispatch.isPending ? 'Recording' : 'Confirm dispatch'}
            </Button>
          </div>
        ) : (
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setConfirming(true)
            }}
          >
            <Truck aria-hidden="true" />
            Record dispatch
          </Button>
        )}
      </div>
      {message ? (
        <p role="alert" className="text-sm text-status-error">
          {message}
        </p>
      ) : null}
    </CardContent>
  )
}

function BatchTable({ batches }: { batches: readonly Batch[] }) {
  return (
    <Table aria-label="Batches" className="min-w-[44rem]">
      <thead>
        <tr>
          <TableHead>Batch</TableHead>
          <TableHead className="text-right">Started with</TableHead>
          <TableHead className="text-right">Holds now</TableHead>
          <TableHead>Current stage</TableHead>
          <TableHead>Status</TableHead>
        </tr>
      </thead>
      <tbody>
        {batches.map((batch) => (
          <TableRow key={batch.id}>
            <TableCell className="metric font-medium">Batch {batch.batchNumber}</TableCell>
            <TableCell className="metric text-right whitespace-nowrap">
              <span className="block">{formatStones(String(batch.stoneCount))}</span>
              <span className="block text-muted-foreground">{formatCarats(batch.roughCarats)}</span>
            </TableCell>
            <TableCell className="metric text-right whitespace-nowrap">
              <span className="block">{formatStones(batch.currentStones)}</span>
              <span className="block text-muted-foreground">{formatCarats(batch.currentCarats)}</span>
            </TableCell>
            <TableCell>
              {batch.currentStageName === null ? (
                <span className="text-muted-foreground">Finished</span>
              ) : (
                <>
                  <span className="metric text-subtle-foreground">{batch.currentStageSequence}.</span>{' '}
                  {batch.currentStageName}
                </>
              )}
            </TableCell>
            <TableCell>
              <BatchStatusBadge status={batch.status} />
            </TableCell>
          </TableRow>
        ))}
      </tbody>
    </Table>
  )
}

function reasonOf(event: OperationalEvent): string | null {
  const reason = event.payload.reason
  return typeof reason === 'string' && reason !== '' ? reason : null
}

function EventTable({ events, timeZone }: { events: readonly OperationalEvent[]; timeZone: string }) {
  // Chronological: by when things happened, then by recording order.
  const ordered = [...events].sort(
    (a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || a.seq - b.seq,
  )
  return (
    <Table aria-label="Event history" className="min-w-[52rem]">
      <thead>
        <tr>
          <TableHead>Occurred</TableHead>
          <TableHead>Event</TableHead>
          <TableHead>Batch</TableHead>
          <TableHead>Stage</TableHead>
          <TableHead className="text-right">Quantity</TableHead>
          <TableHead className="text-right">Working time</TableHead>
          <TableHead>Notes</TableHead>
        </tr>
      </thead>
      <tbody>
        {ordered.map((event) => {
          const reason = reasonOf(event)
          return (
            <TableRow key={event.id} className={cn(event.voided && 'opacity-60')}>
              <TableCell>
                <time dateTime={event.occurredAt} className="metric whitespace-nowrap">
                  {formatShortDateTimeInZone(event.occurredAt, timeZone)}
                </time>
              </TableCell>
              <TableCell>
                <EventTypeLabel type={event.eventType} />
              </TableCell>
              <TableCell className="metric text-muted-foreground">
                {event.batchNumber === null ? '—' : event.batchNumber}
              </TableCell>
              <TableCell className="whitespace-nowrap text-muted-foreground">{event.stageName ?? '—'}</TableCell>
              <TableCell className="text-right">
                <span className="inline-flex justify-end">
                  <EventQuantities event={event} />
                </span>
              </TableCell>
              <TableCell className="metric text-right whitespace-nowrap">
                {event.durationSeconds === null ? (
                  <span className="text-subtle-foreground">—</span>
                ) : (
                  formatDuration(event.durationSeconds)
                )}
              </TableCell>
              <TableCell>
                <span className="flex flex-col items-start gap-1">
                  <CorrectionMarkers event={event} />
                  {event.eventType === 'CORRECTION' ? (
                    <span className="text-xs text-muted-foreground">
                      {event.voids ? 'Voids an earlier event' : 'Replaces a recorded value'}
                    </span>
                  ) : null}
                  {reason ? <span className="text-xs text-muted-foreground">{reason}</span> : null}
                </span>
              </TableCell>
            </TableRow>
          )
        })}
      </tbody>
    </Table>
  )
}

function OrderDetailView({ factory, orderId }: { factory: Factory; orderId: string }) {
  const detail = useQuery({
    queryKey: queryKeys.orders.detail(orderId),
    queryFn: ({ signal }) => api.orders.get(orderId, signal),
  })

  if (detail.isPending) {
    return (
      <div role="status" aria-label="Loading order" className="flex flex-col gap-3">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    )
  }
  if (detail.isError) {
    if (detail.error instanceof ApiError && (detail.error.status === 404 || detail.error.status === 400)) {
      return (
        <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 text-sm text-muted-foreground">
          <p className="text-base font-medium text-foreground">This order does not exist</p>
          <p className="mt-2 max-w-xl">
            It may have been removed by a reset of the demo data. Return to the list to see current orders.
          </p>
        </div>
      )
    }
    return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} retrying={detail.isFetching} />
  }

  const { order, batches, events } = detail.data
  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="order-title">
        <CardHeader>
          <div className="min-w-0">
            <CardTitle id="order-title" className="metric text-base">
              {order.orderNumber}
            </CardTitle>
            <CardDescription>{order.customerRef ?? 'No customer reference'}</CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <OrderStatusBadge status={order.status} />
            {order.delayed ? <LateBadge /> : null}
            {factory.demo ? <DemoTag /> : null}
            <SourceTag source="Actual" />
          </div>
        </CardHeader>
        <dl className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4">
          <Fact label="Priority">
            <PriorityIndicator priority={order.priority} />
          </Fact>
          <Fact label="Placed">
            <When iso={order.placedAt} timeZone={factory.timeZone} />
          </Fact>
          <Fact label="Due">
            <When iso={order.dueAt} timeZone={factory.timeZone} />
          </Fact>
          <Fact label="Completed">
            <When iso={order.completedAt} timeZone={factory.timeZone} />
          </Fact>
          <Fact label="Batches finished">
            <span className="metric">
              {formatInteger(order.batchesCompleted)} of {formatInteger(order.batchCount)}
            </span>
          </Fact>
          <Fact label="Stones ordered">
            <span className="metric">{formatStones(String(order.totalStones))}</span>
          </Fact>
          <Fact label="Rough weight ordered">
            <span className="metric">{formatCarats(order.totalRoughCarats)}</span>
          </Fact>
          <Fact label="Time zone of the times shown">{factory.timeZone}</Fact>
        </dl>
        <DispatchAction detail={detail.data} />
      </Card>

      <Card aria-labelledby="batches-title">
        <CardHeader>
          <div>
            <CardTitle id="batches-title">Batches</CardTitle>
            <CardDescription>
              Each batch moves through the stages on its own. Stones are counted; carats are their weight.
            </CardDescription>
          </div>
        </CardHeader>
        <BatchTable batches={batches} />
      </Card>

      <Card aria-labelledby="history-title">
        <CardHeader>
          <div className="min-w-0 flex-1 basis-72">
            <CardTitle id="history-title">Event history</CardTitle>
            <CardDescription>
              Everything recorded for this order, oldest first. A corrected value shows what was first recorded struck
              through.
            </CardDescription>
          </div>
          <Button asChild variant="link" size="sm" className="px-0">
            <Link to={`/timeline?order=${order.id}`}>
              <History aria-hidden="true" />
              Open in the timeline
            </Link>
          </Button>
        </CardHeader>
        <EventTable events={events} timeZone={factory.timeZone} />
      </Card>
    </div>
  )
}

export function OrderDetailPage() {
  const { id = '' } = useParams()
  return (
    <>
      <PageHeader
        title="Order"
        description="One production order: its batches, where each stands, and the recorded history behind that."
        actions={
          <Button asChild variant="outline" size="sm">
            <Link to="/orders">
              <ArrowLeft aria-hidden="true" />
              All orders
            </Link>
          </Button>
        }
      />
      <FactoryGate>{(factory) => <OrderDetailView factory={factory} orderId={id} />}</FactoryGate>
    </>
  )
}

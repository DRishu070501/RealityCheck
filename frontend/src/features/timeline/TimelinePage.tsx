import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Crosshair, FilterX } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { CorrectionMarkers, EventQuantities, EventTypeLabel } from '@/components/production'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input, Label, Select } from '@/components/ui/form'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { ActivityChart } from '@/features/timeline/ActivityChart'
import { StateInspector } from '@/features/timeline/StateInspector'
import { api, type TimelineParams } from '@/lib/api'
import {
  formatCarats,
  formatDateTimeInZone,
  formatDay,
  formatDuration,
  formatInteger,
  formatStones,
  formatTimeInZone,
  localDayKey,
  localDayRange,
} from '@/lib/format'
import { EVENT_TYPE_LABELS, EVENT_TYPES, eventSubject } from '@/lib/production'
import { queryKeys } from '@/lib/queryKeys'
import { eventTypeSchema, type Factory, type OperationalEvent } from '@/lib/schemas'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 50
const DAY = /^\d{4}-\d{2}-\d{2}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** What the address bar holds for this page. Dates are factory-local days, "YYYY-MM-DD". */
interface Filters {
  stageId: string | null
  orderId: string | null
  type: OperationalEvent['eventType'] | null
  from: string | null
  to: string | null
  ascending: boolean
  includeVoided: boolean
  page: number
  /** The instant shown by the point-in-time inspector. */
  at: string | null
}

function readFilters(params: URLSearchParams): Filters {
  const text = (key: string, pattern: RegExp): string | null => {
    const value = params.get(key)
    return value !== null && pattern.test(value) ? value : null
  }
  const type = eventTypeSchema.safeParse(params.get('type'))
  return {
    stageId: text('stage', UUID),
    orderId: text('order', UUID),
    type: type.success ? type.data : null,
    from: text('from', DAY),
    to: text('to', DAY),
    ascending: params.get('sort') === 'asc',
    includeVoided: params.get('voided') === '1',
    page: Math.max(0, Number(params.get('page') ?? '0') || 0),
    at: params.get('at'),
  }
}

function FilterField({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] gap-3 py-1.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-sm break-words text-foreground">{children}</dd>
    </div>
  )
}

function EventDetailDialog({
  event,
  timeZone,
  onClose,
  onInspect,
}: {
  event: OperationalEvent
  timeZone: string
  onClose: () => void
  onInspect: (at: string) => void
}) {
  const payloadEntries = Object.entries(event.payload)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{EVENT_TYPE_LABELS[event.eventType]}</DialogTitle>
          <DialogDescription>
            {eventSubject(event)}. Times are factory local time ({timeZone}).
          </DialogDescription>
        </DialogHeader>
        <DialogBody>
          <dl className="divide-y divide-border">
            <Detail label="Occurred">
              <time dateTime={event.occurredAt} className="metric">
                {formatDateTimeInZone(event.occurredAt, timeZone)}
              </time>
            </Detail>
            <Detail label="Recorded">
              <time dateTime={event.recordedAt} className="metric">
                {formatDateTimeInZone(event.recordedAt, timeZone)}
              </time>
              <span className="block text-xs text-subtle-foreground">When the entry was written to the history.</span>
            </Detail>
            {event.orderId !== null ? (
              <Detail label="Order">
                <Link
                  to={`/orders/${event.orderId}`}
                  className="metric text-primary underline-offset-4 hover:underline"
                >
                  {event.orderNumber}
                </Link>
                {event.batchNumber !== null ? (
                  <span className="metric text-muted-foreground"> · batch {event.batchNumber}</span>
                ) : null}
              </Detail>
            ) : null}
            {event.stageName !== null ? <Detail label="Stage">{event.stageName}</Detail> : null}
            {event.resourceName !== null ? <Detail label="Resource pool">{event.resourceName}</Detail> : null}
            {event.stoneQuantity !== null ? (
              <Detail label="Stones">
                <span className="metric">{formatStones(event.stoneQuantity)}</span>
                {event.original?.stoneQuantity != null && event.original.stoneQuantity !== event.stoneQuantity ? (
                  <span className="metric block text-xs text-subtle-foreground">
                    First recorded as {formatStones(event.original.stoneQuantity)}
                  </span>
                ) : null}
              </Detail>
            ) : null}
            {event.caratQuantity !== null ? (
              <Detail label="Carats">
                <span className="metric">{formatCarats(event.caratQuantity)}</span>
                {event.original?.caratQuantity != null && event.original.caratQuantity !== event.caratQuantity ? (
                  <span className="metric block text-xs text-subtle-foreground">
                    First recorded as {formatCarats(event.original.caratQuantity)}
                  </span>
                ) : null}
              </Detail>
            ) : null}
            {event.durationSeconds !== null ? (
              <Detail label={event.eventType === 'RESOURCE_UNAVAILABLE' ? 'Lasts' : 'Working time'}>
                <span className="metric">{formatDuration(event.durationSeconds)}</span>
              </Detail>
            ) : null}
            {event.corrected || event.voided ? (
              <Detail label="Corrections">
                <CorrectionMarkers event={event} />
                <span className="mt-1 block text-xs text-subtle-foreground">
                  {event.voided
                    ? 'A later correction cancelled this event. The original entry is kept.'
                    : 'A later correction replaced a value. The original entry is kept unchanged.'}
                </span>
              </Detail>
            ) : null}
            {payloadEntries.map(([key, value]) => (
              <Detail key={key} label={key}>
                <span className="metric">{typeof value === 'string' ? value : JSON.stringify(value)}</span>
              </Detail>
            ))}
            <Detail label="Sequence">
              <span className="metric">{formatInteger(event.seq)}</span>
              <span className="block text-xs text-subtle-foreground">Position in recording order.</span>
            </Detail>
            <Detail label="Event id">
              <span className="metric text-xs text-muted-foreground">{event.id}</span>
            </Detail>
          </dl>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
          <Button
            onClick={() => {
              onInspect(event.occurredAt)
            }}
          >
            <Crosshair aria-hidden="true" />
            Inspect factory at this moment
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EventRow({
  event,
  timeZone,
  onSelect,
}: {
  event: OperationalEvent
  timeZone: string
  onSelect: (event: OperationalEvent) => void
}) {
  const time = formatTimeInZone(event.occurredAt, timeZone)
  return (
    <li>
      <button
        type="button"
        aria-label={`${time} ${EVENT_TYPE_LABELS[event.eventType]}, ${eventSubject(event)}`}
        onClick={() => {
          onSelect(event)
        }}
        className={cn(
          'grid w-full grid-cols-[3.5rem_1fr] items-start gap-x-4 gap-y-1 px-5 py-2.5 text-left text-sm transition-colors hover:bg-surface-hover md:grid-cols-[3.5rem_12rem_minmax(9rem,1fr)_minmax(7rem,1fr)_9rem_auto]',
          event.voided && 'opacity-60',
        )}
      >
        <time dateTime={event.occurredAt} className="metric text-muted-foreground">
          {time}
        </time>
        <EventTypeLabel type={event.eventType} className="font-medium text-foreground" />
        <span className="metric col-start-2 text-foreground md:col-start-auto">{eventSubject(event)}</span>
        <span className="col-start-2 text-muted-foreground md:col-start-auto">{event.stageName ?? ''}</span>
        <span className="col-start-2 md:col-start-auto md:justify-self-end md:text-right">
          {event.stoneQuantity !== null || event.caratQuantity !== null ? <EventQuantities event={event} /> : null}
        </span>
        <span className="col-start-2 md:col-start-auto">
          <CorrectionMarkers event={event} />
        </span>
      </button>
    </li>
  )
}

/** Events in the order received, split into runs that share a factory-local day. */
function groupByDay(events: readonly OperationalEvent[], timeZone: string): { day: string; events: OperationalEvent[] }[] {
  const groups: { day: string; events: OperationalEvent[] }[] = []
  for (const event of events) {
    const day = localDayKey(event.occurredAt, timeZone)
    const last = groups[groups.length - 1]
    if (last && last.day === day) last.events.push(event)
    else groups.push({ day, events: [event] })
  }
  return groups
}

function TimelineExplorer({ factory }: { factory: Factory }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = readFilters(searchParams)
  const [selected, setSelected] = useState<OperationalEvent | null>(null)

  /** Changing a filter returns to the first page; `undefined` removes a parameter. */
  const update = (changes: Record<string, string | undefined>, keepPage = false) => {
    const next = new URLSearchParams(searchParams)
    if (!keepPage) next.delete('page')
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === '') next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next, { replace: true })
  }

  const fromInstant = filters.from === null ? undefined : localDayRange(filters.from, factory.timeZone)?.from
  const toInstant = filters.to === null ? undefined : localDayRange(filters.to, factory.timeZone)?.to
  const rangeInverted = filters.from !== null && filters.to !== null && filters.to < filters.from

  const params: TimelineParams = {
    ...(filters.orderId === null ? {} : { orderId: filters.orderId }),
    ...(filters.stageId === null ? {} : { stageId: filters.stageId }),
    ...(filters.type === null ? {} : { type: filters.type }),
    ...(fromInstant === undefined ? {} : { from: fromInstant }),
    ...(toInstant === undefined ? {} : { to: toInstant }),
    ...(filters.includeVoided ? { includeVoided: true } : {}),
    order: filters.ascending ? 'asc' : 'desc',
    page: filters.page,
    size: PAGE_SIZE,
  }

  const summary = useQuery({
    queryKey: queryKeys.timeline.summary(factory.id, 'DAY'),
    queryFn: ({ signal }) => api.timeline.summary(factory.id, 'DAY', signal),
  })
  const events = useQuery({
    queryKey: queryKeys.timeline.list(factory.id, params),
    queryFn: ({ signal }) => api.timeline.list(factory.id, params, signal),
    placeholderData: keepPreviousData,
    enabled: !rangeInverted,
  })
  const workflows = useQuery({
    queryKey: queryKeys.workflows.list(factory.id),
    queryFn: ({ signal }) => api.workflows.list(factory.id, signal),
  })
  // The backend lists the active workflow first.
  const workflowId = workflows.data?.items[0]?.id
  const workflow = useQuery({
    queryKey: queryKeys.workflows.detail(workflowId ?? 'none'),
    queryFn: ({ signal }) => api.workflows.get(workflowId ?? '', signal),
    enabled: workflowId !== undefined,
  })
  const orderOptions = useQuery({
    queryKey: queryKeys.orders.list(factory.id, { page: 0, size: 200 }),
    queryFn: ({ signal }) => api.orders.list(factory.id, { page: 0, size: 200 }, signal),
  })

  const orders = orderOptions.data?.items ?? []
  const selectedOrder = orders.find((order) => order.id === filters.orderId)
  const [orderText, setOrderText] = useState<string | null>(null)
  const orderInput = orderText ?? selectedOrder?.orderNumber ?? ''

  const filtered =
    filters.stageId !== null ||
    filters.orderId !== null ||
    filters.type !== null ||
    filters.from !== null ||
    filters.to !== null ||
    filters.includeVoided
  const selectedDay = filters.from !== null && filters.from === filters.to ? filters.from : null

  const noHistory = summary.data !== undefined && summary.data.buckets.length === 0

  if (noHistory) {
    return (
      <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 text-sm text-muted-foreground">
        <p className="text-base font-medium text-foreground">Nothing has been recorded yet</p>
        <p className="mt-2 max-w-xl">
          The timeline fills as orders are placed and work on them is recorded. Place an order with{' '}
          <code className="metric">POST /api/orders</code>, or reset the demo data in Settings.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="activity-title">
        <CardHeader>
          <div className="min-w-0 flex-1 basis-72">
            <CardTitle id="activity-title">Activity by day</CardTitle>
            <CardDescription>
              Recorded events per day. Corrections and voided events are not counted.
              {summary.data?.firstEventAt && summary.data.lastEventAt ? (
                <>
                  {' '}
                  History runs from{' '}
                  <span className="metric">{formatDateTimeInZone(summary.data.firstEventAt, factory.timeZone)}</span> to{' '}
                  <span className="metric">{formatDateTimeInZone(summary.data.lastEventAt, factory.timeZone)}</span>.
                </>
              ) : null}
            </CardDescription>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {factory.demo ? <DemoTag /> : null}
            <SourceTag source="Actual" />
          </div>
        </CardHeader>
        {summary.isPending ? (
          <CardContent role="status" aria-label="Loading activity">
            <Skeleton className="h-56 w-full" />
          </CardContent>
        ) : summary.isError ? (
          <CardContent>
            <ErrorState error={summary.error} onRetry={() => void summary.refetch()} retrying={summary.isFetching} />
          </CardContent>
        ) : (
          <ActivityChart
            summary={summary.data}
            selectedDay={selectedDay}
            onSelectDay={(day) => {
              update({ from: day, to: day })
            }}
          />
        )}
      </Card>

      <StateInspector
        factory={factory}
        at={filters.at}
        firstEventAt={summary.data?.firstEventAt ?? null}
        onAt={(at) => {
          update({ at: at ?? undefined }, true)
        }}
      />

      <Card aria-labelledby="events-title" aria-busy={events.isFetching}>
        <CardHeader>
          <div className="min-w-0">
            <CardTitle id="events-title">Events</CardTitle>
            <CardDescription>
              What happened, as now known. Times are factory local time ({factory.timeZone}). Select an event for its
              details.
            </CardDescription>
          </div>
          {events.data ? (
            <p className="metric text-sm text-muted-foreground">
              {formatInteger(events.data.totalItems)} {events.data.totalItems === 1 ? 'event' : 'events'}
            </p>
          ) : null}
        </CardHeader>

        <form
          aria-label="Filter events"
          className="grid grid-cols-1 gap-3 border-b border-border px-5 py-4 sm:grid-cols-2 lg:grid-cols-5"
          onSubmit={(event) => {
            event.preventDefault()
          }}
        >
          <FilterField id="filter-stage" label="Stage">
            <Select
              id="filter-stage"
              value={filters.stageId ?? ''}
              onChange={(event) => {
                update({ stage: event.target.value })
              }}
            >
              <option value="">All stages</option>
              {(workflow.data?.stages ?? []).map((stage) => (
                <option key={stage.id} value={stage.id}>
                  {stage.sequence}. {stage.name}
                </option>
              ))}
            </Select>
          </FilterField>
          <FilterField id="filter-type" label="Event type">
            <Select
              id="filter-type"
              value={filters.type ?? ''}
              onChange={(event) => {
                update({ type: event.target.value })
              }}
            >
              <option value="">All types</option>
              {EVENT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {EVENT_TYPE_LABELS[type]}
                </option>
              ))}
            </Select>
          </FilterField>
          <FilterField id="filter-order" label="Order number">
            <Input
              id="filter-order"
              list="filter-order-options"
              placeholder="All orders"
              autoComplete="off"
              value={orderInput}
              onChange={(event) => {
                const value = event.target.value
                const match = orders.find((order) => order.orderNumber.toLowerCase() === value.trim().toLowerCase())
                if (match) {
                  setOrderText(null)
                  update({ order: match.id })
                } else {
                  setOrderText(value)
                  if (filters.orderId !== null) update({ order: undefined })
                }
              }}
            />
            <datalist id="filter-order-options">
              {orders.map((order) => (
                <option key={order.id} value={order.orderNumber} />
              ))}
            </datalist>
          </FilterField>
          <FilterField id="filter-from" label="From day">
            <Input
              id="filter-from"
              type="date"
              value={filters.from ?? ''}
              onChange={(event) => {
                update({ from: event.target.value })
              }}
            />
          </FilterField>
          <FilterField id="filter-to" label="To day">
            <Input
              id="filter-to"
              type="date"
              value={filters.to ?? ''}
              aria-invalid={rangeInverted}
              onChange={(event) => {
                update({ to: event.target.value })
              }}
            />
          </FilterField>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 sm:col-span-2 lg:col-span-5">
            <Segmented
              label="Order of events"
              value={filters.ascending ? 'asc' : 'desc'}
              options={[
                { value: 'desc', label: 'Newest first' },
                { value: 'asc', label: 'Oldest first' },
              ]}
              onChange={(value) => {
                update({ sort: value === 'asc' ? 'asc' : undefined })
              }}
            />
            <label className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
              <input
                type="checkbox"
                className="size-4 accent-primary"
                checked={filters.includeVoided}
                onChange={(event) => {
                  update({ voided: event.target.checked ? '1' : undefined })
                }}
              />
              Include voided events
            </label>
            {filtered ? (
              <Button
                variant="ghost"
                size="sm"
                className="ml-auto"
                onClick={() => {
                  setOrderText(null)
                  update({ stage: undefined, order: undefined, type: undefined, from: undefined, to: undefined, voided: undefined })
                }}
              >
                <FilterX aria-hidden="true" />
                Clear filters
              </Button>
            ) : null}
          </div>
        </form>

        {rangeInverted ? (
          <CardContent role="alert" className="text-sm text-status-error">
            “To day” is before “From day”. Change one of them to see events.
          </CardContent>
        ) : events.isPending ? (
          <CardContent role="status" aria-label="Loading events">
            <Skeleton className="h-80 w-full" />
          </CardContent>
        ) : events.isError ? (
          <CardContent>
            <ErrorState error={events.error} onRetry={() => void events.refetch()} retrying={events.isFetching} />
          </CardContent>
        ) : events.data.totalItems === 0 ? (
          <CardContent className="text-sm text-muted-foreground">
            <p className="font-medium text-foreground">No events match these filters</p>
            <p className="mt-1">Widen the dates or clear a filter to see more of the history.</p>
          </CardContent>
        ) : (
          <>
            {groupByDay(events.data.items, factory.timeZone).map((group) => (
              <section key={group.day} aria-label={formatDay(group.day)}>
                <h3 className="sticky top-0 z-10 border-b border-border bg-surface-raised px-5 py-1.5 text-xs font-semibold text-foreground">
                  {formatDay(group.day)}
                </h3>
                <ul className="divide-y divide-border">
                  {group.events.map((event) => (
                    <EventRow key={event.id} event={event} timeZone={factory.timeZone} onSelect={setSelected} />
                  ))}
                </ul>
              </section>
            ))}
            <Pagination
              page={events.data.page}
              size={events.data.size}
              totalItems={events.data.totalItems}
              totalPages={events.data.totalPages}
              noun="events"
              onPage={(next) => {
                update({ page: next === 0 ? undefined : String(next) }, true)
              }}
            />
          </>
        )}
      </Card>

      {selected ? (
        <EventDetailDialog
          event={selected}
          timeZone={factory.timeZone}
          onClose={() => {
            setSelected(null)
          }}
          onInspect={(at) => {
            setSelected(null)
            update({ at }, true)
          }}
        />
      ) : null}
    </div>
  )
}

export function TimelinePage() {
  return (
    <>
      <PageHeader
        title="Operational Timeline"
        description="The recorded history of the factory. Browse what happened, then pick any moment to see the factory as it stood."
      />
      <FactoryGate>{(factory) => <TimelineExplorer factory={factory} />}</FactoryGate>
    </>
  )
}

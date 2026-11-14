import { CircleCheck, CircleDashed, Clock, Hourglass, Play, Truck, type LucideIcon } from 'lucide-react'

import { Badge, type BadgeVariant } from '@/components/ui/badge'
import { formatCarats, formatStones } from '@/lib/format'
import {
  BATCH_STATUS_LABELS,
  EVENT_TYPE_ICONS,
  EVENT_TYPE_LABELS,
  ORDER_STATUS_LABELS,
  priorityName,
} from '@/lib/production'
import type { BatchStatus, EventType, OperationalEvent, OrderStatus } from '@/lib/schemas'
import { cn } from '@/lib/utils'

/* Small building blocks shared by every view of recorded production history. */

const ORDER_STATUS_STYLE: Record<OrderStatus, { variant: BadgeVariant; icon: LucideIcon }> = {
  PENDING: { variant: 'neutral', icon: CircleDashed },
  IN_PROGRESS: { variant: 'info', icon: Play },
  COMPLETED: { variant: 'ok', icon: CircleCheck },
  DISPATCHED: { variant: 'ok', icon: Truck },
}

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const { variant, icon: Icon } = ORDER_STATUS_STYLE[status]
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}

export function BatchStatusBadge({ status }: { status: BatchStatus }) {
  const variant: BadgeVariant = status === 'QUEUED' ? 'neutral' : status === 'IN_PROGRESS' ? 'info' : 'ok'
  const Icon = status === 'QUEUED' ? Hourglass : status === 'IN_PROGRESS' ? Play : CircleCheck
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {BATCH_STATUS_LABELS[status]}
    </Badge>
  )
}

/** Shown wherever an order is late: finished after its due date, or unfinished and past it. */
export function LateBadge() {
  return (
    <Badge variant="error" title="Finished after its due date, or unfinished and already past it.">
      <Clock aria-hidden="true" />
      Late
    </Badge>
  )
}

/** Priority as five pips and a word, so that it reads without knowing which end of the scale is urgent. */
export function PriorityIndicator({ priority }: { priority: number }) {
  return (
    <span
      className="inline-flex items-center gap-2 whitespace-nowrap"
      title={`Priority ${priority} of 5 (5 is most urgent)`}
    >
      <span aria-hidden="true" className="flex gap-0.5">
        {[1, 2, 3, 4, 5].map((pip) => (
          <span
            key={pip}
            className={cn(
              'h-3 w-1 rounded-[1px]',
              pip <= priority ? (priority >= 4 ? 'bg-status-warn' : 'bg-primary') : 'bg-border-strong',
            )}
          />
        ))}
      </span>
      <span className="text-xs text-muted-foreground">
        <span className="metric">{priority}</span> {priorityName(priority)}
      </span>
    </span>
  )
}

/** Icon and human label of an event type. */
export function EventTypeLabel({ type, className }: { type: EventType; className?: string }) {
  const Icon = EVENT_TYPE_ICONS[type]
  return (
    <span className={cn('inline-flex items-center gap-2 whitespace-nowrap', className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
      {EVENT_TYPE_LABELS[type]}
    </span>
  )
}

function Corrected({ original, effective }: { original: string | null; effective: string }) {
  if (original === null || original === effective) return <>{effective}</>
  return (
    <>
      <s className="text-subtle-foreground" title="As first recorded">
        {original}
      </s>{' '}
      <span title="After correction">{effective}</span>
    </>
  )
}

/**
 * The stones and carats an event carries, each with its unit. When the event was corrected, the
 * value first recorded is shown struck through beside the effective one.
 */
export function EventQuantities({ event }: { event: OperationalEvent }) {
  if (event.stoneQuantity === null && event.caratQuantity === null) {
    return <span className="text-subtle-foreground">—</span>
  }
  const originalStones = event.original?.stoneQuantity ?? null
  const originalCarats = event.original?.caratQuantity ?? null
  return (
    <span className="metric flex flex-col gap-0.5 whitespace-nowrap">
      {event.stoneQuantity !== null ? (
        <span>
          <Corrected
            original={originalStones === null ? null : formatStones(originalStones)}
            effective={formatStones(event.stoneQuantity)}
          />
        </span>
      ) : null}
      {event.caratQuantity !== null ? (
        <span className="text-muted-foreground">
          <Corrected
            original={originalCarats === null ? null : formatCarats(originalCarats)}
            effective={formatCarats(event.caratQuantity)}
          />
        </span>
      ) : null}
    </span>
  )
}

/** Markers for an event that a correction has changed or cancelled. */
export function CorrectionMarkers({ event }: { event: OperationalEvent }) {
  if (!event.corrected && !event.voided) return null
  return (
    <span className="inline-flex flex-wrap gap-1">
      {event.corrected ? (
        <Badge variant="warn" title="A later correction replaced a value of this event. The original row is unchanged.">
          Corrected
        </Badge>
      ) : null}
      {event.voided ? (
        <Badge variant="error" title="A later correction cancelled this event. It no longer counts.">
          Voided
        </Badge>
      ) : null}
    </span>
  )
}

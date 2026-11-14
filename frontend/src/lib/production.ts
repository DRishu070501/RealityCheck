import {
  Ban,
  CircleCheck,
  ClipboardList,
  FilePen,
  Hourglass,
  PackageCheck,
  Play,
  Truck,
  type LucideIcon,
} from 'lucide-react'

import type { BatchStatus, EventType, OperationalEvent, OrderStatus } from '@/lib/schemas'

/* Names for the enums of recorded production history, shared by every view that shows them. */

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING: 'Pending',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  DISPATCHED: 'Dispatched',
}

export const BATCH_STATUS_LABELS: Record<BatchStatus, string> = {
  QUEUED: 'Queued',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Finished',
}

const PRIORITY_NAMES = ['Lowest', 'Low', 'Normal', 'High', 'Urgent'] as const

/** Name of a priority from 1 (lowest) to 5 (most urgent). */
export function priorityName(priority: number): string {
  return PRIORITY_NAMES[priority - 1] ?? `Priority ${priority}`
}

export const EVENT_TYPES: readonly EventType[] = [
  'ORDER_CREATED',
  'STAGE_QUEUED',
  'STAGE_STARTED',
  'STAGE_COMPLETED',
  'ORDER_COMPLETED',
  'ORDER_DISPATCHED',
  'RESOURCE_UNAVAILABLE',
]

export const EVENT_TYPE_LABELS: Record<EventType, string> = {
  ORDER_CREATED: 'Order placed',
  STAGE_QUEUED: 'Queued at stage',
  STAGE_STARTED: 'Stage started',
  STAGE_COMPLETED: 'Stage completed',
  RESOURCE_UNAVAILABLE: 'Resource unavailable',
  ORDER_COMPLETED: 'Order completed',
  ORDER_DISPATCHED: 'Order dispatched',
  CORRECTION: 'Correction',
}

export const EVENT_TYPE_ICONS: Record<EventType, LucideIcon> = {
  ORDER_CREATED: ClipboardList,
  STAGE_QUEUED: Hourglass,
  STAGE_STARTED: Play,
  STAGE_COMPLETED: CircleCheck,
  RESOURCE_UNAVAILABLE: Ban,
  ORDER_COMPLETED: PackageCheck,
  ORDER_DISPATCHED: Truck,
  CORRECTION: FilePen,
}

/** "SDW-0042 · batch 1", or the resource name for a resource event. */
export function eventSubject(event: OperationalEvent): string {
  if (event.orderNumber !== null) {
    return event.batchNumber !== null ? `${event.orderNumber} · batch ${event.batchNumber}` : event.orderNumber
  }
  return event.resourceName ?? '—'
}

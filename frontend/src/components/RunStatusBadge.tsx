import { CircleCheck, CircleX, Clock, LoaderCircle } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import type { RunStatus } from '@/lib/scenarioSchemas'

const STATUS = {
  PENDING: { label: 'Queued', variant: 'neutral', icon: Clock },
  RUNNING: { label: 'Running', variant: 'info', icon: LoaderCircle },
  COMPLETED: { label: 'Completed', variant: 'ok', icon: CircleCheck },
  FAILED: { label: 'Failed', variant: 'error', icon: CircleX },
} as const

/** A simulation run's status as a word and an icon. "Not run" when there is no run. */
export function RunStatusBadge({ status }: { status: RunStatus | null | undefined }) {
  if (!status) return <Badge variant="neutral">Not run</Badge>
  const { label, variant, icon: Icon } = STATUS[status]
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" className={status === 'RUNNING' ? 'animate-spin' : undefined} />
      {label}
    </Badge>
  )
}

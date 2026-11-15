import { ArrowDown, ArrowUp, Minus } from 'lucide-react'

import { NOT_AVAILABLE, formatByUnit, formatSignedPercent } from '@/lib/metrics'
import type { Direction } from '@/lib/scenarioSchemas'
import { cn } from '@/lib/utils'

const DIRECTION = {
  IMPROVED: { label: 'improved', className: 'text-status-ok' },
  WORSENED: { label: 'worsened', className: 'text-status-error' },
  UNCHANGED: { label: 'unchanged', className: 'text-muted-foreground' },
} as const

interface DeltaCellProps {
  absolute: string | null
  percent: string | null
  unit: string
  currencyCode: string
  /** Null when the metric has no better direction (utilization) or the difference is unknown. */
  direction?: Direction | null
}

/**
 * A difference, alternative minus baseline. Direction is given by an arrow and a word as well as
 * colour, never colour alone; metrics without a better direction are shown neutrally.
 */
export function DeltaCell({ absolute, percent, unit, currencyCode, direction = null }: DeltaCellProps) {
  if (absolute === null) {
    return (
      <span className="text-muted-foreground" title="One of the runs has no value for this metric.">
        {NOT_AVAILABLE}
      </span>
    )
  }
  const number = Number(absolute)
  const Icon = number > 0 ? ArrowUp : number < 0 ? ArrowDown : Minus
  const style = direction ? DIRECTION[direction] : null
  return (
    <span className={cn('metric inline-flex items-center gap-1', style?.className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span>{formatByUnit(absolute, unit, currencyCode, true)}</span>
      {percent !== null ? <span className="text-xs opacity-80">({formatSignedPercent(percent)})</span> : null}
      {style ? <span className="text-xs">{style.label}</span> : null}
    </span>
  )
}

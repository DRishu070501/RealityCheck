import { formatInteger } from '@/lib/format'
import type { StageState } from '@/lib/schemas'
import { cn } from '@/lib/utils'

/**
 * Every stage in sequence with how many batches are waiting, being worked on, and through it.
 * The stage or stages with the most batches waiting are marked "Longest queue": a statement of
 * fact about the counts, not a bottleneck verdict, which only a simulation run can give.
 */
export function StageStrip({ stages, label }: { stages: readonly StageState[]; label: string }) {
  const longest = Math.max(0, ...stages.map((stage) => stage.queued))
  return (
    <ol
      aria-label={label}
      className="grid grid-cols-2 gap-2 px-5 py-4 sm:grid-cols-4 xl:grid-cols-8"
    >
      {stages.map((stage) => {
        const isLongest = longest > 0 && stage.queued === longest
        return (
          <li
            key={stage.stageId}
            aria-label={stage.name}
            className={cn(
              'flex min-w-0 flex-col gap-2 rounded-md border px-3 py-2.5',
              isLongest ? 'border-status-warn/50 bg-status-warn-surface' : 'border-border-strong bg-surface-raised',
            )}
          >
            <div className="min-w-0">
              <p className="metric text-xs text-subtle-foreground">Stage {stage.sequence}</p>
              <p className="min-h-10 text-sm leading-5 font-medium text-foreground">{stage.name}</p>
            </div>
            <dl className="metric flex flex-col gap-0.5 text-xs">
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-muted-foreground">Queued</dt>
                <dd className={cn('text-sm font-semibold', isLongest ? 'text-status-warn' : 'text-foreground')}>
                  {formatInteger(stage.queued)}
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-muted-foreground">In progress</dt>
                <dd className="text-sm font-semibold text-foreground">{formatInteger(stage.inProgress)}</dd>
              </div>
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-subtle-foreground">Completed</dt>
                <dd className="text-muted-foreground">{formatInteger(stage.completed)}</dd>
              </div>
            </dl>
            {isLongest ? <p className="text-xs font-medium text-status-warn">Longest queue</p> : null}
          </li>
        )
      })}
    </ol>
  )
}

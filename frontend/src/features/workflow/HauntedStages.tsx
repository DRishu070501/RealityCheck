import { Ghost } from 'lucide-react'

import { SourceTag } from '@/components/DataTags'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { SimpleTable } from '@/features/results/resultsUi'
import { formatDuration, formatInteger, formatYield } from '@/lib/format'
import type { Insights, StageTime } from '@/lib/schemas'

const KIND_WORDS: Record<StageTime['kind'], string> = {
  WORKING: 'waiting to be worked on',
  PARKING: 'parked or waiting',
  WAITING: 'waiting',
  JOB_WORK: 'out with the job worker or waiting',
}

/** Rounded to whole hours above two hours, so long totals stay readable. */
function hours(seconds: number): string {
  return seconds >= 7200 ? `${formatInteger(Math.round(seconds / 3600))} h` : formatDuration(seconds)
}

/** "34% of all packet time was spent parked or waiting here." */
function hauntedSentence(stage: StageTime): string {
  return `${formatYield(stage.idleShare ?? '0')} of all packet time was spent ${KIND_WORDS[stage.kind]} here.`
}

/**
 * The stages where recorded packets lost the most time without being worked on: waiting in a queue, or
 * held at a parking, waiting or job-work stage. Ranked by the backend from recorded history (Actual).
 */
export function HauntedStages({ insights }: { insights: Insights }) {
  const haunted = insights.stages
    .filter((stage) => stage.hauntedRank !== null)
    .sort((a, b) => (a.hauntedRank ?? 0) - (b.hauntedRank ?? 0))
  return (
    <Card aria-labelledby="haunted-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="haunted-title" className="flex items-center gap-2">
            <Ghost aria-hidden="true" className="size-4 text-accent" />
            Haunted stages
          </CardTitle>
          <CardDescription>
            Where packets lose the most time without anyone working on them, from every recorded visit. More people
            only help where the time is spent waiting for capacity.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {insights.totalSeconds === 0 ? (
          <p className="text-sm text-muted-foreground">No packet has visited a stage yet.</p>
        ) : haunted.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No stage holds 5% or more of all packet time without work: time is spread evenly.
          </p>
        ) : (
          <ol aria-label="Haunted stages, worst first" className="flex flex-col gap-2">
            {haunted.map((stage) => (
              <li
                key={stage.stageId}
                className="flex flex-col gap-1 rounded-md border border-border-strong bg-surface-raised px-3 py-2.5"
              >
                <span className="flex items-baseline justify-between gap-3">
                  <span className="text-sm font-medium text-foreground">
                    <span className="metric text-subtle-foreground">#{stage.hauntedRank} </span>
                    {stage.name}
                  </span>
                  <span className="metric text-base font-semibold text-foreground">
                    {formatYield(stage.idleShare ?? '0')}
                  </span>
                </span>
                <span className="text-xs text-muted-foreground">{hauntedSentence(stage)}</span>
                <span className="metric text-xs text-subtle-foreground">
                  {formatInteger(stage.visits)} visits · waiting {hours(stage.waitingSeconds)} ·{' '}
                  {stage.kind === 'WORKING' ? 'worked' : 'held'} {hours(stage.activeSeconds)}
                </span>
              </li>
            ))}
          </ol>
        )}
        {insights.totalSeconds > 0 ? (
          <details className="text-xs">
            <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
              Show every stage as a table
            </summary>
            <div className="mt-2">
              <SimpleTable
                caption="Packet time at every stage, from recorded history"
                headers={['Stage', 'Visits', 'Waiting', 'Worked or held', 'Share of all time', 'Without work']}
                rows={insights.stages.map((stage) => [
                  `${stage.sequence}. ${stage.name}`,
                  formatInteger(stage.visits),
                  hours(stage.waitingSeconds),
                  hours(stage.activeSeconds),
                  stage.share === null ? '—' : formatYield(stage.share),
                  stage.idleShare === null ? '—' : formatYield(stage.idleShare),
                ])}
              />
            </div>
          </details>
        ) : null}
      </CardContent>
    </Card>
  )
}

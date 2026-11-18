import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { SimpleTable } from '@/features/results/resultsUi'
import { HauntedStages } from '@/features/workflow/HauntedStages'
import { api } from '@/lib/api'
import { formatDecimal, formatInteger, formatShortDateTimeInZone } from '@/lib/format'
import { formatElapsed, formatFraction, NOT_AVAILABLE } from '@/lib/metrics'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, Insights, StageTime } from '@/lib/schemas'

function elapsed(seconds: number | null): string {
  return seconds === null ? NOT_AVAILABLE : formatElapsed(seconds)
}

function share(fraction: string | null): string {
  return fraction === null ? NOT_AVAILABLE : formatFraction(fraction)
}

function Figure({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="flex flex-col gap-0.5 px-5 py-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="metric text-xl font-semibold text-foreground">{value}</dd>
      <dd className="text-xs text-subtle-foreground">{hint}</dd>
    </div>
  )
}

function LeadTimes({ insights, timeZone }: { insights: Insights; timeZone: string }) {
  const all = insights.leadTimes[0]
  // Parked packets are mostly still parked, so finished packets understate parking: show it over all time.
  const parkedAll = insights.stages
    .filter((stage) => stage.kind === 'PARKING')
    .reduce((sum, stage) => sum + Number(stage.share ?? '0'), 0)
  const window = insights.from
    ? `${formatShortDateTimeInZone(insights.from, timeZone)} to ${formatShortDateTimeInZone(insights.until, timeZone)}`
    : 'no recorded history yet'
  return (
    <Card aria-labelledby="lead-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="lead-title">Lead time</CardTitle>
          <CardDescription>
            From a packet first being queued to its last stage, for packets that finished in the recorded history
            ({window}). Packets still on their way are not counted, so in a short history long journeys are
            under-represented.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      {all && all.finished > 0 ? (
        <>
          <dl className="grid grid-cols-1 divide-y divide-border border-b border-border sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-4">
            <Figure label="Median lead time" value={elapsed(all.medianLeadSeconds)} hint="Calendar time, nights included." />
            <Figure
              label="Median without parking"
              value={elapsed(all.medianWithoutParkingSeconds)}
              hint="The same packets, time in the parking store left out."
            />
            <Figure
              label="Time parked, all packets"
              value={insights.totalSeconds === 0 ? NOT_AVAILABLE : formatFraction(String(parkedAll))}
              hint="Of all recorded packet time, including packets still on their way (most parked ones are)."
            />
            <Figure label="Packets finished" value={formatInteger(all.finished)} hint="In the recorded history." />
          </dl>
          <CardContent>
            <SimpleTable
              caption="Lead time by route"
              headers={['Route', 'Packets finished', 'Median lead time', 'Median without parking', 'Parked (finished packets)']}
              rows={insights.leadTimes.map((row) => [
                row.name,
                formatInteger(row.finished),
                elapsed(row.medianLeadSeconds),
                elapsed(row.medianWithoutParkingSeconds),
                share(row.parkingShare),
              ])}
            />
          </CardContent>
        </>
      ) : (
        <CardContent className="text-sm text-muted-foreground">No packet has finished its journey yet.</CardContent>
      )}
    </Card>
  )
}

const SEGMENTS = [
  { key: 'waiting', label: 'Waiting in a queue', className: 'bg-status-warn' },
  { key: 'worked', label: 'Being worked on', className: 'bg-source-actual' },
  { key: 'held', label: 'Held (parked, at a job worker, waiting for dispatch)', className: 'bg-accent' },
] as const

function segments(stage: StageTime): Record<(typeof SEGMENTS)[number]['key'], number> {
  const working = stage.kind === 'WORKING'
  return {
    waiting: stage.waitingSeconds,
    worked: working ? stage.activeSeconds : 0,
    held: working ? 0 : stage.activeSeconds,
  }
}

/** A stacked bar per stage, scaled to the stage with the most packet time. */
function StageBars({ insights }: { insights: Insights }) {
  const max = Math.max(1, ...insights.stages.map((stage) => stage.waitingSeconds + stage.activeSeconds))
  return (
    <Card aria-labelledby="stage-time-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="stage-time-title">Where packet time goes, by stage</CardTitle>
          <CardDescription>
            Calendar time summed over every recorded visit. Waiting is from being queued to being started; at a
            working stage this includes time the work was done but the packet had not yet moved on.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul aria-label="Legend" className="flex flex-wrap gap-4 text-xs text-muted-foreground">
          {SEGMENTS.map((segment) => (
            <li key={segment.key} className="flex items-center gap-1.5">
              <span aria-hidden="true" className={`size-2.5 rounded-sm ${segment.className}`} />
              {segment.label}
            </li>
          ))}
        </ul>
        <ol aria-label="Packet time per stage" className="flex flex-col gap-1.5" data-testid="stage-bars">
          {insights.stages.map((stage) => {
            const parts = segments(stage)
            const total = stage.waitingSeconds + stage.activeSeconds
            return (
              <li key={stage.stageId} className="grid grid-cols-[minmax(7rem,11rem)_1fr_4.5rem] items-center gap-2 text-xs">
                <span className="truncate text-foreground">{stage.name}</span>
                <span className="flex h-3 overflow-hidden rounded-sm bg-surface-hover" aria-hidden="true">
                  {SEGMENTS.map((segment) =>
                    parts[segment.key] > 0 ? (
                      <span
                        key={segment.key}
                        className={segment.className}
                        style={{ width: `${(parts[segment.key] / max) * 100}%` }}
                      />
                    ) : null,
                  )}
                </span>
                <span className="metric text-right text-muted-foreground">{share(stage.share)}</span>
                <span className="sr-only">
                  {`${stage.name}: ${share(stage.share)} of all packet time; waiting ${formatElapsed(parts.waiting)}, worked ${formatElapsed(parts.worked)}, held ${formatElapsed(parts.held)}. Total ${formatElapsed(total)}.`}
                </span>
              </li>
            )
          })}
        </ol>
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Show as table</summary>
          <div className="mt-2">
            <SimpleTable
              caption="Packet time per stage"
              headers={['Stage', 'Visits', 'Waiting', 'Worked', 'Held', 'Share of all time']}
              rows={insights.stages.map((stage) => {
                const parts = segments(stage)
                return [
                  `${stage.sequence}. ${stage.name}`,
                  formatInteger(stage.visits),
                  formatElapsed(parts.waiting),
                  formatElapsed(parts.worked),
                  formatElapsed(parts.held),
                  share(stage.share),
                ]
              })}
            />
          </div>
        </details>
      </CardContent>
    </Card>
  )
}

function Yields({ insights }: { insights: Insights }) {
  return (
    <Card aria-labelledby="yield-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="yield-title">Yield by stage</CardTitle>
          <CardDescription>What left each stage over what entered it, over visits that finished there.</CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <CardContent>
        <SimpleTable
          caption="Recorded yield by stage"
          headers={['Stage', 'Visits finished', 'Stones in', 'Stones out', 'Stone yield', 'Carats in', 'Carats out', 'Carat yield']}
          rows={insights.stages.map((stage) => [
            `${stage.sequence}. ${stage.name}`,
            formatInteger(stage.completedVisits),
            formatDecimal(stage.stonesIn, 0),
            formatDecimal(stage.stonesOut, 0),
            share(stage.stoneYield),
            formatDecimal(stage.caratsIn, 2),
            formatDecimal(stage.caratsOut, 2),
            share(stage.caratYield),
          ])}
        />
      </CardContent>
    </Card>
  )
}

function Dispatch({ insights }: { insights: Insights }) {
  const { dispatch } = insights
  return (
    <Card aria-labelledby="dispatch-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="dispatch-title">Waiting for dispatch</CardTitle>
          <CardDescription>Lots that finished every stage, and how long they waited to leave.</CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <dl className="grid grid-cols-1 divide-y divide-border sm:grid-cols-3 sm:divide-y-0">
        <Figure label="Lots dispatched" value={formatInteger(dispatch.dispatched)} hint="After finishing every stage." />
        <Figure
          label="Median wait to dispatch"
          value={elapsed(dispatch.medianWaitSeconds)}
          hint="From the lot's last stage to leaving."
        />
        <Figure label="Finished, not yet dispatched" value={formatInteger(dispatch.waiting)} hint="At the end of the history." />
      </dl>
    </Card>
  )
}

function InsightsView({ factory }: { factory: Factory }) {
  const insights = useQuery({
    queryKey: queryKeys.factories.insights(factory.id),
    queryFn: ({ signal }) => api.factories.insights(factory.id, signal),
  })
  if (insights.isPending) {
    return (
      <div role="status" aria-label="Loading where time goes" className="flex flex-col gap-3">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    )
  }
  if (insights.isError) {
    return <ErrorState error={insights.error} onRetry={() => void insights.refetch()} retrying={insights.isFetching} />
  }
  return (
    <div className="flex flex-col gap-6">
      <LeadTimes insights={insights.data} timeZone={factory.timeZone} />
      <StageBars insights={insights.data} />
      <HauntedStages insights={insights.data} />
      <Yields insights={insights.data} />
      <Dispatch insights={insights.data} />
      <p className="flex items-start gap-2 text-xs text-subtle-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
        <span>
          Every figure on this page is computed from recorded events only, corrections applied. Nothing here is
          simulated or estimated; Scenario Lab shows what a change would do.
        </span>
      </p>
    </div>
  )
}

export function InsightsPage() {
  return (
    <>
      <PageHeader
        title="Where Time Goes"
        description="Where packets spend their time between arriving and leaving, from recorded history."
      />
      <FactoryGate>{(factory) => <InsightsView factory={factory} />}</FactoryGate>
    </>
  )
}

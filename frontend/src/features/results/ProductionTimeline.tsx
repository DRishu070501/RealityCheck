import { useQuery } from '@tanstack/react-query'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { SimpleTable } from '@/features/results/resultsUi'
import { api } from '@/lib/api'
import { formatInteger, formatShortDateTimeInZone } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { buildLanes, type Lane } from '@/features/results/lanes'
import type { SimulatedEvent, StageResult } from '@/lib/scenarioSchemas'

const PAGE_SIZE = 200
/** At most this many pages (2,000 events) are drawn; the panel says so when a run has more. */
const MAX_PAGES = 10

interface Loaded {
  events: SimulatedEvent[]
  total: number
}

async function loadEvents(runId: string, signal: AbortSignal): Promise<Loaded> {
  const events: SimulatedEvent[] = []
  let total = 0
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await api.simulations.events(runId, page, PAGE_SIZE, signal)
    events.push(...result.items)
    total = result.totalItems
    if (page + 1 >= result.totalPages) break
  }
  return { events, total }
}

const WIDTH = 1000
const LABEL = 150
const LANE = 26
const AXIS = 28
const TICKS = 5

function Gantt({ lanes, timeZone }: { lanes: Lane[]; timeZone: string }) {
  const all = lanes.flatMap((lane) => lane.bars)
  const min = Math.min(...all.map((bar) => bar.start))
  const max = Math.max(...all.map((bar) => bar.end))
  const span = Math.max(1, max - min)
  const x = (time: number) => LABEL + ((time - min) / span) * (WIDTH - LABEL - 10)
  const height = lanes.length * LANE + AXIS
  const ticks = Array.from({ length: TICKS + 1 }, (_, index) => min + (span * index) / TICKS)
  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${height}`}
      className="h-auto w-full"
      role="img"
      aria-label="Production timeline: jobs in progress at each stage over simulated time"
      data-testid="production-gantt"
    >
      {lanes.map((lane, index) => (
        <g key={lane.stage.stageId} data-testid="gantt-lane">
          <text x={0} y={index * LANE + LANE / 2 + 4} fontSize={11} fill="var(--muted-foreground)">
            {lane.stage.name}
          </text>
          <line
            x1={LABEL}
            x2={WIDTH - 10}
            y1={(index + 1) * LANE}
            y2={(index + 1) * LANE}
            stroke="var(--border)"
          />
          {lane.bars.map((bar) => (
            <rect
              key={`${bar.batchId}-${bar.start}`}
              x={x(bar.start)}
              y={index * LANE + 5}
              width={Math.max(1.5, x(bar.end) - x(bar.start))}
              height={LANE - 10}
              rx={2}
              fill="var(--source-simulated)"
              fillOpacity={0.55}
            />
          ))}
        </g>
      ))}
      {ticks.map((tick) => (
        <text
          key={tick}
          x={Math.min(x(tick), WIDTH - 90)}
          y={lanes.length * LANE + 18}
          fontSize={10}
          fill="var(--subtle-foreground)"
        >
          {formatShortDateTimeInZone(new Date(tick).toISOString(), timeZone)}
        </text>
      ))}
    </svg>
  )
}

/** Stage lanes over simulated time, from the run's stored event trace. */
export function ProductionTimeline({
  runId,
  stages,
  timeZone,
}: {
  runId: string
  stages: readonly StageResult[]
  timeZone: string
}) {
  const events = useQuery({
    queryKey: queryKeys.simulations.events(runId),
    queryFn: ({ signal }) => loadEvents(runId, signal),
    staleTime: Infinity,
  })
  const lanes = events.data ? buildLanes(events.data.events, stages) : []
  const drawn = lanes.some((lane) => lane.bars.length > 0)
  return (
    <Card aria-labelledby="timeline-title">
      <CardHeader>
        <div>
          <CardTitle id="timeline-title">Production timeline</CardTitle>
          <CardDescription>
            Each bar is one batch being worked on at a stage, from start to completion. Times are in the factory's
            zone ({timeZone}).
          </CardDescription>
        </div>
        <SourceTag source="Simulated" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {events.isPending ? (
          <Skeleton className="h-56 w-full" />
        ) : events.isError ? (
          <ErrorState error={events.error} onRetry={() => void events.refetch()} retrying={events.isFetching} />
        ) : !drawn ? (
          <p className="text-sm text-muted-foreground">No job started in this run.</p>
        ) : (
          <>
            {events.data.events.length < events.data.total ? (
              <p className="text-xs text-status-warn">
                Showing the first {formatInteger(events.data.events.length)} of {formatInteger(events.data.total)}{' '}
                simulated events; later activity is not drawn.
              </p>
            ) : null}
            <Gantt lanes={lanes} timeZone={timeZone} />
            <details className="text-xs">
              <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Show as table</summary>
              <div className="mt-2">
                <SimpleTable
                  caption="Jobs per stage in the production timeline"
                  headers={['Stage', 'Jobs', 'First start', 'Last completion']}
                  rows={lanes.map((lane) => {
                    const starts = lane.bars.map((bar) => bar.start)
                    const ends = lane.bars.map((bar) => bar.end)
                    return [
                      lane.stage.name,
                      formatInteger(lane.bars.length),
                      starts.length ? formatShortDateTimeInZone(new Date(Math.min(...starts)).toISOString(), timeZone) : '—',
                      ends.length ? formatShortDateTimeInZone(new Date(Math.max(...ends)).toISOString(), timeZone) : '—',
                    ]
                  })}
                />
              </div>
            </details>
          </>
        )}
      </CardContent>
    </Card>
  )
}

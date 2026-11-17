import { Ghost } from 'lucide-react'

import { SourceTag } from '@/components/DataTags'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { formatElapsed } from '@/lib/metrics'
import { formatInteger, formatShortDateTimeInZone } from '@/lib/format'
import type { ActualOutcome, AlternativeComparison } from '@/lib/scenarioSchemas'

/** At most this many orders are drawn; the card says so when there are more. */
const MAX_ROWS = 60
const WIDTH = 1000
const LABEL = 90
const ROW = 12
const AXIS = 26
const TICKS = 4

interface Row {
  orderNumber: string
  baseline: number | null
  ghost: number | null
  recorded: number | null
}

function time(value: string | null | undefined): number | null {
  return value ? Date.parse(value) : null
}

/** "12 of 40 orders finish earlier, by a median of 3 d 4 h; 2 later." */
function ghostSummary(rows: readonly Row[], alternativeName: string): string {
  const moved = rows.filter((row) => row.baseline !== null && row.ghost !== null)
  const earlier = moved.filter((row) => (row.ghost ?? 0) < (row.baseline ?? 0))
  const later = moved.filter((row) => (row.ghost ?? 0) > (row.baseline ?? 0))
  if (earlier.length === 0 && later.length === 0) {
    return `In “${alternativeName}” every order finishes when it does in the baseline.`
  }
  const gains = earlier.map((row) => (row.baseline ?? 0) - (row.ghost ?? 0)).sort((a, b) => a - b)
  const median = gains.length > 0 ? gains[Math.floor((gains.length - 1) / 2)] : undefined
  const by = median === undefined ? '' : `, by a median of ${formatElapsed(Math.round(median / 1000))}`
  return (
    `In “${alternativeName}” ${formatInteger(earlier.length)} of ${formatInteger(rows.length)} orders finish ` +
    `earlier${by}; ${formatInteger(later.length)} later.`
  )
}

/**
 * Ghost futures: each order's simulated completion in the baseline, with the alternative's completion drawn
 * as a translucent ghost beside it, and what recorded history says where it already knows. Simulated, except
 * the recorded marks.
 */
export function GhostFutures({
  alternative,
  actual,
  forkAt,
  baselineName,
  alternativeName,
  timeZone,
}: {
  alternative: AlternativeComparison
  actual: ActualOutcome | null
  forkAt: string
  baselineName: string
  alternativeName: string
  timeZone: string
}) {
  const recordedById = new Map((actual?.orders ?? []).map((order) => [order.orderId, time(order.completedAt)]))
  const all: Row[] = alternative.orders
    .map((order) => ({
      orderNumber: order.orderNumber,
      baseline: time(order.baselineCompletionAt),
      ghost: time(order.alternativeCompletionAt),
      recorded: recordedById.get(order.orderId) ?? null,
    }))
    .filter((row) => row.baseline !== null || row.ghost !== null)
    .sort((a, b) => (a.baseline ?? a.ghost ?? 0) - (b.baseline ?? b.ghost ?? 0))
  const rows = all.slice(0, MAX_ROWS)

  const start = Date.parse(forkAt)
  const times = rows.flatMap((row) => [row.baseline, row.ghost, row.recorded]).filter((t): t is number => t !== null)
  const end = Math.max(start + 1, ...times)
  const x = (t: number) => LABEL + ((t - start) / (end - start)) * (WIDTH - LABEL - 12)
  const height = rows.length * ROW + AXIS
  const ticks = Array.from({ length: TICKS + 1 }, (_, index) => start + ((end - start) * index) / TICKS)
  const summary = ghostSummary(all, alternativeName)

  return (
    <Card aria-labelledby="ghost-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="ghost-title" className="flex items-center gap-2">
            <Ghost aria-hidden="true" className="size-4 text-accent" />
            Ghost futures
          </CardTitle>
          <CardDescription>
            When each order finishes in {baselineName} (solid), and its ghost: when it would finish in{' '}
            {alternativeName}. {actual ? 'Diamonds mark what recorded history says happened, where it already knows.' : ''}
          </CardDescription>
        </div>
        <div className="flex flex-wrap gap-2">
          <SourceTag source="Simulated" />
          {actual ? <SourceTag source="Actual" /> : null}
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="text-sm text-foreground">{summary}</p>
        {rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">No order finishes in either run.</p>
        ) : (
          <>
            {all.length > rows.length ? (
              <p className="text-xs text-muted-foreground">
                Showing the first {formatInteger(rows.length)} of {formatInteger(all.length)} orders to finish; the
                order delivery table lists them all.
              </p>
            ) : null}
            <svg
              viewBox={`0 0 ${WIDTH} ${height}`}
              className="h-auto w-full"
              role="img"
              aria-label={`Ghost futures: ${summary} The order delivery table below lists every order.`}
              data-testid="ghost-futures"
            >
              {rows.map((row, index) => {
                const y = index * ROW + ROW / 2
                return (
                  <g key={row.orderNumber} data-testid="ghost-row">
                    <text x={0} y={y + 3} fontSize={9} fill="var(--muted-foreground)">
                      {row.orderNumber}
                    </text>
                    {row.baseline !== null && row.ghost !== null ? (
                      <line
                        x1={x(row.baseline)}
                        x2={x(row.ghost)}
                        y1={y}
                        y2={y}
                        stroke="var(--accent)"
                        strokeOpacity={0.5}
                        strokeDasharray="3 2"
                      />
                    ) : null}
                    {row.baseline !== null ? (
                      <circle cx={x(row.baseline)} cy={y} r={3.5} fill="var(--source-simulated)" data-testid="baseline-dot" />
                    ) : null}
                    {row.ghost !== null ? (
                      <circle
                        cx={x(row.ghost)}
                        cy={y}
                        r={4.5}
                        fill="var(--accent)"
                        fillOpacity={0.3}
                        stroke="var(--accent)"
                        strokeDasharray="2 1.5"
                        data-testid="ghost-dot"
                      />
                    ) : null}
                    {row.recorded !== null ? (
                      <rect
                        x={x(row.recorded) - 3}
                        y={y - 3}
                        width={6}
                        height={6}
                        transform={`rotate(45 ${x(row.recorded)} ${y})`}
                        fill="var(--source-actual)"
                        data-testid="recorded-mark"
                      />
                    ) : null}
                  </g>
                )
              })}
              {ticks.map((tick) => (
                <text
                  key={tick}
                  x={Math.min(x(tick), WIDTH - 90)}
                  y={rows.length * ROW + 18}
                  fontSize={10}
                  fill="var(--subtle-foreground)"
                >
                  {formatShortDateTimeInZone(new Date(tick).toISOString(), timeZone)}
                </text>
              ))}
            </svg>
            <ul aria-label="Legend" className="flex flex-wrap gap-4 text-xs text-muted-foreground">
              <li className="flex items-center gap-1.5">
                <span aria-hidden="true" className="size-2.5 rounded-full bg-source-simulated" />
                {baselineName}
              </li>
              <li className="flex items-center gap-1.5">
                <span aria-hidden="true" className="size-3 rounded-full border border-dashed border-accent bg-accent/30" />
                {alternativeName} (ghost)
              </li>
              {actual ? (
                <li className="flex items-center gap-1.5">
                  <span aria-hidden="true" className="size-2 rotate-45 bg-source-actual" />
                  Recorded
                </li>
              ) : null}
            </ul>
          </>
        )}
      </CardContent>
    </Card>
  )
}

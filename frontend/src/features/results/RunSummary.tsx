import { Star, TriangleAlert } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { SimpleTable, Tile } from '@/features/results/resultsUi'
import { formatDecimal, formatInteger, formatMoney, formatShortDateTimeInZone } from '@/lib/format'
import { formatElapsed, formatFraction, formatWorkingHours, NOT_AVAILABLE, NULL_REASON } from '@/lib/metrics'
import type { RunResults, StageResult } from '@/lib/scenarioSchemas'

const UNFINISHED_STATES: Record<string, string> = {
  QUEUED: 'waiting in a queue',
  RUNNING: 'still being worked on',
  NOT_ARRIVED: 'not yet arrived',
}

/** Shown whenever a run stopped on a limit: its totals cover only the work that was done. */
export function LimitWarning({ results }: { results: RunResults }) {
  if (results.terminationReason === 'COMPLETED') return null
  const counts = new Map<string, number>()
  for (const job of results.unfinished) counts.set(job.state, (counts.get(job.state) ?? 0) + 1)
  return (
    <div
      role="alert"
      className="flex items-start gap-2 rounded-md border border-status-warn/30 bg-status-warn-surface px-4 py-3 text-sm"
    >
      <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-warn" />
      <div>
        <p className="font-medium text-foreground">The simulation did not finish all work</p>
        <p className="text-muted-foreground">
          It stopped at {results.terminationReason === 'HORIZON_REACHED' ? 'the simulated-time limit' : 'the event limit'}
          . Totals below cover only what was done. Unfinished batches: {formatInteger(results.unfinished.length)}
          {counts.size > 0
            ? ` (${[...counts.entries()]
                .map(([state, count]) => `${formatInteger(count)} ${UNFINISHED_STATES[state] ?? state}`)
                .join(', ')})`
            : ''}
          .
        </p>
      </div>
    </div>
  )
}

/** The headline figures of one run. */
export function SummaryTiles({ results }: { results: RunResults }) {
  const s = results.summary
  const currency = results.currencyCode
  return (
    <dl className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-4">
      <Tile
        label="Completion time"
        value={formatElapsed(s.elapsedSeconds)}
        hint="From the fork to the last completion, nights and closed days included"
      />
      <Tile label="Working time" value={formatWorkingHours(s.workingSeconds)} hint="Factory open hours in that window" />
      <Tile
        label="Throughput"
        value={s.throughputStonesPerHour === null ? null : `${formatDecimal(s.throughputStonesPerHour, 2)} stones/h`}
        nullReason={NULL_REASON.throughputStonesPerHour}
        hint="Finished stones per working hour"
      />
      <Tile
        label="Output"
        value={`${formatInteger(Number(s.outputStonesRounded))} stones`}
        title={`Exactly ${s.outputStones} stones (expected value)`}
        hint={`${formatDecimal(s.outputCarats, 2)} ct finished`}
      />
      <Tile
        label="Stone yield"
        value={s.stoneYield === null ? null : formatFraction(s.stoneYield)}
        nullReason={NULL_REASON.stoneYield}
        hint={s.caratYield === null ? undefined : `Carat yield ${formatFraction(s.caratYield)}`}
      />
      <Tile
        label="Machine utilization"
        value={s.machineUtilization === null ? null : formatFraction(s.machineUtilization)}
        nullReason={NULL_REASON.machineUtilization}
      />
      <Tile
        label="Employee utilization"
        value={s.employeeUtilization === null ? null : formatFraction(s.employeeUtilization)}
        nullReason={NULL_REASON.employeeUtilization}
      />
      <Tile
        label="Total cost"
        value={formatMoney(s.cost.total, currency)}
        hint={
          s.cost.perOutputStone === null
            ? 'No cost per stone: nothing was produced'
            : `${formatMoney(s.cost.perOutputStone, currency)} per finished stone`
        }
      />
      <Tile
        label="Orders completed"
        value={`${formatInteger(s.ordersCompleted)} of ${formatInteger(s.ordersTotal)}`}
        hint={`${formatInteger(s.ordersDelayed)} delayed`}
      />
      <Tile
        label="On-time delivery"
        value={s.onTimeDeliveryRate === null ? null : formatFraction(s.onTimeDeliveryRate)}
        nullReason={NULL_REASON.onTimeDeliveryRate}
        hint={`Of ${formatInteger(s.ordersWithDueDate)} orders with a due date`}
      />
    </dl>
  )
}

function hours(seconds: string): string {
  return `${formatDecimal(String(Number(seconds) / 3600), 1)} h`
}

/** The server's bottleneck sentence and the evidence behind every stage's score. */
export function BottleneckCard({ results, stages }: { results: RunResults; stages: readonly StageResult[] }) {
  const primary = results.bottleneck.stageId
  return (
    <Card aria-labelledby="bottleneck-title">
      <CardHeader>
        <div>
          <CardTitle id="bottleneck-title">Bottleneck</CardTitle>
          <CardDescription>
            Decided by fixed rules from the run's numbers, not by AI. A stage is named only if its score is at least{' '}
            {results.bottleneck.scoreThreshold} and work waited behind full slots for at least{' '}
            {formatFraction(results.bottleneck.saturationThreshold, 0)} of the time it had work.
          </CardDescription>
        </div>
        {results.bottleneck.stageName ? (
          <Badge variant="warn">{results.bottleneck.stageName}</Badge>
        ) : (
          <Badge variant="neutral">None named</Badge>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-foreground">{results.bottleneck.explanation}</p>
        <SimpleTable
          caption="Bottleneck evidence per stage"
          headers={['Stage', 'Limiting pool', 'Pool utilization', 'Mean wait', 'Max queue', 'Saturation', 'Score']}
          rows={stages.map((stage) => [
            <span key="name" className="inline-flex items-center gap-1">
              {stage.stageId === primary ? (
                <>
                  <Star aria-hidden="true" className="size-3 text-status-warn" />
                  <span className="sr-only">Bottleneck: </span>
                </>
              ) : null}
              {stage.name}
            </span>,
            stage.bottleneck.limitingPoolName ?? 'None required',
            stage.bottleneck.limitingPoolUtilization === null
              ? NOT_AVAILABLE
              : formatFraction(stage.bottleneck.limitingPoolUtilization),
            `${hours(stage.meanWaitWorkingSeconds)} working`,
            formatInteger(stage.maxQueueLength),
            formatFraction(stage.bottleneck.saturation),
            stage.bottleneck.score,
          ])}
        />
      </CardContent>
    </Card>
  )
}

export function PoolsCard({ results }: { results: RunResults }) {
  return (
    <Card aria-labelledby="pools-title">
      <CardHeader>
        <div>
          <CardTitle id="pools-title">Resource pools</CardTitle>
          <CardDescription>
            Capacity as simulated (after the scenario's changes). Utilization is busy over available unit time during
            working hours.
          </CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <SimpleTable
          caption="Resource pool results"
          headers={['Pool', 'Kind', 'Capacity', 'Utilization', 'Cost', 'Note']}
          rows={results.resources.map((pool) => [
            pool.name,
            pool.kind === 'MACHINE' ? 'Machine' : 'Employee',
            `${formatInteger(pool.capacity)} units`,
            pool.utilization === null ? (
              <span key="u" title="No capacity was available during the run.">
                {NOT_AVAILABLE}
              </span>
            ) : (
              formatFraction(pool.utilization)
            ),
            formatMoney(pool.cost, results.currencyCode),
            pool.overcommitted ? 'Over-committed: running work kept units through a reduction' : '',
          ])}
        />
      </CardContent>
    </Card>
  )
}

const OUTCOMES: Record<string, string> = { ON_TIME: 'On time', LATE: 'Late', UNFINISHED: 'Unfinished' }

export function OrdersCard({ results, timeZone }: { results: RunResults; timeZone: string }) {
  return (
    <Card aria-labelledby="orders-title">
      <CardHeader>
        <div>
          <CardTitle id="orders-title">Order delivery</CardTitle>
          <CardDescription>When each open order would finish under this scenario.</CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <SimpleTable
          caption="Simulated order outcomes"
          headers={['Order', 'Due', 'Simulated completion', 'Outcome']}
          rows={results.orders.map((order) => [
            order.orderNumber,
            order.dueAt ? formatShortDateTimeInZone(order.dueAt, timeZone) : 'No due date',
            order.completesAt ? formatShortDateTimeInZone(order.completesAt, timeZone) : 'Not finished',
            OUTCOMES[order.outcome] ?? order.outcome,
          ])}
        />
      </CardContent>
    </Card>
  )
}

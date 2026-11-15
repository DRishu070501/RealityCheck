import { Info, TriangleAlert } from 'lucide-react'
import { lazy, Suspense } from 'react'

import { SourceTag } from '@/components/DataTags'
import { DeltaCell } from '@/components/Delta'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import type { BarRow } from '@/features/results/ComparisonBars'
import { ChartFigure, SimpleTable } from '@/features/results/resultsUi'
import { formatDecimal, formatInteger, formatMoney, formatShortDateTimeInZone } from '@/lib/format'
import { formatByUnit, formatElapsed, NOT_AVAILABLE } from '@/lib/metrics'
import { bottleneckSentence } from '@/features/results/resultsFormat'
import type { ActualOutcome, AlternativeComparison, BottleneckChange, Comparison, Delta } from '@/lib/scenarioSchemas'

/** Recharts is large; it is fetched only when a comparison is drawn. */
const ComparisonBars = lazy(() => import('@/features/results/ComparisonBars'))

function num(value: string | null): number | null {
  return value === null ? null : Number(value)
}

function Chart(props: {
  rows: BarRow[]
  baselineName: string
  alternativeName: string
  unitLabel: string
  format: (value: number) => string
  axisFormat?: (value: number) => string
}) {
  return (
    <Suspense fallback={<Skeleton className="h-56 w-full" />}>
      <ComparisonBars {...props} />
    </Suspense>
  )
}

/** "baseline → alternative", with "—" for a side the run does not have. */
function deltaText(delta: Delta, format: (value: string) => string): string {
  const side = (value: string | null) => (value === null ? NOT_AVAILABLE : format(value))
  return `${side(delta.baseline)} → ${side(delta.alternative)}`
}

const hours = (seconds: string) => `${formatDecimal(String(Number(seconds) / 3600), 1)} h`

function BottleneckChangeCard({
  change,
  baselineName,
  alternativeName,
}: {
  change: BottleneckChange
  baselineName: string
  alternativeName: string
}) {
  const stages = [change.baselineStage, change.alternativeStage].filter(
    (stage, index, all): stage is NonNullable<typeof stage> =>
      stage !== null && all.findIndex((other) => other?.stageId === stage.stageId) === index,
  )
  return (
    <Card aria-labelledby="bottleneck-change-title">
      <CardHeader>
        <div>
          <CardTitle id="bottleneck-change-title">Bottleneck</CardTitle>
          <CardDescription>Decided by fixed rules from each run's numbers, not by AI.</CardDescription>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="text-sm font-medium text-foreground">{bottleneckSentence(change)}</p>
        {stages.length > 0 ? (
          <SimpleTable
            caption="Bottleneck scores of the stages involved"
            headers={['Stage', `Score in ${baselineName}`, `Score in ${alternativeName}`]}
            rows={stages.map((stage) => [
              stage.name ?? stage.code ?? stage.stageId,
              stage.baselineScore ?? NOT_AVAILABLE,
              stage.alternativeScore ?? NOT_AVAILABLE,
            ])}
          />
        ) : null}
      </CardContent>
    </Card>
  )
}

/**
 * Recorded history after the fork beside the baseline's simulated figures. No differences: history stops at
 * `asOf`, while the simulation runs every order to completion.
 */
function ActualOutcomeCard({
  actual,
  alternative,
  baselineName,
  timeZone,
}: {
  actual: ActualOutcome
  alternative: AlternativeComparison
  baselineName: string
  timeZone: string
}) {
  const simulated = (key: string) => alternative.metrics.find((m) => m.key === key)?.baseline ?? null
  const asOf = formatShortDateTimeInZone(actual.asOf, timeZone)
  const completed = actual.orders
    .filter((order) => order.completedAt !== null)
    .sort((a, b) => (a.completedAt ?? '').localeCompare(b.completedAt ?? ''))
  const simulatedOrders = simulated('ordersCompleted')
  return (
    <Card aria-labelledby="actual-title">
      <CardHeader>
        <div>
          <CardTitle id="actual-title">What actually happened after the fork</CardTitle>
          <CardDescription>
            Recorded history for the same orders, up to the latest recorded event ({asOf}). History stops there while
            the simulation runs every order to completion, so the two are shown side by side and not subtracted.
          </CardDescription>
        </div>
        <SourceTag source="Actual" />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <SimpleTable
          caption="Recorded outcome beside the baseline's simulated outcome"
          headers={['', `${baselineName} (simulated, to completion)`, `Recorded (as of ${asOf})`]}
          rows={[
            [
              'Orders completed',
              simulatedOrders === null
                ? NOT_AVAILABLE
                : `${formatInteger(Number(simulatedOrders))} of ${formatInteger(actual.ordersInScope)}`,
              `${formatInteger(actual.ordersCompleted)} of ${formatInteger(actual.ordersInScope)}`,
            ],
            ['Output stones', formatByUnit(simulated('outputStones'), 'stones', ''), formatByUnit(actual.outputStones, 'stones', '')],
            ['Output carats', formatByUnit(simulated('outputCarats'), 'carats', ''), formatByUnit(actual.outputCarats, 'carats', '')],
          ]}
        />
        {completed.length > 0 ? (
          <SimpleTable
            caption="Orders completed in recorded history after the fork"
            headers={['Order', 'Recorded completion']}
            rows={completed.map((order) => [order.orderNumber, formatShortDateTimeInZone(order.completedAt ?? '', timeZone)])}
          />
        ) : (
          <p className="text-sm text-muted-foreground">None of these orders had finished by the latest recorded event.</p>
        )}
      </CardContent>
    </Card>
  )
}

function Charts({
  alternative,
  baselineName,
  alternativeName,
  currency,
}: {
  alternative: AlternativeComparison
  baselineName: string
  alternativeName: string
  currency: string
}) {
  const metric = (key: string) => alternative.metrics.find((m) => m.key === key)
  const pair = (key: string, label: string, scale = 1): BarRow => {
    const m = metric(key)
    const b = num(m?.baseline ?? null)
    const a = num(m?.alternative ?? null)
    return { label, baseline: b === null ? null : b * scale, alternative: a === null ? null : a * scale }
  }
  const names = { baselineName, alternativeName }
  const tableHeaders = ['', baselineName, alternativeName]
  const money = (value: number) => formatMoney(String(value), currency)
  // Axis ticks only: compact (lakh and crore in en-IN), so long amounts are not clipped.
  const compactMoney = (value: number) => {
    try {
      return new Intl.NumberFormat('en-IN', { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(value)
    } catch {
      return money(value)
    }
  }
  const pct = (value: number) => `${formatDecimal(String(value), 1)}%`

  const completion = [pair('elapsedSeconds', 'Completion time', 1 / 3600)]
  const cost = [pair('cost.total', 'Total cost')]
  const perStone = [pair('cost.perOutputStone', 'Cost per stone')]
  const throughput = [pair('throughputStonesPerHour', 'Throughput')]
  const utilization = [
    pair('machineUtilization', 'Machines', 100),
    pair('employeeUtilization', 'Employees', 100),
  ]
  const waits: BarRow[] = alternative.stages.map((stage) => ({
    label: stage.name,
    baseline: stage.meanWaitWorkingSeconds.baseline === null ? null : Number(stage.meanWaitWorkingSeconds.baseline) / 3600,
    alternative:
      stage.meanWaitWorkingSeconds.alternative === null ? null : Number(stage.meanWaitWorkingSeconds.alternative) / 3600,
  }))
  const tableRows = (rows: BarRow[], format: (value: number) => string) =>
    rows.map((row) => [
      row.label,
      row.baseline === null ? NOT_AVAILABLE : format(row.baseline),
      row.alternative === null ? NOT_AVAILABLE : format(row.alternative),
    ])
  const hoursFormat = (value: number) => `${formatDecimal(String(value), 1)} h`

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <ChartFigure
        title="Completion time"
        description="Hours from the fork to the last completion, nights and closed days included"
        chart={<Chart rows={completion} unitLabel="Hours" format={hoursFormat} {...names} />}
        table={<SimpleTable caption="Completion time in hours" headers={tableHeaders} rows={tableRows(completion, hoursFormat)} />}
      />
      <ChartFigure
        title="Total cost"
        description={`${currency}. Runs of different length accrue cost over different periods; compare cost per stone too.`}
        chart={<Chart rows={cost} unitLabel={currency} format={money} axisFormat={compactMoney} {...names} />}
        table={<SimpleTable caption="Total cost" headers={tableHeaders} rows={tableRows(cost, money)} />}
      />
      <ChartFigure
        title="Cost per finished stone"
        description={`${currency} per stone`}
        chart={<Chart rows={perStone} unitLabel={`${currency} per stone`} format={money} {...names} />}
        table={<SimpleTable caption="Cost per finished stone" headers={tableHeaders} rows={tableRows(perStone, money)} />}
      />
      <ChartFigure
        title="Throughput"
        description="Finished stones per working hour"
        chart={
          <Chart rows={throughput} unitLabel="Stones per hour" format={(v) => formatDecimal(String(v), 2)} {...names} />
        }
        table={
          <SimpleTable
            caption="Throughput in stones per working hour"
            headers={tableHeaders}
            rows={tableRows(throughput, (v) => `${formatDecimal(String(v), 2)} stones/h`)}
          />
        }
      />
      <ChartFigure
        title="Utilization"
        description="Busy over available unit time, in percent. Higher is not in itself better."
        chart={<Chart rows={utilization} unitLabel="Percent" format={pct} {...names} />}
        table={<SimpleTable caption="Machine and employee utilization" headers={tableHeaders} rows={tableRows(utilization, pct)} />}
      />
      <ChartFigure
        title="Mean waiting time by stage"
        description="Working hours a job waited in the stage's queue, on average"
        chart={<Chart rows={waits} unitLabel="Working hours" format={hoursFormat} {...names} />}
        table={
          <SimpleTable caption="Mean waiting time by stage in working hours" headers={['Stage', baselineName, alternativeName]} rows={tableRows(waits, hoursFormat)} />
        }
      />
    </div>
  )
}

/** Baseline against an alternative, every value as the comparison API returned it. */
export function ComparisonView({ comparison, timeZone }: { comparison: Comparison; timeZone: string }) {
  const alternative = comparison.alternatives[0]
  if (!alternative) return null
  const currency = comparison.currencyCode ?? ''
  const baselineName = comparison.baseline.scenarioName ?? 'Baseline'
  const alternativeName = alternative.run.scenarioName ?? 'Alternative'

  return (
    <div className="flex flex-col gap-6">
      {comparison.warnings.length > 0 ? (
        <div role="alert" className="flex flex-col gap-1 rounded-md border border-status-warn/30 bg-status-warn-surface px-4 py-3 text-sm">
          {comparison.warnings.map((warning) => (
            <p key={`${warning.code}-${warning.runId}`} className="flex items-start gap-2">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-warn" />
              <span>
                <span className="font-medium text-foreground">{warning.code.replaceAll('_', ' ').toLowerCase()}:</span>{' '}
                <span className="text-muted-foreground">{warning.message}</span>
              </span>
            </p>
          ))}
        </div>
      ) : null}

      <Card aria-labelledby="changes-title">
        <CardHeader>
          <div>
            <CardTitle id="changes-title">What differs</CardTitle>
            <CardDescription>The changes each run was computed with, as stored with the run.</CardDescription>
          </div>
          <SourceTag source="Simulated" />
        </CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {[comparison.baseline, alternative.run].map((run, index) => (
            <div key={run.runId}>
              <p className="text-xs text-muted-foreground">{index === 0 ? 'Baseline' : 'Alternative'}</p>
              <p className="text-sm font-medium text-foreground">{run.scenarioName}</p>
              {run.appliedChanges.length === 0 ? (
                <p className="text-sm text-muted-foreground">No changes: the factory as recorded.</p>
              ) : (
                <ol className="mt-1 list-decimal pl-5 text-sm text-foreground">
                  {run.appliedChanges.map((change) => (
                    <li key={change.ordinal}>{change.summary}</li>
                  ))}
                </ol>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      <Card aria-labelledby="metrics-title">
        <CardHeader>
          <div>
            <CardTitle id="metrics-title">Headline metrics</CardTitle>
            <CardDescription>
              Difference is alternative minus baseline. "Improved" describes how a metric moved under the simulation's
              assumptions; it is not a recommendation.
            </CardDescription>
          </div>
        </CardHeader>
        <Table>
          <caption className="sr-only">Headline metrics, baseline against alternative</caption>
          <thead>
            <tr>
              <TableHead>Metric</TableHead>
              <TableHead>{baselineName}</TableHead>
              <TableHead>{alternativeName}</TableHead>
              <TableHead>Difference</TableHead>
            </tr>
          </thead>
          <tbody>
            {alternative.metrics.map((metric) => (
              <TableRow key={metric.key} data-metric={metric.key}>
                <TableCell className="text-foreground">{metric.label}</TableCell>
                <TableCell className="metric">{formatByUnit(metric.baseline, metric.unit, currency)}</TableCell>
                <TableCell className="metric">{formatByUnit(metric.alternative, metric.unit, currency)}</TableCell>
                <TableCell>
                  <DeltaCell
                    absolute={metric.absolute}
                    percent={metric.percent}
                    unit={metric.unit}
                    currencyCode={currency}
                    direction={metric.direction}
                  />
                </TableCell>
              </TableRow>
            ))}
          </tbody>
        </Table>
        <CardContent className="flex items-start gap-2 border-t border-border text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          Utilization and processing time carry no direction: more of either is not in itself better.
        </CardContent>
      </Card>

      <BottleneckChangeCard change={alternative.bottleneck} baselineName={baselineName} alternativeName={alternativeName} />

      <Charts alternative={alternative} baselineName={baselineName} alternativeName={alternativeName} currency={currency} />

      {comparison.actual ? (
        <ActualOutcomeCard
          actual={comparison.actual}
          alternative={alternative}
          baselineName={baselineName}
          timeZone={timeZone}
        />
      ) : null}

      <Card aria-labelledby="stage-compare-title">
        <CardHeader>
          <div>
            <CardTitle id="stage-compare-title">Stages</CardTitle>
            <CardDescription>Where in the factory the difference arises. Each cell: baseline, then alternative.</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          <SimpleTable
            caption="Stage comparison"
            headers={['Stage', 'Mean wait', 'Max queue', 'Throughput (stones/h)', 'Limiting pool utilization', 'Bottleneck score']}
            rows={alternative.stages.map((stage) => [
              stage.name,
              deltaText(stage.meanWaitWorkingSeconds, hours),
              deltaText(stage.maxQueueLength, (v) => formatInteger(Number(v))),
              deltaText(stage.throughputStonesPerHour, (v) => formatDecimal(v, 2)),
              deltaText(stage.limitingUtilization, (v) => formatByUnit(v, 'fraction', currency)),
              deltaText(stage.bottleneckScore, (v) => v),
            ])}
          />
        </CardContent>
      </Card>

      <Card aria-labelledby="resource-compare-title">
        <CardHeader>
          <div>
            <CardTitle id="resource-compare-title">Resource pools</CardTitle>
            <CardDescription>Each cell: baseline, then alternative.</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          <SimpleTable
            caption="Resource pool comparison"
            headers={['Pool', 'Capacity', 'Utilization', 'Cost']}
            rows={alternative.resources.map((pool) => [
              pool.name,
              deltaText(pool.capacity, (v) => `${formatInteger(Number(v))} units`),
              deltaText(pool.utilization, (v) => formatByUnit(v, 'fraction', currency)),
              deltaText(pool.cost, (v) => formatMoney(v, currency)),
            ])}
          />
        </CardContent>
      </Card>

      <Card aria-labelledby="order-compare-title">
        <CardHeader>
          <div>
            <CardTitle id="order-compare-title">Order delivery</CardTitle>
            <CardDescription>Orders present in both runs. A negative difference means the order finishes earlier.</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          <SimpleTable
            caption="Order delivery comparison"
            headers={['Order', 'Due', `Completion (${baselineName})`, `Completion (${alternativeName})`, 'Difference', 'Outcome']}
            rows={alternative.orders.map((order) => [
              order.orderNumber,
              order.dueAt ? formatShortDateTimeInZone(order.dueAt, timeZone) : 'No due date',
              order.baselineCompletionAt ? formatShortDateTimeInZone(order.baselineCompletionAt, timeZone) : 'Not finished',
              order.alternativeCompletionAt ? formatShortDateTimeInZone(order.alternativeCompletionAt, timeZone) : 'Not finished',
              order.differenceSeconds === null
                ? NOT_AVAILABLE
                : `${order.differenceSeconds > 0 ? '+' : order.differenceSeconds < 0 ? '−' : ''}${formatElapsed(Math.abs(order.differenceSeconds))}`,
              `${outcome(order.baselineOutcome)} → ${outcome(order.alternativeOutcome)}`,
            ])}
          />
        </CardContent>
      </Card>
    </div>
  )
}

function outcome(value: string): string {
  return value === 'ON_TIME' ? 'On time' : value === 'LATE' ? 'Late' : value === 'UNFINISHED' ? 'Unfinished' : value
}

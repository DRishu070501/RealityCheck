import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, LoaderCircle, Play } from 'lucide-react'
import { useState } from 'react'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Select } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { SimpleTable } from '@/features/results/resultsUi'
import { api } from '@/lib/api'
import { formatInteger, formatShortDateTimeInZone } from '@/lib/format'
import { formatElapsed, formatFraction, formatSignedPercent, NOT_AVAILABLE } from '@/lib/metrics'
import { queryKeys } from '@/lib/queryKeys'
import type { Backtest, BacktestFork, BacktestMetric, Factory } from '@/lib/schemas'

function fraction(value: string | null): string {
  return value === null ? NOT_AVAILABLE : formatFraction(value)
}

/** A signed error such as "−12.5%"; the API gives it as a fraction of recorded. */
function signed(value: string | null): string {
  return value === null ? NOT_AVAILABLE : formatSignedPercent(String(Number(value) * 100))
}

function count(value: number | null): string {
  return value === null ? NOT_AVAILABLE : formatInteger(value)
}

function elapsed(value: number | null): string {
  return value === null ? NOT_AVAILABLE : formatElapsed(value)
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

function Sources() {
  return (
    <div className="flex flex-wrap gap-1.5">
      <SourceTag source="Simulated" />
      <SourceTag source="Actual" />
    </div>
  )
}

function Overall({ backtest, timeZone }: { backtest: Backtest; timeZone: string }) {
  const { overall } = backtest
  return (
    <Card aria-labelledby="overall-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="overall-title">Typical error</CardTitle>
          <CardDescription>
            Across {formatInteger(overall.forks)} past dates, the middle size of each error, simulated against
            recorded. Run {formatShortDateTimeInZone(backtest.createdAt, timeZone)} with algorithm{' '}
            {backtest.algorithmVersion}.
          </CardDescription>
        </div>
        <Sources />
      </CardHeader>
      <dl className="grid grid-cols-1 divide-y divide-border sm:grid-cols-3 sm:divide-y-0">
        <Figure
          label="Packets finished"
          value={fraction(overall.finishedError)}
          hint="How many packets finished in the window."
        />
        <Figure
          label="Median lead time"
          value={fraction(overall.leadTimeError)}
          hint="From the date (or arrival) to finishing."
        />
        <Figure
          label="Work in progress by stage"
          value={fraction(overall.wipError)}
          hint="Packets placed at the wrong stage, over all in progress."
        />
      </dl>
    </Card>
  )
}

function metricCells(metric: BacktestMetric, format: (value: number | null) => string): string[] {
  return [format(metric.simulated), format(metric.recorded), signed(metric.percent)]
}

/** Paired bars, simulated over recorded, scaled to the largest value in the chart. */
function PairedBars({ label, rows }: { label: string; rows: { name: string; simulated: number; recorded: number }[] }) {
  const max = Math.max(1, ...rows.flatMap((row) => [row.simulated, row.recorded]))
  return (
    <ol aria-label={label} className="flex flex-col gap-2">
      {rows.map((row) => (
        <li key={row.name} className="grid grid-cols-[minmax(7rem,11rem)_1fr] items-center gap-2 text-xs">
          <span className="truncate text-foreground">{row.name}</span>
          <span aria-hidden="true" className="flex flex-col gap-0.5">
            <span className="h-2 rounded-sm bg-source-simulated" style={{ width: `${(row.simulated / max) * 100}%` }} />
            <span className="h-2 rounded-sm bg-source-actual" style={{ width: `${(row.recorded / max) * 100}%` }} />
          </span>
          <span className="sr-only">{`${row.name}: simulated ${formatInteger(row.simulated)}, recorded ${formatInteger(row.recorded)}.`}</span>
        </li>
      ))}
    </ol>
  )
}

function Legend() {
  return (
    <ul aria-label="Legend" className="flex flex-wrap gap-4 text-xs text-muted-foreground">
      <li className="flex items-center gap-1.5">
        <span aria-hidden="true" className="size-2.5 rounded-sm bg-source-simulated" />
        Simulated
      </li>
      <li className="flex items-center gap-1.5">
        <span aria-hidden="true" className="size-2.5 rounded-sm bg-source-actual" />
        Recorded
      </li>
    </ul>
  )
}

function window(fork: BacktestFork, timeZone: string): string {
  return `${formatShortDateTimeInZone(fork.forkAt, timeZone)} to ${formatShortDateTimeInZone(fork.comparedUntil, timeZone)}`
}

function Forks({ backtest, timeZone }: { backtest: Backtest; timeZone: string }) {
  const cut = backtest.forks.some((fork) => fork.cutAtLastEvent)
  return (
    <Card aria-labelledby="forks-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="forks-title">Each date, predicted against recorded</CardTitle>
          <CardDescription>
            The factory as recorded on each date, simulated with no changes and with the lots that really arrived
            afterwards, then compared with what was recorded over the same window, for the same packets.
            {cut ? ' Where recorded history ends first, the comparison stops at the last recorded event.' : ''}
          </CardDescription>
        </div>
        <Sources />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Legend />
        <PairedBars
          label="Packets finished, simulated and recorded"
          rows={backtest.forks.map((fork) => ({
            name: formatShortDateTimeInZone(fork.forkAt, timeZone),
            simulated: fork.finished.simulated ?? 0,
            recorded: fork.finished.recorded ?? 0,
          }))}
        />
        <SimpleTable
          caption="Each date, predicted against recorded"
          headers={[
            'Window',
            'Packets',
            'Finished, simulated',
            'Finished, recorded',
            'Finished error',
            'Median lead, simulated',
            'Median lead, recorded',
            'Lead time error',
            'Work in progress error',
          ]}
          rows={backtest.forks.map((fork) => [
            `${window(fork, timeZone)}${fork.cutAtLastEvent ? ' (last recorded event)' : ''}`,
            formatInteger(fork.batches),
            ...metricCells(fork.finished, count),
            ...metricCells(fork.medianLeadSeconds, elapsed),
            fraction(fork.wipError),
          ])}
        />
      </CardContent>
    </Card>
  )
}

function StageWip({ backtest, timeZone }: { backtest: Backtest; timeZone: string }) {
  const [chosen, setChosen] = useState(0)
  const fork = backtest.forks[Math.min(chosen, backtest.forks.length - 1)]
  if (!fork) return null
  return (
    <Card aria-labelledby="wip-title">
      <CardHeader>
        <div className="min-w-0 flex-1 basis-72">
          <CardTitle id="wip-title">Work in progress by stage at the end of the window</CardTitle>
          <CardDescription>
            Packets queued, being worked on or held at each stage when the comparison ends: where the simulation
            expected them, and where they were recorded.
          </CardDescription>
        </div>
        <Sources />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="max-w-sm">
          <Field htmlFor="accuracy-fork" label="Date simulated from">
            <Select id="accuracy-fork" value={String(chosen)} onChange={(event) => { setChosen(Number(event.target.value)) }}>
              {backtest.forks.map((option, index) => (
                <option key={option.forkAt} value={index}>
                  {window(option, timeZone)}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <Legend />
        <PairedBars
          label="Work in progress per stage, simulated and recorded"
          rows={fork.wip.map((stage) => ({ name: stage.name, simulated: stage.simulated, recorded: stage.recorded }))}
        />
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Show as table</summary>
          <div className="mt-2">
            <SimpleTable
              caption="Work in progress per stage"
              headers={['Stage', 'Simulated', 'Recorded', 'Difference']}
              rows={fork.wip.map((stage) => [
                `${stage.sequence}. ${stage.name}`,
                formatInteger(stage.simulated),
                formatInteger(stage.recorded),
                `${stage.simulated - stage.recorded > 0 ? '+' : ''}${formatInteger(stage.simulated - stage.recorded)}`,
              ])}
            />
          </div>
        </details>
        <p className="text-xs text-muted-foreground">
          {`${formatInteger(fork.wipAbsoluteError)} misplaced across stages; error ${fraction(fork.wipError)}.`}
        </p>
      </CardContent>
    </Card>
  )
}

function AccuracyView({ factory }: { factory: Factory }) {
  const queryClient = useQueryClient()
  const key = queryKeys.factories.backtest(factory.id)
  const latest = useQuery({ queryKey: key, queryFn: ({ signal }) => api.backtests.latest(factory.id, signal) })
  const run = useMutation({
    mutationFn: () => api.backtests.run(factory.id),
    onSuccess: (backtest) => queryClient.setQueryData(key, backtest),
  })

  if (latest.isPending) {
    return (
      <div role="status" aria-label="Loading accuracy" className="flex flex-col gap-3">
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-72 w-full" />
      </div>
    )
  }
  if (latest.isError) {
    return <ErrorState error={latest.error} onRetry={() => void latest.refetch()} retrying={latest.isFetching} />
  }
  const backtest = latest.data
  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="backtest-title">
        <CardHeader>
          <div className="min-w-0 flex-1 basis-72">
            <CardTitle id="backtest-title">Backtest</CardTitle>
            <CardDescription>
              Would the simulation have predicted what really happened? A backtest simulates the factory from four
              past dates spread over the recorded history, changing nothing, and compares each simulation with the
              record. The engine is deterministic, so the same history always gives the same result.
            </CardDescription>
          </div>
          <Button onClick={() => { run.mutate(); }} disabled={run.isPending}>
            {run.isPending ? (
              <LoaderCircle aria-hidden="true" className="animate-spin" />
            ) : (
              <Play aria-hidden="true" />
            )}
            {backtest ? 'Run again' : 'Run a backtest'}
          </Button>
        </CardHeader>
        {run.isError ? (
          <CardContent>
            <ErrorState error={run.error} onRetry={() => { run.mutate(); }} retrying={run.isPending} />
          </CardContent>
        ) : null}
        {!backtest && !run.isError ? (
          <CardContent className="text-sm text-muted-foreground">No backtest has been run for this factory.</CardContent>
        ) : null}
      </Card>
      {backtest ? (
        <>
          <Overall backtest={backtest} timeZone={factory.timeZone} />
          <Forks backtest={backtest} timeZone={factory.timeZone} />
          <StageWip backtest={backtest} timeZone={factory.timeZone} />
          <p className="flex items-start gap-2 text-xs text-subtle-foreground">
            <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span>
              Errors are simulated minus recorded, as a share of recorded. Lead time here runs from the date simulated
              from (or a packet’s arrival, if later) to finishing, on both sides, so it is shorter than the full lead
              time on Where Time Goes.
            </span>
          </p>
        </>
      ) : null}
    </div>
  )
}

export function AccuracyPage() {
  return (
    <>
      <PageHeader
        title="Accuracy"
        description="How well the simulation predicts what really happened, checked against recorded history."
      />
      <FactoryGate>{(factory) => <AccuracyView factory={factory} />}</FactoryGate>
    </>
  )
}

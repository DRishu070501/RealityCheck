import { useQueries, useQuery } from '@tanstack/react-query'
import { ChartColumn, TriangleAlert } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Select } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { ComparisonView } from '@/features/results/ComparisonView'
import { ProductionTimeline } from '@/features/results/ProductionTimeline'
import { termination } from '@/features/results/resultsFormat'
import { BottleneckCard, LimitWarning, OrdersCard, PoolsCard, SummaryTiles } from '@/features/results/RunSummary'
import { api, ApiError } from '@/lib/api'
import { formatDateTimeInZone, formatInteger } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { ScenarioSummary } from '@/lib/scenarioSchemas'
import type { Factory } from '@/lib/schemas'

/** A completed run that can be picked: each scenario's latest completed run. */
interface RunOption {
  runId: string
  scenarioName: string
  baseline: boolean
}

function completedRuns(scenarios: readonly ScenarioSummary[]): RunOption[] {
  return scenarios.flatMap((scenario) =>
    scenario.latestRun?.status === 'COMPLETED'
      ? [{ runId: scenario.latestRun.runId, scenarioName: scenario.name, baseline: scenario.baseline }]
      : [],
  )
}

const NONE = 'none'

function EmptyResults() {
  return (
    <div className="flex flex-col items-start gap-4 rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 sm:px-10">
      <span className="flex size-10 items-center justify-center rounded-md border border-border bg-surface-raised">
        <ChartColumn aria-hidden="true" className="size-5 text-muted-foreground" />
      </span>
      <div>
        <p className="text-base font-medium text-foreground">No completed simulation yet</p>
        <p className="mt-2 max-w-xl text-sm text-muted-foreground">
          Results appear here once a scenario has been simulated. Open Scenario Lab, pick or create a scenario and run
          it.
        </p>
      </div>
      <Button asChild>
        <Link to="/scenarios">Go to Scenario Lab</Link>
      </Button>
    </div>
  )
}

function Results({ factory }: { factory: Factory }) {
  const [params, setParams] = useSearchParams()
  const scenarios = useQuery({
    queryKey: queryKeys.scenarios.list(factory.id),
    queryFn: ({ signal }) => api.scenarios.list(factory.id, signal),
  })
  const options = scenarios.data ? completedRuns(scenarios.data.items) : []
  const requestedRun = params.get('run')
  const runId =
    requestedRun ?? options.find((option) => !option.baseline)?.runId ?? options[0]?.runId ?? null
  const requestedBaseline = params.get('baseline')
  const allowDifferent = params.get('allowDifferent') === '1'

  const run = useQuery({
    queryKey: queryKeys.simulations.run(runId ?? 'none'),
    queryFn: ({ signal }) => api.simulations.get(runId ?? '', signal),
    enabled: runId !== null,
  })

  // Automatic baseline: the latest completed run of a baseline scenario that started from the same state.
  const candidates = requestedBaseline === null ? options.filter((o) => o.baseline && o.runId !== runId) : []
  const candidateRuns = useQueries({
    queries: candidates.map((candidate) => ({
      queryKey: queryKeys.simulations.run(candidate.runId),
      queryFn: ({ signal }: { signal: AbortSignal }) => api.simulations.get(candidate.runId, signal),
    })),
  })
  const autoBaseline =
    run.data === undefined
      ? undefined
      : candidateRuns.find((query) => query.data?.baseStateHash === run.data.baseStateHash)?.data?.runId
  const baselineId =
    requestedBaseline === NONE ? null : requestedBaseline ?? autoBaseline ?? null

  const results = useQuery({
    queryKey: queryKeys.simulations.results(runId ?? 'none'),
    queryFn: ({ signal }) => api.simulations.results(runId ?? '', signal),
    enabled: runId !== null && run.data?.status === 'COMPLETED',
    staleTime: Infinity,
  })
  const stages = useQuery({
    queryKey: queryKeys.simulations.stages(runId ?? 'none'),
    queryFn: ({ signal }) => api.simulations.stages(runId ?? '', signal),
    enabled: runId !== null && run.data?.status === 'COMPLETED',
    staleTime: Infinity,
  })
  const comparison = useQuery({
    queryKey: queryKeys.simulations.compare(baselineId ?? 'none', runId ?? 'none', allowDifferent),
    queryFn: () =>
      api.simulations.compare({
        baselineRunId: baselineId ?? '',
        alternativeRunIds: [runId ?? ''],
        allowDifferentBaseline: allowDifferent,
      }),
    enabled: runId !== null && baselineId !== null && baselineId !== runId,
    retry: false,
    staleTime: Infinity,
  })

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) next.delete(key)
      else next.set(key, value)
    }
    setParams(next, { replace: true })
  }

  if (scenarios.isPending) return <Skeleton className="h-64 w-full" />
  if (scenarios.isError) {
    return <ErrorState error={scenarios.error} onRetry={() => void scenarios.refetch()} retrying={scenarios.isFetching} />
  }
  if (runId === null) return <EmptyResults />

  const runOptions =
    options.some((option) => option.runId === runId) || run.data === undefined
      ? options
      : [...options, { runId, scenarioName: `${run.data.scenarioName ?? 'Run'} (earlier run)`, baseline: false }]
  const baselineOptions = runOptions.filter((option) => option.runId !== runId)
  const baselineExplanation =
    requestedBaseline === NONE
      ? 'No baseline: the run is shown on its own.'
      : requestedBaseline !== null
        ? 'Chosen by you.'
        : autoBaseline
          ? "Chosen automatically: the latest run of a baseline scenario (no changes) that started from the same factory state."
          : 'No run of a baseline scenario with the same starting state exists, so the run is shown on its own.'

  const comparisonError = comparison.error instanceof ApiError ? comparison.error : null
  const needsConsent = comparisonError?.status === 422 && comparisonError.code === 'DIFFERENT_STARTING_STATE'

  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="pick-title">
        <CardHeader>
          <div>
            <CardTitle id="pick-title">Runs</CardTitle>
            <CardDescription>Each scenario's latest completed run. Pick a baseline to compare against.</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field htmlFor="results-run" label="Run">
            <Select
              id="results-run"
              value={runId}
              onChange={(event) => { update({ run: event.target.value, baseline: null, allowDifferent: null }); }}
            >
              {runOptions.map((option) => (
                <option key={option.runId} value={option.runId}>
                  {option.scenarioName}
                </option>
              ))}
            </Select>
          </Field>
          <Field htmlFor="results-baseline" label="Compare with" hint={baselineExplanation}>
            <Select
              id="results-baseline"
              value={baselineId ?? NONE}
              onChange={(event) => { update({ baseline: event.target.value, allowDifferent: null }); }}
            >
              <option value={NONE}>No baseline (this run only)</option>
              {baselineOptions.map((option) => (
                <option key={option.runId} value={option.runId}>
                  {option.scenarioName}
                </option>
              ))}
            </Select>
          </Field>
        </CardContent>
      </Card>

      {run.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : run.isError ? (
        <ErrorState error={run.error} onRetry={() => void run.refetch()} retrying={run.isFetching} />
      ) : (
        <Card aria-labelledby="run-title">
          <CardHeader>
            <div>
              <CardTitle id="run-title">{run.data.scenarioName ?? 'Simulation run'}</CardTitle>
              <CardDescription>
                Computed by the deterministic engine from recorded history at the fork and the scenario's changes.
              </CardDescription>
            </div>
            <SourceTag source="Simulated" scenarioName={run.data.scenarioName ?? undefined} />
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <dt className="text-xs text-muted-foreground">Starts from (fork)</dt>
                <dd className="metric">{formatDateTimeInZone(run.data.forkAt, factory.timeZone)}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Simulated end</dt>
                <dd className="metric">
                  {run.data.simulatedEndAt ? formatDateTimeInZone(run.data.simulatedEndAt, factory.timeZone) : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Outcome</dt>
                <dd>{run.data.status === 'COMPLETED' ? termination(run.data.terminationReason) : run.data.status}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Algorithm version</dt>
                <dd className="metric">
                  {run.data.algorithmVersion}
                  {run.data.eventsProcessed === null ? '' : `, ${formatInteger(run.data.eventsProcessed)} events`}
                </dd>
              </div>
            </dl>
            {run.data.status === 'FAILED' ? (
              <p role="alert" className="mt-3 text-sm text-status-error">
                {run.data.failureMessage ?? 'The run failed.'}
              </p>
            ) : null}
          </CardContent>
        </Card>
      )}

      {results.data ? <LimitWarning results={results.data} /> : null}

      {baselineId !== null && baselineId !== runId ? (
        comparison.isPending ? (
          <Skeleton className="h-96 w-full" />
        ) : needsConsent ? (
          <div
            role="alert"
            className="flex flex-col gap-3 rounded-md border border-status-warn/30 bg-status-warn-surface px-4 py-3 text-sm"
          >
            <div className="flex items-start gap-2">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-warn" />
              <div>
                <p className="font-medium text-foreground">These runs started from different factory states</p>
                <p className="text-muted-foreground">{comparisonError.detail}</p>
              </div>
            </div>
            <Button variant="outline" size="sm" className="self-start" onClick={() => { update({ allowDifferent: '1' }); }}>
              Compare anyway
            </Button>
          </div>
        ) : comparison.isError ? (
          <ErrorState
            error={
              comparisonError?.status === 409
                ? new ApiError({
                    kind: 'http',
                    status: 409,
                    title: 'A run is not finished',
                    detail: comparisonError.detail,
                  })
                : comparison.error
            }
            onRetry={() => void comparison.refetch()}
            retrying={comparison.isFetching}
          />
        ) : (
          <ComparisonView comparison={comparison.data} timeZone={factory.timeZone} />
        )
      ) : results.isError ? (
        <ErrorState error={results.error} onRetry={() => void results.refetch()} retrying={results.isFetching} />
      ) : results.data && stages.data ? (
        <>
          <SummaryTiles results={results.data} />
          <BottleneckCard results={results.data} stages={stages.data} />
          <PoolsCard results={results.data} />
          <OrdersCard results={results.data} timeZone={factory.timeZone} />
        </>
      ) : run.data?.status === 'COMPLETED' ? (
        <Skeleton className="h-64 w-full" />
      ) : null}

      {stages.data ? <ProductionTimeline runId={runId} stages={stages.data} timeZone={factory.timeZone} /> : null}
    </div>
  )
}

/** Simulation Results: one run's outcome, or a baseline compared with an alternative. */
export function ResultsPage() {
  return (
    <>
      <PageHeader
        title="Simulation Results"
        description="What a scenario would do, as computed by the simulation engine. Every figure is an estimate under the engine's rules."
      />
      <FactoryGate>{(factory) => <Results factory={factory} />}</FactoryGate>
    </>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Play } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { RunStatusBadge } from '@/components/RunStatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { api, ApiError } from '@/lib/api'
import { formatDateTimeInZone, formatDecimal } from '@/lib/format'
import { formatElapsed, formatFraction, NOT_AVAILABLE, NULL_REASON } from '@/lib/metrics'
import { queryKeys } from '@/lib/queryKeys'
import type { ScenarioSummary } from '@/lib/scenarioSchemas'
import type { Factory } from '@/lib/schemas'
import { useCurrentFactory } from '@/lib/useCurrentFactory'

const POLL_MS = 1_000
const PREFERRED = 'Current factory operations'

/** The scenario with no changes, preferring the seeded "Current factory operations". */
function baselineScenario(scenarios: readonly ScenarioSummary[]): ScenarioSummary | undefined {
  const baselines = scenarios.filter((scenario) => scenario.baseline)
  return baselines.find((scenario) => scenario.name === PREFERRED) ?? baselines[0]
}

function Figure({ label, value, nullReason }: { label: string; value: string | null; nullReason?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="metric text-lg font-semibold text-foreground" title={value === null ? nullReason : undefined}>
        {value ?? NOT_AVAILABLE}
      </dd>
    </div>
  )
}

function CompletedRun({ runId, scenario, factory }: { runId: string; scenario: ScenarioSummary; factory: Factory }) {
  const results = useQuery({
    queryKey: queryKeys.simulations.results(runId),
    queryFn: ({ signal }) => api.simulations.results(runId, signal),
    staleTime: Infinity,
  })
  if (results.isPending) return <Skeleton className="h-24 w-full" />
  if (results.isError) {
    return <ErrorState error={results.error} onRetry={() => void results.refetch()} retrying={results.isFetching} />
  }
  const s = results.data.summary
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        <Figure
          label="Machine utilization"
          value={s.machineUtilization === null ? null : formatFraction(s.machineUtilization)}
          nullReason={NULL_REASON.machineUtilization}
        />
        <Figure
          label="Employee utilization"
          value={s.employeeUtilization === null ? null : formatFraction(s.employeeUtilization)}
          nullReason={NULL_REASON.employeeUtilization}
        />
        <Figure
          label="Throughput"
          value={s.throughputStonesPerHour === null ? null : `${formatDecimal(s.throughputStonesPerHour, 2)} stones/h`}
          nullReason={NULL_REASON.throughputStonesPerHour}
        />
        <Figure label="Bottleneck" value={results.data.bottleneck.stageName ?? 'None named'} />
        <Figure
          label="On-time delivery"
          value={s.onTimeDeliveryRate === null ? null : formatFraction(s.onTimeDeliveryRate)}
          nullReason={NULL_REASON.onTimeDeliveryRate}
        />
        <Figure label="All open work done in" value={formatElapsed(s.elapsedSeconds)} />
      </dl>
      <p className="text-xs text-muted-foreground">
        If nothing changes, from {formatDateTimeInZone(results.data.forkAt, factory.timeZone)} ({scenario.name}).{' '}
        <Link to={`/results?run=${runId}`} className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline">
          View the full results
          <ArrowRight aria-hidden="true" className="size-3" />
        </Link>
      </p>
    </div>
  )
}

function RunBaseline({ scenario, initialRunId }: { scenario: ScenarioSummary; initialRunId: string | null }) {
  const queryClient = useQueryClient()
  const [runId, setRunId] = useState<string | null>(initialRunId)
  const submit = useMutation({
    mutationFn: () => api.scenarios.simulate(scenario.id, crypto.randomUUID()),
    onSuccess: (submitted) => {
      setRunId(submitted.runId)
    },
  })
  const run = useQuery({
    queryKey: queryKeys.simulations.run(runId ?? 'none'),
    queryFn: ({ signal }) => api.simulations.get(runId ?? '', signal),
    enabled: runId !== null,
    refetchInterval: (query) =>
      query.state.data?.status === 'COMPLETED' || query.state.data?.status === 'FAILED' ? false : POLL_MS,
  })
  const status = run.data?.status
  useEffect(() => {
    if (status === 'COMPLETED') {
      void queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
    }
  }, [status, queryClient])

  const busy = submit.isPending || status === 'PENDING' || status === 'RUNNING'
  const failure = submit.error instanceof ApiError ? submit.error : null
  return (
    <div className="flex flex-col items-start gap-3">
      <p className="max-w-2xl text-sm text-muted-foreground">
        Utilization, throughput and the bottleneck are results of a simulation, so they are shown only once the
        baseline scenario "{scenario.name}" has been run. Nothing is estimated in the meantime.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={() => { submit.mutate(); }} disabled={busy}>
          <Play aria-hidden="true" />
          {busy ? 'Simulating' : 'Run baseline simulation'}
        </Button>
        {status ? <RunStatusBadge status={status} /> : null}
      </div>
      {status === 'FAILED' ? (
        <p role="alert" className="text-sm text-status-error">
          {run.data?.failureMessage ?? 'The simulation failed.'}
        </p>
      ) : null}
      {failure ? (
        <p role="alert" className="text-sm text-status-error">
          {failure.status === 429
            ? 'The simulator is busy. Try again in a few seconds.'
            : (failure.detail ?? failure.title)}
        </p>
      ) : null}
    </div>
  )
}

function Panel({ factory }: { factory: Factory }) {
  const scenarios = useQuery({
    queryKey: queryKeys.scenarios.list(factory.id),
    queryFn: ({ signal }) => api.scenarios.list(factory.id, signal),
  })
  const scenario = scenarios.data ? baselineScenario(scenarios.data.items) : undefined
  const latest = scenario?.latestRun ?? null

  return (
    <Card aria-labelledby="simulated-baseline-title">
      <CardHeader>
        <div>
          <CardTitle id="simulated-baseline-title">If nothing changes</CardTitle>
          <CardDescription>
            The baseline scenario simulated forward from its fork: an estimate under the engine's rules, not a
            measurement.
          </CardDescription>
        </div>
        <SourceTag source="Simulated" scenarioName={scenario?.name} />
      </CardHeader>
      <CardContent>
        {scenarios.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : scenarios.isError ? (
          <ErrorState error={scenarios.error} onRetry={() => void scenarios.refetch()} retrying={scenarios.isFetching} />
        ) : scenario === undefined ? (
          <div className="flex flex-col items-start gap-3">
            <p className="text-sm text-muted-foreground">
              There is no baseline scenario (one with no changes) to simulate yet.
            </p>
            <Button asChild variant="outline" size="sm">
              <Link to="/scenarios">Go to Scenario Lab</Link>
            </Button>
          </div>
        ) : latest?.status === 'COMPLETED' ? (
          <CompletedRun runId={latest.runId} scenario={scenario} factory={factory} />
        ) : (
          <RunBaseline
            key={scenario.id}
            scenario={scenario}
            initialRunId={latest?.status === 'PENDING' || latest?.status === 'RUNNING' ? latest.runId : null}
          />
        )}
      </CardContent>
    </Card>
  )
}

/** Simulated headline figures of the baseline scenario, kept apart from recorded ("Actual") ones. */
export function SimulatedBaselinePanel() {
  const current = useCurrentFactory()
  // Without a factory the factory summary above already explains what is missing.
  if (current.status !== 'ready') return null
  return <Panel factory={current.factory} />
}

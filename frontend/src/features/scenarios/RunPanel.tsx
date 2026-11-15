import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChartColumn, GitCompare, Play } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { SourceTag } from '@/components/DataTags'
import { RunStatusBadge } from '@/components/RunStatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ProblemBanner } from '@/features/scenarios/ProblemBanner'
import { asApiError, baselineNameFor, scenarioProblemMessages } from '@/features/scenarios/scenarioShared'
import { api, type ApiError } from '@/lib/api'
import { formatShortDateTimeInZone } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'
import type { Run, RunStatus, Scenario } from '@/lib/scenarioSchemas'

/** How often a pending or running run is polled. */
export const POLL_MS = 1000

function isActive(status: RunStatus | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** Polls a run every second while it is pending or running. */
function useRun(runId: string | null) {
  return useQuery({
    queryKey: queryKeys.simulations.run(runId ?? 'none'),
    queryFn: ({ signal }) => api.simulations.get(runId ?? '', signal),
    enabled: runId !== null,
    refetchInterval: (query) => (isActive(query.state.data?.status) ? POLL_MS : false),
  })
}

/** What the user is told after pressing a run button, beyond the run's own status. */
type Notice =
  | { kind: 'reused' }
  | { kind: 'busy' }
  | { kind: 'invalid'; messages: string[] }
  | { kind: 'error'; error: ApiError }

function describeFailure(error: unknown): Notice {
  const apiError = asApiError(error, 'The simulation could not be started')
  if (apiError.status === 429) return { kind: 'busy' }
  if (apiError.status === 422) return { kind: 'invalid', messages: scenarioProblemMessages(apiError) }
  return { kind: 'error', error: apiError }
}

function NoticeView({ notice }: { notice: Notice | null }) {
  if (!notice) return null
  switch (notice.kind) {
    case 'reused':
      return (
        <p role="status" className="text-sm text-muted-foreground">
          An identical run of this scenario had already completed, so its stored results were reused instead of
          simulating again.
        </p>
      )
    case 'busy':
      return (
        <ProblemBanner title="The simulator is busy">
          <p className="text-muted-foreground">Other simulations are running or queued. Try again in a few seconds.</p>
        </ProblemBanner>
      )
    case 'invalid':
      return (
        <ProblemBanner title="The scenario cannot be simulated">
          <ul className="mt-1 list-disc pl-4 text-muted-foreground">
            {notice.messages.length === 0 ? <li>Check the problems listed above.</li> : null}
            {notice.messages.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </ProblemBanner>
      )
    case 'error':
      return <ProblemBanner error={notice.error} />
  }
}

function RunOutcome({ run, compareTo, timeZone }: { run: Run; compareTo: string | null; timeZone: string }) {
  if (run.status === 'FAILED') {
    return (
      <ProblemBanner title="The simulation failed">
        <p className="text-muted-foreground">{run.failureMessage ?? 'No reason was recorded.'}</p>
      </ProblemBanner>
    )
  }
  if (run.status !== 'COMPLETED') {
    return (
      <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {run.status === 'PENDING' ? 'Waiting for a free simulator.' : 'Simulating.'} The status is checked every second.
      </p>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      <p role="status" aria-live="polite" className="text-sm text-foreground">
        Completed{run.finishedAt ? ` ${formatShortDateTimeInZone(run.finishedAt, timeZone)}` : ''}.
        {run.terminationReason && run.terminationReason !== 'COMPLETED' ? (
          <span className="text-status-warn">
            {' '}
            It stopped at a simulation limit ({run.terminationReason}) with work unfinished.
          </span>
        ) : null}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button asChild variant="outline">
          <Link to={`/results?run=${run.runId}`}>
            <ChartColumn aria-hidden="true" />
            View results
          </Link>
        </Button>
        {compareTo ? (
          <Button asChild>
            <Link to={`/results?run=${run.runId}&baseline=${compareTo}`}>
              <GitCompare aria-hidden="true" />
              Compare with baseline
            </Link>
          </Button>
        ) : null}
      </div>
    </div>
  )
}

interface RunPanelProps {
  factory: Factory
  scenario: Scenario
  /** Null while validation is loading or failed. */
  valid: boolean | null
  /** True when the check request itself failed, as opposed to still loading. */
  checkFailed?: boolean
}

/**
 * Runs the scenario in the background and follows the run to completion. For a scenario with
 * changes, also offers to run the baseline it would be compared with when that has not been run.
 */
export function RunPanel({ factory, scenario, valid, checkFailed = false }: RunPanelProps) {
  const queryClient = useQueryClient()
  const [runId, setRunId] = useState<string | null>(scenario.latestRun?.runId ?? null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [baselineRunId, setBaselineRunId] = useState<string | null>(null)
  const [baselineNotice, setBaselineNotice] = useState<Notice | null>(null)

  const run = useRun(runId)
  const baselineRun = useRun(baselineRunId)
  const comparisons = useQuery({
    queryKey: queryKeys.scenarios.comparisons(scenario.id),
    queryFn: ({ signal }) => api.scenarios.comparisons(scenario.id, signal),
    enabled: !scenario.baseline,
  })

  const runStatus = run.data?.status
  const baselineStatus = baselineRun.data?.status
  useEffect(() => {
    // A run that has just finished changes the scenario list, run history and comparisons.
    if (runStatus === 'COMPLETED' || runStatus === 'FAILED' || baselineStatus === 'COMPLETED') {
      void queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
    }
  }, [runStatus, baselineStatus, queryClient])

  const simulate = useMutation({
    mutationFn: (scenarioId: string) => api.scenarios.simulate(scenarioId, crypto.randomUUID()),
  })

  const start = () => {
    setNotice(null)
    simulate.mutate(scenario.id, {
      onSuccess: (submitted) => {
        setRunId(submitted.runId)
        setNotice(submitted.reused ? { kind: 'reused' } : null)
        void queryClient.invalidateQueries({ queryKey: queryKeys.simulations.run(submitted.runId) })
      },
      onError: (error) => {
        setNotice(describeFailure(error))
      },
    })
  }

  const startBaseline = (baselineScenarioId: string) => {
    setBaselineNotice(null)
    simulate.mutate(baselineScenarioId, {
      onSuccess: (submitted) => {
        setBaselineRunId(submitted.runId)
        setBaselineNotice(submitted.reused ? { kind: 'reused' } : null)
      },
      onError: (error) => {
        setBaselineNotice(describeFailure(error))
      },
    })
  }

  // No baseline scenario starts where this one does (it was forked from its own moment): make one.
  const createBaseline = useMutation({
    mutationFn: () =>
      api.scenarios.create({
        factoryId: scenario.factoryId,
        name: baselineNameFor(scenario.name),
        description: `The factory as recorded, with no changes, from the same moment as "${scenario.name}".`,
        forkAt: scenario.forkAt,
        includeFutureOrders: scenario.includeFutureOrders,
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.scenarios.all })
      startBaseline(created.id)
    },
    onError: (error) => {
      setBaselineNotice({ kind: 'error', error: asApiError(error, 'The baseline could not be created') })
    },
  })

  const running = isActive(runStatus)
  const disabledReason =
    valid === null
      ? checkFailed
        ? 'The check above could not be completed. Retry it before running.'
        : 'Waiting for the check above to finish.'
      : !valid
        ? 'Fix the problems listed above before running.'
        : running
          ? 'A run of this scenario is in progress.'
          : null
  const compareTo = comparisons.data?.items[0]?.baseline.runId ?? null
  const suggested = comparisons.data?.baselineMissing ? comparisons.data.suggestedBaselineScenarioId : null
  const noBaselineScenario =
    comparisons.data?.baselineMissing === true && suggested === null && baselineRunId === null

  return (
    <Card aria-labelledby="run-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="run-title">Simulation</CardTitle>
          <CardDescription>
            The deterministic engine runs in the background; the same scenario always gives the same results.
          </CardDescription>
        </div>
        <SourceTag source="Simulated" scenarioName={scenario.name} />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {scenario.baseline ? (
          <p className="text-sm text-muted-foreground">
            This scenario has no changes: it is the baseline, the factory as recorded. Scenarios with the same start are
            compared with its run.
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-3">
          <Button
            onClick={start}
            disabled={disabledReason !== null || simulate.isPending}
            aria-describedby={disabledReason ? 'run-disabled-reason' : undefined}
          >
            <Play aria-hidden="true" />
            {simulate.isPending && !baselineRunId ? 'Starting' : 'Run simulation'}
          </Button>
          {run.data ? <RunStatusBadge status={run.data.status} /> : null}
          {disabledReason ? (
            <p id="run-disabled-reason" className="text-xs text-muted-foreground">
              {disabledReason}
            </p>
          ) : null}
        </div>

        <NoticeView notice={notice} />
        {run.data ? (
          <RunOutcome run={run.data} compareTo={compareTo} timeZone={factory.timeZone} />
        ) : runId === null ? (
          <p className="text-sm text-muted-foreground">This scenario has not been run yet.</p>
        ) : null}

        {noBaselineScenario ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-raised px-4 py-3">
            <p className="text-sm text-foreground">
              No baseline starts at this scenario's moment yet, so there is nothing to compare it with. A baseline is the
              factory as recorded from the same moment, with no changes.
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <Button
                variant="outline"
                disabled={createBaseline.isPending || simulate.isPending}
                onClick={() => {
                  setBaselineNotice(null)
                  createBaseline.mutate()
                }}
              >
                <Play aria-hidden="true" />
                {createBaseline.isPending ? 'Creating baseline' : 'Create and run baseline'}
              </Button>
            </div>
            <NoticeView notice={baselineNotice} />
          </div>
        ) : null}

        {baselineRunId !== null && !suggested ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-raised px-4 py-3">
            <div className="flex flex-wrap items-center gap-3">
              <p className="text-sm text-foreground">Baseline</p>
              {baselineRun.data ? <RunStatusBadge status={baselineRun.data.status} /> : null}
            </div>
            <NoticeView notice={baselineNotice} />
            {baselineRun.data?.status === 'FAILED' ? (
              <ProblemBanner title="The baseline simulation failed">
                <p className="text-muted-foreground">{baselineRun.data.failureMessage ?? 'No reason was recorded.'}</p>
              </ProblemBanner>
            ) : null}
          </div>
        ) : null}

        {suggested ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-raised px-4 py-3">
            <p className="text-sm text-foreground">
              The baseline for this start time has not been run, so there is nothing to compare this scenario with yet.
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <Button
                variant="outline"
                disabled={simulate.isPending || isActive(baselineStatus)}
                onClick={() => {
                  startBaseline(suggested)
                }}
              >
                <Play aria-hidden="true" />
                Run baseline
              </Button>
              {baselineRun.data ? <RunStatusBadge status={baselineRun.data.status} /> : null}
            </div>
            <NoticeView notice={baselineNotice} />
            {baselineRun.data?.status === 'FAILED' ? (
              <ProblemBanner title="The baseline simulation failed">
                <p className="text-muted-foreground">{baselineRun.data.failureMessage ?? 'No reason was recorded.'}</p>
              </ProblemBanner>
            ) : null}
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

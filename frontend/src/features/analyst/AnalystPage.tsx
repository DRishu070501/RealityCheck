import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, LoaderCircle, Send } from 'lucide-react'
import { useEffect, useMemo, useState, type SyntheticEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Select } from '@/components/ui/form'
import { Skeleton } from '@/components/ui/skeleton'
import { authorOf, exampleQuestions, INSIGHT_LABELS } from '@/features/analyst/analystShared'
import { InsightCard } from '@/features/analyst/InsightCard'
import { QUESTION_MAX, type Insight, type InsightInput } from '@/lib/aiSchemas'
import { api, ApiError } from '@/lib/api'
import { queryKeys } from '@/lib/queryKeys'
import type { ScenarioSummary } from '@/lib/scenarioSchemas'
import type { Factory } from '@/lib/schemas'

interface RunOption {
  runId: string
  scenarioName: string
  baseline: boolean
}

/** Each scenario's latest completed run. */
function completedRuns(scenarios: readonly ScenarioSummary[]): RunOption[] {
  return scenarios.flatMap((scenario) =>
    scenario.latestRun?.status === 'COMPLETED'
      ? [{ runId: scenario.latestRun.runId, scenarioName: scenario.name, baseline: scenario.baseline }]
      : [],
  )
}

const NONE = 'none'

type ReportType = InsightInput['type']

const ACTIONS: readonly { type: ReportType; label: string; needsBaseline?: boolean }[] = [
  { type: 'EXPLANATION', label: 'Explain this run' },
  { type: 'BOTTLENECK', label: 'Explain the bottleneck' },
  { type: 'RECOMMENDATIONS', label: 'Recommend next steps' },
  { type: 'DELAY_ANALYSIS', label: 'Analyse delays' },
  { type: 'COMPARISON_SUMMARY', label: 'Compare with baseline', needsBaseline: true },
  { type: 'EXECUTIVE_REPORT', label: 'Executive report' },
]

function EmptyAnalyst() {
  return (
    <div className="flex flex-col items-start gap-4 rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 sm:px-10">
      <span className="flex size-10 items-center justify-center rounded-md border border-border bg-surface-raised">
        <Bot aria-hidden="true" className="size-5 text-muted-foreground" />
      </span>
      <div>
        <p className="text-base font-medium text-foreground">No completed simulation to analyse</p>
        <p className="mt-2 max-w-xl text-sm text-muted-foreground">
          The analyst explains simulation results, so it needs a completed run. Open Scenario Lab, pick or create a
          scenario and run it.
        </p>
      </div>
      <Button asChild>
        <Link to="/scenarios">Go to Scenario Lab</Link>
      </Button>
    </div>
  )
}

/** Explains why a request was refused, in the analyst's own terms. */
function requestError(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error
  if (error.status === 422 && error.code === 'DIFFERENT_STARTING_STATE') {
    return new ApiError({
      kind: 'http',
      status: 422,
      title: 'These runs started from different factory states',
      detail: 'Pick a baseline that started from the same state, or analyse the run on its own.',
    })
  }
  if (error.status === 429) {
    return new ApiError({ kind: 'http', status: 429, title: 'Too many AI requests', detail: error.detail })
  }
  return error
}

function Analyst({ factory }: { factory: Factory }) {
  const [params, setParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [question, setQuestion] = useState('')
  const [printingId, setPrintingId] = useState<string | null>(null)

  const info = useQuery({ queryKey: queryKeys.system.info, queryFn: ({ signal }) => api.system.info(signal) })
  const scenarios = useQuery({
    queryKey: queryKeys.scenarios.list(factory.id),
    queryFn: ({ signal }) => api.scenarios.list(factory.id, signal),
  })
  const options = useMemo(() => (scenarios.data ? completedRuns(scenarios.data.items) : []), [scenarios.data])
  const runId = params.get('run') ?? options.find((o) => !o.baseline)?.runId ?? options[0]?.runId ?? null
  const requestedBaseline = params.get('baseline')
  const defaultBaseline = options.find((o) => o.baseline && o.runId !== runId)?.runId ?? null
  const baselineId = requestedBaseline === NONE ? null : (requestedBaseline ?? defaultBaseline)
  const runNames = useMemo(() => new Map(options.map((o) => [o.runId, o.scenarioName])), [options])

  const history = useQuery({
    queryKey: queryKeys.ai.history(runId ?? 'none'),
    queryFn: ({ signal }) => api.ai.history(runId ?? '', signal),
    enabled: runId !== null,
  })

  const onAnswered = (insight: Insight) => {
    queryClient.setQueryData(queryKeys.ai.history(insight.runId), (old: typeof history.data) =>
      old
        ? { ...old, items: [insight, ...old.items.filter((i) => i.id !== insight.id)], totalItems: old.totalItems + 1 }
        : old,
    )
    void queryClient.invalidateQueries({ queryKey: queryKeys.ai.history(insight.runId) })
  }

  const report = useMutation({
    mutationFn: (type: ReportType) =>
      api.ai.insight({ runId: runId ?? '', baselineRunId: baselineId ?? undefined, type }),
    onSuccess: onAnswered,
  })
  const ask = useMutation({
    mutationFn: (text: string) =>
      api.ai.ask({ runId: runId ?? '', baselineRunId: baselineId ?? undefined, question: text }),
    onSuccess: (insight) => {
      setQuestion('')
      onAnswered(insight)
    },
  })
  const busy = report.isPending || ask.isPending
  const failure = report.error ?? ask.error

  // Print only the chosen executive report, then restore the page.
  useEffect(() => {
    if (printingId === null) return
    document.body.classList.add('printing-insight')
    const done = () => {
      document.body.classList.remove('printing-insight')
      setPrintingId(null)
    }
    window.addEventListener('afterprint', done, { once: true })
    window.print()
    return () => {
      window.removeEventListener('afterprint', done)
      document.body.classList.remove('printing-insight')
    }
  }, [printingId])

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) next.delete(key)
      else next.set(key, value)
    }
    setParams(next, { replace: true })
    report.reset()
    ask.reset()
  }

  if (scenarios.isPending) return <Skeleton className="h-64 w-full" />
  if (scenarios.isError) {
    return <ErrorState error={scenarios.error} onRetry={() => void scenarios.refetch()} retrying={scenarios.isFetching} />
  }
  if (runId === null) return <EmptyAnalyst />

  const baselineOptions = options.filter((o) => o.runId !== runId)
  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    const text = question.trim()
    if (text !== '' && !busy) ask.mutate(text)
  }
  const ai = info.data?.ai
  const author = ai
    ? authorOf({ provider: ai.provider, model: ai.model ?? null })
    : 'Checking which analyst is configured…'
  const pendingReport = report.isPending ? report.variables : undefined

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]">
      <div className="flex flex-col gap-6 print:hidden">
        <Card aria-labelledby="context-title">
          <CardHeader>
            <div>
              <CardTitle id="context-title">Context</CardTitle>
              <CardDescription>The analyst sees only the stored results of these runs.</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Field htmlFor="analyst-run" label="Run">
              <Select
                id="analyst-run"
                value={runId}
                onChange={(event) => { update({ run: event.target.value, baseline: null }); }}
              >
                {options.map((option) => (
                  <option key={option.runId} value={option.runId}>
                    {option.scenarioName}
                  </option>
                ))}
              </Select>
            </Field>
            <Field htmlFor="analyst-baseline" label="Compare with" hint="Optional. Must start from the same factory state.">
              <Select
                id="analyst-baseline"
                value={baselineId ?? NONE}
                onChange={(event) => { update({ baseline: event.target.value }); }}
              >
                <option value={NONE}>No baseline</option>
                {baselineOptions.map((option) => (
                  <option key={option.runId} value={option.runId}>
                    {option.scenarioName}
                  </option>
                ))}
              </Select>
            </Field>
            <p className="text-xs text-muted-foreground">
              Answered by: <span className="text-foreground">{author}</span>
            </p>
          </CardContent>
        </Card>

        <Card aria-labelledby="reports-title">
          <CardHeader>
            <CardTitle id="reports-title">Reports</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            {ACTIONS.map((action) => (
              <Button
                key={action.type}
                variant="outline"
                className="justify-start"
                disabled={busy || (action.needsBaseline === true && baselineId === null)}
                title={action.needsBaseline && baselineId === null ? 'Choose a baseline first.' : undefined}
                onClick={() => { report.mutate(action.type); }}
              >
                {action.label}
              </Button>
            ))}
          </CardContent>
        </Card>

        <Card aria-labelledby="examples-title">
          <CardHeader>
            <CardTitle id="examples-title">Example questions</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col items-start gap-2">
            {exampleQuestions(baselineId !== null).map((example) => (
              <Button
                key={example}
                variant="ghost"
                size="sm"
                className="h-auto justify-start py-1.5 text-left whitespace-normal"
                disabled={busy}
                onClick={() => { setQuestion(example); ask.mutate(example); }}
              >
                {example}
              </Button>
            ))}
          </CardContent>
        </Card>
      </div>

      <div className="flex min-w-0 flex-col gap-6">
        <form onSubmit={submit} className="flex flex-col gap-2 print:hidden" aria-label="Ask the analyst">
          <label htmlFor="analyst-question" className="text-sm font-medium text-foreground">
            Ask about this run
          </label>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
            <textarea
              id="analyst-question"
              value={question}
              maxLength={QUESTION_MAX}
              rows={2}
              placeholder="For example: why is polishing the bottleneck?"
              onChange={(event) => { setQuestion(event.target.value); }}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) submit(event)
              }}
              className="min-h-16 w-full rounded-md border border-border-strong bg-background px-3 py-2 text-sm text-foreground placeholder:text-subtle-foreground"
              aria-describedby="analyst-question-hint"
            />
            <Button type="submit" disabled={busy || question.trim() === ''} className="sm:self-stretch">
              {ask.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : <Send aria-hidden="true" />}
              Ask
            </Button>
          </div>
          <p id="analyst-question-hint" className="text-xs text-subtle-foreground">
            {question.length}/{QUESTION_MAX} characters. Answers use only the simulation's results; changes that were not
            simulated are not estimated.
          </p>
        </form>

        {busy ? (
          <div role="status" className="flex flex-col gap-3 rounded-lg border border-border bg-surface p-5">
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
              {pendingReport ? `Writing: ${INSIGHT_LABELS[pendingReport]}…` : 'Answering…'}{' '}
              This can take up to half a minute.
            </p>
            <Skeleton className="h-24 w-full" />
          </div>
        ) : null}

        {failure ? <ErrorState error={requestError(failure)} /> : null}

        {history.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : history.isError ? (
          <ErrorState error={history.error} onRetry={() => void history.refetch()} retrying={history.isFetching} />
        ) : history.data.items.length === 0 ? (
          busy ? null : (
            <p className="rounded-lg border border-dashed border-border-strong px-5 py-8 text-sm text-muted-foreground">
              Nothing asked about this run yet. Choose a report or ask a question.
            </p>
          )
        ) : (
          <div className="flex flex-col gap-6">
            <h2 className="sr-only">Insights for this run, newest first</h2>
            {history.data.items.map((insight) => (
              <InsightCard
                key={insight.id}
                insight={insight}
                runNames={runNames}
                onPrint={setPrintingId}
                printing={printingId === insight.id}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/** AI Analyst: explanations and answers grounded in a chosen simulation run. */
export function AnalystPage() {
  return (
    <>
      <PageHeader
        title="AI Analyst"
        description="Explanations of simulated results. The analyst interprets; it never calculates. Every number it shows was checked against the simulation's stored results."
      />
      <FactoryGate>{(factory) => <Analyst factory={factory} />}</FactoryGate>
    </>
  )
}

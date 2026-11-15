import { Info, Printer, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

import { SourceTag } from '@/components/DataTags'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { authorOf, INSIGHT_LABELS, shownValue } from '@/features/analyst/analystShared'
import type { EvidenceItem, Insight } from '@/lib/aiSchemas'
import { formatDateTime } from '@/lib/format'

const FALLBACK_TEXT = {
  VALIDATION_FAILED:
    "The language model's answer did not pass the evidence check (it stated something the simulation does not support), so the built-in analyst answered instead.",
  PROVIDER_ERROR: 'The AI provider was unavailable, so the built-in analyst answered instead.',
  TIMEOUT: 'The AI provider did not answer in time, so the built-in analyst answered instead.',
} as const

interface EvidenceListProps {
  keys: readonly string[]
  evidence: readonly EvidenceItem[]
  runNames: ReadonlyMap<string, string>
  summary: string
}

/** The evidence items behind a statement, in a disclosure. Each one is a stored simulated value. */
function EvidenceList({ keys, evidence, runNames, summary }: EvidenceListProps) {
  const items = keys.flatMap((key) => evidence.filter((item) => item.key === key))
  if (items.length === 0) return null
  return (
    <details className="group mt-2 rounded-md border border-border bg-surface px-3 py-2 text-sm">
      <summary className="cursor-pointer text-xs font-medium text-muted-foreground select-none">
        {summary} ({items.length})
      </summary>
      <div className="mt-2 flex flex-col gap-2">
        <SourceTag source="Simulated" />
        <ul className="flex flex-col divide-y divide-border">
          {items.map((item) => (
            <li key={item.key} className="flex flex-col gap-0.5 py-1.5 sm:flex-row sm:items-baseline sm:justify-between sm:gap-4">
              <span className="text-muted-foreground">{item.label}</span>
              <span className="flex flex-col items-start sm:items-end">
                <span className="metric font-medium text-foreground">{shownValue(item)}</span>
                <span className="text-xs text-subtle-foreground">
                  {runNames.get(item.sourceRunId) ?? 'Simulation run'}
                </span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </details>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {children}
    </section>
  )
}

function Bullets({ items }: { items: readonly string[] }) {
  return (
    <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-foreground">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  )
}

interface InsightCardProps {
  insight: Insight
  runNames: ReadonlyMap<string, string>
  onPrint?: (id: string) => void
  printing?: boolean
}

/**
 * One insight as a structured report: summary, key changes, risks, bottleneck, recommendations
 * with their evidence, assumptions and limitations. Tagged "AI interpretation"; its evidence is
 * tagged "Simulated".
 */
export function InsightCard({ insight, runNames, onPrint, printing = false }: InsightCardProps) {
  const { content } = insight
  const title = insight.type === 'ANSWER' ? (insight.question ?? 'Question') : INSIGHT_LABELS[insight.type]
  const runName = runNames.get(insight.runId) ?? 'Simulation run'
  const baselineName = insight.baselineRunId ? (runNames.get(insight.baselineRunId) ?? 'baseline') : null
  return (
    <Card
      aria-labelledby={`insight-${insight.id}`}
      className={printing ? 'print-target' : undefined}
      data-testid="insight-card"
    >
      <CardHeader>
        <div className="min-w-0">
          {insight.type === 'ANSWER' ? (
            <p className="text-xs font-medium text-muted-foreground">Question</p>
          ) : null}
          <CardTitle id={`insight-${insight.id}`} className="break-words">
            {title}
          </CardTitle>
          <p className="mt-1 text-xs text-muted-foreground">
            {runName}
            {baselineName ? ` compared with ${baselineName}` : ''} · {authorOf(insight)} ·{' '}
            <time dateTime={insight.createdAt}>{formatDateTime(insight.createdAt)}</time>
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <SourceTag source="AI interpretation" />
          {insight.type === 'EXECUTIVE_REPORT' && onPrint ? (
            <Button variant="outline" size="sm" className="print:hidden" onClick={() => { onPrint(insight.id); }}>
              <Printer aria-hidden="true" />
              Print
            </Button>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        {insight.fallback && insight.fallbackReason ? (
          <div
            role="status"
            className="flex items-start gap-2 rounded-md border border-status-warn/30 bg-status-warn-surface px-3 py-2 text-sm"
          >
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-warn" />
            <p>{FALLBACK_TEXT[insight.fallbackReason]}</p>
          </div>
        ) : null}

        <Section title="Summary">
          <p className="text-sm leading-relaxed text-foreground">{content.summary}</p>
          <EvidenceList
            keys={content.metricRefs}
            evidence={insight.evidence}
            runNames={runNames}
            summary="Evidence behind this answer"
          />
        </Section>

        {content.keyChanges.length > 0 ? (
          <Section title="Key changes">
            <Bullets items={content.keyChanges} />
          </Section>
        ) : null}

        {content.risks.length > 0 ? (
          <Section title="Risks">
            <Bullets items={content.risks} />
          </Section>
        ) : null}

        {content.bottleneckExplanation.trim() !== '' ? (
          <Section title="Bottleneck">
            <p className="text-sm leading-relaxed text-foreground">{content.bottleneckExplanation}</p>
          </Section>
        ) : null}

        {content.recommendations.length > 0 ? (
          <Section title="Recommendations">
            <ol className="flex flex-col gap-3">
              {content.recommendations.map((recommendation, index) => (
                <li key={index} className="rounded-md border border-border px-3 py-2.5">
                  <p className="text-sm font-medium text-foreground">{recommendation.title}</p>
                  <p className="mt-1 text-sm text-muted-foreground">{recommendation.rationale}</p>
                  <EvidenceList
                    keys={recommendation.metricRefs}
                    evidence={insight.evidence}
                    runNames={runNames}
                    summary="Evidence"
                  />
                </li>
              ))}
            </ol>
          </Section>
        ) : null}

        {content.assumptions.length > 0 ? (
          <Section title="Assumptions">
            <Bullets items={content.assumptions} />
          </Section>
        ) : null}

        {content.limitations.length > 0 ? (
          <Section title="Limitations">
            <Bullets items={content.limitations} />
          </Section>
        ) : null}

        <p className="flex items-start gap-1.5 text-xs text-subtle-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          An interpretation of simulated estimates, not a guarantee. Every number above was checked against the
          simulation's stored results before it was shown.
        </p>
      </CardContent>
    </Card>
  )
}

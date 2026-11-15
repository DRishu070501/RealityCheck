import type { EvidenceItem, Insight, InsightType } from '@/lib/aiSchemas'

export const INSIGHT_LABELS: Record<InsightType, string> = {
  EXPLANATION: 'Explanation of the run',
  BOTTLENECK: 'Bottleneck analysis',
  RECOMMENDATIONS: 'Recommendations',
  DELAY_ANALYSIS: 'Delivery and delays',
  COMPARISON_SUMMARY: 'Comparison with the baseline',
  EXECUTIVE_REPORT: 'Executive report',
  ANSWER: 'Answer',
}

/** Who wrote the content, in words. */
export function authorOf(insight: Pick<Insight, 'provider' | 'model'>): string {
  if (insight.provider === 'template') return 'Built-in analyst (fixed rules, no language model)'
  const vendor = insight.provider === 'anthropic' ? 'Claude' : insight.provider
  return insight.model ? `${vendor} · ${insight.model}` : vendor
}

/** The value of an evidence item as the analyst was allowed to quote it. */
export function shownValue(item: EvidenceItem): string {
  return item.display[0] ?? item.text ?? item.value ?? '—'
}

/** Questions suited to the chosen context. */
export function exampleQuestions(hasBaseline: boolean): string[] {
  const always = [
    'Where does work wait longest?',
    'Which orders are late, and why?',
    'Which resources are under-used?',
    'What if we added another shift?',
  ]
  return hasBaseline
    ? ['What changed compared with the baseline?', 'Is this scenario worth its extra cost?', ...always]
    : always
}

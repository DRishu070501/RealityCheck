import { NOT_AVAILABLE } from '@/lib/metrics'
import type { BottleneckChange } from '@/lib/scenarioSchemas'

/** Series colours: baseline neutral, alternative in the simulated accent. Names always accompany them. */
export const SERIES_COLORS = { baseline: 'var(--subtle-foreground)', alternative: 'var(--source-simulated)' } as const

export function termination(reason: string | null): string {
  switch (reason) {
    case 'COMPLETED':
      return 'All work finished'
    case 'HORIZON_REACHED':
      return 'Stopped at the time limit with work left'
    case 'EVENT_LIMIT_REACHED':
      return 'Stopped at the event limit with work left'
    default:
      return reason ?? NOT_AVAILABLE
  }
}

/** The bottleneck change of a comparison as one sentence. */
export function bottleneckSentence(change: BottleneckChange): string {
  const before = change.baselineStage?.name ?? change.baselineStage?.code ?? 'a stage'
  const after = change.alternativeStage?.name ?? change.alternativeStage?.code ?? 'a stage'
  switch (change.change) {
    case 'SAME':
      return `The bottleneck stays at ${before}.`
    case 'MOVED':
      return `The bottleneck moves from ${before} to ${after}.`
    case 'RESOLVED':
      return `${before} is no longer a bottleneck: no stage qualifies in the alternative.`
    case 'EMERGED':
      return `A bottleneck emerges at ${after}: no stage qualified in the baseline.`
    case 'NONE':
      return 'Neither run has a stage that qualifies as a bottleneck.'
  }
}

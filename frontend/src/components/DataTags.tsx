import { FlaskRound, SlidersHorizontal } from 'lucide-react'

import { Badge } from '@/components/ui/badge'

/**
 * Says where the figures in a view come from. Milestone 2 shows only configuration; "Actual"
 * (recorded history) and "Simulated" (engine output) join it in later milestones.
 */
export function SourceTag({ source = 'Configuration' }: { source?: 'Configuration' }) {
  return (
    <Badge variant="neutral" title="These values are the factory's configured settings, not measurements or simulation results.">
      <SlidersHorizontal aria-hidden="true" />
      {source}
    </Badge>
  )
}

/** Marks everything belonging to the seeded, fictional demonstration factory. */
export function DemoTag() {
  return (
    <Badge variant="warn" title="This factory is fictional. Its capacities, rates, yields and costs are synthetic, not industry benchmarks.">
      <FlaskRound aria-hidden="true" />
      Synthetic demo data
    </Badge>
  )
}

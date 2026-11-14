import { ClipboardCheck, FlaskRound, SlidersHorizontal } from 'lucide-react'

import { Badge } from '@/components/ui/badge'

export type DataSource = 'Configuration' | 'Actual'

const SOURCES = {
  Configuration: {
    icon: SlidersHorizontal,
    className: '',
    title: "These values are the factory's configured settings, not measurements or simulation results.",
  },
  Actual: {
    icon: ClipboardCheck,
    className: 'border-source-actual/30 text-source-actual',
    title: 'These values come from recorded operational history: orders and events as they were entered. They are not simulated.',
  },
} as const satisfies Record<DataSource, unknown>

/**
 * Says where the figures in a view come from: "Configuration" (what was set up) or "Actual"
 * (recorded history). "Simulated" (engine output) and "AI interpretation" join them in later
 * milestones.
 */
export function SourceTag({ source = 'Configuration' }: { source?: DataSource }) {
  const { icon: Icon, className, title } = SOURCES[source]
  return (
    <Badge variant="neutral" className={className} title={title}>
      <Icon aria-hidden="true" />
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

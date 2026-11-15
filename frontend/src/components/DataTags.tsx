import { ClipboardCheck, Cpu, FlaskRound, SlidersHorizontal } from 'lucide-react'

import { Badge } from '@/components/ui/badge'

export type DataSource = 'Configuration' | 'Actual' | 'Simulated'

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
  Simulated: {
    icon: Cpu,
    className: 'border-source-simulated/30 text-source-simulated',
    title:
      'These values were computed by the deterministic simulation engine from recorded data and the scenario’s changes. They are estimates under the engine’s rules, not measurements.',
  },
} as const satisfies Record<DataSource, unknown>

/**
 * Says where the figures in a view come from: "Configuration" (what was set up) or "Actual"
 * (recorded history) or "Simulated" (engine output, optionally with the scenario's name).
 * "AI interpretation" joins them in Milestone 7.
 */
export function SourceTag({ source = 'Configuration', scenarioName }: { source?: DataSource; scenarioName?: string }) {
  const { icon: Icon, className, title } = SOURCES[source]
  return (
    <Badge variant="neutral" className={className} title={title}>
      <Icon aria-hidden="true" />
      {source}
      {scenarioName ? <span className="font-normal">: {scenarioName}</span> : null}
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

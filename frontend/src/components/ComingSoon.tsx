import { Construction } from 'lucide-react'

import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'

interface ComingSoonProps {
  title: string
  /** What the screen will do once built. */
  summary: string
  /** Roadmap milestone that delivers it, for example "Milestone 2". */
  milestone: string
}

/**
 * Honest placeholder for a screen that is not built yet. It shows no data, sample or otherwise,
 * so nothing here can be mistaken for a real metric.
 */
export function ComingSoon({ title, summary, milestone }: ComingSoonProps) {
  return (
    <>
      <PageHeader title={title} />
      <div className="flex flex-col items-start gap-4 rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 sm:px-10">
        <span className="flex size-10 items-center justify-center rounded-md border border-border bg-surface-raised">
          <Construction aria-hidden="true" className="size-5 text-muted-foreground" />
        </span>
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-base font-medium text-foreground">Not implemented yet</p>
            <Badge variant="info">Planned for {milestone}</Badge>
          </div>
          <p className="mt-2 max-w-xl text-sm text-muted-foreground">{summary}</p>
          <p className="mt-2 max-w-xl text-sm text-subtle-foreground">
            This screen shows no data until the feature is built.
          </p>
        </div>
      </div>
    </>
  )
}

import { TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

import type { ApiError } from '@/lib/api'

/** A request failure that is not tied to one form field. */
export function ProblemBanner({ error, title, children }: { error?: ApiError | null; title?: string; children?: ReactNode }) {
  if (!error && !children) return null
  return (
    <div
      role="alert"
      className="flex items-start gap-2 rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5"
    >
      <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
      <div className="min-w-0 text-sm">
        <p className="font-medium text-foreground">{title ?? error?.title ?? 'The request failed'}</p>
        {error ? <p className="text-muted-foreground">{error.detail ?? 'The request could not be completed.'}</p> : null}
        {children}
      </div>
    </div>
  )
}

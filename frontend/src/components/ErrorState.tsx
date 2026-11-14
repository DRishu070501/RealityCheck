import { RotateCw, TriangleAlert } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api'

interface ErrorStateProps {
  error: unknown
  onRetry?: () => void
  /** True while a retry is in flight. */
  retrying?: boolean
}

function describe(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    const status = error.status === undefined ? '' : ` (HTTP ${error.status})`
    return {
      title: `${error.title}${status}`,
      detail: error.detail ?? 'The request could not be completed.',
    }
  }
  return { title: 'Something went wrong', detail: 'An unexpected error occurred while loading this view.' }
}

/** Shared error panel for any data view. Never shows stack traces or raw exception text. */
export function ErrorState({ error, onRetry, retrying = false }: ErrorStateProps) {
  const { title, detail } = describe(error)
  return (
    <div
      role="alert"
      className="flex flex-col gap-3 rounded-lg border border-status-error/30 bg-status-error-surface px-5 py-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex min-w-0 items-start gap-3">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground">{title}</p>
          <p className="mt-0.5 text-sm text-muted-foreground">{detail}</p>
        </div>
      </div>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry} disabled={retrying}>
          <RotateCw aria-hidden="true" className={retrying ? 'animate-spin' : undefined} />
          {retrying ? 'Retrying' : 'Retry'}
        </Button>
      ) : null}
    </div>
  )
}

import { Factory as FactoryIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { ErrorState } from '@/components/ErrorState'
import { Skeleton } from '@/components/ui/skeleton'
import type { Factory } from '@/lib/schemas'
import { useCurrentFactory } from '@/lib/useCurrentFactory'

/** Shown when the backend is healthy but holds no factory. Explains the two ways to get one. */
export function NoFactoryState() {
  return (
    <div className="flex flex-col items-start gap-4 rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 sm:px-10">
      <span className="flex size-10 items-center justify-center rounded-md border border-border bg-surface-raised">
        <FactoryIcon aria-hidden="true" className="size-5 text-muted-foreground" />
      </span>
      <div>
        <p className="text-base font-medium text-foreground">No factory is configured yet</p>
        <p className="mt-2 max-w-xl text-sm text-muted-foreground">
          The database has no factory, so there is nothing to show. To load the fictional demonstration factory,
          either start the backend with <code className="metric text-foreground">REALITYCHECK_DEMO_SEED=true</code>, or
          use <Link to="/settings" className="text-primary underline-offset-4 hover:underline">Settings</Link> to reset
          the demo data (available when{' '}
          <code className="metric text-foreground">REALITYCHECK_DEMO_RESET_ENABLED=true</code>).
        </p>
      </div>
    </div>
  )
}

function LoadingBlock() {
  return (
    <div role="status" aria-label="Loading factory" className="flex flex-col gap-3">
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-64 w-full" />
    </div>
  )
}

/**
 * Resolves the current factory and renders `children` with it, or the shared loading, error or
 * empty state. Every factory-scoped page sits inside one.
 */
export function FactoryGate({ children }: { children: (factory: Factory) => ReactNode }) {
  const current = useCurrentFactory()
  switch (current.status) {
    case 'loading':
      return <LoadingBlock />
    case 'error':
      return <ErrorState error={current.error} onRetry={current.retry} retrying={current.retrying} />
    case 'empty':
      return <NoFactoryState />
    case 'ready':
      return <>{children(current.factory)}</>
  }
}

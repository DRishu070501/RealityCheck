import { Skeleton } from '@/components/ui/skeleton'

/** Shown in place of a screen while its code is being fetched. */
export function RouteFallback() {
  return (
    <div role="status" aria-label="Loading screen" className="flex flex-col gap-3">
      <Skeleton className="h-10 w-64" />
      <Skeleton className="h-64 w-full" />
    </div>
  )
}

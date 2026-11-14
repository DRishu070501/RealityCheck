import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

/** Placeholder block shown while data loads. Decorative: hidden from assistive technology. */
export function Skeleton({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      data-slot="skeleton"
      aria-hidden="true"
      className={cn('animate-pulse rounded-sm bg-surface-hover', className)}
      {...props}
    />
  )
}

import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

export function Card({ className, ...props }: ComponentProps<'section'>) {
  return (
    <section
      data-slot="card"
      className={cn('rounded-lg border border-border bg-surface text-foreground', className)}
      {...props}
    />
  )
}

export function CardHeader({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      data-slot="card-header"
      className={cn('flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4', className)}
      {...props}
    />
  )
}

export function CardTitle({ className, children, ...props }: ComponentProps<'h2'>) {
  return (
    <h2 data-slot="card-title" className={cn('text-sm font-semibold tracking-tight', className)} {...props}>
      {children}
    </h2>
  )
}

export function CardDescription({ className, ...props }: ComponentProps<'p'>) {
  return (
    <p data-slot="card-description" className={cn('mt-0.5 text-xs text-muted-foreground', className)} {...props} />
  )
}

export function CardContent({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-content" className={cn('px-5 py-4', className)} {...props} />
}

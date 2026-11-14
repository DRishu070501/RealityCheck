import type { ComponentProps, ReactNode } from 'react'

import { cn } from '@/lib/utils'

const CONTROL =
  'h-9 w-full rounded-md border border-border-strong bg-background px-3 text-sm text-foreground placeholder:text-subtle-foreground disabled:opacity-50 aria-invalid:border-status-error'

export function Label({ className, ...props }: ComponentProps<'label'>) {
  return <label data-slot="label" className={cn('text-sm font-medium text-foreground', className)} {...props} />
}

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return <input data-slot="input" className={cn(CONTROL, 'metric', className)} {...props} />
}

/** A native select with the shared control styling; native keeps keyboard and screen-reader behaviour. */
export function Select({ className, ...props }: ComponentProps<'select'>) {
  return <select data-slot="select" className={cn(CONTROL, 'pr-8', className)} {...props} />
}

interface FieldProps {
  /** Must equal the `id` of the control passed as `children`. */
  htmlFor: string
  label: string
  /** Unit or format guidance shown under the label. */
  hint?: string
  error?: string
  children: ReactNode
}

/**
 * Label, control, hint and error for one form field. The control should set
 * `aria-invalid` and `aria-describedby={describedBy(id, ...)}` (from `@/lib/utils`) so the messages are announced.
 */
export function Field({ htmlFor, label, hint, error, children }: FieldProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {hint ? (
        <p id={`${htmlFor}-hint`} className="text-xs text-subtle-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={`${htmlFor}-error`} role="alert" className="text-xs text-status-error">
          {error}
        </p>
      ) : null}
    </div>
  )
}

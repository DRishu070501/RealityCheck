import { cn } from '@/lib/utils'

interface SegmentedProps<T extends string> {
  /** Accessible name of the group, for example "Filter by status". */
  label: string
  value: T
  options: readonly { value: T; label: string }[]
  onChange: (value: T) => void
  className?: string
}

/** A row of mutually exclusive toggle buttons. The pressed one is the current value. */
export function Segmented<T extends string>({ label, value, options, onChange, className }: SegmentedProps<T>) {
  return (
    <div role="group" aria-label={label} className={cn('flex w-fit flex-wrap rounded-md border border-border-strong p-0.5', className)}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => {
            onChange(option.value)
          }}
          className={cn(
            'rounded-sm px-3 py-1 text-xs font-medium whitespace-nowrap transition-colors',
            value === option.value ? 'bg-surface-hover text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

import { useRef, type ComponentProps } from 'react'

import { useScrolls } from '@/lib/useScrollFocus'
import { cn } from '@/lib/utils'

/**
 * Scrolls sideways inside its own box on narrow screens so the page itself never does. While it
 * scrolls, the box takes keyboard focus (with a visible ring) so arrow keys can scroll it.
 */
export function Table({ className, ...props }: ComponentProps<'table'>) {
  const box = useRef<HTMLDivElement>(null)
  const scrolls = useScrolls(box, 'x')
  return (
    <div
      ref={box}
      className="w-full overflow-x-auto rounded-sm focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring"
      {...(scrolls ? { tabIndex: 0, role: 'region', 'aria-label': 'Table, scrolls sideways' } : {})}
    >
      <table data-slot="table" className={cn('w-full border-collapse text-left text-sm', className)} {...props} />
    </div>
  )
}

export function TableHead({ className, ...props }: ComponentProps<'th'>) {
  return (
    <th
      scope="col"
      className={cn(
        'border-b border-border px-5 py-2.5 text-xs font-medium whitespace-nowrap text-muted-foreground',
        className,
      )}
      {...props}
    />
  )
}

export function TableRow({ className, ...props }: ComponentProps<'tr'>) {
  return <tr className={cn('border-b border-border last:border-b-0', className)} {...props} />
}

export function TableCell({ className, ...props }: ComponentProps<'td'>) {
  return <td className={cn('px-5 py-3 align-top', className)} {...props} />
}

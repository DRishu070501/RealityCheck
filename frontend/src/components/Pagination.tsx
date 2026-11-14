import { ChevronLeft, ChevronRight } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { formatInteger } from '@/lib/format'

interface PaginationProps {
  /** Zero-based page currently shown. */
  page: number
  size: number
  totalItems: number
  totalPages: number
  /** Plural noun for what is being paged, for example "orders". */
  noun: string
  onPage: (page: number) => void
}

/** "26–47 of 47 orders" with previous and next. Pages are fetched from the server one at a time. */
export function Pagination({ page, size, totalItems, totalPages, noun, onPage }: PaginationProps) {
  const first = totalItems === 0 ? 0 : page * size + 1
  const last = Math.min(totalItems, (page + 1) * size)
  return (
    <nav
      aria-label={`Pages of ${noun}`}
      className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-5 py-3"
    >
      <p className="metric text-xs text-muted-foreground" aria-live="polite">
        {formatInteger(first)}–{formatInteger(last)} of {formatInteger(totalItems)} {noun}
      </p>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => {
            onPage(page - 1)
          }}
        >
          <ChevronLeft aria-hidden="true" />
          Previous
        </Button>
        <span className="metric text-xs text-muted-foreground">
          Page {formatInteger(page + 1)} of {formatInteger(Math.max(1, totalPages))}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={page + 1 >= totalPages}
          onClick={() => {
            onPage(page + 1)
          }}
        >
          Next
          <ChevronRight aria-hidden="true" />
        </Button>
      </div>
    </nav>
  )
}

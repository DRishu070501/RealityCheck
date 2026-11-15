import type { ReactNode } from 'react'

import { NOT_AVAILABLE } from '@/lib/metrics'
import { Table } from '@/components/ui/table'
import { cn } from '@/lib/utils'

/** One headline figure. A null value shows "—" with the reason as a tooltip, never zero. */
export function Tile({
  label,
  value,
  hint,
  nullReason,
  title,
}: {
  label: string
  value: string | null
  hint?: ReactNode
  nullReason?: string
  /** Exact value or explanation on hover. */
  title?: string
}) {
  return (
    <div className="flex flex-col gap-1 rounded-md border border-border bg-surface-raised px-4 py-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="metric text-lg font-semibold text-foreground" title={value === null ? nullReason : title}>
        {value ?? NOT_AVAILABLE}
      </dd>
      {hint ? <dd className="text-xs text-subtle-foreground">{hint}</dd> : null}
    </div>
  )
}

/** A chart with a caption, and the same data as a table for screen readers and on request. */
export function ChartFigure({
  title,
  description,
  chart,
  table,
}: {
  title: string
  description?: string
  chart: ReactNode
  table: ReactNode
}) {
  return (
    <figure className="flex min-w-0 flex-col gap-2 rounded-md border border-border bg-surface-raised p-4">
      <figcaption>
        <span className="text-sm font-medium text-foreground">{title}</span>
        {description ? <span className="block text-xs text-muted-foreground">{description}</span> : null}
      </figcaption>
      {chart}
      <details className="text-xs">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Show as table</summary>
        <div className="mt-2">{table}</div>
      </details>
    </figure>
  )
}

/** A compact table for chart alternatives and small result lists. */
export function SimpleTable({
  caption,
  headers,
  rows,
  className,
}: {
  caption: string
  headers: readonly string[]
  rows: readonly (readonly ReactNode[])[]
  className?: string
}) {
  return (
    <Table className={cn('text-xs', className)}>
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr>
          {headers.map((header) => (
            <th key={header} scope="col" className="border-b border-border px-2 py-1.5 font-medium text-muted-foreground">
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index} className="border-b border-border last:border-b-0">
            {row.map((cell, cellIndex) =>
              cellIndex === 0 ? (
                <th key={cellIndex} scope="row" className="px-2 py-1.5 font-normal text-foreground">
                  {cell}
                </th>
              ) : (
                <td key={cellIndex} className="metric px-2 py-1.5 text-foreground">
                  {cell}
                </td>
              ),
            )}
          </tr>
        ))}
      </tbody>
    </Table>
  )
}

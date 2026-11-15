import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { SERIES_COLORS } from '@/features/results/resultsFormat'

export interface BarRow {
  label: string
  baseline: number | null
  alternative: number | null
}

interface ComparisonBarsProps {
  rows: readonly BarRow[]
  baselineName: string
  alternativeName: string
  /** The y-axis label, with its unit, for example "Hours". */
  unitLabel: string
  format: (value: number) => string
  /** Shorter form for axis ticks, for example compact money; defaults to `format`. */
  axisFormat?: (value: number) => string
}

/**
 * Baseline and alternative side by side, one group per row. Drawn for sighted users; the same
 * numbers are in the table next to every chart, which screen readers use instead.
 */
export default function ComparisonBars({
  rows,
  baselineName,
  alternativeName,
  unitLabel,
  format,
  axisFormat,
}: ComparisonBarsProps) {
  // Many categories (stages) do not fit side by side: slant their labels.
  const slanted = rows.length > 4
  return (
    <div aria-hidden="true" className={slanted ? 'h-64 w-full' : 'h-56 w-full'}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={[...rows]} margin={{ top: 4, right: 8, bottom: 0, left: 8 }} barCategoryGap="24%">
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="label"
            tickLine={false}
            axisLine={{ stroke: 'var(--border-strong)' }}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            interval={0}
            angle={slanted ? -30 : 0}
            textAnchor={slanted ? 'end' : 'middle'}
            height={slanted ? 64 : 30}
          />
          <YAxis
            tickLine={false}
            axisLine={false}
            width={64}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickFormatter={(value: number) => (axisFormat ?? format)(value)}
            label={{
              value: unitLabel,
              angle: -90,
              position: 'insideLeft',
              offset: 0,
              fill: 'var(--subtle-foreground)',
              fontSize: 11,
            }}
          />
          <Tooltip
            formatter={(value) => (typeof value === 'number' ? format(value) : String(value))}
            contentStyle={{
              background: 'var(--surface-raised)',
              border: '1px solid var(--border-strong)',
              fontSize: 12,
            }}
            cursor={{ fill: 'var(--surface-hover)' }}
            isAnimationActive={false}
          />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          <Bar dataKey="baseline" name={baselineName} fill={SERIES_COLORS.baseline} isAnimationActive={false} />
          <Bar dataKey="alternative" name={alternativeName} fill={SERIES_COLORS.alternative} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

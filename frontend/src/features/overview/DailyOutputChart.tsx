import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { formatCarats, formatDay, formatInteger, formatStones } from '@/lib/format'
import type { DailyOutput } from '@/lib/schemas'

interface Row {
  date: string
  label: string
  /** Display only: recorded stones are whole numbers. */
  stones: number
  day: DailyOutput
}

function OutputTooltip({ active, payload }: { active?: boolean; payload?: readonly { payload?: Row }[] }) {
  const row = payload?.[0]?.payload
  if (!active || !row) return null
  return (
    <div className="rounded-md border border-border-strong bg-surface-raised px-3 py-2 text-xs shadow-lg shadow-black/40">
      <p className="font-medium text-foreground">{formatDay(row.date)}</p>
      <dl className="metric mt-1.5 flex flex-col gap-0.5">
        <div className="flex justify-between gap-6">
          <dt className="text-muted-foreground">Stones finished</dt>
          <dd className="text-foreground">{formatStones(row.day.stonesCompleted)}</dd>
        </div>
        <div className="flex justify-between gap-6">
          <dt className="text-muted-foreground">Weight finished</dt>
          <dd className="text-foreground">{formatCarats(row.day.caratsCompleted)}</dd>
        </div>
        <div className="flex justify-between gap-6">
          <dt className="text-muted-foreground">Batches finished</dt>
          <dd className="text-foreground">{formatInteger(row.day.batchesCompleted)}</dd>
        </div>
      </dl>
    </div>
  )
}

/** Stones that finished the last stage on each factory-local day. Carats are in the tooltip and the table. */
export default function DailyOutputChart({ days }: { days: readonly DailyOutput[] }) {
  const rows: Row[] = days.map((day) => ({
    date: day.date,
    label: formatDay(day.date, true),
    stones: Number(day.stonesCompleted),
    day,
  }))
  return (
    <div aria-hidden="true" className="h-56 w-full px-2 pt-4">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 4, right: 12, bottom: 0, left: 0 }} barCategoryGap="22%">
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="label"
            tickLine={false}
            axisLine={{ stroke: 'var(--border-strong)' }}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            interval="preserveStartEnd"
            minTickGap={12}
          />
          <YAxis
            allowDecimals={false}
            tickLine={false}
            axisLine={false}
            width={48}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            label={{
              value: 'Stones',
              angle: -90,
              position: 'insideLeft',
              offset: 14,
              fill: 'var(--subtle-foreground)',
              fontSize: 11,
            }}
          />
          <Tooltip content={<OutputTooltip />} cursor={{ fill: 'var(--surface-hover)' }} isAnimationActive={false} />
          <Bar dataKey="stones" name="Stones finished" fill="var(--source-actual)" isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

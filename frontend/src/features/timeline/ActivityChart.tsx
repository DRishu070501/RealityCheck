import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { formatDay, formatInteger, localDayKey } from '@/lib/format'
import { EVENT_TYPE_LABELS } from '@/lib/production'
import type { EventType, TimelineSummary } from '@/lib/schemas'

/** The event types drawn as their own series; everything else is grouped as "Other". */
const SERIES = [
  { key: 'STAGE_STARTED', label: EVENT_TYPE_LABELS.STAGE_STARTED, color: 'var(--primary)' },
  { key: 'STAGE_COMPLETED', label: EVENT_TYPE_LABELS.STAGE_COMPLETED, color: 'var(--status-ok)' },
  { key: 'STAGE_QUEUED', label: EVENT_TYPE_LABELS.STAGE_QUEUED, color: 'var(--accent)' },
  { key: 'OTHER', label: 'Other events', color: 'var(--subtle-foreground)' },
] as const

type SeriesKey = (typeof SERIES)[number]['key']

interface DayRow extends Record<SeriesKey, number> {
  /** Factory-local day, "YYYY-MM-DD". */
  day: string
  label: string
  total: number
}

function toRows(summary: TimelineSummary): DayRow[] {
  return summary.buckets.map((bucket) => {
    const count = (type: EventType): number => bucket.byType[type] ?? 0
    const started = count('STAGE_STARTED')
    const completed = count('STAGE_COMPLETED')
    const queued = count('STAGE_QUEUED')
    const day = localDayKey(bucket.start, summary.timeZone)
    return {
      day,
      label: formatDay(day, true),
      total: bucket.total,
      STAGE_STARTED: started,
      STAGE_COMPLETED: completed,
      STAGE_QUEUED: queued,
      OTHER: bucket.total - started - completed - queued,
    }
  })
}

function DayTooltip({ active, payload }: { active?: boolean; payload?: readonly { payload?: DayRow }[] }) {
  const row = payload?.[0]?.payload
  if (!active || !row) return null
  return (
    <div className="rounded-md border border-border-strong bg-surface-raised px-3 py-2 text-xs shadow-lg shadow-black/40">
      <p className="font-medium text-foreground">{formatDay(row.day)}</p>
      <p className="metric mt-0.5 text-muted-foreground">{formatInteger(row.total)} events</p>
      <dl className="metric mt-1.5 flex flex-col gap-0.5">
        {SERIES.map((series) => (
          <div key={series.key} className="flex items-center justify-between gap-6">
            <dt className="flex items-center gap-1.5 text-muted-foreground">
              <span aria-hidden="true" className="size-2 rounded-[1px]" style={{ backgroundColor: series.color }} />
              {series.label}
            </dt>
            <dd className="text-foreground">{formatInteger(row[series.key])}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

interface ActivityChartProps {
  summary: TimelineSummary
  /** The day currently used as the time filter, if the filter is exactly one day. */
  selectedDay: string | null
  onSelectDay: (day: string) => void
}

/**
 * Events per factory-local day, stacked by type. Selecting a bar filters the timeline to that
 * day. The same days are offered as a list of buttons for keyboard and screen-reader users, who
 * cannot reach the bars.
 */
export function ActivityChart({ summary, selectedDay, onSelectDay }: ActivityChartProps) {
  const rows = toRows(summary)
  return (
    <div>
      <div aria-hidden="true" className="h-56 w-full px-2 pt-4">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart accessibilityLayer={false} data={rows} margin={{ top: 4, right: 12, bottom: 0, left: 0 }} barCategoryGap="18%">
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
              width={44}
              tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
              label={{
                value: 'Events',
                angle: -90,
                position: 'insideLeft',
                offset: 14,
                fill: 'var(--subtle-foreground)',
                fontSize: 11,
              }}
            />
            <Tooltip content={<DayTooltip />} cursor={{ fill: 'var(--surface-hover)' }} isAnimationActive={false} />
            {SERIES.map((series) => (
              <Bar
                key={series.key}
                dataKey={series.key}
                name={series.label}
                stackId="events"
                fill={series.color}
                isAnimationActive={false}
                className="cursor-pointer"
                onClick={(entry: { payload?: DayRow }) => {
                  if (entry.payload) onSelectDay(entry.payload.day)
                }}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </div>

      <ul aria-hidden="true" className="flex flex-wrap gap-x-4 gap-y-1 px-5 pt-2 pb-4 text-xs text-muted-foreground">
        {SERIES.map((series) => (
          <li key={series.key} className="flex items-center gap-1.5">
            <span className="size-2 rounded-[1px]" style={{ backgroundColor: series.color }} />
            {series.label}
          </li>
        ))}
        <li className="text-subtle-foreground">Days in {summary.timeZone}. Select a bar to show that day.</li>
      </ul>

      <ul aria-label="Activity by day" className="sr-only">
        {rows.map((row) => (
          <li key={row.day}>
            <button
              type="button"
              aria-pressed={row.day === selectedDay}
              onClick={() => {
                onSelectDay(row.day)
              }}
            >
              {formatDay(row.day)}: {formatInteger(row.total)} events. Show this day.
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

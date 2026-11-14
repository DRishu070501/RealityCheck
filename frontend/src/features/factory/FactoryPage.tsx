import type { ReactNode } from 'react'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { DAY_NAMES, formatDateTimeInZone, formatDecimal, formatInteger, humanizeCode } from '@/lib/format'
import type { Factory, Shift } from '@/lib/schemas'

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 px-5 py-3 sm:grid-cols-[14rem_1fr] sm:items-baseline sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-sm text-foreground">{children}</dd>
    </div>
  )
}

function shiftSeconds(shift: Shift): number {
  const seconds = (time: string): number => {
    const [hours = '0', minutes = '0'] = time.split(':')
    return Number(hours) * 3600 + Number(minutes) * 60
  }
  const breaks = shift.breaks.reduce((total, pause) => total + seconds(pause.endTime) - seconds(pause.startTime), 0)
  return seconds(shift.endTime) - seconds(shift.startTime) - breaks
}

/** Count keys are plural nouns ("resources"); one of them reads better in the singular. */
function countNoun(key: string, value: number): string {
  const noun = humanizeCode(key).toLowerCase()
  if (key === 'resources') return value === 1 ? 'resource pool' : 'resource pools'
  return value === 1 && noun.endsWith('s') ? noun.slice(0, -1) : noun
}

function hours(seconds: number): string {
  return `${formatDecimal(String(seconds / 3600), 2)} h`
}

function WeekCalendar({ factory }: { factory: Factory }) {
  const { shifts, weeklyWorkingSeconds } = factory.calendar
  return (
    <Card aria-labelledby="calendar-title">
      <CardHeader>
        <div>
          <CardTitle id="calendar-title">Working calendar</CardTitle>
          <CardDescription>
            Weekly shifts in factory local time ({factory.timeZone}). Work is scheduled only inside these hours.
          </CardDescription>
        </div>
        <p className="text-sm text-muted-foreground">
          <span className="metric text-base font-semibold text-foreground">{hours(weeklyWorkingSeconds)}</span> working
          time per week
        </p>
      </CardHeader>
      <ol className="divide-y divide-border">
        {DAY_NAMES.map((day, index) => {
          const dayShifts = shifts.filter((shift) => shift.dayOfWeek === index + 1)
          const worked = dayShifts.reduce((total, shift) => total + shiftSeconds(shift), 0)
          return (
            <li
              key={day}
              aria-label={day}
              className="grid grid-cols-[7rem_1fr] items-start gap-x-4 gap-y-1 px-5 py-3 sm:grid-cols-[9rem_1fr_6rem]"
            >
              <span className="text-sm font-medium text-foreground">{day}</span>
              {dayShifts.length === 0 ? (
                <span className="text-sm text-subtle-foreground">Closed</span>
              ) : (
                <ul className="flex flex-col gap-1.5">
                  {dayShifts.map((shift) => (
                    <li key={shift.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                      <span className="metric text-foreground">
                        {shift.startTime} – {shift.endTime}
                      </span>
                      {shift.breaks.map((pause) => (
                        <span key={pause.startTime} className="metric text-muted-foreground">
                          break {pause.startTime} – {pause.endTime}
                        </span>
                      ))}
                    </li>
                  ))}
                </ul>
              )}
              <span className="metric col-start-2 text-sm text-muted-foreground sm:col-start-3 sm:text-right">
                {dayShifts.length === 0 ? '0 h' : hours(worked)}
              </span>
            </li>
          )
        })}
      </ol>
    </Card>
  )
}

function FactoryProfile({ factory }: { factory: Factory }) {
  const counts = Object.entries(factory.counts).sort(([a], [b]) => a.localeCompare(b))
  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="profile-title">
        <CardHeader>
          <div className="min-w-0">
            <CardTitle id="profile-title" className="text-base">
              {factory.name}
            </CardTitle>
            {factory.description ? <CardDescription>{factory.description}</CardDescription> : null}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {factory.demo ? <DemoTag /> : null}
            <SourceTag />
          </div>
        </CardHeader>
        <dl className="divide-y divide-border">
          <Fact label="Time zone">
            <span className="metric">{factory.timeZone}</span>
          </Fact>
          <Fact label="Currency">
            <span className="metric">{factory.currencyCode}</span>
          </Fact>
          <Fact label="Configured">
            {counts.length === 0 ? (
              <span className="text-muted-foreground">Nothing yet</span>
            ) : (
              <span className="flex flex-wrap gap-2">
                {counts.map(([key, value]) => (
                  <Badge key={key} variant="neutral" className="metric">
                    {formatInteger(value)} {countNoun(key, value)}
                  </Badge>
                ))}
              </span>
            )}
          </Fact>
          <Fact label="Created">
            <time className="metric" dateTime={factory.createdAt}>
              {formatDateTimeInZone(factory.createdAt, factory.timeZone)}
            </time>
          </Fact>
        </dl>
        {factory.demo ? (
          <CardContent className="border-t border-border text-xs text-subtle-foreground">
            This factory is fictional. Its capacities, rates, yields and costs were invented for demonstration and are
            not industry benchmarks.
          </CardContent>
        ) : null}
      </Card>

      <WeekCalendar factory={factory} />
    </div>
  )
}

export function FactoryPage() {
  return (
    <>
      <PageHeader title="Factory" description="The factory's profile and the weekly calendar that work is scheduled against." />
      <FactoryGate>{(factory) => <FactoryProfile factory={factory} />}</FactoryGate>
    </>
  )
}

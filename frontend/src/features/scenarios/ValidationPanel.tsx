import { CircleCheck, CircleX, Info, TriangleAlert } from 'lucide-react'
import { useState } from 'react'

import { ErrorState } from '@/components/ErrorState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { formatDecimal, formatInteger } from '@/lib/format'
import { formatWorkingHours } from '@/lib/metrics'
import type { ScenarioValidation } from '@/lib/scenarioSchemas'
import { cn } from '@/lib/utils'

interface ValidationPanelProps {
  validation: ScenarioValidation | undefined
  pending: boolean
  error: unknown
  onRetry: () => void
  retrying: boolean
}

function Changed({ before, after, children }: { before: string; after: string; children?: string }) {
  const changed = before !== after
  return (
    <span className={cn('metric', changed && 'font-semibold text-foreground')}>
      {children ?? after}
      {changed ? <span className="sr-only"> (changed from {before})</span> : null}
    </span>
  )
}

/**
 * What the scenario's changes do to the configuration, before any simulation: errors that stop a
 * run, warnings that do not, and capacities, rates and working time before and after.
 */
export function ValidationPanel({ validation, pending, error, onRetry, retrying }: ValidationPanelProps) {
  const [showAll, setShowAll] = useState(false)

  if (pending) {
    return (
      <Card aria-labelledby="validation-title">
        <CardHeader>
          <CardTitle id="validation-title">Check before running</CardTitle>
        </CardHeader>
        <CardContent role="status" aria-label="Checking the scenario">
          <Skeleton className="h-40 w-full" />
        </CardContent>
      </Card>
    )
  }
  if (error || !validation) {
    return (
      <Card aria-labelledby="validation-title">
        <CardHeader>
          <CardTitle id="validation-title">Check before running</CardTitle>
        </CardHeader>
        <CardContent>
          <ErrorState error={error} onRetry={onRetry} retrying={retrying} />
        </CardContent>
      </Card>
    )
  }

  const preview = validation.preview
  const pools = preview
    ? [...preview.pools].sort(
        (a, b) => (a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === 'MACHINE' ? -1 : 1),
      )
    : []
  const changedPools = pools.filter((pool) => pool.capacityBefore !== pool.capacityAfter || pool.windowsAdded !== 0)
  const stages = preview ? [...preview.stages] : []
  const changedStages = stages.filter(
    (stage) => stage.stonesPerHourBefore !== stage.stonesPerHourAfter || stage.maxSlotsBefore !== stage.maxSlotsAfter,
  )
  const shownPools = showAll ? pools : changedPools
  const shownStages = showAll ? stages : changedStages

  return (
    <Card aria-labelledby="validation-title">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle id="validation-title">Check before running</CardTitle>
          <CardDescription>
            The configuration the simulation would use, with the changes applied. Configuration, not results: nothing
            has been simulated yet.
          </CardDescription>
        </div>
        {validation.valid ? (
          <Badge variant="ok">
            <CircleCheck aria-hidden="true" />
            Ready to run
          </Badge>
        ) : (
          <Badge variant="error">
            <CircleX aria-hidden="true" />
            Cannot run
          </Badge>
        )}
      </CardHeader>

      {validation.errors.length > 0 ? (
        <CardContent className="border-b border-border">
          <div role="alert" className="flex flex-col gap-2">
            <p className="flex items-center gap-2 text-sm font-medium text-status-error">
              <CircleX aria-hidden="true" className="size-4" />
              {validation.errors.length === 1 ? 'This problem stops a run:' : 'These problems stop a run:'}
            </p>
            <ul className="list-disc pl-6 text-sm text-foreground">
              {validation.errors.map((problem, index) => (
                <li key={`${problem.code}-${String(index)}`}>
                  {problem.message} <span className="metric text-xs text-subtle-foreground">({problem.code})</span>
                </li>
              ))}
            </ul>
          </div>
        </CardContent>
      ) : null}

      {validation.warnings.length > 0 ? (
        <CardContent className="border-b border-border">
          <p className="flex items-center gap-2 text-sm font-medium text-status-warn">
            <TriangleAlert aria-hidden="true" className="size-4" />
            Warnings (the scenario can still run)
          </p>
          <ul className="mt-2 list-disc pl-6 text-sm text-foreground">
            {validation.warnings.map((warning) => (
              <li key={`${warning.ordinal}-${warning.code}`}>
                Change {warning.ordinal}: {warning.message}
              </li>
            ))}
          </ul>
        </CardContent>
      ) : null}

      {preview ? (
        <>
          <dl className="grid grid-cols-1 border-b border-border sm:grid-cols-2">
            <div className="flex flex-col gap-0.5 px-5 py-3">
              <dt className="text-xs text-muted-foreground">Working time per week</dt>
              <dd className="text-sm text-foreground">
                <span className="metric">{formatWorkingHours(preview.weeklyWorkingSecondsBefore)}</span> →{' '}
                <Changed
                  before={String(preview.weeklyWorkingSecondsBefore)}
                  after={String(preview.weeklyWorkingSecondsAfter)}
                >
                  {formatWorkingHours(preview.weeklyWorkingSecondsAfter)}
                </Changed>
              </dd>
            </div>
            <div className="flex flex-col gap-0.5 px-5 py-3">
              <dt className="text-xs text-muted-foreground">Stones not yet started (including later orders)</dt>
              <dd className="text-sm text-foreground">
                <span className="metric">{formatDecimal(preview.unstartedStonesBefore, 1)}</span> →{' '}
                <Changed before={preview.unstartedStonesBefore} after={preview.unstartedStonesAfter}>
                  {formatDecimal(preview.unstartedStonesAfter, 1)}
                </Changed>
              </dd>
            </div>
          </dl>

          <CardContent className="flex flex-wrap items-center justify-between gap-2 border-b border-border py-3">
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Info aria-hidden="true" className="size-3.5" />
              {showAll
                ? 'Showing every pool and stage.'
                : changedPools.length + changedStages.length === 0
                  ? 'No pool or stage is changed.'
                  : 'Showing only the pools and stages the changes affect.'}
            </p>
            <Button
              variant="ghost"
              size="sm"
              aria-pressed={showAll}
              onClick={() => {
                setShowAll((value) => !value)
              }}
            >
              {showAll ? 'Show only changed' : 'Show all'}
            </Button>
          </CardContent>

          {shownPools.length > 0 ? (
            <Table aria-label="Resource pools before and after" className="min-w-[32rem]">
              <thead>
                <tr>
                  <TableHead>Pool</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead className="text-right">Units before</TableHead>
                  <TableHead className="text-right">Units after</TableHead>
                  <TableHead>Dated reductions added</TableHead>
                </tr>
              </thead>
              <tbody>
                {shownPools.map((pool) => (
                  <TableRow key={pool.resourceId}>
                    <TableCell className="font-medium">{pool.name}</TableCell>
                    <TableCell className="text-muted-foreground">{pool.kind === 'MACHINE' ? 'Machine' : 'Employee'}</TableCell>
                    <TableCell className="metric text-right">{formatInteger(pool.capacityBefore)}</TableCell>
                    <TableCell className="text-right">
                      <Changed before={String(pool.capacityBefore)} after={String(pool.capacityAfter)} />
                    </TableCell>
                    <TableCell className="metric text-muted-foreground">
                      {pool.windowsAdded === 0 ? '—' : formatInteger(pool.windowsAdded)}
                    </TableCell>
                  </TableRow>
                ))}
              </tbody>
            </Table>
          ) : null}

          {shownStages.length > 0 ? (
            <Table aria-label="Stages before and after" className="min-w-[36rem]">
              <thead>
                <tr>
                  <TableHead>Stage</TableHead>
                  <TableHead className="text-right">Stones/h per slot, before</TableHead>
                  <TableHead className="text-right">After</TableHead>
                  <TableHead className="text-right">Parallel slots, before</TableHead>
                  <TableHead className="text-right">After</TableHead>
                </tr>
              </thead>
              <tbody>
                {shownStages.map((stage) => (
                  <TableRow key={stage.stageId}>
                    <TableCell className="font-medium">{stage.name}</TableCell>
                    <TableCell className="metric text-right">{formatDecimal(stage.stonesPerHourBefore, 4)}</TableCell>
                    <TableCell className="text-right">
                      <Changed before={stage.stonesPerHourBefore} after={stage.stonesPerHourAfter}>
                        {formatDecimal(stage.stonesPerHourAfter, 4)}
                      </Changed>
                    </TableCell>
                    <TableCell className="metric text-right">
                      {stage.maxSlotsBefore === null ? 'Unlimited' : formatInteger(stage.maxSlotsBefore)}
                    </TableCell>
                    <TableCell className="text-right">
                      <Changed before={String(stage.maxSlotsBefore)} after={String(stage.maxSlotsAfter)}>
                        {stage.maxSlotsAfter === null ? 'Unlimited' : formatInteger(stage.maxSlotsAfter)}
                      </Changed>
                    </TableCell>
                  </TableRow>
                ))}
              </tbody>
            </Table>
          ) : null}
        </>
      ) : null}
    </Card>
  )
}

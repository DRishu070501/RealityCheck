import { useQuery } from '@tanstack/react-query'
import { FlaskConical, GitBranch, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { DemoTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { RunStatusBadge } from '@/components/RunStatusBadge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { CreateScenarioDialog, type CreateMode } from '@/features/scenarios/CreateScenarioDialog'
import { api } from '@/lib/api'
import { formatInteger, formatShortDateTimeInZone } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'

function ScenarioList({ factory }: { factory: Factory }) {
  const [creating, setCreating] = useState<CreateMode | null>(null)
  const list = useQuery({
    queryKey: queryKeys.scenarios.list(factory.id),
    queryFn: ({ signal }) => api.scenarios.list(factory.id, signal),
  })

  const scenarios = list.data?.items ?? []
  const names = new Map(scenarios.map((scenario) => [scenario.id, scenario.name]))

  return (
    <>
      <PageHeader
        title="Scenario Lab"
        description="Each scenario starts from the factory as recorded at one moment and applies the changes you are considering. Nothing here alters live data or history."
        actions={
          <>
            <Button
              variant="outline"
              disabled={scenarios.length === 0}
              onClick={() => {
                setCreating({ kind: 'branch', parents: scenarios })
              }}
            >
              <GitBranch aria-hidden="true" />
              Branch
            </Button>
            <Button
              onClick={() => {
                setCreating({ kind: 'new' })
              }}
            >
              <Plus aria-hidden="true" />
              New scenario
            </Button>
          </>
        }
      />

      <Card aria-labelledby="scenarios-title" aria-busy={list.isFetching}>
        <CardHeader>
          <div className="min-w-0">
            <CardTitle id="scenarios-title">Scenarios</CardTitle>
            <CardDescription>
              Start times are factory local time ({factory.timeZone}). Open a scenario to edit its changes and run it.
            </CardDescription>
          </div>
          {factory.demo ? <DemoTag /> : null}
        </CardHeader>

        {list.isPending ? (
          <div role="status" aria-label="Loading scenarios" className="flex flex-col gap-2 p-5">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : list.isError ? (
          <div className="p-5">
            <ErrorState error={list.error} onRetry={() => void list.refetch()} retrying={list.isFetching} />
          </div>
        ) : scenarios.length === 0 ? (
          <div className="flex flex-col items-start gap-3 px-5 py-10">
            <FlaskConical aria-hidden="true" className="size-5 text-muted-foreground" />
            <p className="text-base font-medium text-foreground">No scenarios yet</p>
            <p className="max-w-xl text-sm text-muted-foreground">
              Create one to ask “what if”: choose a moment in the recorded history, then add changes such as more
              machines, fewer people, higher demand or an extra shift.
            </p>
            <Button
              onClick={() => {
                setCreating({ kind: 'new' })
              }}
            >
              <Plus aria-hidden="true" />
              New scenario
            </Button>
          </div>
        ) : (
          <Table aria-label="Scenarios" className="min-w-[48rem]">
            <thead>
              <tr>
                <TableHead>Scenario</TableHead>
                <TableHead>Starts from</TableHead>
                <TableHead className="text-right">Changes</TableHead>
                <TableHead>Latest run</TableHead>
                <TableHead>Branched from</TableHead>
              </tr>
            </thead>
            <tbody>
              {scenarios.map((scenario) => (
                <TableRow key={scenario.id}>
                  <TableCell>
                    <Link
                      to={`/scenarios/${scenario.id}`}
                      className="font-medium text-foreground underline-offset-4 hover:underline"
                    >
                      {scenario.name}
                    </Link>
                    {scenario.baseline ? (
                      <Badge variant="info" className="ml-2" title="No changes: the factory as recorded. Other scenarios are compared with it.">
                        Baseline
                      </Badge>
                    ) : null}
                  </TableCell>
                  <TableCell>
                    <time dateTime={scenario.forkAt} className="metric whitespace-nowrap">
                      {formatShortDateTimeInZone(scenario.forkAt, factory.timeZone)}
                    </time>
                  </TableCell>
                  <TableCell className="metric text-right">{formatInteger(scenario.changeCount)}</TableCell>
                  <TableCell>
                    <RunStatusBadge status={scenario.latestRun?.status} />
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {scenario.parentScenarioId === null ? '—' : (names.get(scenario.parentScenarioId) ?? 'A deleted scenario')}
                  </TableCell>
                </TableRow>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {creating ? (
        <CreateScenarioDialog
          factory={factory}
          mode={creating}
          onClose={() => {
            setCreating(null)
          }}
        />
      ) : null}
    </>
  )
}

/** Scenario Lab: the list of what-ifs, and creating or branching one. */
export function ScenariosPage() {
  return <FactoryGate>{(factory) => <ScenarioList factory={factory} />}</FactoryGate>
}

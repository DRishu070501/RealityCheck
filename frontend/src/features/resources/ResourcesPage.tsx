import { useQuery } from '@tanstack/react-query'
import { CalendarClock, Pencil } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { AvailabilityDialog } from '@/features/resources/AvailabilityDialog'
import { EditResourceDialog } from '@/features/resources/EditResourceDialog'
import { api } from '@/lib/api'
import { formatInteger, formatMoney, humanizeCode } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, Resource, ResourceKind } from '@/lib/schemas'
import { cn } from '@/lib/utils'

type KindFilter = 'ALL' | ResourceKind

const FILTERS: readonly { value: KindFilter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'MACHINE', label: 'Machines' },
  { value: 'EMPLOYEE', label: 'Employees' },
]

const GROUPS: readonly { kind: ResourceKind; title: string; description: string }[] = [
  { kind: 'MACHINE', title: 'Machines', description: 'Pools of interchangeable machines of one type.' },
  { kind: 'EMPLOYEE', title: 'Employees', description: 'Pools of employees who share one skill.' },
]

type Action = 'edit' | 'availability'
/** Search parameters that hold the open dialog, so it survives a reload and Back closes it. */
const ACTIONS: readonly Action[] = ['edit', 'availability']

function PoolTable({
  title,
  pools,
  currencyCode,
  onOpen,
}: {
  title: string
  pools: readonly Resource[]
  currencyCode: string
  onOpen: (action: Action, resource: Resource) => void
}) {
  return (
    <Table aria-label={title} className="min-w-[56rem] table-fixed">
      <colgroup>
        <col className="w-[23%]" />
        <col className="w-[10%]" />
        <col className="w-[16%]" />
        <col className="w-[9%]" />
        <col className="w-[23%]" />
        <col className="w-[19%]" />
      </colgroup>
      <thead>
        <tr>
          <TableHead>Pool</TableHead>
          <TableHead className="text-right">Capacity</TableHead>
          <TableHead className="text-right">Hourly cost per unit</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Required by</TableHead>
          <TableHead>
            <span className="sr-only">Actions</span>
          </TableHead>
        </tr>
      </thead>
      <tbody>
        {pools.map((pool) => (
          <TableRow key={pool.id}>
            <TableCell>
              <span className="block font-medium text-foreground">{pool.name}</span>
              <span className="block text-xs text-subtle-foreground">{humanizeCode(pool.category)}</span>
            </TableCell>
            <TableCell className="metric text-right whitespace-nowrap">{formatInteger(pool.capacity)} units</TableCell>
            <TableCell className="metric text-right whitespace-nowrap">
              {formatMoney(pool.hourlyCost, currencyCode)}
            </TableCell>
            <TableCell>
              <Badge variant={pool.status === 'ACTIVE' ? 'ok' : 'neutral'}>
                {pool.status === 'ACTIVE' ? 'Active' : 'Inactive'}
              </Badge>
            </TableCell>
            <TableCell className="text-muted-foreground">
              {pool.requiredByStages.length === 0 ? (
                <span className="text-subtle-foreground">No stage</span>
              ) : (
                <ul className="flex flex-col gap-0.5">
                  {pool.requiredByStages.map((stage) => (
                    <li key={stage.stageId}>
                      {stage.stageName}{' '}
                      <span className="metric text-subtle-foreground">
                        ({formatInteger(stage.unitsPerSlot)} per slot)
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </TableCell>
            <TableCell>
              <div className="flex justify-end gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Edit ${pool.name}`}
                  onClick={() => {
                    onOpen('edit', pool)
                  }}
                >
                  <Pencil aria-hidden="true" />
                  Edit
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Availability of ${pool.name}`}
                  onClick={() => {
                    onOpen('availability', pool)
                  }}
                >
                  <CalendarClock aria-hidden="true" />
                  Availability
                </Button>
              </div>
            </TableCell>
          </TableRow>
        ))}
      </tbody>
    </Table>
  )
}

function ResourceManager({ factory }: { factory: Factory }) {
  const [filter, setFilter] = useState<KindFilter>('ALL')
  const [searchParams, setSearchParams] = useSearchParams()
  const kind = filter === 'ALL' ? undefined : filter

  const resources = useQuery({
    queryKey: queryKeys.resources.list(factory.id, kind),
    queryFn: ({ signal }) => api.resources.list(factory.id, kind, signal),
  })

  const openAction = ACTIONS.find((action) => searchParams.has(action))
  const openId = openAction ? searchParams.get(openAction) : null
  const openResource = resources.data?.items.find((pool) => pool.id === openId)
  const open = (action: Action, resource: Resource) => {
    setSearchParams({ [action]: resource.id })
  }
  const close = () => {
    setSearchParams({}, { replace: true })
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div role="group" aria-label="Filter by kind" className="flex rounded-md border border-border-strong p-0.5">
          {FILTERS.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={filter === option.value}
              onClick={() => {
                setFilter(option.value)
              }}
              className={cn(
                'rounded-sm px-3 py-1 text-xs font-medium transition-colors',
                filter === option.value
                  ? 'bg-surface-hover text-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {factory.demo ? <DemoTag /> : null}
          <SourceTag />
        </div>
      </div>

      {resources.isPending ? (
        <div role="status" aria-label="Loading resources" className="flex flex-col gap-3">
          <Skeleton className="h-56 w-full" />
          <Skeleton className="h-56 w-full" />
        </div>
      ) : resources.isError ? (
        <ErrorState error={resources.error} onRetry={() => void resources.refetch()} retrying={resources.isFetching} />
      ) : resources.data.items.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 text-sm text-muted-foreground">
          <p className="text-base font-medium text-foreground">
            {filter === 'ALL' ? 'No resource pools are configured' : `No ${filter === 'MACHINE' ? 'machine' : 'employee'} pools are configured`}
          </p>
          <p className="mt-2 max-w-xl">
            Create pools with <code className="metric">POST /api/resources</code>, or reset the demo data in Settings.
          </p>
        </div>
      ) : (
        GROUPS.filter((group) => filter === 'ALL' || filter === group.kind).map((group) => {
          const pools = resources.data.items.filter((pool) => pool.kind === group.kind)
          if (pools.length === 0) return null
          const units = pools.reduce((total, pool) => total + pool.capacity, 0)
          const titleId = `${group.kind.toLowerCase()}-title`
          return (
            <Card key={group.kind} aria-labelledby={titleId}>
              <CardHeader>
                <div>
                  <CardTitle id={titleId}>{group.title}</CardTitle>
                  <CardDescription>{group.description}</CardDescription>
                </div>
                <p className="metric text-sm text-muted-foreground">
                  {formatInteger(pools.length)} {pools.length === 1 ? 'pool' : 'pools'}, {formatInteger(units)} units
                </p>
              </CardHeader>
              <PoolTable title={group.title} pools={pools} currencyCode={factory.currencyCode} onOpen={open} />
            </Card>
          )
        })
      )}

      <p className="text-xs text-subtle-foreground">
        Capacity and cost are configuration. Units in use and utilization arrive with production tracking and
        simulation, and are not shown until they can be read from recorded or simulated data.
      </p>

      {openAction === 'edit' && openResource ? (
        <EditResourceDialog
          key={openResource.id}
          resource={openResource}
          currencyCode={factory.currencyCode}
          onClose={close}
        />
      ) : null}
      {openAction === 'availability' && openResource ? (
        <AvailabilityDialog
          key={openResource.id}
          resource={openResource}
          timeZone={factory.timeZone}
          onClose={close}
        />
      ) : null}
    </div>
  )
}

export function ResourcesPage() {
  return (
    <>
      <PageHeader
        title="Resources"
        description="Machine and employee pools: how many units each has, what a unit costs per hour, and when capacity changes."
      />
      <FactoryGate>{(factory) => <ResourceManager factory={factory} />}</FactoryGate>
    </>
  )
}

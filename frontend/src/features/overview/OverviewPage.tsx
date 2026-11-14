import { useQuery } from '@tanstack/react-query'
import { ExternalLink, RotateCw } from 'lucide-react'
import type { ReactNode } from 'react'

import { ErrorState } from '@/components/ErrorState'
import { PageHeader } from '@/components/PageHeader'
import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'

const HEALTH_POLL_MS = 30_000

function StatusRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 px-5 py-3 sm:grid-cols-[14rem_1fr] sm:items-center sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm text-foreground">{children}</dd>
    </div>
  )
}

function RowSkeleton({ label }: { label: string }) {
  return (
    <StatusRow label={label}>
      <Skeleton className="h-5 w-40" />
    </StatusRow>
  )
}

/**
 * Milestone 1 overview: live status of the backend, database, migrations and AI layer.
 * Factory metrics replace this once Milestone 2 provides real factory data; nothing here is
 * sample data.
 */
export function OverviewPage() {
  const health = useQuery({
    queryKey: queryKeys.system.health,
    queryFn: ({ signal }) => api.system.health(signal),
    refetchInterval: HEALTH_POLL_MS,
  })
  const info = useQuery({
    queryKey: queryKeys.system.info,
    queryFn: ({ signal }) => api.system.info(signal),
  })

  const refreshing = health.isFetching || info.isFetching
  const refresh = () => {
    void health.refetch()
    void info.refetch()
  }

  return (
    <>
      <PageHeader
        title="Overview"
        description="Live status of the RealityCheck platform. Factory metrics appear here once the factory model is available."
        actions={
          <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing}>
            <RotateCw aria-hidden="true" className={refreshing ? 'animate-spin' : undefined} />
            Refresh
          </Button>
        }
      />

      <div className="flex flex-col gap-6">
        <Card aria-labelledby="system-status-title">
          <CardHeader>
            <div>
              <CardTitle id="system-status-title">System status</CardTitle>
              <CardDescription>Reported by the backend. Health is re-checked every 30 seconds.</CardDescription>
            </div>
          </CardHeader>

          <dl className="divide-y divide-border" aria-busy={health.isPending || info.isPending}>
            {health.isPending ? (
              <RowSkeleton label="Backend API" />
            ) : (
              <StatusRow label="Backend API">
                {health.isError ? (
                  <StatusBadge label="Unreachable" tone="error" />
                ) : (
                  <StatusBadge label={health.data.status} />
                )}
              </StatusRow>
            )}

            {info.isPending ? (
              <>
                <RowSkeleton label="Database" />
                <RowSkeleton label="Schema migrations" />
                <RowSkeleton label="AI layer" />
                <RowSkeleton label="Application version" />
                <RowSkeleton label="Server time" />
              </>
            ) : info.isError ? null : (
              <>
                <StatusRow label="Database">
                  <StatusBadge label={info.data.database.status} />
                  {info.data.database.product ? (
                    <span className="metric text-muted-foreground">
                      {info.data.database.product}
                      {info.data.database.version ? ` ${info.data.database.version}` : ''}
                    </span>
                  ) : null}
                </StatusRow>
                <StatusRow label="Schema migrations">
                  <span className="metric">
                    {info.data.migrations.appliedCount} applied
                    {info.data.migrations.currentVersion
                      ? `, current version ${info.data.migrations.currentVersion}`
                      : ''}
                  </span>
                </StatusRow>
                <StatusRow label="AI layer">
                  {info.data.ai.enabled ? (
                    <>
                      <StatusBadge label="Enabled" tone="ok" />
                      <span className="text-muted-foreground">Provider: {info.data.ai.provider}</span>
                    </>
                  ) : (
                    <>
                      <StatusBadge label="Not configured" tone="neutral" />
                      <span className="text-muted-foreground">The application is fully usable without it.</span>
                    </>
                  )}
                </StatusRow>
                <StatusRow label="Application version">
                  <span className="metric">
                    {info.data.application} {info.data.version}
                  </span>
                </StatusRow>
                <StatusRow label="Server time">
                  <time className="metric" dateTime={info.data.serverTime}>
                    {formatDateTime(info.data.serverTime)}
                  </time>
                </StatusRow>
              </>
            )}
          </dl>

          {info.isError ? (
            <CardContent className="border-t border-border">
              <ErrorState error={info.error} onRetry={refresh} retrying={refreshing} />
            </CardContent>
          ) : null}
        </Card>

        <Card aria-labelledby="next-title">
          <CardHeader>
            <div>
              <CardTitle id="next-title">What comes next</CardTitle>
              <CardDescription>Roadmap notes, not data.</CardDescription>
            </div>
            <Button asChild variant="link" size="sm" className="px-0">
              <a href="/swagger-ui.html" target="_blank" rel="noreferrer">
                API documentation
                <ExternalLink aria-hidden="true" />
              </a>
            </Button>
          </CardHeader>
          <CardContent>
            <ul className="list-disc space-y-1.5 pl-5 text-sm text-muted-foreground marker:text-subtle-foreground">
              <li>Milestone 2 adds the demonstration factory, its eight-stage workflow and resource pools.</li>
              <li>Milestone 3 adds production orders and the operational event timeline.</li>
              <li>Milestones 4 and 5 add the deterministic simulation engine and scenario branching.</li>
              <li>
                Order counts, throughput, utilization and bottleneck indicators will be shown on this page only when
                they can be read from recorded or simulated data.
              </li>
            </ul>
          </CardContent>
        </Card>
      </div>
    </>
  )
}

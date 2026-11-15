import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, RotateCcw, TriangleAlert } from 'lucide-react'
import { useState, type ReactNode } from 'react'

import { ErrorState } from '@/components/ErrorState'
import { PageHeader } from '@/components/PageHeader'
import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogBody,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { api, ApiError } from '@/lib/api'
import { formatDateTime } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 px-5 py-3 sm:grid-cols-[14rem_1fr] sm:items-center sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm text-foreground">{children}</dd>
    </div>
  )
}

function DemoReset() {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)

  const reset = useMutation({
    mutationFn: () => api.demo.reset(),
    onSuccess: async () => {
      setConfirming(false)
      // Every id may have changed and every edit is gone: nothing cached is still true.
      await queryClient.invalidateQueries()
    },
  })

  return (
    <CardContent className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <p className="max-w-xl text-sm text-muted-foreground">
          Erases <strong className="font-medium text-foreground">all data</strong> in this deployment, including
          anything you created or changed, and re-creates the fictional factory Surat Diamond Works exactly as first
          seeded.
        </p>
        <Button
          variant="outline"
          onClick={() => {
            reset.reset()
            setConfirming(true)
          }}
        >
          <RotateCcw aria-hidden="true" />
          Reset demo data
        </Button>
      </div>

      {reset.isSuccess ? (
        <p
          role="status"
          className="flex items-start gap-2 rounded-md border border-status-ok/30 bg-status-ok-surface px-3 py-2.5 text-sm text-foreground"
        >
          <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-ok" />
          <span>
            Demo data was reset at{' '}
            <time className="metric" dateTime={reset.data.resetAt}>
              {formatDateTime(reset.data.resetAt)}
            </time>
            .
          </span>
        </p>
      ) : null}

      <Dialog
        open={confirming}
        onOpenChange={(open) => {
          if (!reset.isPending) setConfirming(open)
        }}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>Erase all data and reset the demo?</DialogTitle>
            <DialogDescription>This cannot be undone.</DialogDescription>
          </DialogHeader>
          <DialogBody className="flex flex-col gap-3 text-sm text-muted-foreground">
            <p>
              Every factory, workflow, resource pool and availability window will be deleted, including your own
              changes. The demonstration factory is then created again from its fixed seed.
            </p>
            {reset.isError ? (
              <p
                role="alert"
                className="flex items-start gap-2 rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5 text-foreground"
              >
                <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
                <span>
                  {reset.error instanceof ApiError
                    ? (reset.error.detail ?? reset.error.title)
                    : 'The reset could not be completed.'}
                </span>
              </p>
            ) : null}
          </DialogBody>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline" disabled={reset.isPending}>
                Cancel
              </Button>
            </DialogClose>
            <Button
              className="bg-status-error text-background hover:bg-status-error/90"
              disabled={reset.isPending}
              onClick={() => {
                reset.mutate()
              }}
            >
              {reset.isPending ? 'Resetting' : 'Erase and reset'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </CardContent>
  )
}

export function SettingsPage() {
  const info = useQuery({
    queryKey: queryKeys.system.info,
    queryFn: ({ signal }) => api.system.info(signal),
  })

  return (
    <>
      <PageHeader title="Settings" description="How this deployment is configured, and the demonstration data reset." />

      {info.isPending ? (
        <div role="status" aria-label="Loading settings" className="flex flex-col gap-3">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-28 w-full" />
        </div>
      ) : info.isError ? (
        <ErrorState error={info.error} onRetry={() => void info.refetch()} retrying={info.isFetching} />
      ) : (
        <div className="flex flex-col gap-6">
          <Card aria-labelledby="deployment-title">
            <CardHeader>
              <div>
                <CardTitle id="deployment-title">Deployment</CardTitle>
                <CardDescription>
                  Set through environment variables when the backend starts; not editable here.
                </CardDescription>
              </div>
            </CardHeader>
            <dl className="divide-y divide-border">
              <Row label="Application version">
                <span className="metric">
                  {info.data.application} {info.data.version}
                </span>
              </Row>
              <Row label="Database">
                <StatusBadge label={info.data.database.status} />
                {info.data.database.product ? (
                  <span className="metric text-muted-foreground">
                    {info.data.database.product}
                    {info.data.database.version ? ` ${info.data.database.version}` : ''}
                  </span>
                ) : null}
              </Row>
              <Row label="Schema migrations">
                <span className="metric">
                  {info.data.migrations.appliedCount} applied
                  {info.data.migrations.currentVersion ? `, current version ${info.data.migrations.currentVersion}` : ''}
                </span>
              </Row>
              <Row label="AI layer">
                {info.data.ai.enabled ? (
                  <>
                    <StatusBadge label="Language model" tone="ok" />
                    <span className="text-muted-foreground">
                      {info.data.ai.provider}
                      {info.data.ai.model ? `, ${info.data.ai.model}` : ''}
                    </span>
                  </>
                ) : (
                  <>
                    <StatusBadge label="Built-in analyst" tone="neutral" />
                    <span className="text-muted-foreground">No language model configured</span>
                  </>
                )}
              </Row>
              <Row label="Demo data reset">
                {info.data.demo.resetEnabled ? (
                  <StatusBadge label="Enabled" tone="warn" />
                ) : (
                  <StatusBadge label="Disabled" tone="neutral" />
                )}
              </Row>
            </dl>
          </Card>

          <Card aria-labelledby="demo-title">
            <CardHeader>
              <div>
                <CardTitle id="demo-title">Demonstration data</CardTitle>
                <CardDescription>Surat Diamond Works is fictional and all of its figures are synthetic.</CardDescription>
              </div>
            </CardHeader>
            {info.data.demo.resetEnabled ? (
              <DemoReset />
            ) : (
              <CardContent className="text-sm text-muted-foreground">
                Resetting the demo data is disabled by configuration. To allow it, start the backend with{' '}
                <code className="metric text-foreground">REALITYCHECK_DEMO_RESET_ENABLED=true</code>. Leave it off for
                any deployment that holds data you want to keep.
              </CardContent>
            )}
          </Card>
        </div>
      )}
    </>
  )
}

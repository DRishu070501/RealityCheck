import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RotateCcw, TriangleAlert } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'

import { ErrorState } from '@/components/ErrorState'
import { PageHeader } from '@/components/PageHeader'
import { StatusBadge } from '@/components/StatusBadge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/form'
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
import { queryKeys } from '@/lib/queryKeys'
import { useTheme, type Theme } from '@/lib/theme'

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 px-5 py-3 sm:grid-cols-[14rem_1fr] sm:items-center sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm text-foreground">{children}</dd>
    </div>
  )
}

/** Typed to confirm the reset, so it cannot happen by a stray click. */
const RESET_CONFIRMATION = 'RESET'

function DemoReset() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [confirming, setConfirming] = useState(false)
  const [typed, setTyped] = useState('')
  const confirmId = useId()

  const reset = useMutation({
    mutationFn: () => api.demo.reset(),
    onSuccess: (result) => {
      setConfirming(false)
      // Every id may have changed and every edit is gone: nothing cached is still true.
      queryClient.clear()
      void navigate('/', { state: { demoResetAt: result.resetAt } })
    },
  })
  const confirmed = typed.trim() === RESET_CONFIRMATION

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
            setTyped('')
            setConfirming(true)
          }}
        >
          <RotateCcw aria-hidden="true" />
          Reset demo data
        </Button>
      </div>

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
              Every factory, workflow, resource pool, order, recorded event, scenario, simulation run and AI insight
              will be deleted, including your own changes. The demonstration factory, its history and its six
              scenarios are then created again from the fixed seed.
            </p>
            <div className="flex flex-col gap-1.5">
              <label htmlFor={confirmId} className="text-foreground">
                Type <strong className="metric">{RESET_CONFIRMATION}</strong> to confirm
              </label>
              <Input
                id={confirmId}
                value={typed}
                autoComplete="off"
                spellCheck={false}
                disabled={reset.isPending}
                onChange={(event) => {
                  setTyped(event.target.value)
                }}
              />
            </div>
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
              disabled={reset.isPending || !confirmed}
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

const THEMES: { value: Theme; label: string; hint: string }[] = [
  { value: 'professional', label: 'Professional', hint: 'The default: calm navy and blue.' },
  { value: 'halloween', label: 'Halloween', hint: 'Night purple, pumpkin and slime, for Kiroween. Same contrast, same data.' },
]

/** Chosen per browser; applies at once. Content and data tags are identical in both themes. */
function Appearance() {
  const [theme, setTheme] = useTheme()
  return (
    <Card aria-labelledby="appearance-title">
      <CardHeader>
        <div>
          <CardTitle id="appearance-title">Appearance</CardTitle>
          <CardDescription>Only colours change. Remembered in this browser.</CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <fieldset className="flex flex-col gap-2">
          <legend className="sr-only">Theme</legend>
          {THEMES.map((option) => (
            <label key={option.value} className="flex cursor-pointer items-start gap-2 text-sm">
              <input
                type="radio"
                name="theme"
                value={option.value}
                checked={theme === option.value}
                onChange={() => {
                  setTheme(option.value)
                }}
                className="mt-1 accent-primary"
              />
              <span>
                <span className="font-medium text-foreground">{option.label}</span>
                <span className="block text-xs text-muted-foreground">{option.hint}</span>
              </span>
            </label>
          ))}
        </fieldset>
      </CardContent>
    </Card>
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

      <div className="mb-6">
        <Appearance />
      </div>

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
              <Row label="Simulation algorithm">
                <span className="metric">{info.data.simulation?.algorithmVersion ?? 'Unknown'}</span>
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
                <CardDescription>
                  Surat Diamond Works is fictional: its patterns are calibrated on rounded measurements from one real
                  factory, and every name and other figure is synthetic.
                </CardDescription>
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

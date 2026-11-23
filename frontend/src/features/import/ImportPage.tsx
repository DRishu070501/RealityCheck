import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Download, FileUp, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router-dom'

import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { api, ApiError } from '@/lib/api'
import { formatDateTimeInZone, formatInteger } from '@/lib/format'
import {
  MOVEMENT_COLUMNS,
  ORDER_COLUMNS,
  readMovements,
  readOrders,
  templates,
  type MovementRow,
  type OrderRow,
  type Parsed,
} from '@/lib/importCsv'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory, ImportResult } from '@/lib/schemas'

/** Problems listed per file before the rest are summarised as a count. */
const SHOWN_PROBLEMS = 8

interface Loaded<T> {
  name: string
  parsed: Parsed<T>
}

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

function FilePicker<T>({
  label,
  hint,
  loaded,
  onLoad,
}: {
  label: string
  hint: string
  loaded: Loaded<T> | null
  onLoad: (name: string, text: string) => void
}) {
  const id = useId()
  const problems = loaded?.parsed.problems ?? []
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-sm font-medium text-foreground">
        {label}
      </label>
      <p className="text-xs text-muted-foreground">{hint}</p>
      <input
        id={id}
        type="file"
        accept=".csv,text/csv"
        className="text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-border file:bg-surface-raised file:px-3 file:py-1.5 file:text-sm file:text-foreground"
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file)
            void file.text().then((text) => {
              onLoad(file.name, text)
            })
        }}
      />
      {loaded ? (
        problems.length === 0 ? (
          <p className="flex items-center gap-1.5 text-sm text-foreground">
            <CircleCheck aria-hidden="true" className="size-4 text-status-ok" />
            {loaded.name}: {count(loaded.parsed.rows.length, 'row', 'rows')} read
          </p>
        ) : (
          <div role="alert" className="rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5 text-sm">
            <p className="font-medium text-foreground">
              {loaded.name}: {formatInteger(problems.length)} row{problems.length === 1 ? '' : 's'} to fix
            </p>
            <ul className="mt-1 list-disc pl-5 text-foreground">
              {problems.slice(0, SHOWN_PROBLEMS).map((problem) => (
                <li key={problem.row}>
                  Row {problem.row}: {problem.message}
                </li>
              ))}
            </ul>
            {problems.length > SHOWN_PROBLEMS ? (
              <p className="mt-1 text-muted-foreground">and {problems.length - SHOWN_PROBLEMS} more</p>
            ) : null}
          </div>
        )
      ) : null}
    </div>
  )
}

function count(value: number, one: string, many: string): string {
  return `${formatInteger(value)} ${value === 1 ? one : many}`
}

function Summary({ result, factory }: { result: ImportResult; factory: Factory }) {
  const span =
    result.firstEventAt && result.lastEventAt
      ? `, from ${formatDateTimeInZone(result.firstEventAt, factory.timeZone)} to ${formatDateTimeInZone(result.lastEventAt, factory.timeZone)}`
      : ''
  return (
    <>
      {count(result.orders, 'order', 'orders')}, {count(result.packets, 'packet', 'packets')},{' '}
      {count(result.movements, 'stage visit', 'stage visits')} and{' '}
      {count(result.dispatched, 'dispatch', 'dispatches')}
      {span}.
    </>
  )
}

function ImportScreen({ factory }: { factory: Factory }) {
  const queryClient = useQueryClient()
  const [orders, setOrders] = useState<Loaded<OrderRow> | null>(null)
  const [movements, setMovements] = useState<Loaded<MovementRow> | null>(null)

  const workflows = useQuery({
    queryKey: queryKeys.workflows.list(factory.id),
    queryFn: ({ signal }) => api.workflows.list(factory.id, signal),
  })
  const workflowId = workflows.data?.items.find((w) => w.status === 'ACTIVE')?.id
  const workflow = useQuery({
    queryKey: queryKeys.workflows.detail(workflowId ?? 'none'),
    queryFn: ({ signal }) => api.workflows.get(workflowId ?? '', signal),
    enabled: workflowId !== undefined,
  })
  const stages = workflow.data?.stages ?? []

  const send = (dryRun: boolean) =>
    api.imports.run({
      factoryId: factory.id,
      dryRun,
      orders: orders?.parsed.rows ?? [],
      movements: movements?.parsed.rows ?? [],
    })
  const check = useMutation({ mutationFn: () => send(true) })
  const save = useMutation({
    mutationFn: () => send(false),
    onSuccess: (result) => {
      if (result.saved) {
        // New orders and history change nearly every screen's figures.
        void queryClient.invalidateQueries()
      }
    },
  })

  const reset = () => {
    check.reset()
    save.reset()
  }
  const readable =
    orders !== null &&
    orders.parsed.problems.length === 0 &&
    orders.parsed.rows.length > 0 &&
    (movements === null || movements.parsed.problems.length === 0)
  const checked = check.data && check.data.error === null ? check.data : null
  const failure = save.data?.error ?? check.data?.error ?? null
  const requestError = save.error ?? check.error

  return (
    <div className="flex flex-col gap-6">
      <Card aria-labelledby="format-title">
        <CardHeader>
          <div>
            <CardTitle id="format-title">1. Prepare two CSV files</CardTitle>
            <CardDescription>
              Export them from your production system or a spreadsheet. Times without an offset are read in the
              factory&apos;s time zone ({factory.timeZone}).
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 text-sm">
          <dl className="grid gap-3 sm:grid-cols-[10rem_1fr]">
            <dt className="font-medium text-foreground">Orders</dt>
            <dd className="text-muted-foreground">
              One row per packet: <code className="metric text-foreground">{ORDER_COLUMNS.join(', ')}</code>. Required:
              order_number, stones, carats, and placed_at on an order&apos;s first row. Further packets of an order
              repeat its number and may leave the order columns blank.
            </dd>
            <dt className="font-medium text-foreground">Movements</dt>
            <dd className="text-muted-foreground">
              One row per stage a packet visited: <code className="metric text-foreground">{MOVEMENT_COLUMNS.join(', ')}</code>.
              Leave completed_at blank where the packet still is, and stones_out or carats_out blank for no loss.
            </dd>
            <dt className="font-medium text-foreground">Stages</dt>
            <dd className="text-muted-foreground">
              {stages.length > 0 ? (
                <>
                  Use the code or name of a stage of the active workflow:{' '}
                  <span className="metric text-foreground">{stages.map((s) => s.code).join(', ')}</span>.
                </>
              ) : (
                'The stages of the active workflow, by code or name.'
              )}
            </dd>
          </dl>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                download('orders-template.csv', templates(stages.map((s) => s.code)).orders)
              }}
            >
              <Download aria-hidden="true" />
              Orders template
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                download('movements-template.csv', templates(stages.map((s) => s.code)).movements)
              }}
            >
              <Download aria-hidden="true" />
              Movements template
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card aria-labelledby="files-title">
        <CardHeader>
          <div>
            <CardTitle id="files-title">2. Choose the files</CardTitle>
            <CardDescription>Every row is checked for format as soon as a file is chosen.</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="grid gap-6 md:grid-cols-2">
          <FilePicker
            label="Orders file"
            hint="Required."
            loaded={orders}
            onLoad={(name, text) => {
              reset()
              setOrders({ name, parsed: readOrders(text, factory.timeZone) })
            }}
          />
          <FilePicker
            label="Movements file"
            hint="Optional: without it the orders are queued at their first stage."
            loaded={movements}
            onLoad={(name, text) => {
              reset()
              setMovements({ name, parsed: readMovements(text, factory.timeZone) })
            }}
          />
        </CardContent>
      </Card>

      <Card aria-labelledby="run-title">
        <CardHeader>
          <div>
            <CardTitle id="run-title">3. Check, then import</CardTitle>
            <CardDescription>
              The check runs the whole import through the same rules as live recording and then undoes it. The
              import is all or nothing: if any row breaks a rule, nothing is saved.
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 text-sm">
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={!readable || check.isPending || save.isPending}
              onClick={() => {
                save.reset()
                check.mutate()
              }}
            >
              {check.isPending ? 'Checking' : 'Check'}
            </Button>
            <Button
              disabled={!checked || save.isPending || save.data?.saved === true}
              onClick={() => {
                save.mutate()
              }}
            >
              <FileUp aria-hidden="true" />
              {save.isPending ? 'Importing' : 'Import'}
            </Button>
          </div>

          {failure ? (
            <p
              role="alert"
              className="flex items-start gap-2 rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5 text-foreground"
            >
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
              <span>
                {failure.file === 'orders' ? (orders?.name ?? 'Orders file') : (movements?.name ?? 'Movements file')}
                {failure.row > 0 ? `, row ${String(failure.row)}` : ''}: {failure.message}. Nothing was saved.
              </span>
            </p>
          ) : requestError ? (
            <p role="alert" className="text-foreground">
              {requestError instanceof ApiError
                ? (requestError.detail ?? requestError.title)
                : 'The import could not be sent.'}
            </p>
          ) : save.data?.saved ? (
            <div role="status" className="rounded-md border border-status-ok/30 bg-status-ok-surface px-3 py-2.5">
              <p className="text-foreground">
                Imported <Summary result={save.data} factory={factory} />
              </p>
              <p className="mt-1 flex flex-wrap gap-x-4 text-muted-foreground">
                <Link to="/orders" className="text-primary underline-offset-4 hover:underline">
                  Production Orders
                </Link>
                <Link to="/insights" className="text-primary underline-offset-4 hover:underline">
                  Where Time Goes
                </Link>
                <Link to="/timeline" className="text-primary underline-offset-4 hover:underline">
                  Operational Timeline
                </Link>
              </p>
            </div>
          ) : checked ? (
            <p role="status" className="text-foreground">
              Ready to import <Summary result={checked} factory={factory} />
            </p>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}

export function ImportPage() {
  return (
    <>
      <PageHeader
        title="Import Data"
        description="Bring in your own orders and packet movements from CSV files. They become recorded history, the same as events recorded live."
      />
      <FactoryGate>{(factory) => <ImportScreen factory={factory} />}</FactoryGate>
    </>
  )
}

import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'

import { DemoTag, SourceTag } from '@/components/DataTags'
import { ErrorState } from '@/components/ErrorState'
import { FactoryGate } from '@/components/FactoryGate'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { LateBadge, OrderStatusBadge, PriorityIndicator } from '@/components/production'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableCell, TableHead, TableRow } from '@/components/ui/table'
import { api, type OrderListParams } from '@/lib/api'
import { formatCarats, formatDateInZone, formatInteger, formatStones, formatTimeInZone } from '@/lib/format'
import { ORDER_STATUS_LABELS } from '@/lib/production'
import { queryKeys } from '@/lib/queryKeys'
import { orderStatusSchema, type Factory, type Order, type OrderStatus } from '@/lib/schemas'

const PAGE_SIZE = 25

type StatusFilter = 'ALL' | OrderStatus

const STATUS_FILTERS: readonly { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  ...orderStatusSchema.options.map((status) => ({ value: status, label: ORDER_STATUS_LABELS[status] })),
]

function When({ iso, timeZone }: { iso: string | null; timeZone: string }) {
  if (iso === null) return <span className="text-subtle-foreground">—</span>
  return (
    <time dateTime={iso} className="metric whitespace-nowrap">
      <span className="block">{formatDateInZone(iso, timeZone)}</span>{' '}
      <span className="block text-muted-foreground">{formatTimeInZone(iso, timeZone)}</span>
    </time>
  )
}

function OrderRow({ order, timeZone }: { order: Order; timeZone: string }) {
  const navigate = useNavigate()
  const path = `/orders/${order.id}`
  return (
    <TableRow
      className="cursor-pointer hover:bg-surface-hover"
      onClick={() => {
        void navigate(path)
      }}
    >
      <TableCell>
        <Link
          to={path}
          className="metric font-medium whitespace-nowrap text-foreground underline-offset-4 hover:underline"
          onClick={(event) => {
            event.stopPropagation()
          }}
        >
          {order.orderNumber}
        </Link>
        <span className="block text-xs whitespace-nowrap text-subtle-foreground">
          {order.customerRef ?? 'No customer reference'}
        </span>
      </TableCell>
      <TableCell>
        <PriorityIndicator priority={order.priority} />
      </TableCell>
      <TableCell>
        <span className="flex flex-col items-start gap-1.5">
          <OrderStatusBadge status={order.status} />
          {order.delayed ? <LateBadge /> : null}
        </span>
      </TableCell>
      <TableCell>
        <When iso={order.placedAt} timeZone={timeZone} />
      </TableCell>
      <TableCell>
        <When iso={order.dueAt} timeZone={timeZone} />
      </TableCell>
      <TableCell>
        <When iso={order.completedAt} timeZone={timeZone} />
      </TableCell>
      <TableCell className="metric text-right whitespace-nowrap">
        {formatInteger(order.batchesCompleted)} of {formatInteger(order.batchCount)}
      </TableCell>
      <TableCell className="metric text-right whitespace-nowrap">
        <span className="block">{formatStones(String(order.totalStones))}</span>{' '}
        <span className="block text-muted-foreground">{formatCarats(order.totalRoughCarats)}</span>
      </TableCell>
    </TableRow>
  )
}

function OrderList({ factory }: { factory: Factory }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const statusParam = orderStatusSchema.safeParse(searchParams.get('status'))
  const status: StatusFilter = statusParam.success ? statusParam.data : 'ALL'
  const lateOnly = searchParams.get('late') === '1'
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0)

  const params: OrderListParams = {
    ...(status === 'ALL' ? {} : { status }),
    ...(lateOnly ? { delayed: true } : {}),
    page,
    size: PAGE_SIZE,
  }
  const orders = useQuery({
    queryKey: queryKeys.orders.list(factory.id, params),
    queryFn: ({ signal }) => api.orders.list(factory.id, params, signal),
    placeholderData: keepPreviousData,
  })

  /** Changing a filter returns to the first page; `undefined` removes a parameter. */
  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    next.delete('page')
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined) next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next, { replace: true })
  }

  const filtered = status !== 'ALL' || lateOnly

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Segmented
            label="Filter by status"
            value={status}
            options={STATUS_FILTERS}
            onChange={(value) => {
              update({ status: value === 'ALL' ? undefined : value })
            }}
          />
          <label className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
            <input
              type="checkbox"
              className="size-4 accent-primary"
              checked={lateOnly}
              onChange={(event) => {
                update({ late: event.target.checked ? '1' : undefined })
              }}
            />
            Late only
          </label>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {factory.demo ? <DemoTag /> : null}
          <SourceTag source="Actual" />
        </div>
      </div>

      {orders.isPending ? (
        <div role="status" aria-label="Loading orders">
          <Skeleton className="h-96 w-full" />
        </div>
      ) : orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => void orders.refetch()} retrying={orders.isFetching} />
      ) : orders.data.totalItems === 0 ? (
        <div className="rounded-lg border border-dashed border-border-strong bg-surface px-6 py-10 text-sm text-muted-foreground">
          <p className="text-base font-medium text-foreground">
            {filtered ? 'No orders match these filters' : 'No production orders have been placed'}
          </p>
          <p className="mt-2 max-w-xl">
            {filtered ? (
              'Change the status filter or clear "Late only" to see other orders.'
            ) : (
              <>
                Place one with <code className="metric">POST /api/orders</code>, or reset the demo data in Settings.
              </>
            )}
          </p>
        </div>
      ) : (
        <Card aria-labelledby="orders-title" aria-busy={orders.isFetching}>
          <CardHeader>
            <div>
              <CardTitle id="orders-title">Orders</CardTitle>
              <CardDescription>
                Earliest due first. Times are factory local time ({factory.timeZone}). Select an order for its batches
                and history.
              </CardDescription>
            </div>
            <p className="metric text-sm text-muted-foreground">
              {formatInteger(orders.data.totalItems)} {orders.data.totalItems === 1 ? 'order' : 'orders'}
            </p>
          </CardHeader>
          <Table aria-label="Production orders" className="min-w-[58rem]">
            <thead>
              <tr>
                <TableHead>Order</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Placed</TableHead>
                <TableHead>Due</TableHead>
                <TableHead>Completed</TableHead>
                <TableHead className="text-right">Batches finished</TableHead>
                <TableHead className="text-right">Ordered</TableHead>
              </tr>
            </thead>
            <tbody>
              {orders.data.items.map((order) => (
                <OrderRow key={order.id} order={order} timeZone={factory.timeZone} />
              ))}
            </tbody>
          </Table>
          <Pagination
            page={orders.data.page}
            size={orders.data.size}
            totalItems={orders.data.totalItems}
            totalPages={orders.data.totalPages}
            noun="orders"
            onPage={(next) => {
              const params = new URLSearchParams(searchParams)
              if (next === 0) params.delete('page')
              else params.set('page', String(next))
              setSearchParams(params)
            }}
          />
        </Card>
      )}
    </div>
  )
}

export function OrdersPage() {
  return (
    <>
      <PageHeader
        title="Production Orders"
        description="Every order placed with the factory: how urgent it is, when it is due, how far its batches have got, and whether it is late."
      />
      <FactoryGate>{(factory) => <OrderList factory={factory} />}</FactoryGate>
    </>
  )
}

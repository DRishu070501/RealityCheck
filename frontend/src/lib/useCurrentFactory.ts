import { useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import { queryKeys } from '@/lib/queryKeys'
import type { Factory } from '@/lib/schemas'

export type CurrentFactory =
  | { status: 'loading' }
  | { status: 'error'; error: unknown; retry: () => void; retrying: boolean }
  /** The backend is reachable and holds no factory. */
  | { status: 'empty' }
  | { status: 'ready'; factory: Factory }

/**
 * The factory the application is showing. The MVP models a single factory, so this is the first
 * one the backend lists; a factory switcher would replace this hook without touching the pages.
 */
export function useCurrentFactory(): CurrentFactory {
  const list = useQuery({
    queryKey: queryKeys.factories.list,
    queryFn: ({ signal }) => api.factories.list(signal),
  })
  const firstId = list.data?.items[0]?.id
  const detail = useQuery({
    queryKey: queryKeys.factories.detail(firstId ?? 'none'),
    queryFn: ({ signal }) => api.factories.get(firstId ?? '', signal),
    enabled: firstId !== undefined,
  })

  if (list.isError) {
    return { status: 'error', error: list.error, retry: () => void list.refetch(), retrying: list.isFetching }
  }
  if (list.isPending) return { status: 'loading' }
  if (firstId === undefined) return { status: 'empty' }
  if (detail.isError) {
    return { status: 'error', error: detail.error, retry: () => void detail.refetch(), retrying: detail.isFetching }
  }
  if (detail.isPending) return { status: 'loading' }
  return { status: 'ready', factory: detail.data }
}

import type { ResourceKind } from '@/lib/schemas'

/** Central TanStack Query keys, so invalidation never depends on string literals in components. */
export const queryKeys = {
  system: {
    info: ['system', 'info'] as const,
    health: ['system', 'health'] as const,
  },
  factories: {
    all: ['factories'] as const,
    list: ['factories', 'list'] as const,
    detail: (id: string) => ['factories', 'detail', id] as const,
    /** `at` is an ISO instant, or 'now' for the present. */
    state: (id: string, at: string) => ['factories', 'state', id, at] as const,
    overview: (id: string) => ['factories', 'overview', id] as const,
  },
  orders: {
    all: ['orders'] as const,
    list: (factoryId: string, params: object) => ['orders', 'list', factoryId, params] as const,
    detail: (id: string) => ['orders', 'detail', id] as const,
  },
  timeline: {
    all: ['timeline'] as const,
    list: (factoryId: string, params: object) => ['timeline', 'list', factoryId, params] as const,
    summary: (factoryId: string, bucket: string) => ['timeline', 'summary', factoryId, bucket] as const,
  },
  workflows: {
    all: ['workflows'] as const,
    list: (factoryId: string) => ['workflows', 'list', factoryId] as const,
    detail: (id: string) => ['workflows', 'detail', id] as const,
  },
  resources: {
    all: ['resources'] as const,
    list: (factoryId: string, kind?: ResourceKind) => ['resources', 'list', factoryId, kind ?? 'ALL'] as const,
    availability: (id: string) => ['resources', 'availability', id] as const,
  },
} as const

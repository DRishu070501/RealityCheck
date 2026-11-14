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

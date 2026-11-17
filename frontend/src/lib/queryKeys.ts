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
    insights: (id: string) => ['factories', 'insights', id] as const,
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
  scenarios: {
    all: ['scenarios'] as const,
    list: (factoryId: string) => ['scenarios', 'list', factoryId] as const,
    detail: (id: string) => ['scenarios', 'detail', id] as const,
    validation: (id: string, version: number) => ['scenarios', 'validation', id, version] as const,
    runs: (id: string) => ['scenarios', 'runs', id] as const,
    comparisons: (id: string) => ['scenarios', 'comparisons', id] as const,
  },
  simulations: {
    all: ['simulations'] as const,
    run: (runId: string) => ['simulations', 'run', runId] as const,
    results: (runId: string) => ['simulations', 'results', runId] as const,
    stages: (runId: string) => ['simulations', 'stages', runId] as const,
    events: (runId: string) => ['simulations', 'events', runId] as const,
    /** Comparisons are computed from immutable runs, so the inputs identify the answer. */
    compare: (baselineRunId: string, alternativeRunId: string, allowDifferent: boolean) =>
      ['simulations', 'compare', baselineRunId, alternativeRunId, allowDifferent] as const,
  },
  ai: {
    all: ['ai'] as const,
    history: (runId: string) => ['ai', 'history', runId] as const,
  },
} as const

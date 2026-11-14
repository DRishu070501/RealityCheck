/** Central TanStack Query keys, so invalidation never depends on string literals in components. */
export const queryKeys = {
  system: {
    info: ['system', 'info'] as const,
    health: ['system', 'health'] as const,
  },
} as const

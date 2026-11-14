import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'

import { routes } from '@/app/routes'
import { ApiError } from '@/lib/api'

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
        // Retrying a 4xx or a malformed response cannot succeed; retry only transient failures.
        retry: (failureCount, error) => {
          if (error instanceof ApiError) {
            if (error.kind === 'schema') return false
            if (error.status !== undefined && error.status < 500) return false
          }
          return failureCount < 1
        },
      },
    },
  })
}

const router = createBrowserRouter(routes)

export function App() {
  const [queryClient] = useState(createQueryClient)
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}

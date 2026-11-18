import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'
import type { RouteObject } from 'react-router-dom'

import { AppShell } from '@/app/AppShell'
import { NAV_ITEMS } from '@/app/nav'
import { NotFoundPage } from '@/app/NotFoundPage'
import { RouteErrorPage } from '@/app/RouteErrorPage'
import { ComingSoon } from '@/components/ComingSoon'
import { RouteFallback } from '@/components/RouteFallback'

/**
 * Each screen is its own chunk, fetched when first visited, so that the shell loads quickly and
 * the charting library is downloaded only by the screens that draw charts.
 */
function screen<T extends Record<string, ComponentType>>(load: () => Promise<T>, name: keyof T): ReactNode {
  const Screen = lazy(async () => ({ default: (await load())[name] as ComponentType }))
  return (
    <Suspense fallback={<RouteFallback />}>
      <Screen />
    </Suspense>
  )
}

/**
 * Every navigation entry that still carries a `planned` note gets a placeholder route. When a
 * feature is built, add its real route above and drop `planned` from its entry in nav.ts.
 */
const placeholderRoutes: RouteObject[] = NAV_ITEMS.flatMap((item) =>
  item.planned
    ? [
        {
          path: item.path,
          element: (
            <ComingSoon title={item.label} summary={item.planned.summary} milestone={item.planned.milestone} />
          ),
        },
      ]
    : [],
)

const screenRoutes: RouteObject[] = [
  { index: true, element: screen(() => import('@/features/overview/OverviewPage'), 'OverviewPage') },
  { path: '/factory', element: screen(() => import('@/features/factory/FactoryPage'), 'FactoryPage') },
  { path: '/workflow', element: screen(() => import('@/features/workflow/WorkflowPage'), 'WorkflowPage') },
  { path: '/resources', element: screen(() => import('@/features/resources/ResourcesPage'), 'ResourcesPage') },
  { path: '/orders', element: screen(() => import('@/features/orders/OrdersPage'), 'OrdersPage') },
  { path: '/orders/:id', element: screen(() => import('@/features/orders/OrderDetailPage'), 'OrderDetailPage') },
  { path: '/timeline', element: screen(() => import('@/features/timeline/TimelinePage'), 'TimelinePage') },
  { path: '/insights', element: screen(() => import('@/features/insights/InsightsPage'), 'InsightsPage') },
  { path: '/scenarios', element: screen(() => import('@/features/scenarios/ScenariosPage'), 'ScenariosPage') },
  {
    path: '/scenarios/:id',
    element: screen(() => import('@/features/scenarios/ScenarioDetailPage'), 'ScenarioDetailPage'),
  },
  { path: '/results', element: screen(() => import('@/features/results/ResultsPage'), 'ResultsPage') },
  { path: '/analyst', element: screen(() => import('@/features/analyst/AnalystPage'), 'AnalystPage') },
  { path: '/settings', element: screen(() => import('@/features/settings/SettingsPage'), 'SettingsPage') },
  ...placeholderRoutes,
  { path: '*', element: <NotFoundPage /> },
]

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    // A pathless layout route: a screen that throws is replaced by RouteErrorPage inside the shell,
    // so the navigation stays usable.
    children: [{ errorElement: <RouteErrorPage />, children: screenRoutes }],
  },
]

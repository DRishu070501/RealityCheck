import type { RouteObject } from 'react-router-dom'

import { AppShell } from '@/app/AppShell'
import { NAV_ITEMS } from '@/app/nav'
import { NotFoundPage } from '@/app/NotFoundPage'
import { ComingSoon } from '@/components/ComingSoon'
import { FactoryPage } from '@/features/factory/FactoryPage'
import { OverviewPage } from '@/features/overview/OverviewPage'
import { ResourcesPage } from '@/features/resources/ResourcesPage'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { WorkflowPage } from '@/features/workflow/WorkflowPage'

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

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: '/factory', element: <FactoryPage /> },
      { path: '/workflow', element: <WorkflowPage /> },
      { path: '/resources', element: <ResourcesPage /> },
      { path: '/settings', element: <SettingsPage /> },
      ...placeholderRoutes,
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

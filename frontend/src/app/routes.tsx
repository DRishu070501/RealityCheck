import type { RouteObject } from 'react-router-dom'

import { AppShell } from '@/app/AppShell'
import { NAV_ITEMS } from '@/app/nav'
import { NotFoundPage } from '@/app/NotFoundPage'
import { ComingSoon } from '@/components/ComingSoon'
import { OverviewPage } from '@/features/overview/OverviewPage'

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
    children: [{ index: true, element: <OverviewPage /> }, ...placeholderRoutes, { path: '*', element: <NotFoundPage /> }],
  },
]

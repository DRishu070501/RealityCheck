import {
  Activity,
  Boxes,
  Bot,
  ChartColumn,
  Factory,
  FlaskConical,
  Gauge,
  History,
  Hourglass,
  LayoutDashboard,
  Settings,
  Upload,
  Workflow,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  /** Route path, also the key used by the router. */
  path: string
  label: string
  icon: LucideIcon
  /**
   * Present while the screen is a placeholder: what it will do and which roadmap milestone
   * delivers it. Remove when the real feature route replaces the placeholder.
   */
  planned?: { milestone: string; summary: string }
}

/** Primary navigation, in the order the product specification defines. */
export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/', label: 'Overview', icon: LayoutDashboard },
  { path: '/factory', label: 'Factory', icon: Factory },
  { path: '/workflow', label: 'Workflow', icon: Workflow },
  { path: '/resources', label: 'Resources', icon: Boxes },
  { path: '/orders', label: 'Production Orders', icon: Activity },
  { path: '/import', label: 'Import Data', icon: Upload },
  { path: '/timeline', label: 'Operational Timeline', icon: History },
  { path: '/insights', label: 'Where Time Goes', icon: Hourglass },
  { path: '/scenarios', label: 'Scenario Lab', icon: FlaskConical },
  { path: '/results', label: 'Simulation Results', icon: ChartColumn },
  { path: '/accuracy', label: 'Accuracy', icon: Gauge },
  { path: '/analyst', label: 'AI Analyst', icon: Bot },
  { path: '/settings', label: 'Settings', icon: Settings },
]

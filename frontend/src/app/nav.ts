import {
  Activity,
  Boxes,
  Bot,
  ChartColumn,
  Factory,
  FlaskConical,
  History,
  LayoutDashboard,
  Settings,
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
  { path: '/timeline', label: 'Operational Timeline', icon: History },
  {
    path: '/scenarios',
    label: 'Scenario Lab',
    icon: FlaskConical,
    planned: {
      milestone: 'Milestones 5 and 6',
      summary:
        'Branch from a historical point in time, change capacity, staffing, demand, rates or schedules, and run a simulation.',
    },
  },
  {
    path: '/results',
    label: 'Simulation Results',
    icon: ChartColumn,
    planned: {
      milestone: 'Milestones 5 and 6',
      summary:
        'Baseline versus alternative: completion time, cost, throughput, utilization, waiting time and bottleneck evidence.',
    },
  },
  {
    path: '/analyst',
    label: 'AI Analyst',
    icon: Bot,
    planned: {
      milestone: 'Milestone 7',
      summary:
        'Explanations and recommendations grounded in a selected simulation run, each with the metrics that support it.',
    },
  },
  { path: '/settings', label: 'Settings', icon: Settings },
]

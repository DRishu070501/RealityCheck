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
  {
    path: '/factory',
    label: 'Factory',
    icon: Factory,
    planned: {
      milestone: 'Milestone 2',
      summary: 'Factory profile, time zone, currency and the working calendar of shifts and breaks.',
    },
  },
  {
    path: '/workflow',
    label: 'Workflow',
    icon: Workflow,
    planned: {
      milestone: 'Milestone 2',
      summary:
        'The eight production stages from Rough Intake to Dispatch, with processing rates, setup times, yields and resource requirements.',
    },
  },
  {
    path: '/resources',
    label: 'Resources',
    icon: Boxes,
    planned: {
      milestone: 'Milestone 2',
      summary: 'Machine and employee pools with capacity, hourly cost, availability and downtime.',
    },
  },
  {
    path: '/orders',
    label: 'Production Orders',
    icon: Activity,
    planned: {
      milestone: 'Milestone 3',
      summary: 'Production orders and their batches, with stone counts, carat weights, due dates and current stage.',
    },
  },
  {
    path: '/timeline',
    label: 'Operational Timeline',
    icon: History,
    planned: {
      milestone: 'Milestone 3',
      summary:
        'The append-only history of operational events, filterable by stage and order, and the starting point for branching a scenario.',
    },
  },
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
  {
    path: '/settings',
    label: 'Settings',
    icon: Settings,
    planned: {
      milestone: 'Milestone 6',
      summary: 'Application preferences and the demo-data reset.',
    },
  },
]

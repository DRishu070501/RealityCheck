import { Gem } from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { NAV_ITEMS } from '@/app/nav'
import { cn } from '@/lib/utils'

interface SidebarProps {
  /** Called after a link is chosen, so the mobile drawer can close. */
  onNavigate?: () => void
  className?: string
  /** Element id, set on the mobile drawer so its toggle can reference it. */
  id?: string
}

export function Sidebar({ onNavigate, className, id }: SidebarProps) {
  return (
    <aside
      id={id}
      className={cn('flex h-full w-62 flex-col border-r border-border bg-surface', className)}
    >
      <div className="flex h-14 shrink-0 items-center gap-2.5 border-b border-border px-4">
        <span className="flex size-7 items-center justify-center rounded-md border border-border-strong bg-surface-raised">
          <Gem aria-hidden="true" className="size-4 text-primary" />
        </span>
        <span className="text-sm font-semibold tracking-tight text-foreground">RealityCheck</span>
      </div>

      <nav aria-label="Primary" className="flex-1 overflow-y-auto px-2 py-3">
        <ul className="flex flex-col gap-0.5">
          {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
            <li key={path}>
              <NavLink
                to={path}
                end={path === '/'}
                onClick={onNavigate}
                className={({ isActive }) =>
                  cn(
                    'group relative flex h-9 items-center gap-3 rounded-md px-3 text-sm transition-colors',
                    isActive
                      ? 'bg-surface-hover font-medium text-foreground'
                      : 'text-muted-foreground hover:bg-surface-raised hover:text-foreground',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <span
                      aria-hidden="true"
                      className={cn(
                        'absolute top-2 bottom-2 left-0 w-0.5 rounded-full bg-primary',
                        isActive ? 'opacity-100' : 'opacity-0',
                      )}
                    />
                    <Icon
                      aria-hidden="true"
                      className={cn('size-4 shrink-0', isActive ? 'text-primary' : 'text-subtle-foreground')}
                    />
                    <span className="truncate">{label}</span>
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <p className="shrink-0 border-t border-border px-4 py-3 text-xs text-subtle-foreground">
        Simulated values are estimates, not guarantees.
      </p>
    </aside>
  )
}

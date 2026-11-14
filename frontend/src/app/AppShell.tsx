import { Menu, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'

import { Sidebar } from '@/app/Sidebar'
import { Button } from '@/components/ui/button'

/**
 * Application frame: a persistent sidebar on wide screens, an off-canvas drawer below the `lg`
 * breakpoint, a header, and the routed page.
 */
export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const { pathname } = useLocation()

  useEffect(() => {
    if (!drawerOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [drawerOpen])

  return (
    <div className="flex h-dvh overflow-hidden bg-background">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-surface-raised focus:px-3 focus:py-2 focus:text-sm focus:text-foreground"
      >
        Skip to content
      </a>

      {/* Wide screens: always visible. */}
      <Sidebar className="hidden lg:flex" />

      {/* Narrow screens: drawer, rendered only while open so its links are not in the tab order. */}
      {drawerOpen ? (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            aria-hidden="true"
            className="absolute inset-0 bg-background/80"
            onClick={() => {
              setDrawerOpen(false)
            }}
          />
          <div className="relative flex h-full w-fit">
            <Sidebar
              id="app-sidebar"
              onNavigate={() => {
                setDrawerOpen(false)
              }}
            />
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close navigation"
              className="mt-2.5 ml-1"
              onClick={() => {
                setDrawerOpen(false)
              }}
            >
              <X aria-hidden="true" />
            </Button>
          </div>
        </div>
      ) : null}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-surface px-4 lg:px-6">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            aria-controls="app-sidebar"
            onClick={() => {
              setDrawerOpen(true)
            }}
          >
            <Menu aria-hidden="true" />
          </Button>
          <span className="text-sm font-semibold tracking-tight text-foreground lg:hidden">RealityCheck</span>
          <p className="hidden truncate text-sm text-muted-foreground sm:block">
            See the future before you change the present.
          </p>
        </header>

        {/*
          key resets scroll position and focus context per route.
          `relative` makes this the containing block for absolutely positioned descendants such as
          `sr-only` text and tables. Without it they are placed against the page instead, escape this
          scroll area's clipping, and make the whole document scroll.
        */}
        <main
          id="main-content"
          key={pathname}
          tabIndex={-1}
          className="relative flex-1 overflow-y-auto outline-none"
        >
          <div className="mx-auto w-full max-w-6xl px-4 py-6 lg:px-8 lg:py-8">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

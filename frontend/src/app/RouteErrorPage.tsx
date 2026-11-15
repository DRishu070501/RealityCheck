import { RotateCw } from 'lucide-react'
import { Link, useRouteError } from 'react-router-dom'

import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'

/** A chunk built before the latest deployment is no longer on the server; reloading fetches the new one. */
function isStaleChunk(error: unknown): boolean {
  return error instanceof Error && /dynamically imported module|Importing a module script failed|Loading chunk/i.test(error.message)
}

/**
 * Shown inside the shell when a screen fails to render, instead of a blank page. Technical details
 * stay in the browser console; the user gets what to do next.
 */
export function RouteErrorPage() {
  const error = useRouteError()
  console.error(error)
  const stale = isStaleChunk(error)
  return (
    <>
      <PageHeader
        title={stale ? 'A newer version is available' : 'This screen could not be shown'}
        description={
          stale
            ? 'The application was updated while this page was open. Reload to continue.'
            : 'Something went wrong while displaying it. Your data is unaffected. Reload the page, or go back to Overview.'
        }
      />
      <div role="alert" className="flex flex-wrap gap-3">
        <Button
          onClick={() => {
            window.location.reload()
          }}
        >
          <RotateCw aria-hidden="true" />
          Reload
        </Button>
        <Button asChild variant="outline">
          <Link to="/">Back to Overview</Link>
        </Button>
      </div>
    </>
  )
}

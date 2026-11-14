import { useLocation } from 'react-router-dom'

/** Renders the current path and query so a test can assert what a page put in the address bar. */
export function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>
}

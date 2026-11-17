import { useCallback, useState } from 'react'

/**
 * The colour themes. Professional is the default; Halloween is the second token set in index.css
 * (`:root[data-theme="halloween"]`), with the same contrast guarantees. Only colours change: content,
 * labels and data tags are identical in both.
 */
export type Theme = 'professional' | 'halloween'

const STORAGE_KEY = 'realitycheck.theme'

/** The theme this browser chose before, or the default. Storage may be blocked; then the default. */
export function readTheme(): Theme {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'halloween' ? 'halloween' : 'professional'
  } catch {
    return 'professional'
  }
}

export function applyTheme(theme: Theme): void {
  if (theme === 'halloween') {
    document.documentElement.dataset.theme = 'halloween'
  } else {
    delete document.documentElement.dataset.theme
  }
}

/** The current theme and a setter that applies it at once and remembers it in this browser. */
export function useTheme(): [Theme, (theme: Theme) => void] {
  const [theme, setThemeState] = useState<Theme>(readTheme)
  const setTheme = useCallback((next: Theme) => {
    applyTheme(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // Not remembered; it still applies until the page is reloaded.
    }
    setThemeState(next)
  }, [])
  return [theme, setTheme]
}

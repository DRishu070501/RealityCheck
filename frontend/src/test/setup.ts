import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// jsdom has no layout, so it has no ResizeObserver. The charting library asks for one to size
// itself; with this stand-in it simply never draws, and tests read the data from the accessible
// alternative each chart provides.
class ResizeObserverStub {
  observe(): void {
    /* no layout to observe */
  }
  unobserve(): void {
    /* no layout to observe */
  }
  disconnect(): void {
    /* no layout to observe */
  }
}
Object.assign(globalThis, { ResizeObserver: ResizeObserverStub })

// Screens are loaded on demand; the first import of a large chunk can take over the default second
// when many test files run at once.
configure({ asyncUtilTimeout: 5000 })

afterEach(() => {
  cleanup()
})

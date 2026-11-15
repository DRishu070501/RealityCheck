import { useLayoutEffect, useState, type RefObject } from 'react'

/**
 * Whether an element currently scrolls (its content is wider or taller than its box). A scrolling
 * box must be reachable by keyboard so that it can be scrolled without a mouse, but a box that fits
 * should not add a tab stop; callers set `tabIndex` from this. Pass `remountKey` when the element is
 * re-created under the same ref (a `key` that changes).
 */
export function useScrolls(ref: RefObject<HTMLElement | null>, axis: 'x' | 'y', remountKey?: unknown): boolean {
  const [scrolls, setScrolls] = useState(false)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const measure = () => {
      setScrolls(
        axis === 'x' ? element.scrollWidth > element.clientWidth + 1 : element.scrollHeight > element.clientHeight + 1,
      )
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    // Content changes size as data arrives; observe the children as well as the box.
    for (const child of Array.from(element.children)) observer.observe(child)
    const mutations = typeof MutationObserver === 'undefined' ? null : new MutationObserver(measure)
    mutations?.observe(element, { childList: true, subtree: true })
    return () => {
      observer.disconnect()
      mutations?.disconnect()
    }
    // remountKey: the element is replaced when it changes (a keyed element), so observe the new one.
  }, [ref, axis, remountKey])
  return scrolls
}

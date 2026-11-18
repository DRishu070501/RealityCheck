import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Every screen, in a real browser against the running stack, at phone, tablet, laptop and desktop
 * widths: no serious or critical axe violations (WCAG 2.1 A and AA, colour contrast included, which
 * jsdom cannot measure) and no sideways scrolling of the page. The Halloween theme (spec 11) only changes
 * colours, so it is checked for the same violations at the narrowest and widest widths.
 */

const PAGES = [
  { path: '/', heading: 'Overview' },
  { path: '/factory', heading: 'Factory' },
  { path: '/workflow', heading: 'Workflow' },
  { path: '/resources', heading: 'Resources' },
  { path: '/orders', heading: 'Production Orders' },
  { path: '/timeline', heading: 'Operational Timeline' },
  { path: '/insights', heading: 'Where Time Goes' },
  { path: '/scenarios', heading: 'Scenario Lab' },
  { path: '/results', heading: 'Simulation Results' },
  { path: '/accuracy', heading: 'Accuracy' },
  { path: '/analyst', heading: 'AI Analyst' },
  { path: '/settings', heading: 'Settings' },
] as const

const WIDTHS = [375, 768, 1024, 1440] as const

async function settle(page: Page, heading: string) {
  await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
  // Loading placeholders are role="status" with a "Loading" label; wait until they are gone.
  await expect(page.getByRole('status', { name: /^Loading/ })).toHaveCount(0, { timeout: 30_000 })
  await page.waitForLoadState('networkidle')
}

const RUNS = [
  ...WIDTHS.map((width) => ({ width, theme: 'professional' as const })),
  { width: 375, theme: 'halloween' as const },
  { width: 1440, theme: 'halloween' as const },
]

for (const { width, theme } of RUNS) {
  test.describe(`at ${width} px${theme === 'halloween' ? ' in the Halloween theme' : ''}`, () => {
    test.use({ viewport: { width, height: 900 } })

    test.beforeEach(async ({ page }) => {
      await page.addInitScript((chosen) => {
        window.localStorage.setItem('realitycheck.theme', chosen)
      }, theme)
    })

    for (const { path, heading } of PAGES) {
      test(`${heading} has no serious accessibility violations and no sideways scroll`, async ({ page }) => {
        await page.goto(path)
        await settle(page, heading)

        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
        const blocking = results.violations
          .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
          .map((violation) => ({
            rule: violation.id,
            impact: violation.impact,
            help: violation.help,
            targets: violation.nodes.slice(0, 5).map((node) => node.target.join(' ')),
          }))
        expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])

        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        )
        expect(overflow, 'the page scrolls sideways').toBeLessThanOrEqual(0)
      })
    }
  })
}

test('the keyboard reaches the navigation and shows where focus is', async ({ page }) => {
  await page.goto('/')
  await settle(page, 'Overview')
  const nav = page.getByRole('navigation', { name: 'Primary' })

  let reached = false
  for (let i = 0; i < 15 && !reached; i++) {
    await page.keyboard.press('Tab')
    reached = await nav.getByRole('link', { name: 'Workflow' }).evaluate((link) => link === document.activeElement)
  }
  expect(reached, 'Tab reaches the Workflow link').toBe(true)

  const focused = nav.getByRole('link', { name: 'Workflow' })
  const outline = await focused.evaluate((link) => {
    const style = getComputedStyle(link)
    return { outline: style.outlineStyle, width: style.outlineWidth, shadow: style.boxShadow }
  })
  expect(outline.outline !== 'none' || outline.shadow !== 'none', JSON.stringify(outline)).toBe(true)

  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1, name: 'Workflow' })).toBeVisible()
})

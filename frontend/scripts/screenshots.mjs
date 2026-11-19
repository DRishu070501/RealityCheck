// Captures the submission screenshots from a running stack (spec 11, task 17).
//
//   node scripts/screenshots.mjs <base-url> <run-id> <baseline-run-id> <output-dir>
//
// Before running: the demonstration seeded, the alternative and baseline runs completed, a backtest run and an
// analyst question asked for the alternative (see docs/submission/video-script.md). Viewport 1440 x 900.
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'

const [base, run, baseline, out] = process.argv.slice(2)
if (!out) {
  console.error('usage: node scripts/screenshots.mjs <base-url> <run-id> <baseline-run-id> <output-dir>')
  process.exit(2)
}
mkdirSync(out, { recursive: true })

const results = `/results?run=${run}&baseline=${baseline}`
const shots = [
  { name: '01-overview', path: '/' },
  { name: '02-haunted-stages', path: '/workflow', focus: '[aria-labelledby="haunted-title"]' },
  { name: '03-scenario-lab', path: '/scenarios' },
  { name: '04-comparison', path: results },
  { name: '05-ghost-futures', path: results, focus: 'text=Ghost futures' },
  { name: '06-where-time-goes', path: '/insights' },
  { name: '07-accuracy', path: '/accuracy' },
  { name: '08-analyst', path: `/analyst?run=${run}&baseline=${baseline}` },
  { name: '09-halloween-ghost-futures', path: results, focus: 'text=Ghost futures', theme: 'halloween' },
]

const browser = await chromium.launch()
for (const shot of shots) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' })
  if (shot.theme) {
    await context.addInitScript((t) => window.localStorage.setItem('realitycheck.theme', t), shot.theme)
  }
  const page = await context.newPage()
  await page.goto(base + shot.path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500) // charts and lazy screens settle
  if (shot.focus) {
    const target = page.locator(shot.focus).first()
    await target.evaluate((element) => element.scrollIntoView({ block: 'start' }))
    await page.evaluate(() => window.scrollBy(0, -90)) // clear the sticky header
    await page.waitForTimeout(300)
  }
  await page.screenshot({ path: join(out, `${shot.name}.png`) })
  console.log('saved', shot.name)
  await context.close()
}
await browser.close()

// Records a short silent clip of the Import Data screen for the demo video, and its screenshot.
//
//   node scripts/import-clip.mjs <base-url> <output-dir>
//
// Writes import-data.webm (1600 x 900, dark, with a visible pointer, matching scripts/video.mjs) and
// 10-import-data.png (1440 x 900, light, matching scripts/screenshots.mjs). It downloads the two templates
// from the screen, chooses them and presses Check; it never presses Import, so nothing is saved.
import { mkdirSync, readdirSync, renameSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'

const [base, out] = process.argv.slice(2)
if (!out) {
  console.error('usage: node scripts/import-clip.mjs <base-url> <output-dir>')
  process.exit(2)
}
mkdirSync(out, { recursive: true })
const files = join(out, 'templates')
mkdirSync(files, { recursive: true })

const sleep = (s) => new Promise((resolve) => setTimeout(resolve, s * 1000))

/** Moves the pointer to an element in small steps, so the viewer can follow it. */
async function glide(page, locator) {
  const box = await locator.boundingBox()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 25 })
  await sleep(0.3)
}

async function scrollTo(page, locator) {
  await locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }))
  await sleep(1)
}

/** Downloads both templates, checks the file names were chosen and runs Check. */
async function walk(page, paced) {
  const pause = (s) => (paced ? sleep(s) : Promise.resolve())
  await page.goto(`${base}/import`, { waitUntil: 'networkidle' })
  await page.getByText('Use the code or name of a stage').waitFor()
  await pause(2.5)

  const paths = {}
  for (const [key, label] of [
    ['orders', 'Orders template'],
    ['movements', 'Movements template'],
  ]) {
    const button = page.getByRole('button', { name: label })
    if (paced) await glide(page, button)
    const [download] = await Promise.all([page.waitForEvent('download'), button.click()])
    paths[key] = join(files, `${key}.csv`)
    await download.saveAs(paths[key])
    await pause(0.8)
  }

  const orders = page.getByLabel('Orders file')
  if (paced) await scrollTo(page, orders)
  if (paced) await glide(page, orders)
  await orders.setInputFiles(paths.orders)
  await page.getByText(/orders\.csv: \d+ rows read/).waitFor()
  await pause(1.2)
  const movements = page.getByLabel('Movements file')
  if (paced) await glide(page, movements)
  await movements.setInputFiles(paths.movements)
  await page.getByText(/movements\.csv: \d+ rows read/).waitFor()
  await pause(1.5)

  const check = page.getByRole('button', { name: 'Check' })
  if (paced) await scrollTo(page, check)
  if (paced) await glide(page, check)
  await check.click()
  await page.getByText('Ready to import').waitFor()
  await pause(1.2)
  if (paced) await glide(page, page.getByRole('button', { name: 'Import' }))
  await pause(3.5)
}

const browser = await chromium.launch()

// The clip.
const clip = await browser.newContext({
  viewport: { width: 1600, height: 900 },
  recordVideo: { dir: out, size: { width: 1600, height: 900 } },
  colorScheme: 'dark',
  acceptDownloads: true,
})
await clip.addInitScript(() => {
  window.addEventListener('DOMContentLoaded', () => {
    const dot = document.createElement('div')
    Object.assign(dot.style, {
      position: 'fixed', left: '-40px', top: '-40px', width: '18px', height: '18px', borderRadius: '50%',
      background: 'rgba(255,255,255,0.85)', border: '2px solid rgba(0,0,0,0.6)', zIndex: '2147483647',
      pointerEvents: 'none', transform: 'translate(-50%,-50%)', transition: 'left 60ms linear, top 60ms linear',
    })
    document.body.appendChild(dot)
    window.addEventListener('mousemove', (e) => {
      dot.style.left = `${e.clientX}px`
      dot.style.top = `${e.clientY}px`
    })
  })
})
const page = await clip.newPage()
const video = page.video()
await walk(page, true)
await clip.close()
const recorded = await video.path()
renameSync(recorded, join(out, 'import-data.webm'))
console.log('saved import-data.webm')

// The screenshot: the same steps, unpaced, then the result in view.
const still = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', acceptDownloads: true })
const shot = await still.newPage()
await walk(shot, false)
// The first card just below the top bar, so no text is cut in half, and the result still in view.
const card = await shot.locator('[aria-labelledby="format-title"]').boundingBox()
await shot.mouse.move(800, 500)
await shot.mouse.wheel(0, card.y - 72)
await sleep(0.5)
await shot.screenshot({ path: join(out, '10-import-data.png') })
console.log('saved 10-import-data.png')
await still.close()
await browser.close()
for (const name of readdirSync(out)) if (name.endsWith('.webm') && name !== 'import-data.webm') console.log('left over', name)

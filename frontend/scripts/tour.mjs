// Records a full walkthrough of every screen, timed to its narration (a long tour for the owner, not the
// three-minute entry video).
//
//   node scripts/tour.mjs <config.json>
//
// config: { base, runs: {base, park}, scenarios: {park}, order, audioDir, outDir }
// audioDir holds <segment>.vtt sentence cues and durations.json (tools/video/assemble.py narrate).
// Writes outDir/picture.webm and outDir/timeline.json, which tools/video/assemble.py build turns into the video.
// Nothing is saved in the app: Import is never pressed, no simulation is run, and the theme is put back.
import { readFileSync, readdirSync, renameSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'

const config = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const { base, runs, scenarios, order, audioDir, outDir } = config
mkdirSync(outDir, { recursive: true })
const files = join(outDir, 'files')
mkdirSync(files, { recursive: true })
const durations = JSON.parse(readFileSync(join(audioDir, 'durations.json'), 'utf8'))
const GAP = 0.8
const W = 1600
const H = 900

function cues(id) {
  const text = readFileSync(join(audioDir, `${id}.vtt`), 'utf8')
  return [...text.matchAll(/(\d\d):(\d\d):(\d\d)[,.](\d\d\d) -->/g)].map(
    ([, h, m, s, ms]) => Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(ms) / 1000,
  )
}

const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: W, height: H },
  recordVideo: { dir: outDir, size: { width: W, height: H } },
  colorScheme: 'dark',
  acceptDownloads: true,
  bypassCSP: true,
})
// A visible pointer, and a soft glow for whatever is being described.
await context.addInitScript(() => {
  window.addEventListener('DOMContentLoaded', () => {
    const style = document.createElement('style')
    style.textContent = `
      #__cursor{position:fixed;left:-40px;top:-40px;width:18px;height:18px;border-radius:50%;
        background:rgba(255,255,255,.88);border:2px solid rgba(0,0,0,.6);z-index:2147483647;pointer-events:none;
        transform:translate(-50%,-50%);transition:left 60ms linear,top 60ms linear}
      .__glow{outline:2px solid rgba(138,180,255,.95)!important;outline-offset:4px;border-radius:8px;
        box-shadow:0 0 0 8px rgba(138,180,255,.18),0 0 28px rgba(138,180,255,.35)!important;
        transition:outline-color .3s,box-shadow .3s}`
    document.head.appendChild(style)
    const dot = document.createElement('div')
    dot.id = '__cursor'
    document.body.appendChild(dot)
    window.addEventListener('mousemove', (e) => {
      dot.style.left = `${e.clientX}px`
      dot.style.top = `${e.clientY}px`
    })
  })
})
const page = await context.newPage()
page.setDefaultTimeout(5000) // a missing element must not stall the narration
const t0 = Date.now()
const now = () => (Date.now() - t0) / 1000
const sleep = (s) => new Promise((resolve) => setTimeout(resolve, Math.max(0, s * 1000)))
const main = () => page.locator('main')

/** Runs a step; a missing element costs a warning, never the recording. */
async function tryStep(label, fn) {
  try {
    await fn()
  } catch (e) {
    console.warn(`  skipped: ${label} (${String(e.message).split('\n')[0]})`)
  }
}

const SCROLLER = `(start) => {
  for (let e = start; e; e = e.parentElement) {
    const o = getComputedStyle(e).overflowY
    if ((o === 'auto' || o === 'scroll') && e.scrollHeight > e.clientHeight) return e
  }
  const all = [...document.querySelectorAll('*')].filter((e) => {
    const o = getComputedStyle(e).overflowY
    return (o === 'auto' || o === 'scroll') && e.scrollHeight > e.clientHeight
  })
  return all.sort((a, b) => b.clientHeight - a.clientHeight)[0] ?? document.scrollingElement
}`

async function glide(handle, offsetFn, seconds) {
  await page.evaluate(async ([target, scroller, offset, ms]) => {
    const find = eval(scroller)
    const box = find(target ?? document.querySelector('main') ?? document.body)
    const from = box.scrollTop
    const to = Math.max(0, Math.min(box.scrollHeight - box.clientHeight, eval(offset)(from, target, box)))
    const start = performance.now()
    await new Promise((resolve) => {
      const step = (t) => {
        const k = Math.min(1, (t - start) / ms)
        box.scrollTop = from + (to - from) * (1 - Math.cos(Math.PI * k)) / 2
        if (k < 1) requestAnimationFrame(step)
        else resolve()
      }
      requestAnimationFrame(step)
    })
  }, [handle, SCROLLER, offsetFn, seconds * 1000])
}

/** Glides so the element's top sits a little under the header. */
async function scrollTo(locator, seconds = 1.2, margin = 24) {
  await locator.waitFor({ timeout: 5000 })
  await glide(await locator.elementHandle(),
    `(from, el, box) => from + el.getBoundingClientRect().top - box.getBoundingClientRect().top - ${margin}`, seconds)
}

async function scrollBy(px, seconds = 1.5) {
  await glide(null, `(from) => from + ${px}`, seconds)
}

async function scrollTop(seconds = 1.0) {
  await glide(null, '() => 0', seconds)
}

/** Moves the pointer onto the element and makes it glow for a while. */
async function point(locator, hold = 1.8, click = false) {
  await locator.waitFor({ timeout: 5000 })
  const box = await locator.boundingBox()
  if (box) await page.mouse.move(box.x + Math.min(box.width / 2, 160), box.y + box.height / 2, { steps: 22 })
  const handle = await locator.elementHandle()
  await page.evaluate((el) => el.classList.add('__glow'), handle)
  if (click) {
    await sleep(0.35)
    await locator.click()
  }
  setTimeout(() => page.evaluate((el) => el?.classList.remove('__glow'), handle).catch(() => {}), hold * 1000)
}

/** A heading on the current screen, by its text. */
const heading = (text) => main().locator('h1, h2, h3').filter({ hasText: text }).first()
/** The section around a heading, for the glow. */
const sectionOf = (text) => main().locator('section, article, [class*="card"]').filter({ has: heading(text) }).last()

async function scrollToSection(text, seconds = 1.3) {
  await scrollTo(heading(text), seconds, 70)
}

/** Points at the section a heading belongs to, scrolling it into view first. */
async function show(text, hold = 2.2) {
  await tryStep(text, async () => {
    await scrollToSection(text)
    const sec = sectionOf(text)
    await point((await sec.count()) ? sec : heading(text), hold)
  })
}

/** Enters a screen through the sidebar, as a person would. */
async function nav(label) {
  const link = page.locator('nav a, aside a').filter({ hasText: label }).first()
  await tryStep(`nav ${label}`, async () => {
    await point(link, 1.0, true)
    await page.waitForLoadState('networkidle')
  })
  await page.waitForTimeout(500)
}

async function open(path) {
  await page.goto(base + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)
}

const timeline = {}
let clock = 1.0

async function segment(id, scene) {
  await sleep(clock - now())
  const start = now()
  timeline[id] = start
  const c = cues(id)
  const at = (i, shift = 0) => sleep(start + (c[i] ?? 0) + shift - now())
  console.log(`${id} at ${start.toFixed(1)} s`)
  await scene(at)
  clock = start + durations[id] + GAP
}

// ---------------------------------------------------------------------------------------------------------------
await open('/')
await segment('01-intro', async (at) => {
  await at(3)
  await tryStep('sidebar', () => point(page.locator('nav, aside').first(), 4))
  await at(5)
  await tryStep('tags', async () => {
    const tag = main().getByText(/^(Actual|Simulated)$/).first()
    await scrollTo(tag, 1.2, 200)
    await point(tag, 4)
  })
  await at(7, 1.5)
  await scrollTop()
})

await segment('02-overview', async (at) => {
  await at(1)
  await tryStep('factory', () => point(heading('Surat Diamond Works'), 2))
  await at(2)
  await show('Production now', 4)
  await at(3)
  await tryStep('card', () => point(main().getByRole('link', { name: /Orders in progress/ }).first(), 2))
  await at(4)
  await show('Work at each stage', 5)
  await at(5)
  await show('Finished output per day', 4)
  await at(6)
  await show('Recent events', 5)
  await at(7)
  await show('If nothing changes', 6)
  await at(8)
  await show('System status', 5)
})

await segment('03-factory', async (at) => {
  await nav('Factory')
  await at(0, 2.0)
  await tryStep('profile', () => point(main().getByText('Time zone').first(), 2.5))
  await at(1)
  await show('Working calendar', 8)
})

await segment('04-workflow', async (at) => {
  await nav('Workflow')
  await at(1)
  await tryStep('stage card', () => point(main().getByRole('button', { name: /Stage 1/ }).first(), 3))
  await at(2, 2)
  await tryStep('stage card 3', () => point(main().getByRole('button', { name: /Stage 3/ }).first(), 3))
  await at(3, 0.5)
  await tryStep('rough store', async () => {
    const b = main().getByRole('button', { name: /Rough store/ }).first()
    await scrollTo(b, 1.2, 160)
    await point(b, 3)
  })
  await at(4, 3)
  await tryStep('job work', () => point(main().getByRole('button', { name: /Outside job work/ }).first(), 3))
  await at(5)
  await show('Haunted stages', 8)
})

await segment('05-resources', async (at) => {
  await nav('Resources')
  await at(1)
  for (const name of ['Machines', 'Employees', 'All']) {
    await tryStep(name, () => point(main().getByRole('button', { name, exact: true }).first(), 1.2, true))
    await sleep(1.3)
  }
  await at(2)
  await tryStep('pool row', () => point(main().locator('tbody tr').first(), 4))
  await at(3)
  await tryStep('edit', () => point(main().getByRole('button', { name: 'Edit' }).first(), 2))
  await at(4)
  await tryStep('availability', () => point(main().getByRole('button', { name: 'Availability' }).first(), 6))
  await at(6)
  await scrollBy(400, 2)
})

await segment('06-orders', async (at) => {
  await nav('Production Orders')
  await at(0, 3)
  await tryStep('table', () => point(main().locator('table').first(), 4))
  await at(1)
  for (const name of ['Pending', 'In progress', 'Completed', 'Dispatched', 'All']) {
    await tryStep(name, () => point(main().getByRole('button', { name, exact: true }).first(), 1, true))
    await sleep(1.0)
  }
  await at(2)
  await tryStep('order', async () => {
    await point(main().getByRole('link', { name: 'SDW-0001' }).first(), 1, true)
    await page.waitForLoadState('networkidle')
  })
  await sleep(0.8)
  await show('Batches', 3)
  await at(3)
  await show('Event history', 6)
  await at(5)
  await tryStep('open in timeline', async () => {
    const b = main().getByRole('link', { name: 'Open in the timeline' }).first()
    await scrollTo(b, 1.2, 200)
    await point(b, 3)
  })
})

await segment('07-import', async (at) => {
  await nav('Import Data')
  const paths = {}
  await at(1)
  for (const [key, label] of [['orders', 'Orders template'], ['movements', 'Movements template']]) {
    await tryStep(label, async () => {
      const button = main().getByRole('button', { name: label })
      await point(button, 2)
      const [download] = await Promise.all([page.waitForEvent('download'), button.click()])
      paths[key] = join(files, `${key}.csv`)
      await download.saveAs(paths[key])
    })
    await sleep(1.5)
  }
  await at(3)
  await tryStep('orders file', async () => {
    const input = page.getByLabel('Orders file')
    await scrollToSection('2. Choose the files')
    await point(input, 2)
    await input.setInputFiles(paths.orders)
  })
  await at(4)
  await tryStep('movements file', async () => {
    const input = page.getByLabel('Movements file')
    await point(input, 2)
    await input.setInputFiles(paths.movements)
  })
  await at(5)
  await tryStep('check', async () => {
    const check = main().getByRole('button', { name: 'Check' })
    await scrollTo(check, 1.2, 300)
    await point(check, 1.5, true)
    await page.getByText('Ready to import').waitFor({ timeout: 8000 })
  })
  await at(6)
  await tryStep('import', () => point(main().getByRole('button', { name: 'Import' }), 5))
})

await segment('08-timeline', async (at) => {
  await nav('Operational Timeline')
  await at(1)
  await show('Activity by day', 4)
  await at(2)
  await tryStep('pick a day', async () => {
    // The bars are drawn in an SVG; the pointer goes to the chart, and the day's own button is pressed.
    const box = await heading('Activity by day').boundingBox()
    if (box) await page.mouse.move(box.x + 700, box.y + 160, { steps: 20 })
    await sleep(0.4)
    await page.getByRole('button', { name: /Thu, 12 Nov 2026: .* Show this day\./ }).dispatchEvent('click')
    await page.waitForLoadState('networkidle')
  })
  await at(3)
  await show('Factory at a point in time', 7)
  await at(6)
  await show('Events', 4)
  await at(7)
  await tryStep('create scenario', async () => {
    const b = main().getByRole('button', { name: 'Create scenario from this point' })
      .or(main().getByRole('link', { name: 'Create scenario from this point' })).first()
    await scrollTo(b, 1.2, 260)
    await point(b, 5)
  })
})

await segment('09-insights', async (at) => {
  await nav('Where Time Goes')
  await at(1)
  await show('Lead time', 9)
  await at(3)
  await show('Where packet time goes', 9)
  await at(5)
  await show('Haunted stages', 3)
  await at(5, 3.5)
  await show('Yield by stage', 3.5)
  await at(6)
  await show('Waiting for dispatch', 4)
})

await segment('10-scenarios', async (at) => {
  await nav('Scenario Lab')
  await at(3)
  await tryStep('list', () => point(main().locator('ul, table').filter({ hasText: 'Park half as long' }).first(), 3))
  await at(4)
  await tryStep('open park', async () => {
    await point(main().getByRole('link', { name: 'Park half as long' }).first(), 0.8, true)
    await page.waitForLoadState('networkidle')
  })
  await at(5)
  await show('Changes', 5)
  await at(7)
  await tryStep('add change', () => point(main().getByRole('button', { name: 'Add change' }), 5))
  await at(8)
  await show('Check before running', 4)
  await at(9)
  await tryStep('run', () => point(main().getByRole('button', { name: 'Run simulation' }), 2.5))
  await at(9, 3.5)
  await show('Run history', 4)
  await at(10)
  await tryStep('branch', async () => {
    await scrollTop()
    await point(main().getByRole('button', { name: 'Branch' }).first(), 4)
  })
})

await segment('11-results', async (at) => {
  await open(`/results?run=${runs.park}&baseline=${runs.base}`)
  await tryStep('nav highlight', () => point(page.locator('nav a, aside a').filter({ hasText: 'Simulation Results' }).first(), 1.5))
  await at(2)
  await show('What differs', 4)
  await at(3)
  await show('Headline metrics', 9)
  await at(5)
  await show('Bottleneck', 4)
  await at(6)
  await show('What actually happened after the fork', 6)
  await at(7)
  await show('Stages', 2.5)
  await at(7, 2.5)
  await show('Resource pools', 2.5)
  await at(8)
  await show('Ghost futures', 12)
  await at(11)
  await show('Order delivery', 2)
  await at(11, 2)
  await show('Production timeline', 2)
  await at(12)
  await tryStep('explain', async () => {
    await scrollTop()
    await point(main().getByRole('button', { name: 'Explain with AI Analyst' }).or(main().getByRole('link', { name: 'Explain with AI Analyst' })).first(), 3)
  })
})

await segment('12-analyst', async (at) => {
  await open(`/analyst?run=${runs.park}&baseline=${runs.base}`)
  await at(2)
  await show('Context', 4)
  await at(3)
  await show('Reports', 9)
  await at(4)
  await show('Example questions', 3)
  await at(5)
  await show('Insights for this run', 4)
  await at(6)
  for (const name of ['Summary', 'Key changes', 'Risks', 'Bottleneck', 'Recommendations']) {
    await tryStep(name, () => point(heading(name), 1.6))
    await sleep(1.6)
  }
  await at(7)
  await tryStep('evidence', async () => {
    const ev = main().getByText('Evidence behind this answer').first()
    await scrollTo(ev, 1.2, 260)
    await point(ev, 1, true)
    await sleep(0.6)
    await scrollBy(320, 1.8)
  })
})

await segment('13-accuracy', async (at) => {
  await nav('Accuracy')
  await at(1)
  await show('Backtest', 5)
  await at(2)
  await show('Typical error', 7)
  await at(3)
  await show('Each date, predicted against recorded', 8)
  await at(4)
  await show('Work in progress by stage', 4)
  await at(5)
  await tryStep('run again', async () => {
    await scrollTop()
    await point(main().getByRole('button', { name: 'Run again' }), 3)
  })
})

await segment('14-settings', async (at) => {
  await nav('Settings')
  await at(1)
  await tryStep('halloween', () => point(main().getByRole('radio', { name: /Halloween/ }), 2, true))
  await at(2, 2.5)
  await tryStep('professional', () => point(main().getByRole('radio', { name: /Professional/ }), 1.5, true))
  await at(3)
  await show('Deployment', 10)
  await at(4)
  await show('Demonstration data', 6)
})

await segment('15-close', async (at) => {
  await at(1)
  await nav('Where Time Goes')
  await at(2)
  await nav('Scenario Lab')
  await at(3)
  await open(`/results?run=${runs.park}&baseline=${runs.base}`)
  await tryStep('ghosts', () => scrollToSection('Ghost futures', 1.5))
})
await sleep(clock + 1.5 - now())

// Leave the app as it was found.
await page.evaluate(() => window.localStorage.setItem('realitycheck.theme', 'professional')).catch(() => {})
const video = page.video()
await context.close()
await browser.close()
renameSync(await video.path(), join(outDir, 'picture.webm'))
writeFileSync(join(outDir, 'timeline.json'), JSON.stringify({ gap: GAP, length: clock + 1.5, segments: timeline }, null, 1))
console.log('picture', join(outDir, 'picture.webm'), `${(clock + 1.5).toFixed(1)} s`)
for (const f of readdirSync(outDir)) if (f.endsWith('.webm') && f !== 'picture.webm') console.log('extra', f)

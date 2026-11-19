// Records the demo video's picture, scene by scene, timed to the narration (spec 11, task 17).
//
//   node scripts/video.mjs <config.json>
//
// config: { base, runs: {base, park, insp}, scenarios: {park, insp}, audioDir, outDir, repo }
// audioDir holds <segment>.srt cues and durations.json from the narration step (docs/submission/narration.json).
// Writes outDir/picture.webm and outDir/timeline.json (when each narration segment starts, in seconds).
import { readFileSync, readdirSync, renameSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'

const config = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const { base, runs, scenarios, audioDir, outDir, repo } = config
mkdirSync(outDir, { recursive: true })
const durations = JSON.parse(readFileSync(join(audioDir, 'durations.json'), 'utf8'))
const GAP = 0.6 // seconds of silence between narration segments
const W = 1600
const H = 900

/** Sentence start times (seconds) within a segment, from the narration's subtitle file. */
function cues(id) {
  const text = readFileSync(join(audioDir, `${id}.vtt`), 'utf8')
  return [...text.matchAll(/(\d\d):(\d\d):(\d\d),(\d\d\d) -->/g)].map(
    ([, h, m, s, ms]) => Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(ms) / 1000,
  )
}

const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: W, height: H },
  recordVideo: { dir: outDir, size: { width: W, height: H } },
  colorScheme: 'dark',
  bypassCSP: true, // the scroll helper evaluates small functions in the page
})
// A visible pointer, since recordings do not show the system cursor.
await context.addInitScript(() => {
  window.addEventListener('DOMContentLoaded', () => {
    const dot = document.createElement('div')
    dot.id = '__cursor'
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
// A full-screen title card that survives navigation, so a page can load and scroll behind it.
await context.addInitScript(() => {
  const html = window.sessionStorage.getItem('__card')
  if (!html) return
  const show = () => {
    const el = document.createElement('div')
    el.id = '__card'
    el.innerHTML = html
    document.documentElement.appendChild(el)
  }
  if (document.body) show()
  else window.addEventListener('DOMContentLoaded', show)
})
const page = await context.newPage()
const t0 = Date.now()
const now = () => (Date.now() - t0) / 1000
const sleep = (s) => new Promise((resolve) => setTimeout(resolve, Math.max(0, s * 1000)))

async function open(path) {
  await page.goto(base + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
}

/** Scrolls the app's scrolling panel (the content area scrolls, not the window) smoothly. */
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

/** Brings the element's top just under the header. */
async function scrollTo(selector, seconds = 1.2) {
  const target = page.locator(selector).first()
  await target.waitFor()
  await glide(await target.elementHandle(),
    '(from, el, box) => from + el.getBoundingClientRect().top - box.getBoundingClientRect().top - 24', seconds)
}

async function scrollBy(px, seconds = 1.5) {
  await glide(null, `(from) => from + ${px}`, seconds)
}

async function pointAndClick(locator) {
  const box = await locator.boundingBox()
  if (box) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 25 })
    await sleep(0.3)
  }
  await locator.click()
}

const OVERLAY_CSS = `<style>
  #__card{position:fixed;inset:0;z-index:2147483646;background:#0b1020;color:#e6e9f2;
    font-family:Inter,Segoe UI,system-ui,sans-serif;display:flex;flex-direction:column;justify-content:center;
    padding:0 140px;box-sizing:border-box;transition:opacity .7s ease}
  #__card h1{font-size:64px;margin:0 0 12px;letter-spacing:-1px;font-weight:700} #__card h1 span{color:#8ab4ff}
  #__card p.sub{font-size:28px;color:#aab3c8;margin:0 0 48px}
  #__card .row{display:grid;grid-template-columns:260px 1fr;gap:24px;align-items:baseline;font-size:30px;margin:14px 0}
  #__card .row b{color:#8ab4ff;font-weight:600} #__card .row i{font-style:normal;color:#aab3c8;font-size:24px;display:block}
</style>`

/** Shows a title card over the current page, kept across navigation until hidden. */
async function showOverlay(inner) {
  await page.evaluate((html) => {
    window.sessionStorage.setItem('__card', html)
    const el = document.createElement('div')
    el.id = '__card'
    el.innerHTML = html
    document.documentElement.appendChild(el)
  }, OVERLAY_CSS + inner)
}

async function hideOverlay() {
  await page.evaluate(async () => {
    window.sessionStorage.removeItem('__card')
    const el = document.getElementById('__card')
    if (!el) return
    el.style.opacity = '0'
    await new Promise((r) => setTimeout(r, 700))
    el.remove()
  })
}

function card(html) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    html,body{margin:0;height:100%;background:#0b1020;color:#e6e9f2;font-family:Inter,Segoe UI,system-ui,sans-serif}
    .wrap{height:100%;display:flex;flex-direction:column;justify-content:center;padding:0 140px;box-sizing:border-box}
    h1{font-size:64px;margin:0 0 12px;letter-spacing:-1px} h1 span{color:#8ab4ff}
    p.sub{font-size:28px;color:#aab3c8;margin:0 0 48px}
    .row{display:grid;grid-template-columns:260px 1fr;gap:24px;align-items:baseline;font-size:30px;margin:14px 0;
      opacity:0;transform:translateY(12px);animation:in .6s ease forwards}
    .row b{color:#8ab4ff;font-weight:600} .row i{font-style:normal;color:#aab3c8;font-size:24px;display:block}
    @keyframes in{to{opacity:1;transform:none}}
    .url{font-size:30px;color:#8ab4ff;margin-top:36px}
  </style></head><body><div class="wrap">${html}</div></body></html>`
}

const timeline = {}
let clock = 0.6 // a moment of picture before the first word

async function segment(id, scene) {
  await sleep(clock - now())
  const start = now()
  timeline[id] = start
  const c = cues(id)
  const at = (s) => sleep(start + s - now())
  await scene(at, c)
  clock = start + durations[id] + GAP
}

// 1. The hook: the factory today.
await open('/')
await segment('01-hook', async (at, c) => {
  await at(c[2] ?? 6)
  await scrollBy(320, 2.5)
})

// 2. The creature: five parts, each appearing as it is named.
await segment('02-creature', async (at, c) => {
  // Sentence 0 names the creature, sentence 1 the idea; the parts follow, one per sentence.
  const delays = [c[2], c[3], c[4], c[5], c[6]].map((s, i) => (s ?? 6 + i * 3) - 0.2)
  const parts = [
    ['The skeleton', 'Append-only history', 'never changes the past'],
    ['The heart', 'Deterministic simulation engine', 'never guesses'],
    ['The blood', "A real factory's patterns", 'never publishes a name'],
    ['The voice', 'AI analyst (Claude)', 'never calculates a number'],
    ['The face', 'One app, every number tagged', 'Actual · Simulated · AI'],
  ]
  await page.setContent(card(`<h1>Reality<span>Check</span></h1><p class="sub">A Frankenstein of five parts</p>` +
    parts.map(([a, b, n], i) => `<div class="row" style="animation-delay:${delays[i]}s"><b>${a}</b><div>${b}<i>${n}</i></div></div>`).join('')))
})

// 3. Recorded history: where time goes, and the haunted stages.
await segment('03-history', async (at, c) => {
  await open('/insights')
  await at((c[2] ?? 8) - 1.6)
  await open('/workflow')
  await scrollTo('[aria-labelledby="haunted-title"]', 1.4)
})

// 4. Back in time: two scenarios forked from a recorded moment.
await segment('04-fork', async (at, c) => {
  await open('/scenarios')
  await at((c[2] ?? 8) - 0.8)
  await open(`/scenarios/${scenarios.park}`)
  await at((c[3] ?? 12) - 0.8)
  await open(`/scenarios/${scenarios.insp}`)
  await at(c[4] ?? 15)
  await scrollBy(250, 1.5)
})

// 5. Results: headline metrics, then the ghost futures.
const results = (run) => `/results?run=${run}&baseline=${runs.base}`
await segment('05-results', async (at, c) => {
  await open(results(runs.park))
  await scrollTo('text=Headline metrics', 1.4)
  await at((c[2] ?? 10) - 2.2)
  await scrollTo('text=Ghost futures', 1.6)
})

// 6. The other decision: three more inspectors.
await segment('06-inspectors', async () => {
  await open(results(runs.insp))
  await scrollTo('text=Headline metrics', 1.4)
})

// 7. The analyst, and the evidence behind its answer.
await segment('07-analyst', async (at, c) => {
  await open(`/analyst?run=${runs.park}&baseline=${runs.base}`)
  await at((c[1] ?? 8) + 1.2)
  const evidence = page.getByText('Evidence behind this answer').first()
  await pointAndClick(evidence)
  await sleep(0.4)
  await scrollTo('text=Evidence behind this answer', 1.2)
})

// 8. Accuracy: the backtest.
await segment('08-accuracy', async (at, c) => {
  await open('/accuracy')
  await at((c[2] ?? 9) - 1.0)
  await scrollTo('text=Each date, predicted against recorded', 1.4)
})

// 9. Kiro, the night theme switched on live over the ghost futures, and the close.
await segment('09-close', async (at, c) => {
  await showOverlay(`<h1>Built with <span>Kiro</span></h1><p class="sub">Spec-first, from the .kiro directory</p>
    <div class="row"><b>Steering</b><div>4 rule documents<i>numbers come from the engine; AI never calculates</i></div></div>
    <div class="row"><b>Specs</b><div>11 features, 83 requirements<i>each task tied to the test that proves it</i></div></div>
    <div class="row"><b>Hooks and agents</b><div>checks on save, scoped specialists</div></div>`)
  await open(results(runs.park))
  await scrollTo('text=Ghost futures', 0.01)
  await at((c[1] ?? 5) - 0.5)
  await hideOverlay()
  await at((c[1] ?? 5) + 0.8)
  await page.evaluate(() => {
    window.localStorage.setItem('realitycheck.theme', 'halloween')
    document.documentElement.setAttribute('data-theme', 'halloween')
  })
  await at((c[2] ?? 10) - 0.3)
  await page.setContent(card(`<h1>Reality<span>Check</span></h1>
    <p class="sub">See the future before you change the present.</p>
    <div class="row" style="animation-delay:0s"><b>Kiroween</b><div>Frankenstein category</div></div>
    <div class="url">${repo}</div>`))
})
await sleep(clock + 1.5 - now())

const video = page.video()
await context.close()
await browser.close()
const recorded = await video.path()
renameSync(recorded, join(outDir, 'picture.webm'))
writeFileSync(join(outDir, 'timeline.json'), JSON.stringify({ gap: GAP, length: clock + 1.5, segments: timeline }, null, 1))
console.log('picture', join(outDir, 'picture.webm'), `${(clock + 1.5).toFixed(1)} s`)
for (const f of readdirSync(outDir)) if (f.endsWith('.webm') && f !== 'picture.webm') console.log('extra', f)

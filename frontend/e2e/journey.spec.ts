import { expect, test, type Page } from '@playwright/test'

/**
 * The definition of success, end to end, through the UI only: the seeded factory, its history, a fork
 * from a past moment, a change, a deterministic run, the comparison with its baseline, the bottleneck,
 * and the AI analyst's recommendations with their evidence. No database edits; nothing is mocked.
 *
 * Every run creates its own uniquely named scenario, so the suite can be repeated on the same stack.
 * It passes with either AI provider: the assertions hold for Claude and for the built-in analyst.
 */

const SIMULATION_TIMEOUT = 90_000
const AI_TIMEOUT = 60_000

async function openFromNavigation(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
}

test('a manager branches from the past, changes capacity, simulates, compares and asks the analyst', async ({ page }) => {
  const scenarioName = `E2E capacity check ${new Date().toISOString().slice(0, 19)}`

  await test.step('1. Open the configured diamond factory', async () => {
    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible()
    const factory = page.getByRole('region', { name: 'Surat Diamond Works' })
    await expect(factory).toBeVisible()
    await expect(factory.getByRole('definition').first()).toHaveText('11')
  })

  await test.step('2. View its eleven production stages', async () => {
    await openFromNavigation(page, 'Workflow')
    const stages = page.getByRole('list', { name: 'Production stages in sequence' }).getByRole('listitem')
    await expect(stages).toHaveCount(11)
    await expect(stages.first()).toContainText('Rough intake')
    await expect(stages.nth(5)).toContainText('Rough store')
    await expect(stages.nth(8)).toContainText('Polishing')
    await expect(stages.last()).toContainText('Dispatch')
  })

  await test.step('3. Inspect machines, employees and capacity', async () => {
    await openFromNavigation(page, 'Resources')
    const machines = page.getByRole('table', { name: 'Machines' })
    const employees = page.getByRole('table', { name: 'Employees' })
    await expect(machines.getByRole('columnheader', { name: 'Capacity' })).toBeVisible()
    await expect(machines).toContainText('Polishing wheels')
    await expect(employees).toContainText('Polishers')
  })

  await test.step('4. Browse historical production events', async () => {
    await openFromNavigation(page, 'Operational Timeline')
    const days = page.getByRole('list', { name: 'Activity by day' }).getByRole('button')
    await expect(days.first()).toBeVisible()
    // A day a few working days back, so recorded history continues after the fork.
    const count = await days.count()
    expect(count).toBeGreaterThan(5)
    // "Tue, 10 Nov 2026: 81 events. Show this day." gives the day's label and its date.
    const dayLabel = ((await days.nth(count - 4).textContent()) ?? '').split(':')[0]?.trim() ?? ''
    const date = new Date(`${dayLabel.slice(5)} 12:00 UTC`).toISOString().slice(0, 10)
    await page.getByLabel('To day').fill(date)
    const dayRegion = page.getByRole('region', { name: dayLabel })
    await expect(dayRegion).toBeVisible()
    await dayRegion.getByRole('button', { name: /Stage (started|completed)/ }).first().click()
  })

  await test.step('5. Select a historical point in time', async () => {
    await page.getByRole('button', { name: 'Inspect factory at this moment' }).click()
    await expect(page).toHaveURL(/\/timeline\?.*\bat=/)
    const inspector = page.getByRole('region', { name: 'Factory at a point in time' })
    await expect(inspector.getByLabel('Orders by status at that moment')).toBeVisible()
    await expect(inspector.getByRole('list', { name: 'Stages at that moment' }).getByRole('listitem')).toHaveCount(11)
  })

  await test.step('6. Create an alternative scenario from that moment', async () => {
    const branch = page.getByRole('button', { name: 'Create scenario from this point' })
    await expect(branch).toBeEnabled()
    await branch.click()
    const dialog = page.getByRole('dialog', { name: 'New scenario' })
    await dialog.getByLabel('Name').fill(scenarioName)
    await dialog.getByRole('button', { name: 'Create scenario' }).click()
    await expect(page).toHaveURL(/\/scenarios\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { level: 1, name: scenarioName })).toBeVisible()
  })

  await test.step('7. Change machine capacity', async () => {
    await page.getByRole('button', { name: 'Add change' }).click()
    const dialog = page.getByRole('dialog', { name: 'Add a change' })
    await dialog.getByLabel('Kind of change').selectOption('RESOURCE_CAPACITY')
    await dialog.getByLabel('Pool').selectOption({ label: 'Polishing wheels (4)' })
    await dialog.getByLabel('Units to add (negative removes)').fill('2')
    await dialog.getByRole('button', { name: 'Add change' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText(/Polishing wheels: capacity \+2 units/).first()).toBeVisible()
  })

  await test.step('8. Execute a deterministic simulation', async () => {
    const run = page.getByRole('button', { name: 'Run simulation' })
    await expect(run).toBeEnabled()
    await run.click()
    await expect(page.getByRole('link', { name: 'View results' })).toBeVisible({ timeout: SIMULATION_TIMEOUT })
  })

  await test.step('9. Compare the original and alternative outcomes', async () => {
    // A fork from its own moment has no baseline yet: create one from the same moment and run it.
    // A baseline scenario for that moment may already exist (then it only needs running) or not.
    const createBaseline = page.getByRole('button', { name: 'Create and run baseline' })
    const runBaseline = page.getByRole('button', { name: 'Run baseline' })
    const compare = page.getByRole('link', { name: 'Compare with baseline' })
    await expect(createBaseline.or(runBaseline).or(compare)).toBeVisible()
    if (await createBaseline.isVisible()) await createBaseline.click()
    else if (await runBaseline.isVisible()) await runBaseline.click()
    await expect(compare).toBeVisible({ timeout: SIMULATION_TIMEOUT })
    await compare.click()

    await expect(page).toHaveURL(/\/results\?run=.+&baseline=.+/)
    const metrics = page.getByRole('region', { name: 'Headline metrics' })
    await expect(metrics).toBeVisible()
    await expect(metrics.getByRole('row')).not.toHaveCount(0)
    await expect(page.getByRole('region', { name: 'What differs' })).toContainText('Polishing wheels: capacity +2 units')
    // What really happened after the fork, beside the simulation.
    await expect(page.getByRole('region', { name: 'What actually happened after the fork' })).toBeVisible()
  })

  await test.step('10. Identify production bottlenecks', async () => {
    const bottleneck = page.getByRole('region', { name: 'Bottleneck' })
    await expect(bottleneck).toBeVisible()
    await expect(bottleneck).toContainText(/bottleneck/i)
    await expect(bottleneck.getByRole('table')).toContainText(/Rough intake|Imaging|Lab grading|Inspection|Planning|Laser sawing|Polishing|Recut/)
  })

  await test.step('11. Ask AI to explain the results', async () => {
    await page.getByRole('link', { name: 'Explain with AI Analyst' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'AI Analyst' })).toBeVisible()
    await expect(page.getByRole('combobox', { name: 'Run' })).toHaveValue(/[0-9a-f-]{36}/)
    await page.getByRole('button', { name: 'Recommend next steps' }).click()
  })

  await test.step('12. Receive recommendations supported by simulation metrics', async () => {
    const card = page.getByTestId('insight-card').first()
    await expect(card).toBeVisible({ timeout: AI_TIMEOUT })
    await expect(card.getByText('AI interpretation')).toBeVisible()
    await expect(card.getByRole('heading', { level: 3, name: 'Recommendations' })).toBeVisible()
    const recommendation = card.getByRole('listitem').filter({ has: page.getByText(/^Evidence \(/) }).first()
    await expect(recommendation).toBeVisible()
    await recommendation.getByText(/^Evidence \(/).click()
    await expect(recommendation.getByText('Simulated').first()).toBeVisible()
  })
})

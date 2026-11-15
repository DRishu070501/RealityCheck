import { describe, expect, it } from 'vitest'

import { pageOf } from '@/lib/schemas'
import {
  comparisonSchema,
  runResultsSchema,
  runSchema,
  scenarioComparisonsSchema,
  scenarioSchema,
  scenarioSummarySchema,
  scenarioValidationSchema,
  simulatedEventSchema,
  stageResultSchema,
} from '@/lib/scenarioSchemas'
import baselineResults from '@/test/fixtures/baselineResults.json'
import comparison from '@/test/fixtures/comparison.json'
import events from '@/test/fixtures/events.json'
import results from '@/test/fixtures/results.json'
import run from '@/test/fixtures/run.json'
import runStages from '@/test/fixtures/runStages.json'
import runs from '@/test/fixtures/runs.json'
import scenario from '@/test/fixtures/scenario.json'
import scenarioBaseline from '@/test/fixtures/scenarioBaseline.json'
import scenarioComparisons from '@/test/fixtures/scenarioComparisons.json'
import scenarios from '@/test/fixtures/scenarios.json'
import validation from '@/test/fixtures/validation.json'

/** Every response captured from the running backend parses with the schema the client uses for it. */
describe('scenario and simulation schemas', () => {
  it.each([
    ['scenarios', pageOf(scenarioSummarySchema), scenarios],
    ['scenario', scenarioSchema, scenario],
    ['baseline scenario', scenarioSchema, scenarioBaseline],
    ['validation', scenarioValidationSchema, validation],
    ['run', runSchema, run],
    ['runs', pageOf(runSchema), runs],
    ['results', runResultsSchema, results],
    ['baseline results', runResultsSchema, baselineResults],
    ['stages', stageResultSchema.array(), runStages],
    ['events', pageOf(simulatedEventSchema), events],
    ['comparison', comparisonSchema, comparison],
    ['scenario comparisons', scenarioComparisonsSchema, scenarioComparisons],
  ] as const)('parses the captured %s response', (_name, schema, fixture) => {
    const parsed = (schema as { safeParse: (v: unknown) => { success: boolean; error?: unknown } }).safeParse(fixture)
    expect(parsed.error).toBeUndefined()
    expect(parsed.success).toBe(true)
  })
})

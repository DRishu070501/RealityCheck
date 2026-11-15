import { z } from 'zod'

/**
 * Shapes of the AI analyst API (`/api/ai/*`). The content is an interpretation of simulated
 * results; every number in it was checked against the evidence on the server before it was
 * returned. The evidence items are simulated values, quoted as stored.
 */

const uuid = z.uuid()
const instant = z.iso.datetime({ offset: true })

export const INSIGHT_TYPES = [
  'EXPLANATION',
  'BOTTLENECK',
  'RECOMMENDATIONS',
  'DELAY_ANALYSIS',
  'COMPARISON_SUMMARY',
  'EXECUTIVE_REPORT',
  'ANSWER',
] as const
export const insightTypeSchema = z.enum(INSIGHT_TYPES)
export type InsightType = z.infer<typeof insightTypeSchema>

export const recommendationSchema = z.object({
  title: z.string(),
  rationale: z.string(),
  /** Evidence keys the recommendation rests on; at least one. */
  metricRefs: z.array(z.string()),
})
export type Recommendation = z.infer<typeof recommendationSchema>

export const insightContentSchema = z.object({
  summary: z.string(),
  keyChanges: z.array(z.string()),
  risks: z.array(z.string()),
  bottleneckExplanation: z.string(),
  recommendations: z.array(recommendationSchema),
  metricRefs: z.array(z.string()),
  assumptions: z.array(z.string()),
  limitations: z.array(z.string()),
})
export type InsightContent = z.infer<typeof insightContentSchema>

export const evidenceItemSchema = z.object({
  key: z.string(),
  label: z.string(),
  /** A decimal string for numeric facts; null for text facts. */
  value: z.string().nullable(),
  text: z.string().nullable(),
  unit: z.string().nullable(),
  /** Preformatted forms of the value, for example "52.0 hours". */
  display: z.array(z.string()),
  sourceRunId: uuid,
  stageId: uuid.nullable(),
  resourceId: uuid.nullable(),
  source: z.literal('SIMULATED'),
})
export type EvidenceItem = z.infer<typeof evidenceItemSchema>

export const fallbackReasonSchema = z.enum(['VALIDATION_FAILED', 'PROVIDER_ERROR', 'TIMEOUT'])

export const insightSchema = z.object({
  id: uuid,
  runId: uuid,
  baselineRunId: uuid.nullable(),
  type: insightTypeSchema,
  question: z.string().nullable(),
  source: z.literal('AI_INTERPRETATION'),
  /** `template` is the built-in analyst; otherwise the language-model vendor. */
  provider: z.string(),
  model: z.string().nullable(),
  /** The language model failed or its answer did not pass the check; the built-in analyst answered. */
  fallback: z.boolean(),
  fallbackReason: fallbackReasonSchema.nullable(),
  inputTokens: z.number().int().nullable(),
  outputTokens: z.number().int().nullable(),
  createdAt: instant,
  content: insightContentSchema,
  evidence: z.array(evidenceItemSchema),
})
export type Insight = z.infer<typeof insightSchema>

export interface InsightInput {
  runId: string
  baselineRunId?: string
  type: Exclude<InsightType, 'ANSWER'>
  regenerate?: boolean
}

export interface AskInput {
  runId: string
  baselineRunId?: string
  question: string
}

export const QUESTION_MAX = 500

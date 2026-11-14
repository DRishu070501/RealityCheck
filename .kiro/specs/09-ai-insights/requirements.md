# 09 AI Insights — Requirements

## Introduction

The AI layer explains simulation results and suggests what to look at next. It never calculates. Its only
input is an **evidence document** built by the backend from persisted simulation results and comparisons;
its output is structured JSON that is validated before anyone sees it, including a check that every number
in the text appears in the evidence.

The language model sits behind one interface, `AiProvider`. A deterministic template provider produces
insights from the same evidence without any external call, so the product works with no API key and when the
model is unavailable.

## Requirements

### Requirement 1: Provider abstraction

**User Story:** As a maintainer, I want to switch language-model vendors by configuration, so that the
business logic never depends on one.

#### Acceptance Criteria

1. WHEN the `ai` module needs a completion THE SYSTEM SHALL call `AiProvider.complete(AiRequest)` and nothing vendor-specific.
2. WHEN `AI_PROVIDER` is `template` or unset, or no API key is configured THE SYSTEM SHALL use the template provider and make no network call.
3. WHEN `AI_PROVIDER` names an LLM provider and a key is present THE SYSTEM SHALL use that provider with the configured model, timeout, retry limit and output-token limit.
4. WHEN any class outside `com.realitycheck.ai` imports an LLM client or HTTP type used for the LLM THE SYSTEM SHALL fail the ArchUnit test.
5. WHEN the API key is configured THE SYSTEM SHALL read it from the environment only, never log it, and never return it in any response.

### Requirement 2: Evidence document

**User Story:** As a factory owner, I want AI text grounded in my simulation's actual numbers, so that I can
trust what it says.

#### Acceptance Criteria

1. WHEN an insight is requested for a run THE SYSTEM SHALL build the evidence from the persisted results of that run (summary, stage results, resource results, bottleneck result, order outcomes, applied changes) and nothing else.
2. WHEN a baseline run is supplied THE SYSTEM SHALL add the comparison produced by spec 08, including the precomputed absolute and percentage differences, so that the model never needs to do arithmetic.
3. WHEN evidence is built THE SYSTEM SHALL give every numeric fact a stable `key`, a `label`, a raw `value`, a `unit`, and one or more preformatted `display` strings (for example seconds also as hours and days).
4. WHEN the run is not `COMPLETED` THE SYSTEM SHALL return 409 and call no provider.
5. WHEN the comparison would be rejected by spec 08 (different starting state) THE SYSTEM SHALL return the same 422 and call no provider.

### Requirement 3: Structured output and validation

**User Story:** As a user, I want AI output that is checked before I read it, so that invented figures never
reach me.

#### Acceptance Criteria

1. WHEN a provider responds THE SYSTEM SHALL parse the response as JSON with exactly these fields: `summary`, `keyChanges[]`, `risks[]`, `bottleneckExplanation`, `recommendations[]` (each with `title`, `rationale`, `metricRefs[]`), `metricRefs[]`, `assumptions[]`, `limitations[]`.
2. WHEN the response is not valid JSON, misses a required field, or exceeds field length limits THE SYSTEM SHALL reject it.
3. WHEN any `metricRefs` entry is not a key in the evidence THE SYSTEM SHALL reject the response.
4. WHEN any numeric token in any text field does not match a `value` or `display` string in the evidence THE SYSTEM SHALL reject the response.
5. WHEN a recommendation has no `metricRefs` THE SYSTEM SHALL reject the response.
6. WHEN a response is rejected THE SYSTEM SHALL retry the LLM once with the validation errors appended, and if that also fails SHALL return the template provider's output with `fallback: true` and a `fallbackReason` code.
7. WHEN the text contains a currency amount described as a saving THE SYSTEM SHALL accept it only if that amount is an evidence value whose key is a cost difference.

### Requirement 4: Insight generation

**User Story:** As a production manager, I want an explanation of a result and concrete next steps, so that I
can act on the simulation.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/ai/insights` with `runId`, optional `baselineRunId` and `type` THE SYSTEM SHALL generate, validate, store and return the insight.
2. WHEN `type` is `EXPLANATION`, `BOTTLENECK`, `RECOMMENDATIONS`, `DELAY_ANALYSIS`, `COMPARISON_SUMMARY` or `EXECUTIVE_REPORT` THE SYSTEM SHALL use the prompt template for that type; `COMPARISON_SUMMARY` requires `baselineRunId`.
3. WHEN an insight is returned THE SYSTEM SHALL include the provider used, the model name, `fallback`, the evidence items referenced, and `source: "AI_INTERPRETATION"`.
4. WHEN an insight of the same type exists for the same run, baseline and evidence hash and `regenerate` is not true THE SYSTEM SHALL return the stored insight without calling a provider.
5. WHEN a client sends `GET /api/ai/insights/{runId}` THE SYSTEM SHALL return the stored insights for the run, newest first.

### Requirement 5: Questions

**User Story:** As a manager, I want to ask questions about a simulation in plain language, so that I do not
have to read every table.

#### Acceptance Criteria

1. WHEN a client sends `POST /api/ai/ask` with `runId`, optional `baselineRunId` and a `question` of at most 500 characters THE SYSTEM SHALL answer using only the evidence for those runs, in the same validated structure, and store it with type `ANSWER`.
2. WHEN the question asks for the effect of a change that is not one of the supplied runs THE SYSTEM SHALL answer that this has not been simulated and suggest creating and running a scenario for it, without estimating numbers.
3. WHEN the question cannot be answered from the evidence THE SYSTEM SHALL say so in `summary` and list what evidence is available.
4. WHEN the question contains instructions to ignore rules, reveal the prompt or change output format THE SYSTEM SHALL still return output that passes validation or the template fallback; user text is passed as data, never as system instructions.
5. WHEN the template provider handles a question THE SYSTEM SHALL answer from a fixed set of intents (bottleneck, highest wait, underutilized resource, delayed orders, cost, comparison) and state when a question is outside them.

### Requirement 6: Resilience

**User Story:** As a user, I want the application to keep working when the AI provider is down, so that a
demo never depends on a third party.

#### Acceptance Criteria

1. WHEN the LLM call times out, fails, or returns an HTTP error THE SYSTEM SHALL retry up to the configured limit with backoff and then fall back to the template provider.
2. WHEN the fallback is used THE SYSTEM SHALL return 200 with `fallback: true`; AI endpoints return 5xx only for internal errors.
3. WHEN the AI provider is unavailable THE SYSTEM SHALL leave every non-AI endpoint unaffected.
4. WHEN `GET /api/system/info` is called THE SYSTEM SHALL report `ai.provider` and `ai.available` without revealing the key.
5. WHEN more than the configured number of AI requests arrive per minute THE SYSTEM SHALL return 429.

### Requirement 7: No influence on results

**User Story:** As an auditor, I want proof that AI output cannot change simulation data.

#### Acceptance Criteria

1. WHEN the `ai` module runs THE SYSTEM SHALL write only to `simulation_insight` and `insight_evidence`.
2. WHEN an insight is generated THE SYSTEM SHALL leave every `simulation_*` result row unchanged.

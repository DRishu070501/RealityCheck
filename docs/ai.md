# AI analyst

The AI layer explains simulation results and suggests what to look at next. **It never calculates.** Every
number it may state is already in an evidence document built from stored simulation results, and every
answer is checked against that document before anyone sees it.

Spec: `.kiro/specs/09-ai-insights/`. Code: `backend/src/main/java/com/realitycheck/ai/`. Screen: **AI Analyst**
(`frontend/src/features/analyst/`).

## Turning it on

The application works without a language model: the built-in **template analyst** answers from fixed rules.
To use Claude, set these in `.env` (never in the frontend, never in the repository):

```dotenv
AI_PROVIDER=anthropic
AI_API_KEY=<your Anthropic API key>
AI_MODEL=claude-haiku-5-5
```

Then restart the backend (`docker compose up -d backend`). `GET /api/system/info` reports
`"ai": {"enabled": true, "provider": "anthropic", "model": "claude-haiku-5-5", "available": true}`; with no key
it reports `"provider": "template"`. The key is never logged, never returned and never sent to the browser.

| Variable | Default | Meaning |
|---|---|---|
| `AI_PROVIDER` | `template` | `template` (built in) or `anthropic`. `none` means `template`. An unknown name falls back to `template` with a warning in the log. |
| `AI_API_KEY` | empty | Without it the template analyst answers. |
| `AI_MODEL` | `claude-haiku-5-5` | Any Claude model name. |
| `AI_BASE_URL` | empty | Overrides the API address (used by tests). |
| `AI_TIMEOUT_SECONDS` | `30` | Per request. |
| `AI_MAX_RETRIES` | `2` | Transport retries on timeouts, 429 and 5xx, with backoff (done by the Anthropic SDK). |
| `AI_MAX_OUTPUT_TOKENS` | `4000` | Upper bound per answer. |
| `AI_REQUESTS_PER_MINUTE` | `20` | Across all users; more get `429` with `Retry-After`. Bounds what the model can cost. |
| `AI_DAILY_BUDGET_USD` | empty | Spending cap per UTC day in US dollars; empty for none. Today's spend is the input and output tokens stored on insights since midnight UTC, priced below. Once reached, the built-in analyst answers (`DAILY_BUDGET`) until midnight. Checked before each request, so simultaneous requests can overshoot by their own cost. |
| `AI_INPUT_USD_PER_MTOK`, `AI_OUTPUT_USD_PER_MTOK` | `0.10`, `0.50` | Price per million input and output tokens of `AI_MODEL`; the defaults are Claude Haiku 5.5's (prompts up to 100K tokens). Change them with the model. |

### What it costs

Measured on the demonstration factory on 15 November 2026 with Claude Haiku 5.5 (USD 0.10 per million
input tokens, 0.50 per million output tokens), effort `low`:

| | Input tokens | Output tokens | Time | Cost |
|---|---|---|---|---|
| One run explained | about 20,000 | about 1,200 | 4 to 9 s | about USD 0.0026 |
| With a baseline (comparison) | about 40,000 | about 1,200 | 6 to 13 s | about USD 0.005 |

A rejected first answer is retried once, which doubles that request. Every insight stores its token counts
(`inputTokens`, `outputTokens`), so real spending can be read from `simulation_insight`. A stored insight for
the same run, type and evidence is returned without calling the model at all.

## How an answer is produced

```
POST /api/ai/insights {runId, baselineRunId?, type}
  1. rate limit                                              → 429
  2. load the run's stored results                          → 404, or 409 if not COMPLETED
     with a baseline: the stored comparison (spec 08)       → 422 if the starting states differ
  3. build the evidence document and hash it
  4. same run + baseline + type + hash already answered by this provider? return it
  5. ask the provider (outside any database transaction)
  6. validate; if rejected, ask once more with the reasons; if rejected again,
     or the provider failed, the template analyst answers with fallback: true
  7. store the insight and the evidence items it cites (append-only tables)
```

`POST /api/ai/ask` does the same with a question (at most 500 characters); answers are never served from
storage. The `ai` module writes only `simulation_insight` and `insight_evidence`. An integration test checks
that a checksum of every simulation result table is unchanged after generating insights.

## The evidence document

Built by `EvidenceBuilder` from stored results only. Every fact has a stable key, a label, and either a
numeric `value` with a `unit` and preformatted `display` strings, or a `text`.

| Key | Example display |
|---|---|
| `run.summary.elapsedSeconds` | `52.0 hours`, `2.2 days`, `52 hours` |
| `run.cost.total` | `INR 1,234,567.89`, `INR 1,234,568` |
| `run.stage.POLISHING.limitingUtilization` | `96.2%`, `96%` |
| `run.stage.POLISHING.name`, `run.bottleneck.primaryStage` | text: `Polishing` |
| `run.resource.<resourceId>.utilization` | `31.0%`, `31%` |
| `run.order.SDW-2026-0001.outcome` | text: `LATE` |
| `run.change.1` | text: the scenario change as summarised by the scenario module |
| `baseline.*` | the same shapes for the baseline run (summary, cost, bottleneck, stages) |
| `delta.summary.cost.total.absolute`, `.percent`, `.direction` | `+INR 34,567.89`, `+2.9%`, text `WORSENED` |
| `delta.stage.POLISHING.meanWaitWorkingSeconds.absolute` | `-1.5 hours` |
| `delta.resource.<id>.utilization.absolute` | `-7.5 percentage points` |
| `delta.order.SDW-2026-0002.outcomeChange` | text: `LATE to ON_TIME` |
| `delta.bottleneck.change` | text: `SAME`, `MOVED`, `RESOLVED`, `EMERGED` or `NONE` |

Every difference comes from the comparison service (which only subtracts); the AI layer computes none. The
display strings exist so that a model can quote hours, days and percentages without converting anything.
Orders are capped at the 50 most delayed (`truncated: true` when more were dropped); on-time orders keep only
their outcome.

## Validation

`InsightValidator` runs on every provider answer, the template's included:

1. **Shape.** Strict JSON with exactly `summary`, `keyChanges`, `risks`, `bottleneckExplanation`,
   `recommendations` (`title`, `rationale`, `metricRefs`), `metricRefs`, `assumptions`, `limitations`. Summary
   at most 1,200 characters, other text 600, lists 8 items (top-level `metricRefs` 16).
2. **References.** Every `metricRefs` entry is an evidence key; every recommendation cites at least one. A
   reference to a field of an item (`run.cost.total.display`) is read as the item; nothing else is corrected.
3. **Numbers.** Every number in every text field (`[-+]?\d[\d,]*(\.\d+)?%?`) must equal, as a decimal, a
   value or a number inside a display string or text item of the evidence. Evidence text that contains
   digits (order numbers, change summaries, dates) is masked before numbers are read.
4. **Savings.** In a sentence about saving or reducing, a money amount must be a cost difference
   (`delta.*cost*`).

Error codes: `MALFORMED_JSON`, `SCHEMA`, `UNKNOWN_METRIC_REF`, `UNSUPPORTED_NUMBER`, `MISSING_METRIC_REF`,
`UNSUPPORTED_SAVING_CLAIM`. The reasons are sent back to the model for its one retry. An answer cut off at the
token limit is retried with an instruction to be shorter.

### What the check does not catch

- Numbers are compared by magnitude, so a sign flip ("rises by 2.2%" when the evidence says it fell) passes;
  the direction words in the evidence (`IMPROVED`, `WORSENED`) and the prompt are the defence.
- A number that is wrong in context but present somewhere in the evidence passes. For example, if the
  evidence contains a 1 anywhere, "a completion time of 1 hour" is not rejected. Small integers are common
  in the evidence, so this matters most for them.
- Spelled-out numbers ("two machines") are not checked. The prompt forbids invented quantities.

The UI says so in plain words: an answer is an interpretation of simulated estimates, and its evidence is shown
beside it so that it can be checked.

## Fallback

The template analyst answers, with `fallback: true` and a `fallbackReason`, when:

| Reason | When |
|---|---|
| `VALIDATION_FAILED` | Both answers failed the check |
| `PROVIDER_ERROR` | HTTP error after retries, refusal, or the API could not be reached |
| `TIMEOUT` | No answer within `AI_TIMEOUT_SECONDS` |
| `DAILY_BUDGET` | Today's spend has reached `AI_DAILY_BUDGET_USD`; the model is not called |

AI endpoints answer `200` in all these cases; they return `5xx` only for internal errors. Non-AI endpoints are
unaffected by the provider's state. The AI Analyst screen states plainly when the built-in analyst answered and
why.

The template analyst selects facts by key and fills fixed sentences, inserting only display strings. A jqwik
property test checks that its output passes the validator for every insight type on random results. For
questions it recognises a fixed set of topics (bottleneck, longest wait, under-used resources, delays, cost,
comparison) and says when a question is outside them; for "what if" questions it says the change has not been
simulated.

## Prompts and prompt injection

The system prompt is `ai/prompts/core.txt` plus one paragraph per insight type (`ai/prompts/<type>.txt`). The
user message holds the evidence in an `<evidence>` block and, for questions, the question in a `<question>`
block introduced as data, not instructions; delimiter tags inside the question are defused. User text never
enters the system prompt. A model that obeys an injected instruction to state a number not in the evidence
fails validation and the template answers instead (`InsightServiceTest`).

The response is constrained to the insight JSON schema (`ai/insight-schema.json`) through the API's structured
output, and Claude runs at effort `low`: explaining stored figures needs little reasoning, and low effort keeps
answers fast and inside the token limit.

## Adding a provider

1. Implement `AiProvider` in `com.realitycheck.ai.provider` (one class; only that package may use a vendor
   client, enforced by `ArchitectureTest`).
2. Add its name to `RealityCheckProperties.Ai.LANGUAGE_MODEL_PROVIDERS` and a case in `AiProviderConfig.select`.
3. Map the vendor's failures to `AiProviderException` with `TIMEOUT` or `PROVIDER_ERROR`, and report truncation
   through `AiResponse.truncated`.
4. Add a stub-server test like `AnthropicAiProviderTest`.

Nothing else changes: the evidence, prompts, validation, fallback and storage are vendor-neutral.

## Tests

| Test | What it covers |
|---|---|
| `EvidenceBuilderTest` | Keys, units, display strings, deltas, truncation, deterministic hash, prompt form |
| `InsightValidatorTest` | Every error code; display strings and quoted order numbers allowed; fabricated numbers and savings rejected |
| `TemplateAiProviderTest` | Each type with and without a baseline; question topics; property test over random results |
| `PromptFactoryTest` | Every type has a prompt; the question never reaches the system prompt; retry carries reasons |
| `InsightServiceTest` | Valid first time; retry with reasons; fallback for each reason; cache; prompt injection; rate limit |
| `AnthropicAiProviderTest` | Request shape (model, prompts, schema, effort), retries on 500 and 429, timeout, refusal, no key in errors; against a local stub server |
| `AiApiIT` | Real runs in PostgreSQL: generate, store, list, regenerate; 400, 404, 409; simulation tables unchanged; cascade delete; append-only |

Integration tests always use the template analyst, so a key in a developer's environment never makes the test
suite call a real model.

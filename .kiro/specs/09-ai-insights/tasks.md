# 09 AI Insights — Tasks

Milestone: **M7 — AI intelligence**. Depends on spec 07 Part B and spec 08. The AI Analyst screen is spec 10.

- [x] 1. Add the insight schema
  - Flyway migration for `simulation_insight` and `insight_evidence` with indexes; entities, `InsightType`, repositories.
  - Test: repository integration test for cascade delete with a run and the cache lookup index query.
  - _Requirements: 4.1, 7.1_

- [x] 2. Define the provider abstraction and configuration
  - `AiProvider`, `AiRequest`, `AiResponse`, `realitycheck.ai.*` properties, `AiProviderConfig` selection rules; key masked in `toString` and Actuator.
  - Test: selection for unset provider, `template`, LLM with and without key; ArchUnit rule for LLM client types.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 3. Implement `EvidenceBuilder` and `EvidenceFormatter`
  - Key scheme, units, display strings, comparison deltas, truncation, evidence hash.
  - Test: `EvidenceBuilderTest`.
  - _Requirements: 2.1, 2.2, 2.3_

- [x] 4. Implement `InsightValidator`
  - Strict parsing, limits, reference checks, numeric-token check, saving-claim check, error codes.
  - Test: `InsightValidatorTest`.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7_

- [x] 5. Implement `TemplateAiProvider`
  - Fixed sentences and rules per insight type; question intents.
  - Test: output for each type on a fixed evidence fixture (snapshot-style assertions on structure and refs); jqwik property that template output always validates.
  - _Requirements: 1.2, 4.2, 5.5, 6.1_

- [x] 6. Implement `PromptFactory`
  - Shared system prompt, per-type paragraphs, schema text, delimited evidence and question blocks.
  - Test: question text never appears in the system prompt; every `InsightType` has a template.
  - _Requirements: 4.2, 5.4_

- [x] 7. Implement `InsightService`
  - Rate limit, run and comparison checks, cache, provider call with transport retry, validation retry, fallback, persistence of insight and referenced evidence.
  - Test: `InsightServiceTest` with the scripted fake provider; `PromptInjectionTest`.
  - _Requirements: 2.4, 2.5, 3.6, 4.1, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.5_

- [x] 8. Implement `LlmAiProvider`
  - `RestClient` call for the configured vendor with timeouts, token limit and JSON-only instruction; maps vendor errors to a provider exception.
  - Test: `LlmAiProviderTest` against the local stub server.
  - _Requirements: 1.3, 1.5, 6.1_

- [x] 9. Expose the endpoints
  - `POST /api/ai/insights`, `POST /api/ai/ask`, `GET /api/ai/insights/{runId}`; `ai.provider` and `ai.available` in `GET /api/system/info`.
  - Test: `AiApiIT`, including unchanged `simulation_*` checksums and application health with an unreachable provider.
  - _Requirements: 4.1, 4.5, 5.1, 6.2, 6.3, 6.4, 7.1, 7.2_

- [x] 10. Document
  - `docs/ai.md`: evidence key scheme, validation rules, fallback behaviour, how to add a provider; AI section in `docs/api.md`; `AI_*` variables in `.env.example`; add `scenario` to the `ai` row of the module table in architecture.md.
  - _Requirements: 1.1, 3.4_

## Implementation notes (Milestone 7)

All ten tasks are built and tested; `docs/ai.md` describes the result. Where the build differs from design.md:

- **Vendor client.** Claude is called through the official Anthropic Java SDK in `AnthropicAiProvider`, not a
  hand-written `RestClient`. The SDK does the transport retries with backoff, so `AI_MAX_RETRIES` is passed to
  it. The response is constrained to `ai/insight-schema.json` with structured output, and Claude runs at effort
  `low`. Live testing showed that at the default effort, thinking used most of the output budget and cut answers off.
- **Storage.** Insights are written with `JdbcTemplate` (`InsightStore`), not JPA entities.
  `insight_evidence` also stores the `display` strings, so stored answers show their evidence exactly as it was
  quoted. Both tables are append-only (update trigger). The cache key also includes the provider, and fallback
  answers are never served from the cache.
- **Limits.** `AI_MAX_OUTPUT_TOKENS` defaults to 4000, not 1500 (answers were cut off at 2000). Top-level
  `metricRefs` may hold 16 keys; other lists hold 8.
- **References.** A reference such as `run.cost.total.display` is read as `run.cost.total`. Live answers made this
  slip, and correcting it changes no number. Any other unknown key is still rejected.
- **Retry hint.** An answer cut off at the token limit is retried with an instruction to be shorter, not with
  the schema errors.
- **Evidence size.** On-time orders keep only their outcome. Stage and pool names, and order numbers, are text
  items, so names containing digits can be quoted.
- **System info.** `ai` reports `enabled` (a language model is configured), `provider` (the one that
  answers: `template` or `anthropic`), `model` and `available`.
- **Problem property.** A 409 for a run that is not completed carries `runStatus`, not `status`, which would
  overwrite the problem document's HTTP status.

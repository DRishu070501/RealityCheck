# 09 AI Insights — Tasks

Milestone: **M7 — AI intelligence**. Depends on spec 07 Part B and spec 08. The AI Analyst screen is spec 10.

- [ ] 1. Add the insight schema
  - Flyway migration for `simulation_insight` and `insight_evidence` with indexes; entities, `InsightType`, repositories.
  - Test: repository integration test for cascade delete with a run and the cache lookup index query.
  - _Requirements: 4.1, 7.1_

- [ ] 2. Define the provider abstraction and configuration
  - `AiProvider`, `AiRequest`, `AiResponse`, `realitycheck.ai.*` properties, `AiProviderConfig` selection rules; key masked in `toString` and Actuator.
  - Test: selection for unset provider, `template`, LLM with and without key; ArchUnit rule for LLM client types.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 3. Implement `EvidenceBuilder` and `EvidenceFormatter`
  - Key scheme, units, display strings, comparison deltas, truncation, evidence hash.
  - Test: `EvidenceBuilderTest`.
  - _Requirements: 2.1, 2.2, 2.3_

- [ ] 4. Implement `InsightValidator`
  - Strict parsing, limits, reference checks, numeric-token check, saving-claim check, error codes.
  - Test: `InsightValidatorTest`.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7_

- [ ] 5. Implement `TemplateAiProvider`
  - Fixed sentences and rules per insight type; question intents.
  - Test: output for each type on a fixed evidence fixture (snapshot-style assertions on structure and refs); jqwik property that template output always validates.
  - _Requirements: 1.2, 4.2, 5.5, 6.1_

- [ ] 6. Implement `PromptFactory`
  - Shared system prompt, per-type paragraphs, schema text, delimited evidence and question blocks.
  - Test: question text never appears in the system prompt; every `InsightType` has a template.
  - _Requirements: 4.2, 5.4_

- [ ] 7. Implement `InsightService`
  - Rate limit, run and comparison checks, cache, provider call with transport retry, validation retry, fallback, persistence of insight and referenced evidence.
  - Test: `InsightServiceTest` with the scripted fake provider; `PromptInjectionTest`.
  - _Requirements: 2.4, 2.5, 3.6, 4.1, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.5_

- [ ] 8. Implement `LlmAiProvider`
  - `RestClient` call for the configured vendor with timeouts, token limit and JSON-only instruction; maps vendor errors to a provider exception.
  - Test: `LlmAiProviderTest` against the local stub server.
  - _Requirements: 1.3, 1.5, 6.1_

- [ ] 9. Expose the endpoints
  - `POST /api/ai/insights`, `POST /api/ai/ask`, `GET /api/ai/insights/{runId}`; `ai.provider` and `ai.available` in `GET /api/system/info`.
  - Test: `AiApiIT`, including unchanged `simulation_*` checksums and application health with an unreachable provider.
  - _Requirements: 4.1, 4.5, 5.1, 6.2, 6.3, 6.4, 7.1, 7.2_

- [ ] 10. Document
  - `docs/ai.md`: evidence key scheme, validation rules, fallback behaviour, how to add a provider; AI section in `docs/api.md`; `AI_*` variables in `.env.example`; add `scenario` to the `ai` row of the module table in architecture.md.
  - _Requirements: 1.1, 3.4_

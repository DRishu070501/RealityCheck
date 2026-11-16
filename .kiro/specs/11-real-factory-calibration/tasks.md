# 11 Real-Factory Calibration and Kiroween Release — Tasks

Milestone: **M9 — Kiroween release**. Code freeze **28 November 2026**; media and submission 29 November to
3 December; deadline 4 December 2026, 2 PM PT. Category: **Frankenstein**.

If time runs short, cut in this order: task 13 (Insights AI), task 11 (Insights screen, keep its numbers in
the docs), task 9 (backtest screen, keep its numbers in the docs).

## Setup (16 November)

- [ ] 1. Release hygiene and data safety
  - MIT licence; `.gitignore` for `local/`, `*.cnf`, private calibration files; steering rule on client data.
  - Owner action: rotate factory A's database password; SELECT-only users for both databases; factory B credentials in a local file.
  - _Requirements: 1.1, 7.1_

## Measure (16–19 November)

- [ ] 2. Department grouping for both factories
  - Draft grouping from department names, companies and flows; owner confirms each factory (about 15 minutes each).
  - _Requirements: 1.2_
- [ ] 3. Calibration tool `tools/calibration`
  - Read-only, credentials from `TRYBE_DB_CNF`, output outside the repository; route mix, working and waiting time per stage, workers per day, loss per process, parking, dispatch waiting, job work.
  - _Requirements: 1.1, 1.3_
- [ ] 4. Publishable calibration
  - Merge both factories, round, strip identifiers → `backend/src/main/resources/demo/calibration.json`; identifier-scan test.
  - _Requirements: 1.4_

## Engine (18–20 November)

- [ ] 5. Routes per packet type
  - Snapshot routes; batches carry packet type; validation.
  - Test: unit and property tests; one-route equivalence with 1.0.0.
  - _Requirements: 2.1, 2.5_
- [ ] 6. Holding and job-work stages; algorithm 2.0.0
  - `StageKind`, deterministic delay draws, new metrics, version bump; 1.0.0 runs untouched.
  - Test: determinism, separation of holding from manufacturing time, stored-run reproducibility.
  - _Requirements: 2.2, 2.3, 2.4_

## Demonstration (20–22 November)

- [ ] 7. Calibrated demo generator and new demo factory
  - Fictional names; history from calibration and a fixed seed; new scenarios (more workers at the busiest stage, faster dispatch, less parking).
  - Test: seed determinism; baseline lead time with and without parking within tolerance of calibration.
  - _Requirements: 3.1, 3.2, 3.3_

## Accuracy (21–23 November)

- [ ] 8. Local import of a real factory (anonymised, local only)
  - Map packets, movements and processes into RealityCheck through the existing services; names replaced by codes.
  - Test: against a small hand-made fake database, never real data.
  - _Requirements: 1.1, 4.1_
- [ ] 9. Backtest service, API and screen
  - Several fork dates; error per metric; comparison window cut at the last recorded event.
  - Test: integration test on the demo factory; component test for the screen.
  - _Requirements: 4.1, 4.2, 4.3_
- [ ] 10. Private backtests on both factories; rounded results for the write-up
  - _Requirements: 4.4_

## Value and theme (24–26 November)

- [ ] 11. Insights: where time goes
  - Lead time with and without parking by route, working vs waiting per stage, workers per day, loss per process, dispatch waiting.
  - Test: values against a hand-computed fixture; accessibility suite.
  - _Requirements: 5.1, 5.2_
- [ ] 12. Frankenstein layer
  - Ghost futures on the comparison timeline; haunted stages on the workflow; optional Halloween theme meeting WCAG AA; README story.
  - Test: component tests; accessibility suite in both themes.
  - _Requirements: 6.1, 6.2, 6.3, 6.4_
- [ ] 13. (Optional) The analyst explains Insights, with the same evidence check
  - _Requirements: 5.1_

## Release (26–28 November)

- [ ] 14. Public deployment
  - Cloud server, HTTPS, nightly reset, UI reset disabled, AI daily spending cap with built-in fallback.
  - _Requirements: 7.2, 7.3_
- [ ] 15. Quality pass and code freeze
  - All suites green; end-to-end journey updated for the new factory; fresh-machine check; docs (method, accuracy, limits, privacy); Kiro usage write-up.
  - _Requirements: 6.4, 7.1_

## Submission (29 November – 3 December)

- [ ] 16. Video under three minutes, screenshots, Devpost description, category, Kiro write-up; optional blog and social posts
  - _Requirements: 7.4_

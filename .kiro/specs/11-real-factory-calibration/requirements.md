# 11 Real-Factory Calibration and Kiroween Release — Requirements

## Introduction

Milestones 1 to 8 built RealityCheck on a synthetic factory with one fixed eight-stage route. Real diamond
factories do not look like that. Production data from two real factories (read locally, never published)
shows routes that vary by packet type, rough that is deliberately parked for days or weeks, finished stones
that wait several days before dispatch, outside job workers, and capacity that is people rather than
machines. This spec makes the engine and the demonstration match that reality, proves how accurate the
simulation is against recorded history, and prepares the public release for the Kiroween 2026 hackathon
(category: **Frankenstein**, submissions close 4 December 2026, 2 PM PT).

Two rules govern everything here:

- **Client data stays private.** Real production data is read only by local tools, through read-only
  credentials held outside the repository. Nothing from it is committed except rounded, aggregate
  calibration values that cannot identify a factory, its people, parties, lots or stones.
- **Accuracy is measured, not claimed.** Every statement about realism is backed by a backtest against
  recorded history, published as rounded percentages with its limits.

## Requirements

### Requirement 1: Calibration from real factories

**User Story:** As the team, I want the patterns of real factories measured, so that the demonstration
behaves like a real factory without containing one.

#### Acceptance Criteria

1. WHEN calibration runs against a factory database THE SYSTEM SHALL use read-only queries only, read credentials from an environment variable or a local file outside the repository, and write its output outside the repository.
2. WHEN departments are grouped into stages THE SYSTEM SHALL use a grouping confirmed by a person who knows the factory, and SHALL classify each group as working, parking, dispatch waiting, job work or inward.
3. WHEN calibration completes THE SYSTEM SHALL produce, per factory: route mix by packet type, working and waiting time per stage, workers per stage per day, weight loss per process, parking rate and duration, dispatch waiting and job-work delay, each as rounded values or ranges.
4. WHEN a calibration file is prepared for publication THE SYSTEM SHALL contain no department, person, party, lot, packet or order name or identifier, and SHALL merge both factories into one set of values.

### Requirement 2: Engine support for real routes

**User Story:** As a planner, I want the simulation to follow how stones really move, so that its answers
apply to a real factory.

#### Acceptance Criteria

1. WHEN a factory defines several routes THE SYSTEM SHALL route each batch by its packet type through that route's stages in order.
2. WHEN a stage is a holding stage (parking or dispatch waiting) THE SYSTEM SHALL delay the batch by a duration drawn deterministically from the stage's distribution, without capacity limits, and SHALL report holding time separately from manufacturing time.
3. WHEN a stage is a job-work stage THE SYSTEM SHALL delay the batch by a deterministic draw from the stage's outside-delay distribution and count it as job work.
4. WHEN the engine's output changes for any existing input THE SYSTEM SHALL record algorithm version 2.0.0 on new runs, and runs stored under 1.0.0 SHALL keep their results and remain reproducible.
5. WHEN a factory has one route and no holding or job-work stages THE SYSTEM SHALL produce the same results as algorithm 1.0.0.

### Requirement 3: Calibrated demonstration factory

**User Story:** As a judge, I want a demonstration factory that behaves like a real one, so that the
results mean something.

#### Acceptance Criteria

1. WHEN the demonstration is seeded THE SYSTEM SHALL generate the factory, its history and its scenarios from the published calibration values and a fixed seed, using fictional names only.
2. WHEN the seeded factory's baseline is simulated THE SYSTEM SHALL reproduce the calibrated median lead time with and without parking within a stated tolerance, checked by an automated test.
3. WHEN scenarios are seeded THE SYSTEM SHALL include at least: more workers at the most loaded stage, faster dispatch from the dispatch-waiting stage, and less parking.

### Requirement 4: Backtest

**User Story:** As a manager deciding whether to trust a simulation, I want to see how well it predicted
the past.

#### Acceptance Criteria

1. WHEN a backtest is requested for a factory and a set of past fork dates THE SYSTEM SHALL, for each date, simulate from the reconstructed state with no changes and compare the result with recorded history over the same period.
2. WHEN a backtest completes THE SYSTEM SHALL report per fork and overall the error of: batches finished, median lead time, and work in progress per stage, as the simulation minus recorded and as a percentage of recorded.
3. WHEN recorded history ends before the simulation THE SYSTEM SHALL compare only up to the last recorded event and say so.
4. WHEN backtest results from real factories are published THE SYSTEM SHALL publish only rounded percentages without dates that identify the factory.

### Requirement 5: Where time goes

**User Story:** As a factory owner, I want to see where my stones spend their days, so that I know what to
fix first.

#### Acceptance Criteria

1. WHEN the Insights screen loads THE SYSTEM SHALL show, from recorded history: lead time from inward to dispatch with and without parking, by route; time per stage split into working and waiting; workers active per stage per day; weight loss per process; and time waiting for dispatch.
2. WHEN a figure is shown THE SYSTEM SHALL tag it Actual, give a table alternative for every chart, and pass the accessibility suite.

### Requirement 6: Frankenstein presentation

**User Story:** As a judge in the Frankenstein category, I want to see unlikely technologies stitched into
something that works, with a theme that helps rather than decorates.

#### Acceptance Criteria

1. WHEN a comparison is shown THE SYSTEM SHALL be able to draw the simulated alternatives as translucent "ghost futures" beside the recorded timeline.
2. WHEN the workflow is shown THE SYSTEM SHALL mark the stages where most time is lost ("haunted stages") using the same evidence as the bottleneck rules.
3. WHEN the viewer chooses the Halloween theme THE SYSTEM SHALL apply it without lowering contrast below WCAG AA; the professional theme SHALL remain the default.
4. WHEN the README is read THE SYSTEM SHALL explain the stitched parts (legacy factory database, discrete-event simulation, an AI that may not calculate, time travel through recorded history) and how Kiro was used.

### Requirement 7: Public release

**User Story:** As a judge, I want to open a link and use the application without installing anything.

#### Acceptance Criteria

1. WHEN the repository is viewed THE SYSTEM SHALL carry an open-source licence file and the `.kiro` directory at its root.
2. WHEN the public link is opened at any time until 29 January 2027 THE SYSTEM SHALL serve the application with demonstration data, reset to its seeded state every night.
3. WHEN the public deployment runs THE SYSTEM SHALL disable demo reset from the UI, cap AI spending per day, and fall back to the built-in analyst when the cap is reached.
4. WHEN the submission is made THE SYSTEM SHALL include a public demo video under three minutes, a description, the category, and a write-up of how Kiro was used.

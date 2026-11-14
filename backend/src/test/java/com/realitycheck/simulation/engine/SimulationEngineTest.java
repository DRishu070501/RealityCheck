package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.DAY;
import static com.realitycheck.simulation.engine.InputBuilder.HOUR;
import static com.realitycheck.simulation.engine.InputBuilder.MONDAY_9AM;
import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.simulation.engine.SimulationResult.BottleneckResult;
import com.realitycheck.simulation.engine.SimulationResult.OrderOutcome;
import com.realitycheck.simulation.engine.SimulationResult.PoolResult;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Each test is named for the rule in .kiro/steering/simulation-rules.md that it checks. */
class SimulationEngineTest {

    @Nested
    class ProcessingTime {

        @Test
        void workIsSetupPlusStonesAtTheSlotRate() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saw = factory.machines("Saws", 1, "360");
            factory.stage("CUTTING", "60", 600, needs(saw, 1));
            factory.job("ORD-1", "120");

            SimulationResult result = factory.run();

            // 600 s setup + 120 stones at 60 per hour = 600 + 7200.
            assertThat(result.summary().processingSeconds()).isEqualTo(7_800);
            assertThat(result.summary().elapsedSeconds()).isEqualTo(7_800);
            assertThat(result.endTime()).isEqualTo(7_800);
            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        }

        @Test
        void aFractionOfASecondIsRoundedUp() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            factory.stage("CUTTING", "7", 0);
            factory.job("ORD-1", "1");

            // One stone at seven per hour is 514.28 s.
            assertThat(factory.run().summary().processingSeconds()).isEqualTo(515);
        }

        @Test
        void aJobThatFitsInOneWorkingIntervalEndsThatManySecondsLater() {
            assertThat(tenStoneJobOfHours(3, MONDAY_9AM).endTime()).isEqualTo(MONDAY_9AM + 3 * HOUR);
        }

        @Test
        void workPausesForTheBreak() {
            // Five hours from 09:00: four before the 13:00 break, one after 14:00.
            assertThat(tenStoneJobOfHours(5, MONDAY_9AM).endTime()).isEqualTo(MONDAY_9AM + 6 * HOUR);
        }

        @Test
        void workCarriesOverTheNightAndElapsedTimeIncludesIt() {
            SimulationResult result = tenStoneJobOfHours(10, MONDAY_9AM);

            // Eight working hours on Monday, two on Tuesday: done Tuesday 11:00.
            assertThat(result.endTime()).isEqualTo(MONDAY_9AM + DAY + 2 * HOUR);
            assertThat(result.summary().elapsedSeconds()).isEqualTo(26 * HOUR);
            assertThat(result.summary().processingSeconds()).isEqualTo(10 * HOUR);
            assertThat(result.summary().workingSecondsInWindow()).isEqualTo(10 * HOUR);
        }

        @Test
        void workSkipsTheClosedSunday() {
            long saturday9am = MONDAY_9AM + 5 * DAY;

            // Eight hours on Saturday, nothing on Sunday, two on Monday.
            assertThat(tenStoneJobOfHours(10, saturday9am).endTime()).isEqualTo(saturday9am + 2 * DAY + 2 * HOUR);
        }

        @Test
        void aJobThatBecomesReadyWhileClosedStartsWhenTheFactoryOpens() {
            InputBuilder factory = InputBuilder.demoCalendar();
            factory.stage("CUTTING", "10", 0);
            long sundayNoon = MONDAY_9AM + 6 * DAY + 3 * HOUR;
            UUID batch = factory.arriving(factory.order("ORD-1"), 1, "10", sundayNoon);

            SimulationResult result = factory.run();

            assertThat(timeOf(result, TraceType.JOB_QUEUED, batch)).isEqualTo(sundayNoon);
            assertThat(timeOf(result, TraceType.JOB_STARTED, batch)).isEqualTo(MONDAY_9AM + 7 * DAY);
            assertThat(result.summary().waitingWorkingSeconds()).isZero();
            assertThat(result.summary().waitingCalendarSeconds()).isEqualTo(21 * HOUR);
        }

        private SimulationResult tenStoneJobOfHours(int hours, long forkTime) {
            InputBuilder factory = InputBuilder.demoCalendar().forkAt(forkTime);
            UUID saw = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "10", 0, needs(saw, 1));
            factory.job("ORD-1", String.valueOf(hours * 10));
            return factory.run();
        }
    }

    @Nested
    class ResourceAllocation {

        @Test
        void aJobDoesNotStartUntilEveryRequiredPoolHasUnitsAndHoldsNothingWhileItWaits() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID wheel = factory.machines("Wheels", 1, "100");
            UUID polishers = factory.employees("Polishers", 1, "100");
            factory.window(polishers, 1_000, 100_000, 1);
            factory.stage("POLISHING", "3600", 0, needs(wheel, 1), needs(polishers, 2));
            UUID batch = factory.job("ORD-1", "500");

            SimulationResult result = factory.run();

            // The wheel is free from the start, but two polishers exist only from t = 1000.
            assertThat(timeOf(result, TraceType.JOB_STARTED, batch)).isEqualTo(1_000);
            assertThat(result.endTime()).isEqualTo(1_500);
            // The wheel was not held while waiting: it was busy for the 500 s of work only.
            assertThat(pool(result, "Wheels").busyUnitSeconds()).isEqualTo(500);
            assertThat(pool(result, "Polishers").busyUnitSeconds()).isEqualTo(1_000);
            assertThat(result.summary().waitingWorkingSeconds()).isEqualTo(1_000);
        }

        @Test
        void twoSlotsRunTwoJobsAtOnceWithoutMakingEitherFaster() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 2, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");
            factory.job("ORD-2", "1000");

            SimulationResult result = factory.run();

            // Elapsed time is one job's time; machine time is two jobs' time.
            assertThat(result.summary().elapsedSeconds()).isEqualTo(1_000);
            assertThat(result.summary().processingSeconds()).isEqualTo(2_000);
            assertThat(pool(result, "Saws").busyUnitSeconds()).isEqualTo(2_000);
            assertThat(result.summary().waitingWorkingSeconds()).isZero();
        }

        @Test
        void aStageWithoutRequirementsStartsEverythingAtOnce() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            factory.stage("INTAKE", "3600", 0);
            for (int i = 1; i <= 5; i++) {
                factory.job("ORD-" + i, "100");
            }

            SimulationResult result = factory.run();

            assertThat(result.summary().elapsedSeconds()).isEqualTo(100);
            assertThat(result.stages().get(0).maxQueueLength()).isZero();
        }

        @Test
        void everyUnitIsReleasedByTheEnd() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 2, "100");
            UUID cutters = factory.employees("Cutters", 3, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1), needs(cutters, 1));
            factory.stage("BRUTING", "3600", 0, needs(cutters, 2));
            for (int i = 1; i <= 6; i++) {
                factory.job("ORD-" + i, String.valueOf(100 * i));
            }

            SimulationResult result = factory.run();

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
            assertThat(result.unfinished()).isEmpty();
            assertThat(heldAtEnd(result)).isZero();
            assertThat(result.stages()).allSatisfy(stage -> assertThat(stage.jobsCompleted()).isEqualTo(6));
        }

        @Test
        void aCapacityIncreaseDuringTheRunLetsWaitingWorkStart() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.window(saws, 400, 100_000, 1);
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");
            UUID second = factory.job("ORD-2", "1000");

            SimulationResult result = factory.run();

            assertThat(timeOf(result, TraceType.JOB_STARTED, second)).isEqualTo(400);
            assertThat(result.endTime()).isEqualTo(1_400);
        }
    }

    @Nested
    class DispatchOrder {

        @Test
        void higherPriorityThenEarlierDueThenLongerWaitingThenBatchId() {
            InputBuilder factory = InputBuilder.alwaysOpen().forkAt(10_000);
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID lowPriority = factory.queued(factory.order("LOW", 1, 20_000L), 1, "10");
            UUID noDueDate = factory.queued(factory.order("NO-DUE", 3, null), 1, "10");
            UUID dueLater = factory.queued(factory.order("DUE-LATER", 3, 90_000L), 1, "10");
            UUID dueSooner = factory.queued(factory.order("DUE-SOONER", 3, 50_000L), 1, "10");
            UUID urgent = factory.queued(factory.order("URGENT", 5, null), 1, "10");
            UUID sameDueWaitedLonger = factory.queuedSince(factory.order("WAITED", 3, 90_000L), 1, "10", 5_000);
            UUID twinA = factory.queued(factory.order("TWIN-A", 2, 70_000L), 1, "10");
            UUID twinB = factory.queued(factory.order("TWIN-B", 2, 70_000L), 1, "10");

            SimulationResult result = factory.run();

            assertThat(startOrder(result)).containsExactly(urgent, dueSooner, sameDueWaitedLonger, dueLater,
                    noDueDate, twinA, twinB, lowPriority);
        }

        @Test
        void whenStagesShareAPoolTheLaterStageIsServedFirst() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID bench = factory.employees("Bench workers", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(bench, 1));
            factory.stage("BRUTING", "3600", 0, needs(bench, 1));
            UUID order = factory.order("ORD-1");
            UUID upstream = factory.queued(order, 1, "100");
            UUID downstream = factory.queued(order, 2, "100");

            SimulationResult result = factory.run();

            assertThat(startOrder(result).subList(0, 2)).containsExactly(downstream, upstream);
        }

        @Test
        void unitsReleasedAtAnInstantAreAvailableToWorkArrivingAtThatInstant() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");
            UUID arriving = factory.arriving(factory.order("ORD-2"), 1, "500", 1_000);

            SimulationResult result = factory.run();

            assertThat(timeOf(result, TraceType.JOB_STARTED, arriving)).isEqualTo(1_000);
            assertThat(result.summary().waitingWorkingSeconds()).isZero();
            assertThat(result.endTime()).isEqualTo(1_500);
        }
    }

    @Nested
    class CapacityWindows {

        @Test
        void aPartialReductionDoesNotPauseARunningJob() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 2, "100");
            factory.window(saws, 200, 600, -1);
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");

            assertThat(factory.run().endTime()).isEqualTo(1_000);
        }

        @Test
        void fullDowntimePausesTheJobUntilThePoolReturns() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.window(saws, 200, 600, -1);
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");

            SimulationResult result = factory.run();

            // 200 s of work, 400 s of downtime, the remaining 800 s of work.
            assertThat(result.endTime()).isEqualTo(1_400);
            assertThat(result.summary().processingSeconds()).isEqualTo(1_000);
            assertThat(pool(result, "Saws").busyUnitSeconds()).isEqualTo(1_000);
            assertThat(pool(result, "Saws").availableUnitSeconds()).isEqualTo(1_000);
            assertThat(pool(result, "Saws").utilization()).isEqualByComparingTo("1");
        }

        @Test
        void aReductionBelowWhatRunningJobsHoldStopsNewStartsButNotThoseJobs() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 2, "100");
            factory.window(saws, 100, 5_000, -1);
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");
            factory.job("ORD-2", "1000");
            UUID third = factory.job("ORD-3", "1000");

            SimulationResult result = factory.run();

            // Both running jobs finish at 1000 although capacity fell to one at 100; then one slot remains.
            assertThat(timeOf(result, TraceType.JOB_STARTED, third)).isEqualTo(1_000);
            assertThat(result.endTime()).isEqualTo(2_000);
            assertThat(pool(result, "Saws").overcommitted()).isTrue();
            assertThat(pool(result, "Saws").utilization()).isEqualByComparingTo("1");
        }
    }

    @Nested
    class WorkInProgressAtTheFork {

        @Test
        void aJobAlreadyRunningKeepsItsUnitsAndOnlyItsRemainingWorkIsSimulated() {
            InputBuilder factory = InputBuilder.alwaysOpen().forkAt(10_000);
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID order = factory.order("ORD-1");
            factory.inProgress(order, 1, "1000", 9_400);
            UUID waiting = factory.queued(factory.order("ORD-2"), 1, "100");

            SimulationResult result = factory.run();

            // 600 of its 1000 s were done before the fork; 400 remain.
            assertThat(timeOf(result, TraceType.JOB_STARTED, waiting)).isEqualTo(10_400);
            assertThat(result.endTime()).isEqualTo(10_500);
            assertThat(result.summary().processingSeconds()).isEqualTo(400 + 100);
            assertThat(result.summary().waitingWorkingSeconds()).isEqualTo(400);
            assertThat(pool(result, "Saws").busyUnitSeconds()).isEqualTo(500);
        }

        @Test
        void aJobWhoseWorkWasAlreadyDoneCompletesAtTheFork() {
            InputBuilder factory = InputBuilder.alwaysOpen().forkAt(10_000);
            factory.stage("CUTTING", "3600", 0);
            factory.inProgress(factory.order("ORD-1"), 1, "1000", 5_000);

            SimulationResult result = factory.run();

            assertThat(result.endTime()).isEqualTo(10_000);
            assertThat(result.summary().processingSeconds()).isZero();
            assertThat(result.orders().get(0).completionTime()).isEqualTo(10_000);
        }

        @Test
        void workDoneBeforeTheForkIsMeasuredInWorkingTime() {
            long tuesday10am = MONDAY_9AM + DAY + HOUR;
            InputBuilder factory = InputBuilder.demoCalendar().forkAt(tuesday10am);
            factory.stage("CUTTING", "10", 0);
            // Started Monday 16:00 with ten hours of work: two on Monday, one on Tuesday so far.
            factory.inProgress(factory.order("ORD-1"), 1, "100", MONDAY_9AM + 7 * HOUR);

            SimulationResult result = factory.run();

            // Seven hours remain: 10:00 to 13:00, then 14:00 to 18:00.
            assertThat(result.endTime()).isEqualTo(tuesday10am + 8 * HOUR);
            assertThat(result.summary().processingSeconds()).isEqualTo(7 * HOUR);
        }

        @Test
        void jobsRunningAboveAReducedCapacityAreFlaggedAndUtilizationIsCapped() {
            InputBuilder factory = InputBuilder.alwaysOpen().forkAt(10_000);
            UUID saws = factory.machines("Saws", 2, "100");
            factory.window(saws, 9_000, 50_000, -1);
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID order = factory.order("ORD-1");
            factory.inProgress(order, 1, "1000", 9_500);
            factory.inProgress(order, 1, "1000", 9_500);

            PoolResult saw = pool(factory.run(), "Saws");

            assertThat(saw.overcommitted()).isTrue();
            assertThat(saw.busyUnitSeconds()).isEqualTo(1_000);
            assertThat(saw.availableUnitSeconds()).isEqualTo(500);
            assertThat(saw.utilization()).isEqualByComparingTo("1");
            assertThat(saw.idleUnitSeconds()).isZero();
        }

        @Test
        void waitingBeforeTheForkDecidesTheOrderButIsNotCountedAsSimulatedWaiting() {
            InputBuilder factory = InputBuilder.alwaysOpen().forkAt(10_000);
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID recent = factory.queuedSince(factory.order("RECENT"), 1, "100", 9_900);
            UUID old = factory.queuedSince(factory.order("OLD"), 1, "100", 2_000);

            SimulationResult result = factory.run();

            assertThat(startOrder(result)).containsExactly(old, recent);
            // Only the second job waits in the simulation, for the first one's 100 s.
            assertThat(result.summary().waitingWorkingSeconds()).isEqualTo(100);
        }

        @Test
        void aStageRecordedAsDoneIsNotSimulatedAgain() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            factory.stage("CUTTING", "3600", 0);
            factory.stage("BRUTING", "3600", 0);
            factory.stage("POLISHING", "3600", 0);
            factory.queued(factory.order("ORD-1"), 3, "100");

            SimulationResult result = factory.run();

            assertThat(result.stages()).extracting(StageResult::jobsCompleted).containsExactly(0, 0, 1);
        }
    }

    @Nested
    class Yield {

        @Test
        void yieldIsAppliedAtEachStageWithoutTruncatingStones() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            String[][] stages = {
                {"ROUGH_INTAKE", "1", "1"}, {"PLANNING", "1", "1"}, {"MARKING", "1", "1"},
                {"CUTTING", "0.985", "0.62"}, {"BRUTING", "0.995", "0.90"}, {"POLISHING", "0.99", "0.82"},
                {"QUALITY_INSPECTION", "0.97", "1"}, {"DISPATCH", "1", "1"}};
            for (String[] stage : stages) {
                factory.stage(stage[0], "3600", 0, stage[1], stage[2], "0");
            }
            factory.job("ORD-1", "100");

            SimulationResult result = factory.run();

            // 100 -> 98.5 -> 98.0075 -> 97.027425 (97.0274) -> 94.116578 (94.1166)
            assertThat(result.stages()).extracting(stage -> stage.outputStones().toPlainString()).containsExactly(
                    "100.0000", "100.0000", "100.0000", "98.5000", "98.0075", "97.0274", "94.1166", "94.1166");
            assertThat(result.summary().outputStones()).isEqualByComparingTo("94.1166");
            assertThat(result.summary().outputStonesRounded()).isEqualByComparingTo("94");
            assertThat(result.summary().stoneYield()).isEqualByComparingTo("0.941166");
            // Carats: 130 -> 80.6 -> 72.54 -> 59.4828 -> unchanged.
            assertThat(result.summary().outputCarats()).isEqualByComparingTo("59.4828");
            assertThat(result.summary().caratYield()).isEqualByComparingTo("0.457560");
        }

        @Test
        void whatEntersAStageEqualsWhatLeavesPlusWhatIsLost() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            factory.stage("CUTTING", "3600", 0, "0.985", "0.62", "0");
            factory.stage("POLISHING", "3600", 0, "0.333333", "0.777777", "0");
            factory.job("ORD-1", "97");
            factory.job("ORD-2", "13");

            for (StageResult stage : factory.run().stages()) {
                assertThat(stage.inputStones()).isEqualByComparingTo(stage.outputStones().add(stage.lossStones()));
                assertThat(stage.inputCarats()).isEqualByComparingTo(stage.outputCarats().add(stage.lossCarats()));
                assertThat(stage.lossStones().signum()).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        void theOutputOfOneStageIsTheInputOfTheNext() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            factory.stage("CUTTING", "3600", 0, "0.9", "0.6", "0");
            factory.stage("POLISHING", "3600", 0, "0.9", "0.8", "0");
            factory.job("ORD-1", "100");

            List<StageResult> stages = factory.run().stages();

            assertThat(stages.get(1).inputStones()).isEqualByComparingTo(stages.get(0).outputStones());
            assertThat(stages.get(1).inputCarats()).isEqualByComparingTo(stages.get(0).outputCarats());
            // The second stage processes 90 stones, not 100: 90 s of work.
            assertThat(stages.get(1).processingSeconds()).isEqualTo(90);
        }
    }

    @Nested
    class Metrics {

        @Test
        void utilizationCostAndThroughputOfAHandWorkedRun() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "360");
            UUID cutters = factory.employees("Cutters", 2, "100");
            factory.stage("CUTTING", "60", 600, "0.5", "0.5", "50", needs(saws, 1), needs(cutters, 1));
            factory.job("ORD-1", "120");

            SimulationResult result = factory.run();

            // One job of 7800 s. Saw: busy 7800 of 7800. Cutters: one of two busy.
            assertThat(pool(result, "Saws").utilization()).isEqualByComparingTo("1");
            assertThat(pool(result, "Cutters").busyUnitSeconds()).isEqualTo(7_800);
            assertThat(pool(result, "Cutters").availableUnitSeconds()).isEqualTo(15_600);
            assertThat(pool(result, "Cutters").idleUnitSeconds()).isEqualTo(7_800);
            assertThat(pool(result, "Cutters").utilization()).isEqualByComparingTo("0.5");
            assertThat(result.summary().machineUtilization()).isEqualByComparingTo("1");
            assertThat(result.summary().employeeUtilization()).isEqualByComparingTo("0.5");

            // Machine: 7800 busy seconds at 360 per hour. Employees: 15600 scheduled seconds at 100 per hour,
            // idle time included. Fixed: one job at 50.
            assertThat(result.summary().cost().machine()).isEqualByComparingTo("780.00");
            assertThat(result.summary().cost().employee()).isEqualByComparingTo("433.33");
            assertThat(result.summary().cost().fixed()).isEqualByComparingTo("50.00");
            assertThat(result.summary().cost().total()).isEqualByComparingTo("1263.33");
            // 60 stones come out: 1263.33 / 60.
            assertThat(result.summary().cost().perOutputStone()).isEqualByComparingTo("21.0555");
            // 60 stones in 7800 working seconds.
            assertThat(result.summary().throughputStonesPerHour()).isEqualByComparingTo("27.6923");
            assertThat(result.stages().get(0).fixedCost()).isEqualByComparingTo("50.00");
        }

        @Test
        void waitingAndQueueLengthWhenTwoJobsShareOneSlot() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "3600");
            factory.job("ORD-2", "3600");

            SimulationResult result = factory.run();
            StageResult cutting = result.stages().get(0);

            // The second job waits one hour of the two-hour run.
            assertThat(cutting.waitingWorkingSeconds()).isEqualTo(3_600);
            assertThat(cutting.meanWaitWorkingSeconds()).isEqualByComparingTo("1800");
            assertThat(cutting.meanQueueLength()).isEqualByComparingTo("0.5");
            assertThat(cutting.maxQueueLength()).isEqualTo(1);
            assertThat(cutting.bottleneck().blockedShare()).isEqualByComparingTo("0.5");
            assertThat(cutting.bottleneck().saturationSignal()).isEqualByComparingTo("0.5");
            // Queue entry to completion: 3600 and 7200.
            assertThat(cutting.meanStageSeconds()).isEqualByComparingTo("5400");
            assertThat(cutting.maxStageSeconds()).isEqualTo(7_200);
        }

        @Test
        void waitingOvernightIsCalendarWaitingNotWorkingWaiting() {
            InputBuilder factory = InputBuilder.demoCalendar();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "10", 0, needs(saws, 1));
            factory.job("ORD-1", "80");
            factory.job("ORD-2", "10");

            SimulationResult result = factory.run();

            // The second job waits Monday's eight working hours and starts Tuesday 09:00.
            assertThat(result.summary().waitingWorkingSeconds()).isEqualTo(8 * HOUR);
            assertThat(result.summary().waitingCalendarSeconds()).isEqualTo(24 * HOUR);
        }

        @Test
        void aPoolWithNoAvailableTimeHasNoUtilizationRatherThanADivisionByZero() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            UUID spare = factory.machines("Spare lathes", 0, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "100");

            SimulationResult result = factory.run();

            assertThat(spare).isNotNull();
            assertThat(pool(result, "Spare lathes").utilization()).isNull();
            assertThat(pool(result, "Spare lathes").utilizationNullReason()).isEqualTo("NO_AVAILABLE_TIME");
            assertThat(pool(result, "Saws").utilizationNullReason()).isNull();
        }

        @Test
        void ordersAreOnTimeLateOrUnfinishedAndCompleteWithTheirLastBatch() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID onTime = factory.order("ON-TIME", 5, 5_000L);
            factory.queued(onTime, 1, "1000");
            UUID twoBatches = factory.order("TWO-BATCHES", 4, 2_500L);
            factory.queued(twoBatches, 1, "1000");
            factory.queued(twoBatches, 1, "1000");
            factory.queued(factory.order("NO-DUE-DATE", 1, null), 1, "1000");

            SimulationResult result = factory.run();

            assertThat(order(result, "ON-TIME").completionTime()).isEqualTo(1_000);
            assertThat(order(result, "ON-TIME").outcome()).isEqualTo(OrderOutcome.ON_TIME);
            // Its batches finish at 2000 and 3000; the order completes with the second, after its due time.
            assertThat(order(result, "TWO-BATCHES").completionTime()).isEqualTo(3_000);
            assertThat(order(result, "TWO-BATCHES").outcome()).isEqualTo(OrderOutcome.LATE);
            assertThat(order(result, "TWO-BATCHES").delayed()).isTrue();
            assertThat(order(result, "NO-DUE-DATE").outcome()).isEqualTo(OrderOutcome.ON_TIME);
            assertThat(result.summary().ordersTotal()).isEqualTo(3);
            assertThat(result.summary().ordersCompleted()).isEqualTo(3);
            assertThat(result.summary().ordersDelayed()).isEqualTo(1);
            assertThat(result.summary().ordersWithDueDate()).isEqualTo(2);
            assertThat(result.summary().onTimeDeliveryRate()).isEqualByComparingTo("0.5");
        }
    }

    @Nested
    class Termination {

        @Test
        void aFactoryWithNoWorkProducesZeroes() {
            InputBuilder factory = InputBuilder.demoCalendar();
            UUID saws = factory.machines("Saws", 2, "100");
            factory.employees("Cutters", 3, "100");
            factory.stage("CUTTING", "10", 600, needs(saws, 1));

            SimulationResult result = factory.run();

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
            assertThat(result.endTime()).isEqualTo(result.forkTime());
            assertThat(result.summary().elapsedSeconds()).isZero();
            assertThat(result.summary().processingSeconds()).isZero();
            assertThat(result.summary().waitingWorkingSeconds()).isZero();
            assertThat(result.summary().cost().total()).isEqualByComparingTo("0");
            assertThat(result.summary().stoneYield()).isNull();
            assertThat(result.summary().throughputStonesPerHour()).isNull();
            assertThat(result.summary().machineUtilization()).isNull();
            assertThat(result.summary().onTimeDeliveryRate()).isNull();
            assertThat(result.bottleneck().primaryStageId()).isNull();
            assertThat(result.bottleneck().reasonCode()).isEqualTo(BottleneckResult.NO_WORK);
            assertThat(result.trace()).isEmpty();
        }

        @Test
        void workThatDoesNotFitInTheHorizonEndsTheRunWithWhatIsLeftListed() {
            InputBuilder factory = InputBuilder.alwaysOpen().limits(10 * HOUR, 1_000_000);
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "1", 0, needs(saws, 1));
            UUID done = factory.job("ORD-1", "2");
            UUID tooLong = factory.job("ORD-2", "100");
            UUID waiting = factory.job("ORD-3", "1");
            UUID late = factory.arriving(factory.order("ORD-4"), 1, "1", 30 * HOUR);

            SimulationResult result = factory.run();

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.HORIZON_REACHED);
            assertThat(result.endTime()).isEqualTo(2 * HOUR);
            assertThat(result.orders()).filteredOn(o -> o.outcome() == OrderOutcome.UNFINISHED).hasSize(3);
            assertThat(result.unfinished()).extracting(u -> u.batchId() + ":" + u.state()).containsExactlyInAnyOrder(
                    tooLong + ":RUNNING", waiting + ":QUEUED", late + ":NOT_ARRIVED");
            assertThat(result.unfinished()).noneMatch(u -> u.batchId().equals(done));
        }

        @Test
        void theEventLimitEndsTheRunWithoutAnError() {
            InputBuilder factory = InputBuilder.alwaysOpen().limits(100 * DAY, 4);
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            for (int i = 1; i <= 10; i++) {
                factory.job("ORD-" + i, "100");
            }

            SimulationResult result = factory.run();

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.EVENT_LIMIT_REACHED);
            assertThat(result.eventsProcessed()).isEqualTo(4);
            assertThat(result.unfinished()).isNotEmpty();
        }

        @Test
        void aRunThatFinishesIsCompletedEvenWithALeftoverWakeUpBeyondTheHorizon() {
            // The work ends Monday 18:00 exactly; the next shift start, Tuesday 09:00, lies past the horizon.
            InputBuilder factory = InputBuilder.demoCalendar().limits(10 * HOUR, 1_000_000);
            factory.stage("CUTTING", "10", 0);
            factory.job("ORD-1", "80");

            SimulationResult result = factory.run();

            assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
            assertThat(result.endTime()).isEqualTo(MONDAY_9AM + 9 * HOUR);
        }
    }

    @Nested
    class Bottleneck {

        @Test
        void aSaturatedStageWithAQueueIsThePrimaryBottleneckAndSaysWhy() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 4, "100");
            UUID wheels = factory.machines("Wheels", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            UUID polishing = factory.stage("POLISHING", "360", 0, needs(wheels, 1));
            for (int i = 1; i <= 8; i++) {
                factory.job("ORD-" + i, "100");
            }

            SimulationResult result = factory.run();
            StageResult stage = result.stages().get(1);

            assertThat(result.bottleneck().primaryStageId()).isEqualTo(polishing);
            assertThat(result.bottleneck().reasonCode()).isEqualTo(BottleneckResult.CONSTRAINED_POOL_WITH_QUEUE);
            assertThat(stage.bottleneck().limitingPoolId()).isEqualTo(wheels);
            assertThat(stage.bottleneck().limitingPoolUtilization()).isGreaterThan(new java.math.BigDecimal("0.85"));
            assertThat(stage.bottleneck().saturationSignal()).isGreaterThan(new java.math.BigDecimal("0.8"));
            assertThat(stage.bottleneck().waitSignal()).isEqualByComparingTo("1");
            assertThat(stage.bottleneck().queueSignal()).isEqualByComparingTo("1");
            assertThat(stage.bottleneck().score()).isGreaterThan(result.stages().get(0).bottleneck().score());
            assertThat(stage.maxQueueLength()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void aSlowStageWithSpareCapacityAndNoQueueIsNotABottleneck() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            UUID wheels = factory.machines("Wheels", 10, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            // Polishing takes ten times as long per job, but there is a wheel for everyone.
            UUID polishing = factory.stage("POLISHING", "360", 0, needs(wheels, 1));
            for (int i = 1; i <= 4; i++) {
                factory.job("ORD-" + i, "100");
            }

            SimulationResult result = factory.run();

            assertThat(result.bottleneck().primaryStageId()).isNotEqualTo(polishing);
            assertThat(result.stages().get(1).processingSeconds())
                    .isGreaterThan(result.stages().get(0).processingSeconds());
            assertThat(result.stages().get(1).maxQueueLength()).isZero();
            assertThat(result.stages().get(1).bottleneck().score()).isLessThan(new java.math.BigDecimal("0.5"));
        }

        @Test
        void aBriefQueueAtAStageThatIsMostlyUncontendedIsNotABottleneck() {
            InputBuilder factory = InputBuilder.alwaysOpen();
            UUID saws = factory.machines("Saws", 1, "100");
            factory.stage("CUTTING", "3600", 0, needs(saws, 1));
            factory.job("ORD-1", "1000");
            factory.job("ORD-2", "1000");
            // A third order arrives long after the queue has cleared, stretching the window.
            factory.arriving(factory.order("ORD-3"), 1, "1000", 20_000);

            SimulationResult result = factory.run();

            // The stage was active for 3000 s and work waited for 1000 of them: a third, below the half
            // that a sustained queue needs. It still has the longest wait of any stage, being the only one.
            assertThat(result.bottleneck().primaryStageId()).isNull();
            assertThat(result.bottleneck().reasonCode()).isEqualTo(BottleneckResult.NO_SUSTAINED_QUEUE);
            assertThat(result.stages().get(0).bottleneck().saturationSignal()).isEqualByComparingTo("0.333333");
            assertThat(result.stages().get(0).bottleneck().waitSignal()).isEqualByComparingTo("1");
            assertThat(pool(result, "Saws").utilization()).isLessThan(new java.math.BigDecimal("0.2"));
        }
    }

    @Nested
    class Determinism {

        @Test
        void theSameInputGivesAnEqualResult() {
            SimulationInput input = mixedFactory().build();

            assertThat(new SimulationEngine().run(input)).isEqualTo(new SimulationEngine().run(input));
        }

        @Test
        void theOrderOfTheInputListsDoesNotMatter() {
            SimulationInput input = mixedFactory().build();
            SimulationInput shuffled = new SimulationInput(input.forkTime(), input.calendar(),
                    reversed(input.stages()), reversed(input.pools()), reversed(input.orders()),
                    reversed(input.batches()), input.limits(), input.bottleneck());

            assertThat(new SimulationEngine().run(shuffled)).isEqualTo(new SimulationEngine().run(input));
        }

        @Test
        void theResultCarriesTheAlgorithmVersion() {
            assertThat(mixedFactory().run().algorithmVersion()).isEqualTo(SimulationEngine.ALGORITHM_VERSION);
        }

        private InputBuilder mixedFactory() {
            InputBuilder factory = InputBuilder.demoCalendar();
            UUID saws = factory.machines("Saws", 2, "300");
            UUID wheels = factory.machines("Wheels", 2, "140");
            UUID workers = factory.employees("Bench workers", 5, "230");
            factory.window(wheels, MONDAY_9AM + 2 * DAY, MONDAY_9AM + 2 * DAY + 9 * HOUR, -1);
            factory.stage("CUTTING", "12", 1200, "0.985", "0.62", "200", needs(saws, 1), needs(workers, 1));
            factory.stage("POLISHING", "6", 900, "0.99", "0.82", "150", needs(wheels, 1), needs(workers, 2));
            for (int i = 1; i <= 9; i++) {
                UUID order = factory.order("ORD-" + i, 1 + i % 5, MONDAY_9AM + (3 + i) * DAY);
                factory.queued(order, 1, String.valueOf(40 + 7 * i));
                if (i % 3 == 0) {
                    factory.arriving(order, 1, String.valueOf(30 + i), MONDAY_9AM + i * 5 * HOUR);
                }
            }
            return factory;
        }

        private static <T> List<T> reversed(List<T> list) {
            List<T> copy = new ArrayList<>(list);
            Collections.reverse(copy);
            return copy;
        }
    }

    // ---- helpers ----

    private static PoolResult pool(SimulationResult result, String name) {
        return result.pools().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static SimulationResult.OrderResult order(SimulationResult result, String number) {
        return result.orders().stream().filter(o -> o.orderNumber().equals(number)).findFirst().orElseThrow();
    }

    private static long timeOf(SimulationResult result, TraceType type, UUID batchId) {
        return result.trace().stream()
                .filter(event -> event.type() == type && batchId.equals(event.batchId()))
                .findFirst().orElseThrow().time();
    }

    /** Batches in the order their jobs started. */
    private static List<UUID> startOrder(SimulationResult result) {
        return result.trace().stream().filter(event -> event.type() == TraceType.JOB_STARTED)
                .map(TraceEvent::batchId).toList();
    }

    /** Net units held after replaying every start and completion in the trace: zero when all were released. */
    private static long heldAtEnd(SimulationResult result) {
        long held = 0;
        for (TraceEvent event : result.trace()) {
            if (event.type() == TraceType.JOB_STARTED) {
                held++;
            } else if (event.type() == TraceType.JOB_COMPLETED) {
                held--;
            }
        }
        return held;
    }
}

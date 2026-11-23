package com.realitycheck.production.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bulk import of a factory's own orders and packet movements, as read from two CSV files. Rows are sent
 * as the files hold them; {@code row} is the line number in the file, so every problem can point at it.
 */
public final class ImportDtos {

    public static final int MAX_ORDER_ROWS = 5_000;
    public static final int MAX_MOVEMENT_ROWS = 20_000;

    private ImportDtos() {
    }

    /**
     * @param dryRun when true, everything is checked and counted exactly as a real import, then rolled back
     */
    public record ImportRequest(
            UUID factoryId,
            boolean dryRun,
            @NotNull @Size(min = 1, max = MAX_ORDER_ROWS) List<@NotNull @Valid OrderRow> orders,
            @Size(max = MAX_MOVEMENT_ROWS) List<@NotNull @Valid MovementRow> movements) {
    }

    /**
     * One packet of an order. Rows of the same order repeat its order-level fields.
     *
     * @param packet       the packet's label within the order, matched by the movements file; blank to
     *                     number packets in the order they appear
     * @param dispatchedAt when the finished order left the factory; blank if it has not
     */
    public record OrderRow(
            int row,
            @Size(max = 40) String orderNumber,
            @Size(max = 120) String customerRef,
            Integer priority,
            Instant placedAt,
            Instant dueAt,
            Instant dispatchedAt,
            @Size(max = 40) String packet,
            Integer stones,
            BigDecimal carats,
            @Size(max = 40) String route) {
    }

    /**
     * One visit of one packet to one stage.
     *
     * @param stage       a stage code or name of the active workflow
     * @param completedAt blank while the packet is still at the stage
     * @param stonesOut   what left the stage; blank for no loss
     */
    public record MovementRow(
            int row,
            @Size(max = 40) String orderNumber,
            @Size(max = 40) String packet,
            @Size(max = 120) String stage,
            Instant startedAt,
            Instant completedAt,
            BigDecimal stonesOut,
            BigDecimal caratsOut) {
    }

    /**
     * What the import created, or would create on a dry run. When {@code error} is present nothing was saved.
     *
     * @param firstEventAt the earliest recorded moment among the imported rows; null when there are none
     */
    public record ImportResult(
            boolean dryRun,
            boolean saved,
            int orders,
            int packets,
            int movements,
            int dispatched,
            Instant firstEventAt,
            Instant lastEventAt,
            ImportError error) {
    }

    /** @param file {@code orders} or {@code movements}; {@code row} is the line in that file */
    public record ImportError(String file, int row, String message) {
    }
}

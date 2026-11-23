package com.realitycheck.production.service;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.production.api.ImportDtos.ImportError;
import com.realitycheck.production.api.ImportDtos.ImportRequest;
import com.realitycheck.production.api.ImportDtos.ImportResult;
import com.realitycheck.production.api.ImportDtos.MovementRow;
import com.realitycheck.production.api.ImportDtos.OrderRow;
import com.realitycheck.production.api.ProductionDtos.AppendEventRequest;
import com.realitycheck.production.api.ProductionDtos.CreateBatchRequest;
import com.realitycheck.production.api.ProductionDtos.CreateOrderRequest;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports a factory's own orders and packet movements in one transaction, through the same services that
 * {@code POST /api/orders} and {@code POST /api/events} use, so every rule of the live API applies. The
 * first row that breaks a rule stops the import and nothing is kept. A dry run does all the same work and
 * then rolls it back, so its answer is exactly what a real import would do.
 */
@Service
public class ImportService {

    static final String ORDERS = "orders";
    static final String MOVEMENTS = "movements";

    private static final int DEFAULT_PRIORITY = 3;
    private static final Pattern BATCH_FIELD = Pattern.compile("batches\\[(\\d+)]\\.(\\w+)");
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("orderNumber", "order_number"), Map.entry("customerRef", "customer_ref"),
            Map.entry("priority", "priority"), Map.entry("placedAt", "placed_at"), Map.entry("dueAt", "due_at"),
            Map.entry("batches", "packets"), Map.entry("stoneCount", "stones"), Map.entry("roughCarats", "carats"),
            Map.entry("routeCode", "route"), Map.entry("stoneQuantity", "stones_out"),
            Map.entry("caratQuantity", "carats_out"), Map.entry("occurredAt", "time"),
            Map.entry("nextStageId", "stage"));

    private final OrderService orderService;
    private final EventAppendService eventAppendService;
    private final ProductionBatchRepository batches;
    private final FactoryResolver factoryResolver;
    private final WorkflowReader workflowReader;
    private final Validator validator;
    private final TransactionTemplate transactions;

    public ImportService(OrderService orderService, EventAppendService eventAppendService,
            ProductionBatchRepository batches, FactoryResolver factoryResolver, WorkflowReader workflowReader,
            Validator validator, TransactionTemplate transactions) {
        this.orderService = orderService;
        this.eventAppendService = eventAppendService;
        this.batches = batches;
        this.factoryResolver = factoryResolver;
        this.workflowReader = workflowReader;
        this.validator = validator;
        this.transactions = transactions;
    }

    public ImportResult importRows(ImportRequest request) {
        return transactions.execute(status -> {
            Counts counts = new Counts();
            try {
                run(request, counts);
            } catch (RowException ex) {
                // A service may already have marked the transaction for rollback; marking it here as well
                // makes the rollback the expected outcome rather than an UnexpectedRollbackException.
                status.setRollbackOnly();
                return counts.result(request.dryRun(), false, new ImportError(ex.file, ex.row, ex.getMessage()));
            }
            if (request.dryRun()) {
                status.setRollbackOnly();
            }
            return counts.result(request.dryRun(), !request.dryRun(), null);
        });
    }

    // ---- The import ---------------------------------------------------------------------------

    private void run(ImportRequest request, Counts counts) {
        UUID factoryId = factoryResolver.resolve(request.factoryId());
        List<StageView> stages = workflowReader.activeStages(factoryId);
        if (stages.isEmpty()) {
            throw new RowException(ORDERS, 0, "The factory has no active workflow: set one up before importing");
        }
        Map<String, OrderGroup> orders = group(request.orders());
        for (OrderGroup order : orders.values()) {
            create(factoryId, order, counts);
        }
        List<MovementRow> movements = request.movements() == null ? List.of() : request.movements();
        for (Map.Entry<Packet, List<MovementRow>> packet : byPacket(movements, orders).entrySet()) {
            move(packet.getKey(), packet.getValue(), stages, counts);
        }
        for (OrderGroup order : orders.values()) {
            Instant dispatchedAt = order.first.dispatchedAt();
            if (dispatchedAt != null) {
                attempt(ORDERS, order.first.row(), () -> eventAppendService.append(new AppendEventRequest(
                        EventType.ORDER_DISPATCHED, null, order.id, null, dispatchedAt, null, null, null, null, null,
                        null)));
                counts.dispatched++;
                counts.saw(dispatchedAt);
            }
        }
    }

    /** Rows of one order, in file order; order-level fields come from the first and must agree in the rest. */
    private static final class OrderGroup {
        final OrderRow first;
        final List<OrderRow> rows = new ArrayList<>();
        final Map<String, UUID> batchByPacket = new LinkedHashMap<>();
        UUID id;

        OrderGroup(OrderRow first) {
            this.first = first;
        }
    }

    private record Packet(OrderGroup order, String label, UUID batchId) {
    }

    private static Map<String, OrderGroup> group(List<OrderRow> rows) {
        Map<String, OrderGroup> orders = new LinkedHashMap<>();
        for (OrderRow row : rows) {
            String number = strip(row.orderNumber());
            if (number == null) {
                throw new RowException(ORDERS, row.row(), "order_number is required");
            }
            if (row.stones() == null) {
                throw new RowException(ORDERS, row.row(), "stones is required");
            }
            if (row.carats() == null) {
                throw new RowException(ORDERS, row.row(), "carats is required");
            }
            OrderGroup order = orders.computeIfAbsent(key(number), k -> new OrderGroup(row));
            if (order.first == row && row.placedAt() == null) {
                throw new RowException(ORDERS, row.row(), "placed_at is required on the first row of an order");
            }
            if (order.first != row) {
                agree(order.first, row, "customer_ref", OrderRow::customerRef);
                agree(order.first, row, "priority", OrderRow::priority);
                agree(order.first, row, "placed_at", OrderRow::placedAt);
                agree(order.first, row, "due_at", OrderRow::dueAt);
                agree(order.first, row, "dispatched_at", OrderRow::dispatchedAt);
            }
            String packet = strip(row.packet());
            String label = packet == null ? String.valueOf(order.rows.size() + 1) : packet;
            if (order.rows.stream().anyMatch(r -> key(labelOf(order, r)).equals(key(label)))) {
                throw new RowException(ORDERS, row.row(),
                        "packet " + label + " appears twice in order " + strip(order.first.orderNumber()));
            }
            order.rows.add(row);
        }
        return orders;
    }

    private static String labelOf(OrderGroup order, OrderRow row) {
        String packet = strip(row.packet());
        return packet == null ? String.valueOf(order.rows.indexOf(row) + 1) : packet;
    }

    private static <T> void agree(OrderRow first, OrderRow row, String column, Function<OrderRow, T> field) {
        T value = field.apply(row);
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value != null && !Objects.equals(value, field.apply(first))) {
            throw new RowException(ORDERS, row.row(), column + " differs from row " + first.row()
                    + " of the same order; repeat it exactly or leave it blank");
        }
    }

    private void create(UUID factoryId, OrderGroup order, Counts counts) {
        OrderRow first = order.first;
        List<CreateBatchRequest> packets = order.rows.stream()
                .map(r -> new CreateBatchRequest(r.stones(), r.carats(), strip(r.route()))).toList();
        CreateOrderRequest request = new CreateOrderRequest(factoryId, strip(first.orderNumber()),
                strip(first.customerRef()), first.priority() == null ? DEFAULT_PRIORITY : first.priority(),
                first.dueAt(), first.placedAt(), packets);
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            ConstraintViolation<CreateOrderRequest> violation = violations.stream()
                    .min(Comparator.comparing(v -> v.getPropertyPath().toString())).orElseThrow();
            String path = violation.getPropertyPath().toString();
            throw new RowException(ORDERS, rowOf(order, path), column(path) + " " + violation.getMessage());
        }
        order.id = attempt(ORDERS, first.row(), () -> orderService.create(request)).id();
        List<ProductionBatch> created = batches.findByOrderIdOrderByBatchNumberAsc(order.id);
        for (int i = 0; i < created.size(); i++) {
            order.batchByPacket.put(key(labelOf(order, order.rows.get(i))), created.get(i).getId());
        }
        counts.orders++;
        counts.packets += created.size();
        counts.saw(first.placedAt());
    }

    private static int rowOf(OrderGroup order, String path) {
        Matcher batch = BATCH_FIELD.matcher(path);
        if (batch.find()) {
            int index = Integer.parseInt(batch.group(1));
            if (index < order.rows.size()) {
                return order.rows.get(index).row();
            }
        }
        return order.first.row();
    }

    private static Map<Packet, List<MovementRow>> byPacket(List<MovementRow> rows, Map<String, OrderGroup> orders) {
        Map<Packet, List<MovementRow>> packets = new LinkedHashMap<>();
        for (MovementRow row : rows) {
            String number = strip(row.orderNumber());
            if (number == null) {
                throw new RowException(MOVEMENTS, row.row(), "order_number is required");
            }
            OrderGroup order = orders.get(key(number));
            if (order == null) {
                throw new RowException(MOVEMENTS, row.row(), "order " + number + " is not in the orders file");
            }
            String label = strip(row.packet());
            if (label == null) {
                if (order.batchByPacket.size() != 1) {
                    throw new RowException(MOVEMENTS, row.row(), "packet is required: order " + number + " has "
                            + order.batchByPacket.size() + " packets");
                }
                label = order.batchByPacket.keySet().iterator().next();
            }
            UUID batchId = order.batchByPacket.get(key(label));
            if (batchId == null) {
                throw new RowException(MOVEMENTS, row.row(),
                        "order " + number + " has no packet " + label + " in the orders file");
            }
            packets.computeIfAbsent(new Packet(order, key(label), batchId), p -> new ArrayList<>()).add(row);
        }
        return packets;
    }

    /** Replays one packet's visits in time order: a start, then a completion that queues it at its next visit's stage. */
    private void move(Packet packet, List<MovementRow> rows, List<StageView> stages, Counts counts) {
        for (MovementRow row : rows) {
            if (row.startedAt() == null) {
                throw new RowException(MOVEMENTS, row.row(), "started_at is required");
            }
            if (row.completedAt() != null && row.completedAt().isBefore(row.startedAt())) {
                throw new RowException(MOVEMENTS, row.row(), "completed_at is before started_at");
            }
        }
        List<MovementRow> visits = rows.stream()
                .sorted(Comparator.comparing(MovementRow::startedAt).thenComparingInt(MovementRow::row)).toList();
        for (int i = 0; i < visits.size(); i++) {
            MovementRow row = visits.get(i);
            MovementRow next = i + 1 < visits.size() ? visits.get(i + 1) : null;
            StageView stage = stage(stages, row);
            if (row.completedAt() == null && next != null) {
                throw new RowException(MOVEMENTS, row.row(), "completed_at is required: the packet moves on to "
                        + strip(next.stage()) + " at " + next.startedAt());
            }
            attempt(MOVEMENTS, row.row(), () -> eventAppendService.append(new AppendEventRequest(
                    EventType.STAGE_STARTED, packet.batchId, null, stage.id(), row.startedAt(), null, null, null, null,
                    null, null)));
            counts.movements++;
            counts.saw(row.startedAt());
            if (row.completedAt() == null) {
                continue;
            }
            ProductionBatch batch = batches.findById(packet.batchId).orElseThrow();
            BigDecimal stones = row.stonesOut() == null ? batch.getCurrentStones() : row.stonesOut();
            BigDecimal carats = row.caratsOut() == null ? batch.getCurrentCarats() : row.caratsOut();
            UUID nextStage = next == null ? null : stage(stages, next).id();
            try {
                eventAppendService.append(new AppendEventRequest(EventType.STAGE_COMPLETED, packet.batchId, null,
                        stage.id(), row.completedAt(), stones, carats, null, null, null, null, nextStage));
            } catch (ApiException ex) {
                String message = message(ex);
                if (next != null && message.contains("cannot be queued at that stage")) {
                    message = strip(next.stage()) + " cannot follow " + strip(row.stage())
                            + " on this packet's route (a required stage would be skipped)";
                }
                throw new RowException(MOVEMENTS, row.row(), message);
            }
            counts.saw(row.completedAt());
        }
    }

    private static StageView stage(List<StageView> stages, MovementRow row) {
        String wanted = strip(row.stage());
        if (wanted == null) {
            throw new RowException(MOVEMENTS, row.row(), "stage is required");
        }
        return stages.stream().filter(s -> s.code().equalsIgnoreCase(wanted)).findFirst()
                .or(() -> stages.stream().filter(s -> s.name().equalsIgnoreCase(wanted)).findFirst())
                .orElseThrow(() -> new RowException(MOVEMENTS, row.row(), "stage " + wanted
                        + " is not in the active workflow; use one of " + stages.stream().map(StageView::code)
                        .collect(Collectors.joining(", "))));
    }

    // ---- Errors and counting --------------------------------------------------------------------

    /** A rule broken by one row of one file. */
    static final class RowException extends RuntimeException {
        final String file;
        final int row;

        RowException(String file, int row, String message) {
            super(message);
            this.file = file;
            this.row = row;
        }
    }

    private static <T> T attempt(String file, int row, Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException ex) {
            throw new RowException(file, row, message(ex));
        }
    }

    private static String message(ApiException ex) {
        if (ex instanceof ValidationException validation) {
            return validation.errors().stream().map(ImportService::describe).collect(Collectors.joining("; "));
        }
        return ex.getMessage();
    }

    private static String describe(FieldViolation violation) {
        return column(violation.field()) + " " + violation.message();
    }

    private static String column(String path) {
        Matcher batch = BATCH_FIELD.matcher(path);
        String field = batch.find() ? batch.group(2) : path.replaceAll("\\[\\d+]$", "");
        return COLUMNS.getOrDefault(field, field);
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String key(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static final class Counts {
        int orders;
        int packets;
        int movements;
        int dispatched;
        Instant first;
        Instant last;

        void saw(Instant at) {
            first = first == null || at.isBefore(first) ? at : first;
            last = last == null || at.isAfter(last) ? at : last;
        }

        ImportResult result(boolean dryRun, boolean saved, ImportError error) {
            return error != null
                    ? new ImportResult(dryRun, false, 0, 0, 0, 0, null, null, error)
                    : new ImportResult(dryRun, saved, orders, packets, movements, dispatched, first, last, null);
        }
    }
}

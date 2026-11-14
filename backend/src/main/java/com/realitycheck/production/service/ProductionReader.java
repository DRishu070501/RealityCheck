package com.realitycheck.production.service;

import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.domain.ProductionOrder;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.production.persistence.ProductionOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to orders for other modules, as plain records rather than entities. */
@Service
@Transactional(readOnly = true)
public class ProductionReader {

    /** @param dueAt null when the order has no due date */
    public record OrderView(UUID id, String orderNumber, int priority, Instant placedAt, Instant dueAt,
            List<BatchView> batches) {
    }

    /** What a batch started with. */
    public record BatchView(UUID id, int batchNumber, int stoneCount, BigDecimal roughCarats) {
    }

    private final ProductionOrderRepository orders;
    private final ProductionBatchRepository batches;

    public ProductionReader(ProductionOrderRepository orders, ProductionBatchRepository batches) {
        this.orders = orders;
        this.batches = batches;
    }

    /** @return the orders that exist among the ids, keyed by id */
    public Map<UUID, OrderView> byIds(Collection<UUID> ids) {
        return toViews(orders.findAllById(ids)).stream().collect(Collectors.toMap(OrderView::id, order -> order));
    }

    /** @return orders placed in {@code (after, until]}, in placement order */
    public List<OrderView> placedBetween(UUID factoryId, Instant after, Instant until) {
        return toViews(orders
                .findByFactoryIdAndPlacedAtGreaterThanAndPlacedAtLessThanEqualOrderByPlacedAtAscIdAsc(factoryId,
                        after, until));
    }

    private List<OrderView> toViews(List<ProductionOrder> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ProductionBatch>> byOrder = batches
                .findByOrderIdInOrderByOrderIdAscBatchNumberAsc(found.stream().map(ProductionOrder::getId).toList())
                .stream().collect(Collectors.groupingBy(ProductionBatch::getOrderId));
        return found.stream()
                .map(order -> new OrderView(order.getId(), order.getOrderNumber(), order.getPriority(),
                        order.getPlacedAt(), order.getDueAt(),
                        byOrder.getOrDefault(order.getId(), List.of()).stream()
                                .map(batch -> new BatchView(batch.getId(), batch.getBatchNumber(),
                                        batch.getStoneCount(), batch.getRoughCarats()))
                                .toList()))
                .toList();
    }
}

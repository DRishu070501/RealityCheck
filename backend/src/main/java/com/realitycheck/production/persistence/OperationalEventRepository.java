package com.realitycheck.production.persistence;

import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface OperationalEventRepository
        extends JpaRepository<OperationalEvent, UUID>, JpaSpecificationExecutor<OperationalEvent> {

    /** Everything recorded for a batch, corrections included, in recording order. */
    List<OperationalEvent> findByBatchIdOrderBySeqAsc(UUID batchId);

    /** Everything recorded for an order and its batches, corrections included, in recording order. */
    List<OperationalEvent> findByOrderIdOrderBySeqAsc(UUID orderId);

    /** Corrections that target any of the given events, in recording order. */
    List<OperationalEvent> findByCorrectsEventIdInOrderBySeqAsc(Collection<UUID> targetIds);

    /**
     * What is needed to rebuild the factory's state at an instant: every event that had occurred
     * by then, and every correction whenever it was recorded, because a correction fixes what is
     * known about the past.
     */
    @Query("""
            select e from OperationalEvent e
            where e.factoryId = :factoryId
              and (e.occurredAt <= :at or e.eventType = com.realitycheck.production.domain.EventType.CORRECTION)
            order by e.seq""")
    List<OperationalEvent> findForStateAt(UUID factoryId, Instant at);

    List<OperationalEvent> findByFactoryIdAndEventTypeAndOccurredAtGreaterThanEqualOrderBySeqAsc(UUID factoryId,
            EventType eventType, Instant from);

    boolean existsByCorrectsEventIdAndVoidsTrue(UUID targetId);

    /** When the latest event of a type at a stage occurred for an order; null if there is none. */
    @Query("""
            select max(e.occurredAt) from OperationalEvent e
            where e.orderId = :orderId and e.eventType = :type and e.stageId = :stageId""")
    Instant findLatestOccurrence(UUID orderId, EventType type, UUID stageId);

    @Query("select min(e.occurredAt) from OperationalEvent e where e.factoryId = :factoryId")
    Instant findFirstOccurrence(UUID factoryId);

    @Query("select max(e.occurredAt) from OperationalEvent e where e.factoryId = :factoryId")
    Instant findLastOccurrence(UUID factoryId);
}

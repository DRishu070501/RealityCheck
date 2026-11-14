package com.realitycheck.production.persistence;

import com.realitycheck.production.domain.ProductionBatch;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, UUID> {

    List<ProductionBatch> findByOrderIdOrderByBatchNumberAsc(UUID orderId);

    List<ProductionBatch> findByOrderIdInOrderByOrderIdAscBatchNumberAsc(Collection<UUID> orderIds);

    /** Serialises concurrent events for one batch, so its state machine cannot be raced. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ProductionBatch b where b.id = :id")
    Optional<ProductionBatch> findForUpdate(UUID id);
}

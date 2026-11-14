package com.realitycheck.production.persistence;

import com.realitycheck.production.domain.ProductionOrder;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductionOrderRepository
        extends JpaRepository<ProductionOrder, UUID>, JpaSpecificationExecutor<ProductionOrder> {

    boolean existsByFactoryIdAndOrderNumberIgnoreCase(UUID factoryId, String orderNumber);

    long countByFactoryId(UUID factoryId);

    List<ProductionOrder> findByFactoryId(UUID factoryId);

    /** Orders placed at or before an instant, in placement order. */
    List<ProductionOrder> findByFactoryIdAndPlacedAtLessThanEqualOrderByPlacedAtAscIdAsc(UUID factoryId,
            Instant at);

    /** Serialises concurrent changes to one order's status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ProductionOrder o where o.id = :id")
    Optional<ProductionOrder> findForUpdate(UUID id);
}

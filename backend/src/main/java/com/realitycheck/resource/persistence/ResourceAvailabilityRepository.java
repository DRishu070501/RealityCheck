package com.realitycheck.resource.persistence;

import com.realitycheck.resource.domain.ResourceAvailability;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ResourceAvailabilityRepository extends JpaRepository<ResourceAvailability, UUID> {

    List<ResourceAvailability> findByResourceIdOrderByStartsAtAscIdAsc(UUID resourceId);

    /** Windows that intersect the half-open range {@code [from, to)}. */
    @Query("""
            select a from ResourceAvailability a
            where a.resourceId = :resourceId and a.startsAt < :to and a.endsAt > :from
            order by a.startsAt, a.id""")
    List<ResourceAvailability> findOverlapping(UUID resourceId, Instant from, Instant to);
}

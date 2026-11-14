package com.realitycheck.resource.service;

import com.realitycheck.factory.service.FactoryCountProvider;
import com.realitycheck.resource.domain.Resource;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.persistence.ResourceAvailabilityRepository;
import com.realitycheck.resource.persistence.ResourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to pools for other modules, as plain records rather than entities. */
@Service
@Transactional(readOnly = true)
public class ResourceReader implements FactoryCountProvider {

    public record ResourceView(UUID id, UUID factoryId, ResourceKind kind, String category, String name,
            int capacity, BigDecimal hourlyCost, ResourceStatus status) {
    }

    private final ResourceRepository resources;
    private final ResourceAvailabilityRepository availabilities;

    public ResourceReader(ResourceRepository resources, ResourceAvailabilityRepository availabilities) {
        this.resources = resources;
        this.availabilities = availabilities;
    }

    /** @return the pools that exist among the given ids, keyed by id; unknown ids are absent */
    public Map<UUID, ResourceView> byIds(Collection<UUID> ids) {
        Map<UUID, ResourceView> views = new LinkedHashMap<>();
        for (Resource resource : resources.findAllById(ids)) {
            views.put(resource.getId(), toView(resource));
        }
        return views;
    }

    /** @return every pool of the factory, machines first, then employees, each by name */
    public List<ResourceView> forFactory(UUID factoryId) {
        return resources.findByFactoryIdOrderByKindDescNameAscIdAsc(factoryId).stream()
                .map(ResourceReader::toView)
                .toList();
    }

    /** @return windows of a pool that intersect {@code [from, to)}, ordered by start */
    public List<CapacityTimeline.Window> windows(UUID resourceId, Instant from, Instant to) {
        return availabilities.findOverlapping(resourceId, from, to).stream()
                .map(a -> new CapacityTimeline.Window(a.getStartsAt(), a.getEndsAt(), a.getCapacityDelta()))
                .toList();
    }

    @Override
    public String key() {
        return "resources";
    }

    @Override
    public long count(UUID factoryId) {
        return resources.countByFactoryId(factoryId);
    }

    private static ResourceView toView(Resource r) {
        return new ResourceView(r.getId(), r.getFactoryId(), r.getKind(), r.getCategory(), r.getName(),
                r.getCapacity(), r.getHourlyCost(), r.getStatus());
    }
}

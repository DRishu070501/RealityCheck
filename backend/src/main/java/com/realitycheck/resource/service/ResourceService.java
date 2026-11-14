package com.realitycheck.resource.service;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.resource.api.ResourceDtos.AvailabilityResponse;
import com.realitycheck.resource.api.ResourceDtos.CapacitySegment;
import com.realitycheck.resource.api.ResourceDtos.CapacityTimelineResponse;
import com.realitycheck.resource.api.ResourceDtos.CreateAvailabilityRequest;
import com.realitycheck.resource.api.ResourceDtos.CreateResourceRequest;
import com.realitycheck.resource.api.ResourceDtos.RequiredByStage;
import com.realitycheck.resource.api.ResourceDtos.ResourceResponse;
import com.realitycheck.resource.api.ResourceDtos.UpdateResourceRequest;
import com.realitycheck.resource.domain.Resource;
import com.realitycheck.resource.domain.ResourceAvailability;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.persistence.ResourceAvailabilityRepository;
import com.realitycheck.resource.persistence.ResourceRepository;
import com.realitycheck.resource.service.RequirementUsage.StageUsage;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {

    private final ResourceRepository resources;
    private final ResourceAvailabilityRepository availabilities;
    private final FactoryResolver factoryResolver;
    private final RequirementUsage requirementUsage;
    private final Clock clock;

    public ResourceService(ResourceRepository resources, ResourceAvailabilityRepository availabilities,
            FactoryResolver factoryResolver, RequirementUsage requirementUsage, Clock clock) {
        this.resources = resources;
        this.availabilities = availabilities;
        this.factoryResolver = factoryResolver;
        this.requirementUsage = requirementUsage;
        this.clock = clock;
    }

    @Transactional
    public ResourceResponse create(CreateResourceRequest request) {
        return create(request, Ids.random(), clock.instant());
    }

    /** Creation with a caller-chosen id and timestamp; the demonstration seed uses fixed values. */
    @Transactional
    public ResourceResponse create(CreateResourceRequest request, UUID id, Instant now) {
        UUID factoryId = factoryResolver.resolve(request.factoryId());
        String name = request.name().strip();
        if (resources.existsByFactoryIdAndNameIgnoreCase(factoryId, name)) {
            throw ApiException.conflict("A resource named \"" + name + "\" already exists in this factory");
        }
        Resource resource = new Resource(id, now, factoryId, request.kind(), request.category(), name,
                request.capacity(), request.hourlyCost().setScale(2, RoundingMode.UNNECESSARY));
        return toResponse(resources.saveAndFlush(resource), List.of());
    }

    @Transactional
    public ResourceResponse update(UUID id, UpdateResourceRequest request) {
        Resource resource = find(id);
        if (resource.getVersion() != request.version()) {
            throw new OptimisticLockingFailureException("Stale version for resource " + id);
        }
        String name = request.name().strip();
        if (resources.existsByFactoryIdAndNameIgnoreCaseAndIdNot(resource.getFactoryId(), name, id)) {
            throw ApiException.conflict("A resource named \"" + name + "\" already exists in this factory");
        }
        List<StageUsage> usages = usagesOf(id);
        if (request.status() == ResourceStatus.INACTIVE && !usages.isEmpty()) {
            throw ApiException.conflict("This pool is required by stage " + usages.get(0).stageName()
                    + " of the active workflow and cannot be deactivated");
        }
        resource.update(name, request.category(), request.capacity(),
                request.hourlyCost().setScale(2, RoundingMode.UNNECESSARY), request.status(), clock.instant());
        return toResponse(resources.saveAndFlush(resource), usages);
    }

    @Transactional(readOnly = true)
    public ResourceResponse get(UUID id) {
        return toResponse(find(id), usagesOf(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ResourceResponse> list(UUID factoryIdOrNull, ResourceKind kindOrNull, Pageable pageable) {
        UUID factoryId = factoryResolver.resolve(factoryIdOrNull);
        Page<Resource> page = kindOrNull == null
                ? resources.findByFactoryId(factoryId, pageable)
                : resources.findByFactoryIdAndKind(factoryId, kindOrNull, pageable);
        Map<UUID, List<StageUsage>> usages = requirementUsage.stagesRequiring(
                page.getContent().stream().map(Resource::getId).toList());
        return PageResponse.from(page, r -> toResponse(r, usages.getOrDefault(r.getId(), List.of())));
    }

    @Transactional
    public AvailabilityResponse addAvailability(UUID resourceId, CreateAvailabilityRequest request) {
        return addAvailability(resourceId, request, Ids.random(), clock.instant());
    }

    @Transactional
    public AvailabilityResponse addAvailability(UUID resourceId, CreateAvailabilityRequest request, UUID id,
            Instant now) {
        find(resourceId);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ValidationException("endsAt", "must be after startsAt");
        }
        if (request.capacityDelta() == 0) {
            throw new ValidationException("capacityDelta", "must not be zero");
        }
        ResourceAvailability window = new ResourceAvailability(id, now, resourceId, request.startsAt(),
                request.endsAt(), request.capacityDelta(), request.reason().strip());
        return toResponse(availabilities.saveAndFlush(window));
    }

    /**
     * Windows and effective capacity of a pool. With no range given, the range spans all of the
     * pool's windows; with no windows either, the timeline is empty.
     */
    @Transactional(readOnly = true)
    public CapacityTimelineResponse availability(UUID resourceId, Instant fromOrNull, Instant toOrNull) {
        Resource resource = find(resourceId);
        if (fromOrNull != null && toOrNull != null && !toOrNull.isAfter(fromOrNull)) {
            throw new ValidationException("to", "must be after from");
        }
        List<ResourceAvailability> all = availabilities.findByResourceIdOrderByStartsAtAscIdAsc(resourceId);
        Instant from = fromOrNull != null ? fromOrNull
                : all.stream().map(ResourceAvailability::getStartsAt).min(Instant::compareTo).orElse(null);
        Instant to = toOrNull != null ? toOrNull
                : all.stream().map(ResourceAvailability::getEndsAt).max(Instant::compareTo).orElse(null);
        if (from == null || to == null || !to.isAfter(from)) {
            return new CapacityTimelineResponse(resourceId, resource.getCapacity(), fromOrNull, toOrNull,
                    List.of(), List.of());
        }
        List<ResourceAvailability> inRange = all.stream()
                .filter(a -> a.getStartsAt().isBefore(to) && a.getEndsAt().isAfter(from))
                .toList();
        List<CapacityTimeline.Window> windows = inRange.stream()
                .map(a -> new CapacityTimeline.Window(a.getStartsAt(), a.getEndsAt(), a.getCapacityDelta()))
                .toList();
        List<CapacitySegment> timeline = CapacityTimeline.segments(resource.getCapacity(), windows, from, to)
                .stream()
                .map(s -> new CapacitySegment(s.from(), s.to(), s.capacity()))
                .toList();
        return new CapacityTimelineResponse(resourceId, resource.getCapacity(), from, to,
                inRange.stream().map(ResourceService::toResponse).toList(), timeline);
    }

    private Resource find(UUID id) {
        return resources.findById(id).orElseThrow(() -> ApiException.notFound("Resource", id));
    }

    private List<StageUsage> usagesOf(UUID id) {
        return requirementUsage.stagesRequiring(List.of(id)).getOrDefault(id, List.of());
    }

    private static ResourceResponse toResponse(Resource r, List<StageUsage> usages) {
        return new ResourceResponse(r.getId(), r.getFactoryId(), r.getKind(), r.getCategory(), r.getName(),
                r.getCapacity(), r.getHourlyCost(), r.getStatus(),
                usages.stream()
                        .map(u -> new RequiredByStage(u.stageId(), u.stageCode(), u.stageName(), u.sequence(),
                                u.unitsPerSlot()))
                        .toList(),
                r.getVersion(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static AvailabilityResponse toResponse(ResourceAvailability a) {
        return new AvailabilityResponse(a.getId(), a.getStartsAt(), a.getEndsAt(), a.getCapacityDelta(),
                a.getReason(), a.getCreatedAt());
    }
}

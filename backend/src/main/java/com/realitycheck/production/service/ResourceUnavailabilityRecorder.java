package com.realitycheck.production.service;

import com.realitycheck.common.domain.Ids;
import com.realitycheck.resource.service.ResourceEventSink;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Writes a {@code RESOURCE_UNAVAILABLE} event for each availability window that removes units. */
@Component
class ResourceUnavailabilityRecorder implements ResourceEventSink {

    private final EventAppendService eventAppendService;

    ResourceUnavailabilityRecorder(EventAppendService eventAppendService) {
        this.eventAppendService = eventAppendService;
    }

    @Override
    public void unitsUnavailable(UUID factoryId, UUID resourceId, UUID windowId, Instant startsAt, Instant endsAt,
            int units, String reason, Instant recordedAt) {
        eventAppendService.resourceUnavailable(factoryId, resourceId, startsAt, endsAt, units, reason,
                Ids.child(windowId, "event"), recordedAt);
    }
}

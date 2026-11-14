package com.realitycheck.resource.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Receives the fact that units of a pool became unavailable, so it can be written to the
 * operational history. Declared here and implemented by the production module, which owns that
 * history; called inside the transaction that creates the availability window.
 */
public interface ResourceEventSink {

    /**
     * @param windowId the availability window that caused it; an implementation derives its event id from this
     * @param units    how many units are unavailable (positive)
     */
    void unitsUnavailable(UUID factoryId, UUID resourceId, UUID windowId, Instant startsAt, Instant endsAt,
            int units, String reason, Instant recordedAt);
}

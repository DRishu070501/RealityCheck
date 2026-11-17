package com.realitycheck.scenario.change;

/** The decisions a scenario can express. Each has one payload record in {@link ChangePayload}. */
public enum ChangeType {

    RESOURCE_CAPACITY(ChangePayload.ResourceCapacity.class),
    RESOURCE_UNAVAILABLE(ChangePayload.ResourceUnavailable.class),
    RESOURCE_DOWNTIME(ChangePayload.ResourceDowntime.class),
    DEMAND_VOLUME(ChangePayload.DemandVolume.class),
    PROCESSING_RATE(ChangePayload.ProcessingRate.class),
    WORKING_SCHEDULE(ChangePayload.WorkingSchedule.class),
    STAGE_DELAY(ChangePayload.StageDelay.class);

    private final Class<? extends ChangePayload> payloadClass;

    ChangeType(Class<? extends ChangePayload> payloadClass) {
        this.payloadClass = payloadClass;
    }

    public Class<? extends ChangePayload> payloadClass() {
        return payloadClass;
    }
}

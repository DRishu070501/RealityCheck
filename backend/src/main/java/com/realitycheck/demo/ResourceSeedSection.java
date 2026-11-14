package com.realitycheck.demo;

import com.realitycheck.resource.api.ResourceDtos.CreateAvailabilityRequest;
import com.realitycheck.resource.api.ResourceDtos.CreateResourceRequest;
import com.realitycheck.resource.service.ResourceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fourteen pools, one per machine type and skill, and one past downtime window on the polishing wheels. */
@Component
class ResourceSeedSection implements DemoSeedSection {

    private final ResourceService resourceService;

    ResourceSeedSection(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    static String key(String category) {
        return "resource:" + category;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void seed(DemoSeedContext context) {
        for (DemoData.Pool pool : DemoData.POOLS) {
            resourceService.create(
                    new CreateResourceRequest(context.factoryId(), pool.kind(), pool.category(), pool.name(),
                            pool.capacity(), new BigDecimal(pool.hourlyCost())),
                    context.id(key(pool.category())), context.anchor());
        }

        // Wednesday of the first week, the whole working day: two of six wheels out for maintenance.
        UUID wheels = context.id(key("POLISHING_WHEEL"));
        resourceService.addAvailability(wheels,
                new CreateAvailabilityRequest(
                        context.anchorPlus(Duration.ofDays(2)),
                        context.anchorPlus(Duration.ofDays(2).plusHours(9)),
                        -2, "Scheduled wheel maintenance (synthetic)"),
                context.id("availability:POLISHING_WHEEL:maintenance-1"), context.anchor());
    }
}

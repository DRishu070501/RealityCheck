package com.realitycheck.demo;

import com.realitycheck.resource.api.ResourceDtos.CreateAvailabilityRequest;
import com.realitycheck.resource.api.ResourceDtos.CreateResourceRequest;
import com.realitycheck.resource.service.ResourceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The calibrated plan's worker and machine pools, and one past absence: two lab graders away for a day. */
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
        for (CalibratedDemoGenerator.Pool pool : CalibratedDemoGenerator.plan().pools()) {
            resourceService.create(
                    new CreateResourceRequest(context.factoryId(), pool.kind(), pool.category(), pool.name(),
                            pool.capacity(), new BigDecimal(pool.hourlyCost())),
                    context.id(key(pool.category())), context.anchor());
        }

        // Wednesday of the first week, the whole working day: two of the four lab graders away.
        UUID graders = context.id(key("LAB_GRADER"));
        resourceService.addAvailability(graders,
                new CreateAvailabilityRequest(
                        context.anchorPlus(Duration.ofDays(2)),
                        context.anchorPlus(Duration.ofDays(2).plusHours(9)),
                        -2, "Two graders on leave (synthetic)"),
                context.id("availability:LAB_GRADER:leave-1"), context.anchor());
    }
}

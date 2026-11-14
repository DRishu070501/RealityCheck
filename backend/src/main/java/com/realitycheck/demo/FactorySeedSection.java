package com.realitycheck.demo;

import com.realitycheck.factory.api.FactoryDtos.BreakRequest;
import com.realitycheck.factory.api.FactoryDtos.CalendarRequest;
import com.realitycheck.factory.api.FactoryDtos.CreateFactoryRequest;
import com.realitycheck.factory.api.FactoryDtos.ShiftRequest;
import com.realitycheck.factory.service.FactoryService;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Surat Diamond Works: Monday to Saturday, 09:00 to 18:00 with a 13:00 to 14:00 break; Sunday closed. */
@Component
class FactorySeedSection implements DemoSeedSection {

    private final FactoryService factoryService;

    FactorySeedSection(FactoryService factoryService) {
        this.factoryService = factoryService;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void seed(DemoSeedContext context) {
        List<ShiftRequest> shifts = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                shifts.add(new ShiftRequest(day.getValue(), LocalTime.of(9, 0), LocalTime.of(18, 0),
                        List.of(new BreakRequest(LocalTime.of(13, 0), LocalTime.of(14, 0)))));
            }
        }
        factoryService.create(
                new CreateFactoryRequest(DemoData.FACTORY_NAME, DemoData.FACTORY_DESCRIPTION, DemoData.TIME_ZONE,
                        DemoData.CURRENCY, new CalendarRequest(shifts)),
                context.factoryId(), context.anchor(), true);
    }
}

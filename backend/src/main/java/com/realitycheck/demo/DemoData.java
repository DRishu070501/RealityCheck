package com.realitycheck.demo;

/**
 * The demonstration factory's fixed names. Its stages, routes, pools and rates come from
 * {@link CalibratedDemoGenerator}; its orders and history from {@code ProductionSeedSection}. The factory is
 * fictional: its patterns are calibrated on one real factory, every name and every other figure is synthetic.
 * The values are tabulated in docs/demo-data.md.
 */
final class DemoData {

    static final String FACTORY_NAME = "Surat Diamond Works";
    static final String FACTORY_DESCRIPTION = "Fictional demonstration factory. Its lead times, routes and waiting "
            + "patterns are calibrated on rounded measurements from one real diamond factory; every name, "
            + "capacity, rate and cost is synthetic.";
    static final String TIME_ZONE = "Asia/Kolkata";
    static final String CURRENCY = "INR";
    static final String WORKFLOW_NAME = "Rough to polished";

    private DemoData() {
    }
}

package com.realitycheck.demo;

/**
 * One part of the demonstration data. Sections run in ascending {@link #order()} inside a single
 * transaction and create their rows through the same services the API uses, so seeded data passes
 * the same validation as user input.
 */
public interface DemoSeedSection {

    int order();

    void seed(DemoSeedContext context);
}

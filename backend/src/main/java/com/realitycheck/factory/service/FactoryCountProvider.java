package com.realitycheck.factory.service;

import java.util.UUID;

/**
 * Lets a module report how many of its things a factory has without {@code factory} depending on
 * that module. Each implementation contributes one entry to {@code FactoryResponse.counts}.
 */
public interface FactoryCountProvider {

    /** The key in the counts map, a plural noun such as {@code resources}. */
    String key();

    long count(UUID factoryId);
}

package com.realitycheck.common.system;

/** Supplies the simulation algorithm version to system information without {@code common} depending on it. */
public interface AlgorithmVersionSource {

    String algorithmVersion();
}

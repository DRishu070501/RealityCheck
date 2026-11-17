package com.realitycheck.demo;

import java.util.Map;

/**
 * Deterministic choices about the demonstration's packets, shared by the seed and the calibration test so
 * both see the same mix: which route a packet follows and how many stones it holds. Synthetic.
 */
final class DemoProductionPlan {

    private DemoProductionPlan() {
    }

    /** A well-spread value in [0, 2^63) from a key and a salt, the same every time. */
    static long mix(long key, long salt) {
        long z = key * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) >>> 1;
    }

    /** Tops for the measured share of packets, Full manufacturing for the rest. */
    static String routeOf(int packet, Map<String, Integer> shares) {
        return mix(packet, 1) % 100 < shares.get("TOPS") ? "TOPS" : "FULL";
    }

    /** Eight to twenty rough stones, fourteen on average. */
    static int stonesOf(int packet) {
        return 8 + (int) (mix(packet, 2) % 13);
    }

    /** Rough carats per stone: 0.90 to 1.50. */
    static java.math.BigDecimal caratsPerStone(int packet) {
        return new java.math.BigDecimal("0.90").add(java.math.BigDecimal.valueOf(mix(packet, 3) % 13)
                .multiply(new java.math.BigDecimal("0.05")));
    }
}

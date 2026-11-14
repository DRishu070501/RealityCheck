package com.realitycheck.simulation.engine;

import java.util.UUID;

/** Units of one pool held by a job. A job holds one of these per requirement of its stage, all or none. */
record ResourceAllocation(UUID poolId, int units) {
}

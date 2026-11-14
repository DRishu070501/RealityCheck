package com.realitycheck.common.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static UUID random() {
        return UUID.randomUUID();
    }

    /**
     * The id of a child row, derived from its parent's id and its natural key within the parent
     * (a stage's code, a shift's day and start). The same parent and key always give the same id,
     * which keeps the demonstration data identical across resets.
     */
    public static UUID child(UUID parent, String naturalKey) {
        return UUID.nameUUIDFromBytes((parent + "/" + naturalKey).getBytes(StandardCharsets.UTF_8));
    }
}

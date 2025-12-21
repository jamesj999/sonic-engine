package uk.co.jamesj999.sonic.data.games;

/**
 * Immutable Zone/Act pair used for pointer table lookups.
 */
public record ZoneAct(int zone, int act) {
    public ZoneAct {
        if (zone < 0) {
            throw new IllegalArgumentException("zone must be non-negative");
        }
        if (act < 0) {
            throw new IllegalArgumentException("act must be non-negative");
        }
    }

    /**
     * Index used by the zone-ordered tables in the Sonic 2 ROM.
     * Tables are laid out with a fixed act stride (usually 2 entries per zone,
     * but some zones have 1 or 3 acts and use duplicate/null entries to pad).
     */
    public int pointerIndex(int actsPerZone) {
        if (actsPerZone <= 0) {
            throw new IllegalArgumentException("actsPerZone must be positive");
        }
        int boundedAct = Math.min(act, actsPerZone - 1);
        return zone * actsPerZone + boundedAct;
    }
}

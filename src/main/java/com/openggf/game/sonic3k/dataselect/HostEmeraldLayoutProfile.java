package com.openggf.game.sonic3k.dataselect;

import java.util.List;

/**
 * Host-specific emerald orbit layout used by the donated S3K Data Select renderer.
 *
 * <p>S3K and donated S2 keep the native seven-emerald ring, while donated S1 uses a six-emerald
 * profile with reference-aligned offsets so the orbit stays visually balanced.</p>
 */
record HostEmeraldLayoutProfile(List<Point> positions, int activeEmeraldCount) {
    private static final List<Point> DEFAULT_SEVEN_POSITIONS = List.of(
            new Point(0, 0),
            new Point(0, 0),
            new Point(0, 0),
            new Point(0, 0),
            new Point(0, 0),
            new Point(0, 0),
            new Point(0, 0)
    );
    private static final List<Point> S1_SIX_RING_POSITIONS = List.of(
            new Point(25, -32),
            new Point(-2, 2),
            new Point(2, 2),
            new Point(-22, 38),
            new Point(33, -11),
            new Point(-25, 18)
    );

    HostEmeraldLayoutProfile {
        positions = List.copyOf(positions);
        if (activeEmeraldCount < 0 || activeEmeraldCount > positions.size()) {
            throw new IllegalArgumentException("activeEmeraldCount out of range: " + activeEmeraldCount);
        }
    }

    /**
     * Native seven-emerald layout used by S3K and donated S2.
     */
    static HostEmeraldLayoutProfile defaultSeven() {
        return new HostEmeraldLayoutProfile(DEFAULT_SEVEN_POSITIONS, 7);
    }

    /**
     * Sonic 1-specific six-emerald orbit that preserves a symmetric ring shape.
     */
    static HostEmeraldLayoutProfile s1SixRing() {
        return new HostEmeraldLayoutProfile(S1_SIX_RING_POSITIONS, 6);
    }

    /**
     * Per-emerald render offset relative to the save-slot origin.
     */
    record Point(int x, int y) {
    }
}

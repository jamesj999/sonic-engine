package uk.co.jamesj999.sonic.physics;

/**
 * Immutable record of a collision event for trace comparison.
 */
public record CollisionEvent(
    EventType type,
    String description,
    int x,
    int y,
    int distance,
    byte angle,
    boolean flag1,  // Context-specific (e.g., standing, inAir)
    boolean flag2   // Context-specific (e.g., pushing, contacted)
) {
    public enum EventType {
        TERRAIN_PROBES_START,
        TERRAIN_PROBE_RESULT,
        TERRAIN_PROBES_COMPLETE,
        SOLID_CONTACTS_START,
        SOLID_CANDIDATE,
        SOLID_RESOLVED,
        SOLID_CONTACTS_COMPLETE,
        POST_ADJUSTMENT
    }

    /** Create a simple event without coordinates */
    public static CollisionEvent simple(EventType type, String description) {
        return new CollisionEvent(type, description, 0, 0, 0, (byte) 0, false, false);
    }

    /** Create a position event */
    public static CollisionEvent position(EventType type, String description, int x, int y) {
        return new CollisionEvent(type, description, x, y, 0, (byte) 0, false, false);
    }

    /** Create a sensor result event */
    public static CollisionEvent sensor(String sensorName, SensorResult result) {
        if (result == null) {
            return new CollisionEvent(EventType.TERRAIN_PROBE_RESULT, sensorName, 0, 0, Integer.MAX_VALUE, (byte) 0, false, false);
        }
        return new CollisionEvent(EventType.TERRAIN_PROBE_RESULT, sensorName, 0, 0, result.distance(), result.angle(), true, false);
    }
}

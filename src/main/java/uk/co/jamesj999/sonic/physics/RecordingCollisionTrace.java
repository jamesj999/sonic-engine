package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.objects.SolidContact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CollisionTrace implementation that records all events for later comparison.
 */
public class RecordingCollisionTrace implements CollisionTrace {
    private final List<CollisionEvent> events = new ArrayList<>();

    @Override
    public void onTerrainProbesStart(int playerX, int playerY, boolean inAir) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.TERRAIN_PROBES_START,
            "terrain_start",
            playerX, playerY, 0, (byte) 0, inAir, false
        ));
    }

    @Override
    public void onTerrainProbeResult(String sensorName, SensorResult result) {
        events.add(CollisionEvent.sensor(sensorName, result));
    }

    @Override
    public void onTerrainProbesComplete(int adjustedX, int adjustedY, byte angle) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.TERRAIN_PROBES_COMPLETE,
            "terrain_complete",
            adjustedX, adjustedY, 0, angle, false, false
        ));
    }

    @Override
    public void onSolidContactsStart(int playerX, int playerY) {
        events.add(CollisionEvent.position(
            CollisionEvent.EventType.SOLID_CONTACTS_START,
            "solid_start",
            playerX, playerY
        ));
    }

    @Override
    public void onSolidCandidate(String objectType, int objectX, int objectY, boolean contacted) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.SOLID_CANDIDATE,
            objectType,
            objectX, objectY, 0, (byte) 0, contacted, false
        ));
    }

    @Override
    public void onSolidResolved(SolidContact contact, boolean standing, boolean pushing) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.SOLID_RESOLVED,
            "solid_resolved",
            0, 0, 0, (byte) 0, standing, pushing
        ));
    }

    @Override
    public void onSolidContactsComplete(boolean ridingObject, int adjustedX, int adjustedY) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.SOLID_CONTACTS_COMPLETE,
            "solid_complete",
            adjustedX, adjustedY, 0, (byte) 0, ridingObject, false
        ));
    }

    @Override
    public void onPostAdjustment(String adjustmentType, int beforeValue, int afterValue) {
        events.add(new CollisionEvent(
            CollisionEvent.EventType.POST_ADJUSTMENT,
            adjustmentType,
            beforeValue, afterValue, afterValue - beforeValue, (byte) 0, false, false
        ));
    }

    @Override
    public List<CollisionEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    @Override
    public void clear() {
        events.clear();
    }

    /** Compare this trace to another, returning differences */
    public List<String> compareWith(CollisionTrace other) {
        List<String> differences = new ArrayList<>();
        List<CollisionEvent> otherEvents = other.getEvents();

        int maxLen = Math.max(events.size(), otherEvents.size());
        for (int i = 0; i < maxLen; i++) {
            if (i >= events.size()) {
                differences.add("Missing event at index " + i + ": " + otherEvents.get(i));
            } else if (i >= otherEvents.size()) {
                differences.add("Extra event at index " + i + ": " + events.get(i));
            } else if (!eventsMatch(events.get(i), otherEvents.get(i))) {
                differences.add("Mismatch at index " + i + ": expected " + otherEvents.get(i) + " but got " + events.get(i));
            }
        }
        return differences;
    }

    private boolean eventsMatch(CollisionEvent a, CollisionEvent b) {
        if (a.type() != b.type()) return false;
        // Allow small position differences (1 pixel tolerance)
        if (Math.abs(a.x() - b.x()) > 1) return false;
        if (Math.abs(a.y() - b.y()) > 1) return false;
        if (Math.abs(a.distance() - b.distance()) > 1) return false;
        if (a.angle() != b.angle()) return false;
        if (a.flag1() != b.flag1()) return false;
        if (a.flag2() != b.flag2()) return false;
        return true;
    }
}

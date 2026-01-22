package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.objects.SolidContact;
import java.util.Collections;
import java.util.List;

/**
 * No-op CollisionTrace for production use - all methods do nothing.
 */
public final class NoOpCollisionTrace implements CollisionTrace {
    public static final NoOpCollisionTrace INSTANCE = new NoOpCollisionTrace();

    private NoOpCollisionTrace() {}

    @Override public void onTerrainProbesStart(int playerX, int playerY, boolean inAir) {}
    @Override public void onTerrainProbeResult(String sensorName, SensorResult result) {}
    @Override public void onTerrainProbesComplete(int adjustedX, int adjustedY, byte angle) {}
    @Override public void onSolidContactsStart(int playerX, int playerY) {}
    @Override public void onSolidCandidate(String objectType, int objectX, int objectY, boolean contacted) {}
    @Override public void onSolidResolved(SolidContact contact, boolean standing, boolean pushing) {}
    @Override public void onSolidContactsComplete(boolean ridingObject, int adjustedX, int adjustedY) {}
    @Override public void onPostAdjustment(String adjustmentType, int beforeValue, int afterValue) {}
    @Override public List<CollisionEvent> getEvents() { return Collections.emptyList(); }
    @Override public void clear() {}
}

package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.objects.SolidContact;
import java.util.List;

/**
 * Interface for recording collision events during processing.
 * Used for testing and debugging collision pipeline behavior.
 */
public interface CollisionTrace {
    /** Called before terrain sensor probes begin */
    void onTerrainProbesStart(int playerX, int playerY, boolean inAir);

    /** Called with each terrain sensor result */
    void onTerrainProbeResult(String sensorName, SensorResult result);

    /** Called when terrain probes complete */
    void onTerrainProbesComplete(int adjustedX, int adjustedY, byte angle);

    /** Called before solid object collision checks begin */
    void onSolidContactsStart(int playerX, int playerY);

    /** Called for each solid object candidate checked */
    void onSolidCandidate(String objectType, int objectX, int objectY, boolean contacted);

    /** Called when solid contact is resolved */
    void onSolidResolved(SolidContact contact, boolean standing, boolean pushing);

    /** Called when solid contacts complete */
    void onSolidContactsComplete(boolean ridingObject, int adjustedX, int adjustedY);

    /** Called when post-resolution adjustments are applied */
    void onPostAdjustment(String adjustmentType, int beforeValue, int afterValue);

    /** Get all recorded events for comparison */
    List<CollisionEvent> getEvents();

    /** Clear recorded events */
    void clear();
}

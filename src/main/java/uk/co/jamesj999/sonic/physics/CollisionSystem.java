package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * Unified collision pipeline that orchestrates terrain probes and solid object
 * collision in a defined order. This is the consolidation point for:
 * - TerrainCollisionManager (terrain sensor probes)
 * - ObjectManager.SolidContacts (solid object collision resolution)
 *
 * The pipeline executes in three explicit phases:
 * 1. Terrain probes (ground/ceiling/wall sensors against level collision)
 * 2. Solid object resolution (platforms, moving solids, sloped solids)
 * 3. Post-resolution adjustments (ground mode, gSpeed recompute, headroom)
 *
 * Initially this delegates to existing managers to prove behavioral parity.
 * Logic will be migrated incrementally once parity tests pass.
 */
public class CollisionSystem {
    private static final Logger LOGGER = Logger.getLogger(CollisionSystem.class.getName());

    private static CollisionSystem instance;

    private final TerrainCollisionManager terrainCollisionManager;
    private ObjectManager objectManager;

    // Trace for debugging/testing - defaults to no-op
    private CollisionTrace trace = NoOpCollisionTrace.INSTANCE;

    // Runtime flag to enable unified pipeline vs legacy
    private boolean unifiedPipelineEnabled = false;

    // Shadow mode: run both pipelines and compare (for validation)
    private boolean shadowModeEnabled = false;

    private CollisionSystem() {
        this.terrainCollisionManager = TerrainCollisionManager.getInstance();
    }

    public static synchronized CollisionSystem getInstance() {
        if (instance == null) {
            instance = new CollisionSystem();
        }
        return instance;
    }

    /** Reset singleton for testing */
    public static synchronized void resetInstance() {
        instance = null;
    }

    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void setTrace(CollisionTrace trace) {
        this.trace = trace != null ? trace : NoOpCollisionTrace.INSTANCE;
    }

    public CollisionTrace getTrace() {
        return trace;
    }

    public void setUnifiedPipelineEnabled(boolean enabled) {
        this.unifiedPipelineEnabled = enabled;
    }

    public boolean isUnifiedPipelineEnabled() {
        return unifiedPipelineEnabled;
    }

    public void setShadowModeEnabled(boolean enabled) {
        this.shadowModeEnabled = enabled;
    }

    public boolean isShadowModeEnabled() {
        return shadowModeEnabled;
    }

    /**
     * Execute the full collision pipeline for a sprite.
     * This is the main entry point that replaces separate calls to
     * terrain collision and solid object managers.
     *
     * @param sprite The playable sprite to process collision for
     * @param groundSensors Ground sensor array
     * @param ceilingSensors Ceiling sensor array
     */
    public void step(AbstractPlayableSprite sprite, Sensor[] groundSensors, Sensor[] ceilingSensors) {
        if (sprite == null || sprite.getDead()) {
            return;
        }

        if (sprite.isDebugMode()) {
            return;
        }

        // Record initial state
        int startX = sprite.getCentreX();
        int startY = sprite.getCentreY();
        boolean inAir = sprite.getAir();

        trace.onTerrainProbesStart(startX, startY, inAir);

        // Phase 1: Terrain probes
        SensorResult[] groundResults = terrainProbes(sprite, groundSensors, "ground");
        SensorResult[] ceilingResults = terrainProbes(sprite, ceilingSensors, "ceiling");

        trace.onTerrainProbesComplete(sprite.getCentreX(), sprite.getCentreY(), sprite.getAngle());

        // Phase 2: Solid object resolution
        trace.onSolidContactsStart(sprite.getCentreX(), sprite.getCentreY());
        resolveSolidContacts(sprite);
        trace.onSolidContactsComplete(
            objectManager != null && objectManager.isRidingObject(),
            sprite.getCentreX(), sprite.getCentreY()
        );

        // Phase 3: Post-resolution adjustments
        postResolutionAdjustments(sprite);
    }

    /**
     * Phase 1: Execute terrain sensor probes.
     * Currently delegates to TerrainCollisionManager.
     */
    public SensorResult[] terrainProbes(AbstractPlayableSprite sprite, Sensor[] sensors, String sensorType) {
        SensorResult[] results = terrainCollisionManager.getSensorResult(sensors);

        // Record each sensor result
        for (int i = 0; i < results.length; i++) {
            trace.onTerrainProbeResult(sensorType + "_" + i, results[i]);
        }

        return results;
    }

    /**
     * Phase 2: Resolve solid object contacts.
     * Currently delegates to ObjectManager.SolidContacts.
     */
    public void resolveSolidContacts(AbstractPlayableSprite sprite) {
        if (objectManager == null) {
            return;
        }

        // Delegate to existing solid contacts system
        objectManager.updateSolidContacts(sprite);
    }

    /**
     * Phase 3: Apply post-resolution adjustments.
     * This includes headroom checks, ground mode updates, and gSpeed recomputation.
     */
    public void postResolutionAdjustments(AbstractPlayableSprite sprite) {
        // Currently minimal - adjustments are done inline in movement code
        // This will be expanded as we migrate logic from PlayableSpriteMovement

        // Example: headroom check
        if (objectManager != null && !sprite.getAir()) {
            int hexAngle = sprite.getAngle() & 0xFF;
            int headroom = objectManager.getHeadroomDistance(sprite, hexAngle);
            if (headroom < Integer.MAX_VALUE && headroom < sprite.getYRadius()) {
                int before = sprite.getCentreY();
                // Headroom clamping would go here
                trace.onPostAdjustment("headroom_check", before, sprite.getCentreY());
            }
        }
    }

    /**
     * Check if player has standing contact with any solid object.
     * Convenience method that delegates to SolidContacts.
     */
    public boolean hasStandingContact(AbstractPlayableSprite player) {
        if (objectManager == null) {
            return false;
        }
        return objectManager.hasStandingContact(player);
    }

    /**
     * Get headroom distance to nearest solid object above player.
     * Convenience method that delegates to SolidContacts.
     */
    public int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
        if (objectManager == null) {
            return Integer.MAX_VALUE;
        }
        return objectManager.getHeadroomDistance(player, hexAngle);
    }

    /**
     * Check if player is currently riding an object.
     */
    public boolean isRidingObject() {
        if (objectManager == null) {
            return false;
        }
        return objectManager.isRidingObject();
    }

    /**
     * Clear riding state (e.g., when player jumps off).
     */
    public void clearRidingObject() {
        if (objectManager != null) {
            objectManager.clearRidingObject();
        }
    }
}

package com.openggf.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the collision phases a caller intends to run for a frame.
 *
 * <p>The plan is deliberately descriptive: terrain probes stay in
 * {@link TerrainCollisionManager}, solid object resolution stays in
 * {@code ObjectManager.SolidContacts}, and {@link CollisionSystem} only
 * orchestrates the requested phases. Playable movement still owns its
 * ROM-order ground-mode recomputation at the call sites that perform
 * terrain attachment or air landing, so the current named factories leave
 * {@link Phase#POST_RESOLUTION_GROUND_MODE} disabled.</p>
 */
public record FrameCollisionPlan(
        boolean runsTerrainProbes,
        boolean runsSolidObjectResolution,
        boolean runsPostResolutionGroundMode,
        boolean recordsTrace) {

    public enum Phase {
        TERRAIN_PROBES,
        SOLID_OBJECT_RESOLUTION,
        POST_RESOLUTION_GROUND_MODE
    }

    /**
     * Legacy full playable collision pass: terrain probes followed by the
     * batched solid-object pass, with trace events emitted around those phases.
     */
    public static FrameCollisionPlan playableFrame() {
        return new FrameCollisionPlan(true, true, false, true);
    }

    /**
     * Terrain sensor probes only. Callers that need trace diagnostics should
     * use their existing terrain-probe instrumentation explicitly.
     */
    public static FrameCollisionPlan terrainOnly() {
        return new FrameCollisionPlan(true, false, false, false);
    }

    /**
     * Solid-object resolution only. Used by frame-order paths that already
     * performed terrain movement and need only the object-solid checkpoint.
     */
    public static FrameCollisionPlan objectResolutionOnly() {
        return new FrameCollisionPlan(false, true, false, false);
    }

    public List<Phase> orderedPhases() {
        List<Phase> phases = new ArrayList<>(3);
        if (runsTerrainProbes) {
            phases.add(Phase.TERRAIN_PROBES);
        }
        if (runsSolidObjectResolution) {
            phases.add(Phase.SOLID_OBJECT_RESOLUTION);
        }
        if (runsPostResolutionGroundMode) {
            phases.add(Phase.POST_RESOLUTION_GROUND_MODE);
        }
        return List.copyOf(phases);
    }
}

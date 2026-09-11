package com.openggf.sprites.playable;

import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

/**
 * Sonic-specific respawn strategy: walks or spindashes in from the nearest floor
 * at the screen edge, opposite to the leader's movement direction.
 * <p>
 * If the leader is moving fast (above {@link #SPINDASH_SPEED_THRESHOLD}), Sonic
 * enters in a rolling state at spindash speed. Otherwise, he walks in at a moderate pace.
 */
public class SonicRespawnStrategy implements SidekickRespawnStrategy {

    /** Maximum downward probe depth in pixels (8 steps x 16px). */
    private static final int FLOOR_SEARCH_DEPTH = 128;

    /** Horizontal distance to scan inward when the first edge floor is embedded in terrain. */
    private static final int SPAWN_CLEARANCE_SEARCH_WIDTH = 96;

    /** Step size for horizontal clearance search. */
    private static final int SPAWN_CLEARANCE_STEP = 8;

    /** Leader ground speed threshold for spindash vs walk entry (subpixels). */
    private static final int SPINDASH_SPEED_THRESHOLD = 0x600;

    /** Initial rolling ground speed when entering via spindash (subpixels). */
    private static final int SPINDASH_RELEASE_SPEED = 0x800;

    /** Initial walking ground speed (subpixels). */
    private static final int WALK_ENTRY_SPEED = 0x200;

    /** Extra ground speed used to close distance on a moving leader. */
    private static final int CATCH_UP_SPEED_MARGIN = 0x200;

    /** Distance in pixels at which the sidekick is considered close enough to the leader. */
    private static final int APPROACH_COMPLETE_THRESHOLD = 32;

    private final SidekickCpuController controller;

    public SonicRespawnStrategy(SidekickCpuController controller) {
        this.controller = controller;
    }

    @Override
    public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
        Camera camera = sidekick.currentCamera();
        if (camera == null) {
            return false;
        }
        LevelManager levelManager = sidekick.currentLevelManager();
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            return false;
        }

        // Determine screen edge: leader moving right -> enter from left, and vice versa
        int edgeX;
        if (leader.getXSpeed() > 0) {
            edgeX = camera.getX() - 32;
        } else if (leader.getXSpeed() < 0) {
            edgeX = camera.getX() + 320 + 32;
        } else {
            // Stopped — default to left edge
            edgeX = camera.getX() - 32;
        }

        int probeY = leader.getCentreY();
        int searchDirection = leader.getCentreX() >= edgeX ? 1 : -1;
        SpawnPlacement placement = findClearFloorPlacement(sidekick, edgeX, probeY, searchDirection);
        if (placement == null) {
            return false; // No clear ground found — stay in SPAWNING
        }

        // Place sidekick on the floor at the screen edge, or the nearest inward
        // floor position whose body probes are clear of all-sides terrain.
        sidekick.setCentreX((short) placement.centreX());
        sidekick.setCentreY((short) placement.centreY());

        // Reset state flags
        sidekick.setAir(false);
        sidekick.setDead(false);
        sidekick.setHurt(false);
        sidekick.clearDrowningDeathState();
        sidekick.setSpindash(false);
        sidekick.setSpindashCounter((short) 0);
        sidekick.setObjectMappingFrameControl(false);

        // Let normal physics drive movement
        sidekick.setControlLocked(false);
        ObjectControlState.none().applyTo(sidekick);
        sidekick.setForcedAnimationId(-1);

        // Face toward the leader
        boolean leaderIsRight = leader.getCentreX() >= placement.centreX();
        sidekick.setDirection(leaderIsRight ? Direction.RIGHT : Direction.LEFT);

        // Choose walk vs spindash based on the fastest relevant leader speed.
        // Staggered multi-sidekick entries may approach a slow direct parent for
        // spacing, but still need enough speed to catch the main player.
        int speedReference = entrySpeedReference(leader);
        if (speedReference > SPINDASH_SPEED_THRESHOLD) {
            sidekick.setRolling(true);
            sidekick.setGSpeed(entrySpeedTowardLeader(speedReference, leaderIsRight, SPINDASH_RELEASE_SPEED));
        } else {
            sidekick.setGSpeed(entrySpeedTowardLeader(speedReference, leaderIsRight, WALK_ENTRY_SPEED));
        }

        // gSpeed drives ground movement; clear axis speeds
        sidekick.setXSpeed((short) 0);
        sidekick.setYSpeed((short) 0);

        return true;
    }

    private SpawnPlacement findClearFloorPlacement(AbstractPlayableSprite sidekick, int edgeX,
                                                   int probeY, int searchDirection) {
        for (int offset = 0; offset <= SPAWN_CLEARANCE_SEARCH_WIDTH; offset += SPAWN_CLEARANCE_STEP) {
            int candidateX = edgeX + (offset * searchDirection);
            Integer floorY = findFloorY(candidateX, probeY);
            if (floorY == null) {
                continue;
            }
            int centreY = floorY - (sidekick.getHeight() / 2);
            if (isWallClearForSpawn(sidekick, candidateX, centreY)) {
                return new SpawnPlacement(candidateX, centreY);
            }
        }
        return null;
    }

    private Integer findFloorY(int probeX, int probeY) {
        TerrainCheckResult floorResult = null;
        int foundProbeY = probeY;
        for (int step = 0; step <= FLOOR_SEARCH_DEPTH / 16; step++) {
            int testY = probeY + (step * 16);
            TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(probeX, testY);
            if (result.foundSurface() && result.distance() >= 0) {
                floorResult = result;
                foundProbeY = testY;
                break;
            }
        }

        if (floorResult == null) {
            return null;
        }
        return foundProbeY + floorResult.distance();
    }

    private static boolean isWallClearForSpawn(AbstractPlayableSprite sidekick, int centreX, int centreY) {
        int xRadius = sidekick.getXRadius();
        int yRadius = sidekick.getYRadius();
        int leftX = centreX - xRadius;
        int rightX = centreX + xRadius;
        int lowerBodyY = centreY + yRadius - 1;

        return !ObjectTerrainUtils.checkLeftWallDist(leftX, centreY).hasCollision()
                && !ObjectTerrainUtils.checkLeftWallDist(leftX, lowerBodyY).hasCollision()
                && !ObjectTerrainUtils.checkRightWallDist(rightX, centreY).hasCollision()
                && !ObjectTerrainUtils.checkRightWallDist(rightX, lowerBodyY).hasCollision();
    }

    private record SpawnPlacement(int centreX, int centreY) {}

    private int entrySpeedReference(AbstractPlayableSprite leader) {
        int speed = Math.abs(leader.getGSpeed());
        AbstractPlayableSprite rootLeader = controller.getRootLeader();
        if (rootLeader != null) {
            speed = Math.max(speed, Math.abs(rootLeader.getGSpeed()));
        }
        return speed;
    }

    private static short entrySpeedTowardLeader(int speedReference, boolean leaderIsRight, int minimumSpeed) {
        int speed = Math.max(minimumSpeed, speedReference + CATCH_UP_SPEED_MARGIN);
        speed = Math.min(Short.MAX_VALUE, speed);
        return (short) (leaderIsRight ? speed : -speed);
    }

    @Override
    public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                     int frameCounter) {
        // Keep pressing toward the leader so friction doesn't stop the sidekick.
        int dx = leader.getCentreX() - sidekick.getCentreX();
        controller.setApproachInput(dx < 0, dx > 0);

        return Math.abs(dx) <= APPROACH_COMPLETE_THRESHOLD;
    }

    @Override
    public boolean requiresPhysics() {
        return true;
    }
}

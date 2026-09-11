package com.openggf.physics;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rules.CollisionRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerAnimationRules;
import com.openggf.game.rules.PlayerMovementRules;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Unified collision pipeline that orchestrates terrain probes and solid object
 * collision in a defined order. This is the consolidation point for:
 * - TerrainCollisionManager (terrain sensor probes)
 * - ObjectManager.SolidContacts (solid object collision resolution)
 *
 * Full-frame callers should use {@link #run(FrameCollisionPlan,
 * AbstractPlayableSprite, Sensor[], Sensor[])} with an explicit plan instead
 * of relying on a method name to imply which collision systems run.
 *
 * The legacy playable-frame plan executes these phases:
 * 1. Terrain probes (ground/ceiling/wall sensors against level collision)
 * 2. Compatibility solid-object resolution when the active frame path still uses a
 *    batched solid pass
 */
public class CollisionSystem implements RewindSnapshottable<CollisionSystem.AngleRegisterSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(CollisionSystem.class.getName());

    private final TerrainCollisionManager terrainCollisionManager;
    private final GroundSensor calcRoomProbe = new GroundSensor(null, Direction.DOWN, (byte) 0, (byte) 0, true);
    private ObjectManager objectManager;
    private int primaryAngleRegister;
    private int secondaryAngleRegister;

    // Trace for debugging/testing - defaults to no-op
    private CollisionTrace trace = NoOpCollisionTrace.INSTANCE;


    public CollisionSystem() {
        this(GameServices.terrainCollision());
    }

    public CollisionSystem(TerrainCollisionManager terrainCollisionManager) {
        this.terrainCollisionManager = terrainCollisionManager;
    }

    /**
     * Resets mutable state without destroying the singleton instance.
     * Cached references held by other classes remain valid.
     */
    public void resetState() {
        terrainCollisionManager.resetState();
        objectManager = null;
        trace = NoOpCollisionTrace.INSTANCE;
        primaryAngleRegister = 0;
        secondaryAngleRegister = 0;
    }

    @Override
    public String key() {
        return "collision";
    }

    @Override
    public AngleRegisterSnapshot capture() {
        return new AngleRegisterSnapshot(primaryAngleRegister, secondaryAngleRegister);
    }

    @Override
    public void restore(AngleRegisterSnapshot snapshot) {
        if (snapshot == null) {
            resetAngleRegisters();
            return;
        }
        primaryAngleRegister = snapshot.primary();
        secondaryAngleRegister = snapshot.secondary();
    }

    @Override
    public void resetForMissingSnapshot() {
        resetAngleRegisters();
    }

    private void resetAngleRegisters() {
        primaryAngleRegister = 0;
        secondaryAngleRegister = 0;
    }

    public record AngleRegisterSnapshot(int primary, int secondary) {}

    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void setTrace(CollisionTrace trace) {
        this.trace = trace != null ? trace : NoOpCollisionTrace.INSTANCE;
    }

    public CollisionTrace getTrace() {
        return trace;
    }

    /**
     * Compatibility wrapper for older callers that expect the legacy full
     * playable-frame pass. New callers should choose a named
     * {@link FrameCollisionPlan} and invoke {@link #run(FrameCollisionPlan,
     * AbstractPlayableSprite, Sensor[], Sensor[])} or
     * {@link #runSolidObjectResolution(FrameCollisionPlan, AbstractPlayableSprite,
     * boolean, boolean)}.
     */
    @Deprecated(since = "0.6.prerelease", forRemoval = false)
    public void step(AbstractPlayableSprite sprite, Sensor[] groundSensors, Sensor[] ceilingSensors) {
        run(FrameCollisionPlan.playableFrame(), sprite, groundSensors, ceilingSensors);
    }

    /**
     * Execute the collision phases requested by {@code plan} for a playable
     * sprite. The plan documents whether this frame is doing terrain probes,
     * batched solid-object resolution, trace recording, or a future
     * post-resolution ground-mode phase.
     *
     * @param plan explicit phase plan for this frame
     * @param sprite playable sprite to process
     * @param groundSensors ground sensor array, used only when the plan runs terrain probes
     * @param ceilingSensors ceiling sensor array, used only when the plan runs terrain probes
     */
    public void run(FrameCollisionPlan plan, AbstractPlayableSprite sprite,
                    Sensor[] groundSensors, Sensor[] ceilingSensors) {
        FrameCollisionPlan effectivePlan = Objects.requireNonNull(plan, "plan");
        if (sprite == null || sprite.getDead()) {
            return;
        }

        if (sprite.isDebugMode()) {
            return;
        }

        if (effectivePlan.runsTerrainProbes()) {
            if (effectivePlan.recordsTrace()) {
                trace.onTerrainProbesStart(sprite.getCentreX(), sprite.getCentreY(), sprite.getAir());
            }

            terrainProbes(sprite, groundSensors, "ground", effectivePlan.recordsTrace());
            terrainProbes(sprite, ceilingSensors, "ceiling", effectivePlan.recordsTrace());

            if (effectivePlan.recordsTrace()) {
                trace.onTerrainProbesComplete(sprite.getCentreX(), sprite.getCentreY(), sprite.getAngle());
            }
        }

        if (effectivePlan.runsSolidObjectResolution()) {
            runSolidObjectResolution(effectivePlan, sprite, false, false);
        }
    }

    /**
     * Phase 1: Execute terrain sensor probes.
     * Currently delegates to TerrainCollisionManager.
     */
    public SensorResult[] terrainProbes(AbstractPlayableSprite sprite, Sensor[] sensors, String sensorType) {
        return terrainProbes(sprite, sensors, sensorType, true);
    }

    private SensorResult[] terrainProbes(AbstractPlayableSprite sprite, Sensor[] sensors,
                                         String sensorType, boolean recordTrace) {
        SensorResult[] results = terrainCollisionManager.getSensorResult(sensors);

        if (recordTrace && trace != NoOpCollisionTrace.INSTANCE) {
            for (int i = 0; i < results.length; i++) {
                trace.onTerrainProbeResult(sensorType + "_" + i, results[i]);
            }
        }

        return results;
    }

    /**
     * Publishes the angle bytes left by a grounded player AnglePos dispatch.
     * Native AnglePos seeds both shared registers with the empty-floor sentinel
     * before its probes, so a missing side writes {@code 3} rather than retaining
     * an earlier collision routine's value.
     */
    public void publishGroundAngleRegisters(SensorResult left, SensorResult right) {
        primaryAngleRegister = probeAngleOrEmptySentinel(right);
        secondaryAngleRegister = probeAngleOrEmptySentinel(left);
    }

    public int getPrimaryAngleRegister() {
        return primaryAngleRegister;
    }

    public int getSecondaryAngleRegister() {
        return secondaryAngleRegister;
    }

    private static int probeAngleOrEmptySentinel(SensorResult result) {
        return result == null ? 3 : result.angle() & 0xFF;
    }

    private void publishPrimaryAngleIfBackedByTile(SensorResult result) {
        if (result != null && result.tileId() != 0) {
            primaryAngleRegister = result.angle() & 0xFF;
        }
    }

    private void publishAirFloorAngleRegisters(SensorResult[] results) {
        if (results == null) {
            return;
        }
        SensorResult left = results.length > 0 ? results[0] : null;
        SensorResult right = results.length > 1 ? results[1] : null;
        publishPrimaryAngleIfBackedByTile(right);
        if (left != null && left.tileId() != 0) {
            secondaryAngleRegister = left.angle() & 0xFF;
        }
    }

    /**
     * Phase 2: Resolve solid object contacts for the legacy batched path.
     * Inline-order modules resolve object solids during object execution instead.
     */
    public void resolveSolidContacts(AbstractPlayableSprite sprite) {
        runSolidObjectResolution(FrameCollisionPlan.objectResolutionOnly(), sprite, false, false);
    }

    public void runSolidObjectResolution(FrameCollisionPlan plan, AbstractPlayableSprite sprite,
                                         boolean postMovement, boolean deferSideToPostMovement) {
        FrameCollisionPlan effectivePlan = Objects.requireNonNull(plan, "plan");
        if (!effectivePlan.runsSolidObjectResolution() || sprite == null || sprite.getDead()
                || sprite.isDebugMode()) {
            return;
        }
        if (effectivePlan.recordsTrace()) {
            trace.onSolidContactsStart(sprite.getCentreX(), sprite.getCentreY());
        }

        if (objectManager != null) {
            objectManager.updateSolidContacts(sprite, postMovement, deferSideToPostMovement);
        }

        if (effectivePlan.recordsTrace()) {
            trace.onSolidContactsComplete(
                objectManager != null && objectManager.isRidingObject(sprite),
                sprite.getCentreX(), sprite.getCentreY()
            );
        }
    }

    /**
     * Check if player has standing contact with any solid object.
     * Convenience method that delegates to SolidContacts.
     */
    public boolean hasStandingContact(AbstractPlayableSprite player) {
        if (player == null || player.getYSpeed() < 0 || objectManager == null) {
            return false;
        }
        return objectManager.latestStandingSnapshot(player);
    }

    /**
     * Get headroom distance to nearest solid object above player.
     * Convenience method that delegates to SolidContacts.
     */
    public int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
        if (objectManager == null) {
            return Integer.MAX_VALUE;
        }
        return objectManager.latestHeadroomSnapshot(player, hexAngle);
    }

    /**
     * Check if player is currently riding an object.
     */
    public boolean isRidingObject(AbstractPlayableSprite player) {
        if (objectManager == null) {
            return false;
        }
        return objectManager.isRidingObject(player);
    }

    /**
     * Clear riding state (e.g., when player jumps off).
     */
    public void clearRidingObject(AbstractPlayableSprite player) {
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
    }

    /**
     * Sonic_Jump clears the player's on-object status bit before objects run.
     * Some object routines still consume their standing routine once later in
     * the same frame, so the engine riding record may need to survive until
     * that object's inline solid checkpoint.
     */
    public void clearRidingObjectForJump(AbstractPlayableSprite player) {
        if (objectManager != null) {
            objectManager.clearRidingObjectForJump(player);
        }
    }

    public boolean hasObjectSupport(AbstractPlayableSprite player) {
        return isRidingObject(player) || hasStandingContact(player) || hasActiveLatchedObjectSupport(player);
    }

    public boolean hasGroundingObjectSupport(AbstractPlayableSprite player) {
        if (player == null || objectManager == null) {
            return false;
        }
        return objectManager.hasGroundingObjectSupport(player);
    }

    private boolean hasActiveLatchedObjectSupport(AbstractPlayableSprite player) {
        if (player == null || objectManager == null || !player.isOnObject()
                || player.getLatchedSolidObjectId() == 0) {
            return false;
        }
        ObjectInstance latchedInstance = player.getLatchedSolidObjectInstance();
        if (latchedInstance == null
                && player.getInteractSlotIndex() == AbstractPlayableSprite.SYNTHETIC_INTERACT_SLOT) {
            // Manager-hosted ROM supports, such as S2 Obj07 oil, still own
            // Status_OnObj until their manager clears it. ROM AnglePos gates on
            // that status bit, not on whether the support has an object instance.
            return true;
        }
        if (!objectManager.isActiveObjectInstance(latchedInstance)) {
            return false;
        }
        // ROM AnglePos exits on Status_OnObj before terrain attachment in all
        // games (S1 Sonic AnglePos.asm:5-11, S2 s2.asm:42559-42571,
        // S3K sonic3k.asm:18728-18741). Non-solid controllers such as S2
        // Obj06 own that bit until their object routine clears it.
        return true;
    }

    public boolean hasEnoughHeadroom(AbstractPlayableSprite player, int hexAngle) {
        // ROM jump routines only call terrain headroom probes before applying
        // jump velocity: S1 Sonic_CalcHeadroom, S2/S3K CalcRoomOverHead
        // (S1 01 Sonic.asm:1123-1128; S2 s2.asm:37031-37037,40010-40016;
        // S3K sonic3k.asm:23300-23307,28531-28538). Solid-object headroom is
        // handled by object collision/crush logic, not by Sonic_Jump/Tails_Jump.
        return getTerrainHeadroomDistance(player, hexAngle) >= 6;
    }

    public void resolveGroundWallCollision(AbstractPlayableSprite sprite) {
        resolveGroundWallCollision(FrameCollisionPlan.terrainOnly(), sprite);
    }

    public void resolveGroundWallCollision(FrameCollisionPlan plan, AbstractPlayableSprite sprite) {
        requireTerrainOnlyPlan(plan, "resolveGroundWallCollision");
        if (sprite == null || sprite.isTunnelMode()
                || sprite.isStickToConvex()
                || sprite.isSuppressGroundWallCollision()) {
            return;
        }
        var levelManager = sprite.currentLevelManager();
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            return;
        }

        int angle = sprite.getAngle() & 0xFF;
        short gSpeed = sprite.getGSpeed();
        // ROM Sonic_Move/loc_11350 only applies the (angle + $40) sign skip
        // when the angle is not on an exact cardinal quadrant. Exact ceiling
        // angle $80 must still run CalcRoomInFront (sonic3k.asm:22708-22716).
        if (((angle & 0x3F) != 0 && (((angle + 0x40) & 0x80) != 0)) || gSpeed == 0) {
            return;
        }

        // ROM-accurate 32-bit prediction (Sonic_WalkSpeed / CalcRoomInFront):
        // ROM loads full 32-bit position (pixel:16 | sub:16) and adds velocity*256.
        // Uses full 16-bit subpixel to prevent carry errors vs ROM's arithmetic.
        int xPos32 = (sprite.getX() << 16) | (sprite.getXSubpixelRaw());
        int yPos32 = (sprite.getY() << 16) | (sprite.getYSubpixelRaw());
        int predictedX = (xPos32 + ((int) sprite.getXSpeed() << 8)) >> 16;
        int predictedY = (yPos32 + ((int) sprite.getYSpeed() << 8)) >> 16;
        short predictedDx = (short) (predictedX - sprite.getX());
        short predictedDy = (short) (predictedY - sprite.getY());
        CalcRoomInFrontProbe probe = describeCalcRoomInFrontProbe(angle, gSpeed);
        SensorResult result = scanCalcRoomInFront(sprite, probe, predictedDx, predictedDy);

        if (result == null) {
            return;
        }

        int rotation = (gSpeed < 0) ? 0x40 : 0xC0;
        int rotatedAngle = (angle + rotation) & 0xFF;
        int mode = (rotatedAngle + 0x20) & 0xC0;
        // ROM CalcRoomInFront (sub_F61C, sonic3k.asm:19679-19743) pushes only when
        // the predicted-position wall distance is negative (the caller's tst.w d1 /
        // bpl, e.g. Tails_InputAcceleration_Path loc_14BA8..loc_14C00,
        // sonic3k.asm:27974-28018). The engine's predicted-position scan already
        // reproduces FindWall's per-cell penetration (sub_F584 loc_F60C not.w d1,
        // sonic3k.asm:19666-19672): a flush empty-cell edge yields distance 0 (no
        // push) and a predicted pixel inside a solid cell yields a negative
        // distance (push). The S3K CPU sidekick reaches the penetrating pixel via
        // the per-frame follow nudge (loc_13E34 addq.w #1,x_pos,
        // sonic3k.asm:26734-26741), so no zero-distance seam override is required.
        int distance = result.distance();
        if (distance == 0 && shouldDeferFlushWallResponseForRiddenDropOnFloor(sprite, mode, gSpeed)) {
            sprite.deferGroundWallVelocityResponse(mode, -1);
            return;
        }
        if (distance >= 0) {
            return;
        }

        // Tails_Move/Sonic_Move applies CalcRoomInFront once. The later S2
        // SolidObject_Always/DropOnFloor object pass does not repeat a negative
        // terrain response; only the exactly-flush handoff above is deferred.
        // Replaying this distance after ObjectMove doubles the stored velocity
        // when the current probe already reports the full penetration.
        applyGroundWallVelocityResponse(sprite, mode, distance);
    }

    private boolean shouldDeferFlushWallResponseForRiddenDropOnFloor(
            AbstractPlayableSprite sprite, int mode, short gSpeed) {
        CollisionRules rules = collisionRulesOrNull(sprite);
        if (rules == null
                || !rules.repeatedObjectRideGroundWallResponseDeferred()
                || objectManager == null
                || !sprite.isOnObject()
                || !sprite.getPushing()) {
            return false;
        }
        ObjectInstance ridingObject = objectManager.getRidingObject(sprite);
        if (!(ridingObject instanceof SolidObjectProvider provider)
                || !provider.dropOnFloor()) {
            return false;
        }
        // S2 Obj30 routes its SolidObject_Always/SlopedSolid helper through
        // DropOnFloor (s2.asm:49560-49604,49674-49676). While riding that support,
        // the ROM stores a repeated push correction only when the rider is on the
        // probed wall side of the object. HTZ2 f3317 (Obj30 x=$1760, Tails left
        // of centre) reaches d1=-1 in CalcRoomInFront; f4422 (Obj30 x=$1920,
        // Tails right of centre) returns d1=0 and must not synthesize the push.
        return isRiderOnGroundWallProbeSide(sprite, ridingObject, mode)
                && ((mode == 0x40 && gSpeed <= -0x18)
                || (mode == 0xC0 && gSpeed >= 0x18));
    }

    static boolean isRiderOnGroundWallProbeSide(
            AbstractPlayableSprite sprite, ObjectInstance ridingObject, int mode) {
        if (sprite == null || ridingObject == null) {
            return false;
        }
        int dx = (short) (sprite.getCentreX() - ridingObject.getX());
        return switch (mode) {
            case 0x40 -> dx <= 0;
            case 0xC0 -> dx >= 0;
            default -> false;
        };
    }

    public void applyDeferredGroundWallVelocityResponse(AbstractPlayableSprite sprite) {
        if (sprite == null || !sprite.hasDeferredGroundWallVelocityResponse()) {
            return;
        }
        int mode = sprite.getDeferredGroundWallVelocityMode();
        int distance = sprite.getDeferredGroundWallVelocityDistance();
        sprite.clearDeferredGroundWallVelocityResponse();
        applyGroundWallVelocityResponse(sprite, mode, distance);
    }

    /**
     * Apply S3K's post-AnglePos background-collision wall clamps.
     *
     * <p>When {@code Background_collision_flag} is set, the grounded stand and
     * roll paths call {@code CheckLeftWallDist} and {@code CheckRightWallDist}
     * after movement, AnglePos, and SlopeRepel. These checks adjust native
     * {@code x_pos} only; unlike CalcRoomInFront they do not alter velocity or
     * Status_Push (sonic3k.asm:27529-27548,27741-27760).
     */
    public boolean hasFatalPostMovementBackgroundFloorOverlap(
            FrameCollisionPlan plan, AbstractPlayableSprite sprite) {
        requireTerrainOnlyPlan(plan, "hasFatalPostMovementBackgroundFloorOverlap");
        var gameState = GameServices.gameStateOrNull();
        if (sprite == null || gameState == null || !gameState.isBackgroundCollisionFlag()) {
            return false;
        }

        // ROM sub_F846 probes FindFloor at (x_pos, y_pos-4) with a3=$10 and
        // the player's live lrb_solid_bit. A negative result is an embedded
        // dual-plane floor and the grounded stand/roll path jumps directly to
        // Kill_Character before running the following wall clamps.
        calcRoomProbe.sprite = sprite;
        SensorResult floor = calcRoomProbe.scanWorld(
                Direction.DOWN, (short) 0, (short) -4,
                (short) 0, (short) 0, sprite.getLrbSolidBit());
        return floor != null && floor.distance() < 0;
    }

    public void resolvePostMovementBackgroundWallClamp(
            FrameCollisionPlan plan, AbstractPlayableSprite sprite) {
        requireTerrainOnlyPlan(plan, "resolvePostMovementBackgroundWallClamp");
        var gameState = GameServices.gameStateOrNull();
        if (sprite == null || gameState == null || !gameState.isBackgroundCollisionFlag()) {
            return;
        }

        calcRoomProbe.sprite = sprite;
        int solidityBit = sprite.getLrbSolidBit();
        SensorResult left = calcRoomProbe.scanWorld(
                Direction.LEFT, (short) -10, (short) 0,
                (short) 0, (short) 0, solidityBit);
        if (left != null && left.distance() < 0) {
            NativePositionOps.addXPosPreserveSubpixel(sprite, -left.distance());
        }

        SensorResult right = calcRoomProbe.scanWorld(
                Direction.RIGHT, (short) 10, (short) 0,
                (short) 0, (short) 0, solidityBit);
        if (right != null && right.distance() < 0) {
            NativePositionOps.addXPosPreserveSubpixel(sprite, right.distance());
        }
    }

    private static void applyGroundWallVelocityResponse(AbstractPlayableSprite sprite, int mode, int distance) {
        int velocityAdjustment = distance << 8;
        switch (mode) {
            case 0x00 -> sprite.setYSpeed((short) (sprite.getYSpeed() + velocityAdjustment));
            case 0x40 -> {
                sprite.setXSpeed((short) (sprite.getXSpeed() - velocityAdjustment));
                sprite.setGSpeed((short) 0);
                if (shouldSetGroundWallPush(sprite, mode)) {
                    sprite.setPushing(true);
                    sprite.markPushFromGroundWallCollision();
                }
            }
            case 0x80 -> sprite.setYSpeed((short) (sprite.getYSpeed() - velocityAdjustment));
            case 0xC0 -> {
                sprite.setXSpeed((short) (sprite.getXSpeed() + velocityAdjustment));
                sprite.setGSpeed((short) 0);
                if (shouldSetGroundWallPush(sprite, mode)) {
                    sprite.setPushing(true);
                    sprite.markPushFromGroundWallCollision();
                }
            }
            default -> {
            }
        }
    }

    private static boolean shouldSetGroundWallPush(AbstractPlayableSprite sprite, int mode) {
        CollisionRules rules = collisionRulesOrNull(sprite);
        if (rules == null || !rules.groundWallPushRequiresFacingIntoWall()) {
            return true;
        }
        boolean facingLeft = sprite.getDirection() == com.openggf.physics.Direction.LEFT;
        // S3K Sonic_Move/Tails_InputAcceleration_Path only sets Status_Push
        // when Status_Facing matches the wall side:
        //   mode $40: btst Status_Facing; beq return; bset Status_Push
        //   mode $C0: btst Status_Facing; bne return; bset Status_Push
        return mode == 0x40 ? facingLeft : !facingLeft;
    }

    static CalcRoomInFrontProbe describeCalcRoomInFrontProbe(int angle, short gSpeed) {
        int rotation = (gSpeed < 0) ? 0x40 : 0xC0;
        int rotatedAngle = (angle + rotation) & 0xFF;

        // ROM probe direction: Sonic_WalkSpeed (S1) and CalcRoomInFront (S2) both use
        // the asymmetric quadrant rounding from AnglePos for dispatching the sensor probe.
        // This differs from the simple (angle+0x20)&0xC0 used for velocity adjustment
        // at the call site (loc_1300C / s2.asm).
        // Key difference: at rotated angle 0xA0, simple gives 0xC0 (RIGHT) but ROM gives
        // 0x80 (UP). Using simple causes false wall detections on steep slopes.
        int probeMode = anglePosQuadrant(rotatedAngle);
        short dynamicYOffset = (short) (((probeMode == 0x40 || probeMode == 0xC0) && (rotatedAngle & 0x38) == 0) ? 8 : 0);

        return switch (probeMode) {
            case 0x00 -> new CalcRoomInFrontProbe(probeMode, rotatedAngle, Direction.DOWN, (short) 0, (short) 10, dynamicYOffset);
            case 0x40 -> new CalcRoomInFrontProbe(probeMode, rotatedAngle, Direction.LEFT, (short) -10, (short) 0, dynamicYOffset);
            case 0x80 -> new CalcRoomInFrontProbe(probeMode, rotatedAngle, Direction.UP, (short) 0, (short) -10, dynamicYOffset);
            default -> new CalcRoomInFrontProbe(probeMode, rotatedAngle, Direction.RIGHT, (short) 10, (short) 0, dynamicYOffset);
        };
    }

    private SensorResult scanCalcRoomInFront(AbstractPlayableSprite sprite,
                                             CalcRoomInFrontProbe probe,
                                             short predictedDx,
                                             short predictedDy) {
        calcRoomProbe.sprite = sprite;
        int solidityBit = sprite.getLrbSolidBit();
        return calcRoomProbe.scanWorld(
                probe.globalDirection(),
                probe.offsetX(),
                probe.offsetY(),
                predictedDx,
                (short) (predictedDy + probe.dynamicYOffset()),
                solidityBit);
    }

    public void resolveGroundAttachment(AbstractPlayableSprite sprite,
                                        int positiveThreshold,
                                        BooleanSupplier hasObjectSupport) {
        resolveGroundAttachment(FrameCollisionPlan.terrainOnly(), sprite, positiveThreshold, hasObjectSupport);
    }

    public void resolveGroundAttachment(FrameCollisionPlan plan,
                                        AbstractPlayableSprite sprite,
                                        int positiveThreshold,
                                        BooleanSupplier hasObjectSupport) {
        requireTerrainOnlyPlan(plan, "resolveGroundAttachment");
        // ROM: btst #0,object_control(a0) at sonic3k.asm:21555-21561 skips the
        // entire status-based dispatch (which includes terrain probes and
        // air-state transitions) when object_control bit 0 is set. Mirror that
        // here so an object-controlling sprite (e.g. AIZ1 intro plane gripping
        // Sonic via {@code move.b #$53,object_control(a1)} at
        // sonic3k.asm:135507) never gets {@code setAir(true)} from a manual
        // ground probe — its position is owned by the controlling object.
        if (sprite.isObjectControlSuppressesMovement()) {
            return;
        }
        // ROM: S1 Sonic_AnglePos, S2 AnglePos, and S3K Player_AnglePos all
        // return early only when the player's Status_OnObj bit is set
        // (S3K: docs/skdisasm/sonic3k.asm:18735-18741). Object-side standing
        // masks can be stale after release; they must not suppress terrain
        // walk-off and airborne transition checks.
        if (sprite.isOnObject()) {
            if (hasObjectSupport == null
                    || hasObjectSupport.getAsBoolean()
                    || sprite.isObjectControlled()
                    || hasPendingStaleObjectSupportLoss(sprite)) {
                return;
            }
            // Engine-side object support can outlive the object/controller that set it
            // across transitions. The ROM's Player_AnglePos early return only applies
            // to a live Status_OnObj owner (sonic3k.asm:18735-18741); stale support must
            // fall through to the terrain walk-off path at sonic3k.asm:18839-18842.
            sprite.setOnObject(false);
        }

        updateGroundMode(sprite);

        SensorResult[] groundResult = terrainProbes(sprite, sprite.getGroundSensors(), "ground");
        SensorResult leftSensor = groundResult[0];
        SensorResult rightSensor = groundResult[1];

        SensorResult selectedResult = selectSensorWithAngle(sprite, rightSensor, leftSensor);
        // Refresh ground mode after the angle has been updated by selectSensorWithAngle.
        // The initial updateGroundMode (line 292) uses the PREVIOUS frame's end-angle for
        // sensor configuration. This second call uses the NEW angle from terrain probes,
        // matching the ROM's end-of-frame ground mode calculation.
        updateGroundMode(sprite);

        if (selectedResult == null) {
            // FindFloor initializes both angle outputs to the flagged empty-tile
            // value 3. Player_Angle sees that odd result and rounds the current
            // angle to its cardinal quadrant before AnglePos detaches the player
            // (sonic3k.asm:18749-18752,18804-18815,18839-18842; the S2 AnglePos
            // routine uses the same sequence). Engine probes represent an empty
            // tile as null, so preserve the ROM's angle normalization explicitly.
            // This is observable when a grounded player crosses the death plane:
            // Kill_Character sets Status_InAir, but its caller still completes
            // MoveSprite_TestGravity2 + AnglePos in the same frame.
            sprite.setAngle((byte) (((sprite.getAngle() & 0xFF) + 0x20) & 0xC0));
            if (sprite.isStickToConvex()) {
                return;
            }
            detachFromTerrain(sprite);
            return;
        }

        byte distance = selectedResult.distance();
        if (distance == 0) {
            return;
        }

        if (distance < 0) {
            if (sprite.getGroundMode() == GroundMode.RIGHTWALL) {
                if (distance < -14) {
                    if (preservesRightWallPenetrationOnDeepProbe(sprite)) {
                        sprite.setAngle((byte) 0xC0);
                        sprite.setRightWallPenetrationTimer(3);
                    }
                    return;
                }
                if (sprite.getRightWallPenetrationTimer() > 0) {
                    sprite.setRightWallPenetrationTimer(sprite.getRightWallPenetrationTimer() - 1);
                    sprite.setAngle((byte) 0xC0);
                    return;
                }
            }
            if (distance >= -14) {
                moveForSensorResult(sprite, selectedResult);
            }
            return;
        }

        if (distance > positiveThreshold) {
            if (sprite.isStickToConvex()) {
                moveForSensorResult(sprite, selectedResult);
                return;
            }
            detachFromTerrain(sprite);
            return;
        }

        moveForSensorResult(sprite, selectedResult);
    }

    private void detachFromTerrain(AbstractPlayableSprite sprite) {
        sprite.setAir(true);
        sprite.setPushing(false);
        // AnglePos writes Run to prev_anim when terrain support is lost. If the
        // selected animation stays Roll, that deliberate mismatch still restarts
        // its script on this frame; an unchanged raw Run correctly keeps its
        // cadence. This is shared by S1 (_incObj/Sonic AnglePos.asm:113-115,
        // 276-278,349-351,422-424), S2 (s2.asm:43108-43110,
        // 43216-43218, 43283-43285, 43350-43352), and S3K
        // (sonic3k.asm:18840-18842, 18965-18967, 19037-19039, 19109-19111).
        sprite.publishRunAsPreviousAnimation();
    }

    private boolean hasPendingStaleObjectSupportLoss(AbstractPlayableSprite sprite) {
        // AIZ1->AIZ2 reload order is a special case of that same Status_OnObj
        // rule. The ROM performs Load_Level/LoadSolids and player coordinate
        // offsets in the level-event path (docs/skdisasm/sonic3k.asm:104725-104756),
        // then the next player slot still sees Status_OnObj and skips AnglePos.
        // Later in that ExecuteObjects pass, Obj_AIZTransitionFloor observes
        // Current_act != 0, moves to x=$7FFF, and still calls SolidObjectTop
        // (104777-104790); the standing branch then clears OnObj/sets InAir
        // without moving the player (41793-41818, 41642-41679). Preserve the
        // status-bit skip until ObjectManager's inline finalizer consumes the
        // pending loss marker.
        return objectManager != null && objectManager.hasPendingStaleObjectSupportLoss(sprite);
    }

    private boolean preservesRightWallPenetrationOnDeepProbe(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return false;
        }
        CollisionRules rules = collisionRulesOrNull(sprite);
        if (rules != null && rules.rightWallDeepProbePreservesPenetration()) {
            return true;
        }
        var registry = GameServices.zoneRuntimeRegistryOrNull();
        return registry != null && registry.current().rightWallDeepProbePreservesPenetration();
    }

    private int getTerrainHeadroomDistance(AbstractPlayableSprite sprite, int hexAngle) {
        int overheadAngle = (hexAngle + 0x80) & 0xFF;
        int quadrant = (overheadAngle + 0x20) & 0xC0;
        var levelManager = sprite.currentLevelManager();
        if (levelManager != null && levelManager.getCurrentLevel() != null) {
            return scanCalcRoomOverHead(sprite, quadrant);
        }

        return fallbackSensorHeadroomDistance(sprite, quadrant);
    }

    private int scanCalcRoomOverHead(AbstractPlayableSprite sprite, int quadrant) {
        CalcRoomOverHeadProbe[] probes = describeCalcRoomOverHeadProbes(sprite, quadrant);
        if (probes.length == 0) {
            return Integer.MAX_VALUE;
        }

        int minDistance = Integer.MAX_VALUE;
        for (CalcRoomOverHeadProbe probe : probes) {
            int distance = scanHeadroomProbe(sprite, probe);
            minDistance = Math.min(minDistance, distance);
        }
        return minDistance;
    }

    static CalcRoomOverHeadProbe[] describeCalcRoomOverHeadProbes(AbstractPlayableSprite sprite, int quadrant) {
        int xRadius = sprite.getXRadius();
        int yRadius = sprite.getYRadius();

        return switch (quadrant) {
            case 0x00 -> new CalcRoomOverHeadProbe[] {
                    new CalcRoomOverHeadProbe(Direction.DOWN, xRadius, yRadius, 0, 0, sprite.getTopSolidBit()),
                    new CalcRoomOverHeadProbe(Direction.DOWN, -xRadius, yRadius, 0, 0, sprite.getTopSolidBit())
            };
            case 0x40 -> {
                int probeX = sprite.getCentreX() - yRadius;
                int xorDelta = (probeX ^ 0x0F) - probeX;
                yield new CalcRoomOverHeadProbe[] {
                        new CalcRoomOverHeadProbe(Direction.LEFT, -yRadius, -xRadius, xorDelta, 0, sprite.getLrbSolidBit()),
                        new CalcRoomOverHeadProbe(Direction.LEFT, -yRadius, xRadius, xorDelta, 0, sprite.getLrbSolidBit())
                };
            }
            case 0x80 -> {
                // ROM S1 Sonic_FindCeiling (sub FindNearestTile & FindFloor & FindWall.asm:
                // 361-403) probes x_pos +/- obWidth at the top edge (obY-obHeight) and applies
                // eori.w #$F to the Y before FindFloor upward. The engine's UP-direction
                // GroundSensor.scanVertical/verticalTileLookupY ALREADY models that flip (the
                // UP distance formula is the post-flip form, and verticalTileLookupY re-flips
                // wrapped negative rows). Passing the eori #$F as a Y pre-offset here would
                // DOUBLE-apply it, corrupting the ceiling distance: S1 SYZ2 f1088 ceiling tile
                // 0x0093 col 6 (height -2) gave dist 3 with the pre-flip, blocking a jump,
                // whereas ROM's Sonic_FindCeiling returns 8 there (BizHawk hook at 0x156CE on
                // bk2 frame 72595 = trace f1088: leftDist=8, obX=074F obW=9 col=6 — same probe
                // X/column as the engine), which is exactly what the UP path computes WITHOUT
                // the pre-flip. So the probe passes the plain top-edge Y (dy=0) and lets
                // scanVertical own the ceiling flip, matching the ordinary ceiling sensor path.
                yield new CalcRoomOverHeadProbe[] {
                        new CalcRoomOverHeadProbe(Direction.UP, xRadius, -yRadius, 0, 0, sprite.getLrbSolidBit()),
                        new CalcRoomOverHeadProbe(Direction.UP, -xRadius, -yRadius, 0, 0, sprite.getLrbSolidBit())
                };
            }
            case 0xC0 -> new CalcRoomOverHeadProbe[] {
                    new CalcRoomOverHeadProbe(Direction.RIGHT, yRadius, -xRadius, 0, 0, sprite.getLrbSolidBit()),
                    new CalcRoomOverHeadProbe(Direction.RIGHT, yRadius, xRadius, 0, 0, sprite.getLrbSolidBit())
            };
            default -> new CalcRoomOverHeadProbe[0];
        };
    }

    private int scanHeadroomProbe(AbstractPlayableSprite sprite, CalcRoomOverHeadProbe probe) {
        calcRoomProbe.sprite = sprite;
        SensorResult result = calcRoomProbe.scanWorld(
                probe.globalDirection(),
                (short) probe.worldOffsetX(),
                (short) probe.worldOffsetY(),
                (short) probe.dx(),
                (short) probe.dy(),
                probe.solidityBit());
        return result != null ? result.distance() : Integer.MAX_VALUE;
    }

    private int fallbackSensorHeadroomDistance(AbstractPlayableSprite sprite, int quadrant) {

        Sensor[] pushSensors = sprite.getPushSensors();
        Sensor[] sensors = switch (quadrant) {
            case 0x00 -> sprite.getCeilingSensors();
            case 0x40 -> pushSensors != null ? new Sensor[]{pushSensors[0]} : sprite.getCeilingSensors();
            case 0x80 -> sprite.getCeilingSensors();
            case 0xC0 -> pushSensors != null ? new Sensor[]{pushSensors[1]} : sprite.getCeilingSensors();
            default -> null;
        };

        if (sensors == null) {
            return Integer.MAX_VALUE;
        }

        int minDistance = Integer.MAX_VALUE;
        for (Sensor sensor : sensors) {
            boolean wasActive = sensor.isActive();
            sensor.setActive(true);
            SensorResult result = sensor.scan();
            sensor.setActive(wasActive);
            if (result != null) {
                int clearance = Math.max(result.distance(), 0);
                minDistance = Math.min(minDistance, clearance);
            }
        }
        return minDistance;
    }

    public void resolveAirCollision(AbstractPlayableSprite sprite,
                                    Consumer<AbstractPlayableSprite> landingHandler) {
        resolveAirCollision(FrameCollisionPlan.terrainOnly(), sprite, landingHandler, false);
    }

    public void resolveAirCollision(FrameCollisionPlan plan,
                                    AbstractPlayableSprite sprite,
                                    Consumer<AbstractPlayableSprite> landingHandler) {
        resolveAirCollision(plan, sprite, landingHandler, false);
    }

    /**
     * @param forceFloorCheck when true, floor collision in quadrants 0x40 and
     *     0xC0 runs even when ySpeed &lt; 0.  ROM equivalent: the
     *     {@code WindTunnel_flag} check at sonic3k.asm:24204/24299 bypasses
     *     the {@code tst.w y_vel} early return so floor terrain always
     *     constrains the player inside HCZ water tunnels.
     */
    public void resolveAirCollision(AbstractPlayableSprite sprite,
                                    Consumer<AbstractPlayableSprite> landingHandler,
                                    boolean forceFloorCheck) {
        resolveAirCollision(FrameCollisionPlan.terrainOnly(), sprite, landingHandler, forceFloorCheck);
    }

    public void resolveAirCollision(FrameCollisionPlan plan,
                                    AbstractPlayableSprite sprite,
                                    Consumer<AbstractPlayableSprite> landingHandler,
                                    boolean forceFloorCheck) {
        resolveAirCollision(plan, sprite, landingHandler, null, forceFloorCheck);
    }

    /**
     * Resolves airborne terrain collision and publishes the exact pair of floor
     * probes when that pair produces a landing.
     *
     * <p>S3K copies the shared {@code Primary_Angle}/{@code Secondary_Angle}
     * bytes into the player's {@code next_tilt}/{@code tilt} fields after the
     * movement dispatch. Callers that model those bytes can consume the probe
     * pair here; callers without that player-tail behavior use the legacy
     * overload.
     */
    public void resolveAirCollision(FrameCollisionPlan plan,
                                    AbstractPlayableSprite sprite,
                                    Consumer<AbstractPlayableSprite> landingHandler,
                                    Consumer<SensorResult[]> landingProbeHandler,
                                    boolean forceFloorCheck) {
        requireTerrainOnlyPlan(plan, "resolveAirCollision");
        // SonicKnux_DoLevelCollision uses explicit world-space floor, ceiling,
        // and wall checks. The engine's sensor offsets otherwise rotate from a
        // stale grounded mode retained after leaving a loop or wall, causing an
        // airborne floor check to scan sideways.
        CollisionRules collisionRules = collisionRulesOrNull(sprite);
        if (collisionRules != null
                && collisionRules.air() != null
                && collisionRules.air().probesResetStaleGroundMode()) {
            sprite.setGroundMode(GroundMode.GROUND);
        }
        int quadrant = TrigLookupTable.calcMovementQuadrant(sprite.getXSpeed(), sprite.getYSpeed());
        switch (quadrant) {
            case 0x00 -> {
                doWallCheckBoth(sprite);
                SensorResult[] groundResult = terrainProbes(sprite, sprite.getGroundSensors(), "ground");
                publishAirFloorAngleRegisters(groundResult);
                doTerrainCollisionAir(sprite, groundResult, landingHandler, landingProbeHandler);
            }
            case 0x40 -> {
                boolean wallHit = doWallCheck(sprite, 0);
                if (wallHit) {
                    if (!airLeftWallHitContinuesIntoCeilingSeparation(sprite)) {
                        return;
                    }
                }
                SensorResult[] ceilingResult = terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
                boolean ceilingHit = doCeilingCollisionInternal(sprite, ceilingResult);
                if (!ceilingHit) {
                    SensorResult[] groundResult = terrainProbes(sprite, sprite.getGroundSensors(), "ground");
                    publishAirFloorAngleRegisters(groundResult);
                    doTerrainCollisionAirDirect(sprite, groundResult, landingHandler,
                            landingProbeHandler, forceFloorCheck);
                }
            }
            case 0x80 -> {
                doWallCheckBoth(sprite);
                SensorResult[] ceilingResult = terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
                doCeilingCollision(sprite, ceilingResult);
            }
            case 0xC0 -> {
                if (doWallCheck(sprite, 1)) {
                    if (!airRightWallHitContinuesIntoCeilingSeparation(sprite)) {
                        return;
                    }
                }
                SensorResult[] ceilingResult = terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
                boolean ceilingHit = doCeilingCollisionInternal(sprite, ceilingResult);
                if (!ceilingHit) {
                    SensorResult[] groundResult = terrainProbes(sprite, sprite.getGroundSensors(), "ground");
                    publishAirFloorAngleRegisters(groundResult);
                    doTerrainCollisionAirDirect(sprite, groundResult, landingHandler,
                            landingProbeHandler, forceFloorCheck);
                }
            }
            default -> {
            }
        }
    }

    private static void requireTerrainOnlyPlan(FrameCollisionPlan plan, String operation) {
        FrameCollisionPlan effectivePlan = Objects.requireNonNull(plan, "plan");
        if (!effectivePlan.runsTerrainProbes()
                || effectivePlan.runsSolidObjectResolution()
                || effectivePlan.runsPostResolutionGroundMode()) {
            throw new IllegalArgumentException(operation + " requires a terrain-only FrameCollisionPlan");
        }
    }

    private boolean airRightWallHitContinuesIntoCeilingSeparation(AbstractPlayableSprite sprite) {
        CollisionRules rules = collisionRulesOrNull(sprite);
        return rules != null
                && rules.air() != null
                && rules.air().rightWallHitContinuesIntoCeilingSeparation();
    }

    private boolean airLeftWallHitContinuesIntoCeilingSeparation(AbstractPlayableSprite sprite) {
        CollisionRules rules = collisionRulesOrNull(sprite);
        return rules != null
                && rules.air() != null
                && rules.air().leftWallHitContinuesIntoCeilingSeparation();
    }

    /**
     * Air terrain landing with the speed-dependent threshold check.
     * ROM: only quadrant 0x00 applies this threshold (sonic.asm / s2.asm).
     * Quadrants 0x40 and 0xC0 use {@link #doTerrainCollisionAirDirect} instead.
     */
    private void doTerrainCollisionAir(AbstractPlayableSprite sprite,
                                       SensorResult[] results,
                                       Consumer<AbstractPlayableSprite> landingHandler) {
        doTerrainCollisionAir(sprite, results, landingHandler, null);
    }

    private void doTerrainCollisionAir(AbstractPlayableSprite sprite,
                                       SensorResult[] results,
                                       Consumer<AbstractPlayableSprite> landingHandler,
                                       Consumer<SensorResult[]> landingProbeHandler) {
        if (sprite.getYSpeed() < 0) {
            return;
        }

        SensorResult lowestResult = findLowestSensorResult(results);
        if (lowestResult == null) {
            return;
        }
        boolean zeroDistanceLanding = shouldTreatZeroDistanceAsGround(sprite, lowestResult);
        if (lowestResult.distance() > 0 || (lowestResult.distance() == 0 && !zeroDistanceLanding)) {
            return;
        }

        short ySpeedPixels = (short) (sprite.getYSpeed() >> 8);
        short threshold = (short) (-(ySpeedPixels + 8));
        boolean canLand = (results[0] != null && results[0].distance() >= threshold)
                || (results[1] != null && results[1].distance() >= threshold);

        if (canLand) {
            publishLandingProbes(results, landingProbeHandler);
            landOnFloor(sprite, lowestResult, landingHandler);
        }
    }

    /**
     * Air terrain landing WITHOUT the speed-dependent threshold check.
     * ROM: quadrants 0x40 and 0xC0 skip the threshold — they land whenever
     * d1 < 0 (floor detected above Sonic's foot sensors).
     *
     * @param forceFloorCheck when true, bypasses the {@code ySpeed < 0} early
     *     return.  ROM: {@code WindTunnel_flag} at sonic3k.asm:24204/24299
     *     gates this — when set, the floor check runs regardless of y velocity
     *     direction, keeping the player constrained inside HCZ water tunnels.
     */
    private void doTerrainCollisionAirDirect(AbstractPlayableSprite sprite,
                                              SensorResult[] results,
                                              Consumer<AbstractPlayableSprite> landingHandler,
                                              boolean forceFloorCheck) {
        doTerrainCollisionAirDirect(sprite, results, landingHandler, null, forceFloorCheck);
    }

    private void doTerrainCollisionAirDirect(AbstractPlayableSprite sprite,
                                              SensorResult[] results,
                                              Consumer<AbstractPlayableSprite> landingHandler,
                                              Consumer<SensorResult[]> landingProbeHandler,
                                              boolean forceFloorCheck) {
        // ROM: tst.b (WindTunnel_flag).w / bne.s loc_12148
        //      tst.w y_vel(a0) / bmi.s locret_12170
        if (!forceFloorCheck && sprite.getYSpeed() < 0) {
            return;
        }

        SensorResult lowestResult = findLowestSensorResult(results);
        if (lowestResult == null) {
            return;
        }
        boolean zeroDistanceLanding = shouldTreatZeroDistanceAsGround(sprite, lowestResult);
        if (lowestResult.distance() > 0 || (lowestResult.distance() == 0 && !zeroDistanceLanding)) {
            return;
        }

        // No threshold check — land immediately if any floor found (d1 < 0).
        publishLandingProbes(results, landingProbeHandler);
        landOnFloor(sprite, lowestResult, landingHandler);
    }

    private static void publishLandingProbes(SensorResult[] results,
                                             Consumer<SensorResult[]> landingProbeHandler) {
        if (landingProbeHandler != null) {
            landingProbeHandler.accept(results);
        }
    }

    private boolean shouldTreatZeroDistanceAsGround(AbstractPlayableSprite sprite, SensorResult support) {
        if (support == null || support.distance() != 0) {
            return false;
        }
        com.openggf.level.LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null) {
            return false;
        }
        com.openggf.game.ZoneFeatureProvider zoneFeatures = levelManager.getZoneFeatureProvider();
        return zoneFeatures != null
                && zoneFeatures.shouldTreatZeroDistanceAirLandingAsGround(sprite, support);
    }

    /** Shared landing logic: snap to floor surface, set angle, invoke landing handler. */
    private void landOnFloor(AbstractPlayableSprite sprite, SensorResult result,
                             Consumer<AbstractPlayableSprite> landingHandler) {
        moveForSensorResult(sprite, result);
        if ((result.angle() & 0x01) != 0) {
            sprite.setAngle((byte) 0x00);
        } else {
            sprite.setAngle(result.angle());
        }
        landingHandler.accept(sprite);
        updateGroundMode(sprite);
    }

    private void doCeilingCollision(AbstractPlayableSprite sprite, SensorResult[] results) {
        SensorResult lowestResult = findLowestSensorResult(results);
        if (lowestResult == null || lowestResult.distance() >= 0) {
            return;
        }
        moveForSensorResult(sprite, lowestResult);

        int ceilingAngle = lowestResult.angle() & 0xFF;
        boolean canLandOnCeiling = ((ceilingAngle + 0x20) & 0x40) != 0;

        if (canLandOnCeiling) {
            // ROM Sonic_FloorUp.angledceiling (s1disasm/_incObj/01 Sonic.asm:1720-1731;
            // S2 loc_1B02C s2.asm:38048-38057; S3K loc_120EA sonic3k.asm:24258-24264):
            // when an in-air player lands on an angled ceiling, the in-air status bit
            // is cleared (Sonic_ResetOnFloor) and inertia is set from y_vel (negated
            // for ascending slopes). resetWallCeilingLandingState() clears the in-air
            // flag below — which, for a hurt player, also clears the hurt routine
            // (AbstractPlayableSprite.setAir).
            boolean wasHurt = sprite.isHurt();
            int savedDoubleJumpFlag = sprite.getDoubleJumpFlag();
            if ((lowestResult.angle() & 0x01) != 0) {
                sprite.setAngle((byte) 0x80);
            } else {
                sprite.setAngle(lowestResult.angle());
            }
            updateGroundMode(sprite);
            resetWallCeilingLandingState(sprite, ceilingAngle);
            if (wasHurt) {
                // ROM Sonic_HurtStop (s1disasm/_incObj/01 Sonic.asm:1918-1923; S2
                // s2.asm:38216-38221; S3K sub_12318 sonic3k.asm:24492-24496): after
                // Sonic_Floor/DoLevelCollision returns, the hurt routine re-checks
                // Status_InAir; since this angled-ceiling land cleared it, the routine
                // zeroes y_vel/x_vel/inertia (ground_vel) before reverting to control.
                // The angledceiling inertia=y_vel assignment is therefore overwritten
                // with 0. Identical in all three games — core hurt recovery, not a
                // per-game divergence. Without this the engine left ground_vel = the
                // converted hurt knockback velocity (SYZ1 f4430: inertia +0x370).
                sprite.setYSpeed((short) 0);
                sprite.setXSpeed((short) 0);
                sprite.setGSpeed((short) 0);
            } else {
                // Player_HitCeilingAndWalls reaches the same
                // Player_TouchFloor_Check_Spindash tail as an ordinary floor
                // landing. In S3K that tail can immediately re-launch Sonic
                // through BubbleShield_Bounce before ground_vel samples the
                // resulting y_vel (sonic3k.asm:24248-24264,24325-24426).
                sprite.applyPostObjectLandingAbilities(savedDoubleJumpFlag);
                short gSpeed = sprite.getYSpeed();
                if ((ceilingAngle & 0x80) != 0) {
                    gSpeed = (short) -gSpeed;
                }
                sprite.setGSpeed(gSpeed);
            }
            updateGroundMode(sprite);
        } else {
            sprite.setYSpeed((short) 0);
        }
    }

    private void resetWallCeilingLandingState(AbstractPlayableSprite sprite, int angle) {
        PlayerAnimationRules animationRules = playerAnimationRulesOrNull(sprite);
        if (sprite.isObjectControlled()) {
            publishWalkOnAcceptedLanding(sprite, animationRules);
            sprite.setAir(false);
            return;
        }

        PlayerMovementRules rules = playerMovementRulesOrNull(sprite);
        boolean preservePinballRoll = rules != null && rules.landing().pinballLandingPreservesRoll();
        boolean preservePinballMode = rules != null && rules.landing().pinballLandingPreservesPinballMode();
        if (sprite.getRolling() && (!sprite.getPinballMode() || !preservePinballRoll)) {
            if (rules != null && rules.landing().landingRollClearUsesCurrentYRadiusDelta()) {
                int oldYRadius = sprite.getYRadius();
                int centreX = sprite.getCentreX();
                int centreY = sprite.getCentreY();
                boolean wallLanding = sprite.getGroundMode() == GroundMode.LEFTWALL
                        || sprite.getGroundMode() == GroundMode.RIGHTWALL;
                sprite.setRolling(false);
                if (wallLanding) {
                    // S3K Player_TouchFloor restores radii and adjusts y_pos only
                    // (docs/skdisasm/sonic3k.asm:24335-24363). Preserve engine centre X when
                    // leaving the narrower roll shape after updateGroundMode has selected a wall.
                    sprite.setCentreXPreserveSubpixel((short) centreX);
                }

                int delta = oldYRadius - sprite.getStandYRadius();
                if (((angle + 0x40) & 0x80) != 0) {
                    delta = -delta;
                }
                sprite.setCentreYPreserveSubpixel((short) (centreY + delta));
            } else {
                // S1/S2 Sonic_ResetOnFloor always clears rolling with a fixed
                // y_pos lift after restoring standing radii (s1 Obj01.asm
                // Sonic_ResetOnFloor, s2.asm:37781-37787), independent of the
                // wall/ceiling angle that led into the reset.
                int centreX = sprite.getCentreX();
                boolean wallLanding = sprite.getGroundMode() == GroundMode.LEFTWALL
                        || sprite.getGroundMode() == GroundMode.RIGHTWALL;
                sprite.setRolling(false);
                if (wallLanding) {
                    sprite.setCentreXPreserveSubpixel((short) centreX);
                }
                sprite.setY((short) (sprite.getY() - sprite.getRollHeightAdjustment()));
                // Sonic_ResetOnFloor's ball branch writes id_Walk before the y_pos
                // lift (s1disasm/_incObj/01 Sonic.asm:1858-1866). The assembled
                // FixBugs = 0 branch (sonic.asm:20) is the one modelled here: the
                // Walk store sits INSIDE `btst #2,obStatus`, so it fires exactly
                // when the ball flag was set and is skipped otherwise — which is
                // why a non-rolling S1 landing keeps its Spring/other animation.
                // (FixBugs = 1 hoists the same store above the btst, making it
                // unconditional; that is the S2/S3K Player_TouchFloor shape already
                // covered by publishWalkOnAcceptedLanding.) Without this the
                // angled ceiling/wall landing left the raw anim byte on Roll for
                // every game whose angledLandingPublishesWalk is false, so the
                // player DPLC kept requesting roll mapping frames after the
                // rolling flag had already been cleared.
                publishWalkOnRollClearedLanding(sprite);
            }
        }

        if (!(sprite.getRolling() && sprite.getPinballMode() && preservePinballMode)) {
            sprite.setPinballMode(false);
        }
        if (!sprite.getPinballMode()) {
            publishWalkOnAcceptedLanding(sprite, animationRules);
        }
        sprite.setAir(false);
        sprite.setPushing(false);
        sprite.setRollingJump(false);
        sprite.setJumping(false);
        sprite.setFlipAngle(0);
        sprite.setFlipType(0);
        sprite.setFlipTurned(false);
        sprite.setFlipsRemaining(0);
        sprite.setLookDelayCounter((short) 0);
    }

    private void publishWalkOnAcceptedLanding(
            AbstractPlayableSprite sprite,
            PlayerAnimationRules animationRules) {
        if (!sprite.getSpindash()
                && animationRules != null
                && animationRules.angledLandingPublishesWalk()) {
            // Player_TouchFloor_Check_Spindash publishes Walk before clearing
            // the airborne state on every accepted S2/S3K terrain landing,
            // including the angled ceiling/wall path (s2.asm:38049-38052,
            // 38123-38127; sonic3k.asm:24258-24325). S1 can retain Spring.
            int walkAnimationId = sprite.resolveAnimationId(CanonicalAnimation.WALK);
            if (walkAnimationId >= 0) {
                sprite.setAnimationId(walkAnimationId);
            }
        }
    }

    /**
     * The Walk store inside {@code Sonic_ResetOnFloor}'s ball branch
     * (s1disasm/_incObj/01 Sonic.asm:1863-1865 under {@code FixBugs = 0};
     * s2.asm's {@code Sonic_ResetOnFloor_Part2} carries the same store). It runs
     * only where the rolling flag was actually cleared, which is the condition the
     * caller has already established, and it is independent of the
     * {@code angledLandingPublishesWalk} rule — that rule describes the FixBugs = 1 /
     * S3K shape where Walk is published on every accepted landing, rolling or not.
     */
    private void publishWalkOnRollClearedLanding(AbstractPlayableSprite sprite) {
        if (sprite.getSpindash()) {
            return;
        }
        int walkAnimationId = sprite.resolveAnimationId(CanonicalAnimation.WALK);
        if (walkAnimationId >= 0) {
            // Looking/crouching are engine-side projections of native anim writes,
            // not independent ROM status bits, so the explicit Walk store replaces
            // them here exactly as it does on the floor-landing owner.
            sprite.setLookingUp(false);
            sprite.setCrouching(false);
            sprite.setAnimationId(walkAnimationId);
        }
    }

    private boolean doCeilingCollisionInternal(AbstractPlayableSprite sprite, SensorResult[] results) {
        SensorResult lowestResult = findLowestSensorResult(results);
        if (lowestResult == null || lowestResult.distance() >= 0) {
            return false;
        }

        moveForSensorResult(sprite, lowestResult);
        if (sprite.getYSpeed() < 0) {
            sprite.setYSpeed((short) 0);
        }
        return true;
    }

    private void doWallCheckBoth(AbstractPlayableSprite sprite) {
        Sensor[] pushSensors = sprite.getPushSensors();
        if (pushSensors == null) {
            return;
        }

        for (int i = 0; i < 2; i++) {
            SensorResult result = pushSensors[i].scan((short) 0, (short) 0);
            publishPrimaryAngleIfBackedByTile(result);

            if (result != null && result.distance() < 0) {
                moveForSensorResult(sprite, result);
                sprite.setXSpeed((short) 0);
            }
        }
    }

    private boolean doWallCheck(AbstractPlayableSprite sprite, int sensorIndex) {
        Sensor[] pushSensors = sprite.getPushSensors();
        if (pushSensors == null) {
            return false;
        }

        SensorResult result = pushSensors[sensorIndex].scan((short) 0, (short) 0);
        publishPrimaryAngleIfBackedByTile(result);
        if (result != null && result.distance() < 0) {
            moveForSensorResult(sprite, result);
            sprite.setXSpeed((short) 0);
            sprite.setGSpeed(sprite.getYSpeed());
            return true;
        }
        return false;
    }

    private SensorResult selectSensorWithAngle(AbstractPlayableSprite sprite,
                                               SensorResult rightSensor,
                                               SensorResult leftSensor) {
        if (rightSensor == null && leftSensor == null) {
            return null;
        }
        if (rightSensor == null) {
            applyAngleFromSensor(sprite, leftSensor.angle());
            return leftSensor;
        }
        if (leftSensor == null) {
            applyAngleFromSensor(sprite, rightSensor.angle());
            return rightSensor;
        }

        GroundMode mode = sprite.getGroundMode();
        boolean leftIsPrimary = (mode == GroundMode.RIGHTWALL || mode == GroundMode.LEFTWALL);
        SensorResult primary = leftIsPrimary ? leftSensor : rightSensor;
        SensorResult secondary = leftIsPrimary ? rightSensor : leftSensor;
        SensorResult selected = primary.distance() < secondary.distance() ? primary : secondary;
        applyAngleFromSensor(sprite, selected.angle());
        return selected;
    }

    private void applyAngleFromSensor(AbstractPlayableSprite sprite, byte sensorAngle) {
        if ((sensorAngle & 0x01) != 0) {
            sprite.setAngle((byte) ((sprite.getAngle() + 0x20) & 0xC0));
            return;
        }

        PlayerMovementRules rules = playerMovementRulesOrNull(sprite);
        if (rules == null || rules.angleDiffCardinalSnap()) {
            int currentAngle = sprite.getAngle() & 0xFF;
            int newAngle = sensorAngle & 0xFF;
            int diff = Math.abs(newAngle - currentAngle);
            if (diff > 0x80) {
                diff = 0x100 - diff;
            }
            if (diff >= 0x20) {
                sprite.setAngle((byte) ((currentAngle + 0x20) & 0xC0));
                return;
            }
        }

        sprite.setAngle(sensorAngle);
    }

    private static CollisionRules collisionRulesOrNull(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return null;
        }
        GameRules rules = sprite.getGameRules();
        if (rules != null && rules.collision() != null) {
            return rules.collision();
        }
        return null;
    }

    private static PlayerMovementRules playerMovementRulesOrNull(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return null;
        }
        GameRules rules = sprite.getGameRules();
        if (rules != null && rules.playerMovement() != null) {
            return rules.playerMovement();
        }
        return null;
    }

    private static PlayerAnimationRules playerAnimationRulesOrNull(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return null;
        }
        GameRules rules = sprite.getGameRules();
        if (rules != null && rules.playerAnimation() != null) {
            return rules.playerAnimation();
        }
        return null;
    }

    private void updateGroundMode(AbstractPlayableSprite sprite) {
        // ROM dispatch (both S1 and S2):
        //   0x40 → WalkVertL (probes LEFT)  → LEFTWALL
        //   0x80 → WalkCeiling              → CEILING
        //   0xC0 → WalkVertR (probes RIGHT) → RIGHTWALL
        //   else → WalkSpeed (floor)         → GROUND
        int angle = sprite.getAngle() & 0xFF;
        int modeBits = anglePosQuadrant(angle);

        GroundMode newMode = switch (modeBits) {
            case 0x00 -> GroundMode.GROUND;
            case 0x40 -> GroundMode.LEFTWALL;
            case 0x80 -> GroundMode.CEILING;
            default -> GroundMode.RIGHTWALL;
        };

        if (newMode != sprite.getGroundMode()) {
            sprite.setGroundMode(newMode);
        }
    }

    /**
     * ROM-accurate ground mode quadrant from Sonic_AnglePos.
     *
     * <p>Both S1 (Sonic AnglePos.asm:8-42) and S2 (s2.asm:42572-42591) use identical logic
     * with asymmetric rounding at exact boundary angles 0x20 and 0xA0, where the simplified
     * {@code (angle + 0x20) & 0xC0} gives wrong results:
     * <ul>
     *   <li>Angle 0x20: simplified → 0x40 (LEFTWALL), ROM → 0x00 (GROUND)</li>
     *   <li>Angle 0xA0: simplified → 0xC0 (RIGHTWALL), ROM → 0x80 (CEILING)</li>
     * </ul>
     *
     * <p>The ROM reloads the raw angle and branches on the sign of (angle + 0x20):
     * <ul>
     *   <li>Positive path (bit 7 clear): adds 0x1F (rounds boundary toward previous quadrant)</li>
     *   <li>Negative path (bit 7 set): subtracts 1 for angles ≥ 0x80, then adds 0x20</li>
     * </ul>
     *
     * <p><b>Note:</b> Used for both Sonic_AnglePos ground mode dispatch AND the probe
     * direction in Sonic_WalkSpeed / CalcRoomInFront. The velocity adjustment direction
     * at the call site (loc_1300C) uses the simple {@code (angle + 0x20) & 0xC0} separately.
     *
     * @param angle raw sprite angle (0x00-0xFF)
     * @return quadrant bits: 0x00 (GROUND), 0x40 (LEFTWALL), 0x80 (CEILING), 0xC0 (RIGHTWALL)
     */
    static int anglePosQuadrant(int angle) {
        angle &= 0xFF;
        int check = (angle + 0x20) & 0xFF;
        if ((check & 0x80) != 0) {
            // Negative path: (angle + 0x20) has bit 7 set
            int d0 = angle;
            if ((angle & 0x80) != 0) {
                d0 = (d0 - 1) & 0xFF;
            }
            return (d0 + 0x20) & 0xC0;
        } else {
            // Positive path: (angle + 0x20) has bit 7 clear
            int d0 = angle;
            if ((angle & 0x80) != 0) {
                d0 = (d0 + 1) & 0xFF;
            }
            return (d0 + 0x1F) & 0xC0;
        }
    }

    private SensorResult findLowestSensorResult(SensorResult[] results) {
        SensorResult lowest = null;
        for (SensorResult result : results) {
            if (result != null && (lowest == null || result.distance() < lowest.distance())) {
                lowest = result;
            }
        }
        return lowest;
    }

    private void moveForSensorResult(AbstractPlayableSprite sprite, SensorResult result) {
        // ROM-accurate: collision adjustment uses add.w/sub.w on pixel position,
        // preserving subpixel fraction. Using shiftX/shiftY instead of setX/setY
        // to avoid zeroing accumulated subpixels.
        byte distance = result.distance();
        switch (result.direction()) {
            case UP -> sprite.shiftY(-distance);
            case DOWN -> sprite.shiftY(distance);
            case LEFT -> sprite.shiftX(-distance);
            case RIGHT -> sprite.shiftX(distance);
        }
    }

    static record CalcRoomInFrontProbe(int mode,
                                       int rotatedAngle,
                                       Direction globalDirection,
                                       short offsetX,
                                       short offsetY,
                                       short dynamicYOffset) {
    }

    static record CalcRoomOverHeadProbe(Direction globalDirection,
                                        int worldOffsetX,
                                        int worldOffsetY,
                                        int dx,
                                        int dy,
                                        int solidityBit) {
    }

}

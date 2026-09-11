package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x4E - CNZ wire cage.
 *
 * <p>Ports the S&K-side {@code Obj_CNZWireCage} player interaction routine
 * ({@code sub_338C4}). The visible cage is level art; this object latches a
 * player into object control and carries them around the cage rim using
 * {@code GetSineCosine}.
 */
public final class CnzWireCageObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final int COOLDOWN_AFTER_RELEASE = 0x10;
    private static final int MIN_SPEED_TO_CONTINUE = 0x300;
    private static final int MIN_AIR_ATTACH_SPEED = 0x400;
    private static final int JUMP_RELEASE_X_SPEED = 0x800;
    private static final int JUMP_RELEASE_Y_SPEED = -0x200;
    private static final int EDGE_RELEASE_X_SPEED = 0x100;
    private static final int P1_STANDING_BIT = 3;
    private static final int P2_STANDING_BIT = 4;
    private static final int DIRTY_P2_STANDING_BIT = 1;
    private static final int CAPTURE_ANIMATION = 0;
    private static final int RELEASE_ANIMATION = 0;
    private static final int RELEASE_PREVIOUS_ANIMATION = 1;
    private static final int[] CAPTURE_FRAMES = {
            0x76, 0x76, 0x77, 0x77, 0x6C, 0x6C, 0x6D, 0x6D, 0x6E, 0x6E, 0x6F, 0x6F,
            0x70, 0x70, 0x71, 0x71, 0x72, 0x72, 0x73, 0x73, 0x74, 0x74, 0x75, 0x75
    };
    private static final int[] RELEASE_FRAMES = {
            0x5E, 0x5E, 0x5E, 0x5F, 0x5F, 0x5F, 0x5F, 0x60, 0x60, 0x60, 0x60, 0x60,
            0x61, 0x61, 0x61, 0x61, 0x5C, 0x5C, 0x5C, 0x5C, 0x5D, 0x5D, 0x5D, 0x5D
    };

    private int verticalOffset;
    private int verticalRange;
    private int verticalVelocity;
    private final Map<AbstractPlayableSprite, CageState> riders = new IdentityHashMap<>();

    /**
     * Tracks whether the leader (Player 1) has been the cage's primary rider
     * since this cage instance was constructed. After Player 1 releases,
     * this only affects sidekick latches whose standing bit was written
     * under the dirty {@code d6=1} path: ROM's later P2 call uses the clean
     * {@code d6=4} from {@code docs/skdisasm/sonic3k.asm:69835-69846}, so
     * {@code btst d6,status(a0)} misses at
     * {@code docs/skdisasm/sonic3k.asm:69872-69874} and falls out before the
     * mounted branch. Clean P2 latches still have status bit 4 set and must
     * continue through {@code loc_339A0}.
     */
    private boolean leaderHasReleased;

    public CnzWireCageObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CNZWireCage");
        this.verticalOffset = (spawn.subtype() & 0xFF) << 3;
        this.verticalRange = verticalOffset << 1;
        this.verticalVelocity = (spawn.renderFlags() & 0x01) != 0 ? 1 : -1;
    }

    /**
     * ROM parity: the cage's per-frame routine ends with
     * {@code jmp (Delete_Sprite_If_Not_In_Range).l} (sonic3k.asm:69867 ->
     * 37301-37317), which falls through to {@code Delete_Current_Sprite}
     * (sonic3k.asm:36108-36124) once the cage has drifted $200+ pixels past
     * the camera's coarse-back chunk. {@code Delete_Current_Sprite} zeros
     * the cage's entire SST. Any sprite still latched onto this cage's slot
     * (e.g. a Tails CPU sidekick stuck in {@code object_control = 0x43} due
     * to the leader-released frozen state) reads zero from
     * {@code (a3)} on its next {@code sub_13EFC} call (sonic3k.asm:26824),
     * fails the {@code Tails_CPU_interact} compare, and warps to
     * {@code (0x7F00, 0)} via {@code sub_13ECA} (sonic3k.asm:26800).
     * <p>
     * Engine analog: {@link com.openggf.level.objects.ObjectManager}'s
     * {@code unloadCounterBasedOutOfRange} removes this instance from the
     * active object list and calls {@code onUnload()}, but the cage's
     * {@code destroyed} flag is never flipped by its own routine because
     * {@code Delete_Sprite_If_Not_In_Range} models reap-by-camera-distance,
     * not self-destruction. The sticky
     * {@link com.openggf.sprites.playable.AbstractPlayableSprite#getLatchedSolidObjectInstance()}
     * reference held by the sidekick therefore stays non-null and equal
     * to {@code lastRidingInstance} across the unload, so the
     * {@link com.openggf.sprites.playable.SidekickCpuController}'s riding-
     * instance-loss check at {@code currentRidingInstance.isDestroyed()}
     * needs the unload itself to flip {@code destroyed}. Mark this instance
     * destroyed at unload time to ROM-mirror the SST zeroing and unblock
     * the freed-slot despawn at CNZ1 trace F2262.
     */
    @Override
    public void onUnload() {
        ObjectLifetimeOps.destroyLatched(this);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite leader = null;
        boolean leaderDplcClobberedD6 = false;
        if (playerEntity instanceof AbstractPlayableSprite player) {
            leader = player;
            CageState leaderState = riders.get(player);
            boolean wasLeaderLatched = leaderState != null && leaderState.latched;
            int leaderMappingFrameBefore = player.getMappingFrame();
            processPlayer(vIntRunCount, player, false, false);
            leaderDplcClobberedD6 = wasLeaderLatched
                    && player.getMappingFrame() != leaderMappingFrameBefore;
            CageState updated = riders.get(player);
            if (wasLeaderLatched && (updated == null || !updated.latched)) {
                // Leader just released this cage on this frame. ROM-side, this
                // is the moment Perform_Player_DPLC stops running for the
                // leader; from now on the d6 register stays uncorrupted at 3
                // (then +1 = 4 for the sidekick), so sub_338C4's
                // btst d6,status(a0) test fails for the sidekick because the
                // cage's status bit was originally set at position 1 (not 4).
                // Cf. sonic3k.asm:69873-69878 / 69895-69897.
                leaderHasReleased = true;
            }
        }

        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        AbstractPlayableSprite nativeP2 = nativeP2From(services, leader);
        if (nativeP2 != null) {
            processPlayer(vIntRunCount, nativeP2, true, leaderDplcClobberedD6);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The CNZ cage is drawn as level art; this object owns player control.
    }

    private void processPlayer(int vIntRunCount, AbstractPlayableSprite player, boolean isSidekick,
                               boolean leaderDplcClobberedD6) {
        CageState state = riders.computeIfAbsent(player, ignored -> new CageState());
        if (state.latched) {
            if (state.standingBit != currentStandingBit(isSidekick, leaderDplcClobberedD6)) {
                /*
                 * ROM tests the current d6 bit in the cage status before
                 * entering loc_339A0 (sonic3k.asm:69872-69874). With FixBugs
                 * off, the P2 latch can be written under dirty bit 1 when
                 * Player 1's Perform_Player_DPLC clobbers d6
                 * (sonic3k.asm:69835-69846, 70039-70041); on later frames d6
                 * can be the real P2 standing bit 4, so the mounted branch is
                 * skipped and loc_338D8 exits at tst.b object_control(a1)
                 * (sonic3k.asm:69895-69897). Leave the rider state untouched.
                 */
                if (isSidekick && leaderHasReleased) {
                    /*
                     * The stale latch no longer suppresses sidekick terrain
                     * wall checks once the ROM path falls out before the ride
                     * continuation. This preserves the later left-wall
                     * correction in Tails_InputAcceleration_Path
                     * (sonic3k.asm:27957-28001).
                     */
                    player.setSuppressGroundWallCollision(false);
                }
                restoreObjectLatchIfTerrainClearedIt(player);
                return;
            }
            continueRide(vIntRunCount, player, state, isSidekick);
            return;
        }

        if (state.cooldown > 0) {
            state.cooldown--;
            if (state.cooldown == 0) {
                player.setControlLocked(false);
            }
            return;
        }

        // ROM still runs the P2 cage capture path after Sonic has released
        // this cage (sonic3k.asm:69835-69846); the leader-release quirk only
        // affects already latched sidekick ride continuation below.
        tryLatch(player, state, isSidekick, leaderDplcClobberedD6);
    }

    private void tryLatch(AbstractPlayableSprite player, CageState state, boolean isSidekick,
                          boolean leaderDplcClobberedD6) {
        if (!canTryCapture(player)) {
            return;
        }

        int horizontalRadius = player.getYRadius() + 0x44;
        int horizontal = player.getCentreX() - spawn.x() + horizontalRadius;
        int horizontalLimit = horizontalRadius << 1;
        if (horizontal < 0 || horizontal >= horizontalLimit) {
            return;
        }

        int vertical = player.getCentreY() - spawn.y() + verticalOffset;
        if (vertical < 0 || vertical >= verticalRange) {
            return;
        }

        int adjustedVertical = vertical - 0x10;
        if (adjustedVertical < 0) {
            player.setCentreYPreserveSubpixel((short) (player.getCentreY() - adjustedVertical));
        }

        // ROM (sonic3k.asm:69905-69921) at loc_33922 branches on the
        // player's Status_InAir bit when cage runs. ROM runs player physics
        // first then objects (slot order), but ROM physics doesn't always
        // ground the player at the cage rim — sub_33C34 calls
        // Player_TouchFloor as part of cage capture (sonic3k.asm:70170).
        // The engine's terrain collision can be more aggressive about
        // grounding the player on the cage's invisible solid-controller
        // area, masking the ROM "still airborne when cage ran" signal.
        //
        // Treat the player as airborne for the capture branch if EITHER
        // the pre-physics snapshot was airborne OR the post-physics state
        // is still airborne. This keeps unit tests that bypass the
        // physics tick (and call setAir(true) directly) working, and
        // mirrors ROM at F1757 where Tails was still airborne when ROM
        // cage ran (engine's terrain pass had grounded her by then —
        // CNZ1 trace F1758 divergence).
        boolean wasAirborne = player.wasPrePhysicsAir() || player.getAir();
        int captureAngle = player.wasPrePhysicsAir()
                ? (player.getPrePhysicsAngle() & 0xFF)
                : (player.getAngle() & 0xFF);
        int captureGSpeed = player.wasPrePhysicsAir()
                ? player.getPrePhysicsGSpeed()
                : player.getGSpeed();
        boolean touchFloorDuringLatch = false;
        if (wasAirborne) {
            if (captureAngle == 0) {
                // ROM loc_3394C: bset #0, object_control(a1) - physics gated next frame.
                beginLatchedCooldown(player, state);
                touchFloorDuringLatch = true;
            } else {
                if (Math.abs(captureGSpeed) < MIN_AIR_ATTACH_SPEED) {
                    // ROM loc_33940 → bhs.s loc_33958 path - low-speed
                    // recapture re-enters loc_3394C and sets bit 0.
                    beginLatchedCooldown(player, state);
                    touchFloorDuringLatch = true;
                }
            }
        }

        latch(player, state, touchFloorDuringLatch, isSidekick, leaderDplcClobberedD6);
    }

    private boolean canTryCapture(AbstractPlayableSprite player) {
        if (player == null || player.getDead() || player.isHurt() || player.isDebugMode()) {
            return false;
        }
        if (player.isObjectControlled() || player.isControlLocked()) {
            return false;
        }
        return !player.isOnObject()
                || player.getLatchedSolidObjectId() == 0
                || player.getLatchedSolidObjectId() == Sonic3kObjectIds.CNZ_WIRE_CAGE;
    }

    private void beginLatchedCooldown(AbstractPlayableSprite player, CageState state) {
        state.cooldown = 1;
        // ROM Obj_CNZWireCage sets bits 6 and 1 of object_control (sonic3k.asm:69937-69938),
        // and bit 0 in the air-recapture branch (sonic3k.asm:69921 loc_3394C). None of
        // those is bit 7, so ROM keeps Tails_CPU_Control running each frame — that is
        // what lets the auto-jump trigger fire at the cage and feed Ctrl_2_logical=$78
        // to loc_33ADE for the cage's launch-with-A/B/C path. (CNZ1 trace F1791.)
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
    }

    private void latch(AbstractPlayableSprite player, CageState state, boolean touchFloorDuringLatch,
                       boolean isSidekick, boolean leaderDplcClobberedD6) {
        state.latched = true;
        state.standingBit = currentStandingBit(isSidekick, leaderDplcClobberedD6);
        if (!touchFloorDuringLatch) {
            state.cooldown = 0;
        }

        if (player.isOnObject()) {
            ObjectServices services = tryServices();
            if (services != null && services.objectManager() != null) {
                services.objectManager().clearRidingObject(player);
            }
        }
        // ROM clears Status_InAir before sub_33C34 on the nonzero-angle,
        // |ground_vel| >= $400 capture path (docs/skdisasm/sonic3k.asm:69905-69916),
        // so sub_33C34 only zeroes x_vel when the touch-floor branch remains
        // airborne (docs/skdisasm/sonic3k.asm:70170-70175).
        if (touchFloorDuringLatch && player.getAir()) {
            touchFloorForAirLatch(player);
        }

        player.setOnObject(true);
        player.setAir(false);
        player.setLatchedSolidObject(Sonic3kObjectIds.CNZ_WIRE_CAGE, this);
        player.setObjectMappingFrameControl(true);
        player.setSuppressGroundWallCollision(true);
        player.setControlLocked(false);
        if (!touchFloorDuringLatch) {
            markNormalLatchObjectControl(player);
        }
        player.setAnimationId(CAPTURE_ANIMATION);
        player.setForcedAnimationId(-1);
        player.setPushing(false);
        player.setRenderFlips(false, false);

        if (player.getCentreX() >= spawn.x()) {
            state.phase = 0x00;
            state.rideAngle = 0x40;
            player.setAngle((byte) state.rideAngle);
        } else {
            state.phase = 0x80;
            state.rideAngle = 0xC0;
            player.setAngle((byte) state.rideAngle);
        }
        // loc_33958 chooses the left/right cage phase and angle, but
        // loc_3397A always clears Status_Facing (sonic3k.asm:69923-69935).
        player.setDirection(Direction.RIGHT);
    }

    private void touchFloorForAirLatch(AbstractPlayableSprite player) {
        player.setXSpeed((short) 0);

        if (player.getRolling()) {
            int oldYRadius = player.getYRadius();
            int standYRadius = player.getStandYRadius();
            int centreY = player.getCentreY();
            player.setRolling(false);
            player.restoreDefaultRadii();
            int delta = oldYRadius - standYRadius;
            if ((((player.getAngle() & 0xFF) + 0x40) & 0x80) != 0) {
                delta = -delta;
            }
            player.setCentreYPreserveSubpixel((short) (centreY + delta));
        } else {
            // sub_33C34 calls Player_TouchFloor for airborne cage captures.
            // Player_TouchFloor resets Tails's y_radius/x_radius to her
            // defaults before loc_33A6A/loc_33BBA read y_radius for the cage
            // orbit X formula. Without this, a previous cage-release
            // y_radius=$13 marker carries into the next capture and shifts
            // Tails four pixels too far right in CNZ1 around F1758.
            player.restoreDefaultRadii();
        }

        player.setAir(false);
        player.setPushing(false);
        player.setJumping(false);
    }

    private void continueRide(int vIntRunCount, AbstractPlayableSprite player, CageState state,
                              boolean isSidekick) {
        if (player == null || player.getDead() || player.isHurt() || player.isDebugMode()
                || player.getLatchedSolidObjectId() != Sonic3kObjectIds.CNZ_WIRE_CAGE) {
            release(player, state, COOLDOWN_AFTER_RELEASE);
            return;
        }

        if (state.cooldown != 0) {
            if (tryJumpRelease(vIntRunCount, player, state)) {
                return;
            }
            // See beginLatchedCooldown: ROM cage uses bits 1+6 (and 0 on
            // air-recapture), never bit 7, so the sidekick CPU keeps running.
            ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
            restoreObjectLatchIfTerrainClearedIt(player);
            updateReleaseRide(player, state);
            return;
        }

        if (player.getAir()) {
            if (player.isJumping()) {
                releaseWithJumpImpulse(vIntRunCount, player, state);
                return;
            }
            restoreObjectLatchIfTerrainClearedIt(player);
        }

        if (Math.abs(player.getGSpeed()) < MIN_SPEED_TO_CONTINUE) {
            state.cooldown = 1;
            // See beginLatchedCooldown: ROM cage uses bits 1+6 (and 0 on
            // air-recapture), never bit 7, so the sidekick CPU keeps running.
            ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
            updateReleaseRide(player, state);
            return;
        }

        if (player.getAir()) {
            releaseWithJumpImpulse(vIntRunCount, player, state);
            return;
        }

        int vertical = player.getCentreY() - spawn.y() + verticalOffset;
        if (vertical < 0 || vertical >= verticalRange) {
            release(player, state, COOLDOWN_AFTER_RELEASE);
            return;
        }

        updateCaptureRide(player, state, vertical);
    }

    private void restoreObjectLatchIfTerrainClearedIt(AbstractPlayableSprite player) {
        /*
         * ROM sub_33C34 stores this object in interact(a1), sets Status_OnObj,
         * and keeps Status_InAir clear. The engine terrain pass can briefly
         * mark the player airborne because this invisible controller is not a
         * generic SolidObject. If the latch still belongs to this cage and no
         * jump is active, restore the object-owned status before loc_339A0.
         *
         * Exception: when {@code Player_SlopeRepel} (sonic3k.asm:23907) has
         * just slipped the player on the CURRENT physics tick (set {@code
         * Status_InAir = 1} and {@code move_lock = 30} because |gSpeed|
         * dropped below $280 at a steep angle), the air state is the
         * legitimate ROM slip-detach for low-speed wall climbs. Preserve it
         * so the cage's released-path (loc_33ADE -> loc_33B1E -> bne
         * loc_33B62) can honour the in_air flag and run a proper release.
         * Use the {@code slopeRepelJustSlipped} per-tick flag rather than
         * {@code move_lock > 0} -- {@code move_lock} can be non-zero from a
         * slip many frames in the past (still counting down) when the cage
         * recaptures the player, in which case the air bit is from the
         * engine's terrain probe (stale) rather than a fresh slip and SHOULD
         * be restored. The CNZ1 trace F1740 fix originally used move_lock,
         * but F1758 (cage recapture 18 frames after the F1740 slip while
         * move_lock=12 was still counting down) showed that condition was
         * too coarse.
         */
        if (player.isSlopeRepelJustSlipped()) {
            return;
        }
        if (player.getLatchedSolidObjectId() == Sonic3kObjectIds.CNZ_WIRE_CAGE && !player.isJumping()) {
            player.setAir(false);
            player.setOnObject(true);
        }
    }

    private boolean tryJumpRelease(int vIntRunCount, AbstractPlayableSprite player, CageState state) {
        // ROM loc_33ADE masks the low byte of Ctrl_1_logical/Ctrl_2_logical
        // for A/B/C (docs/skdisasm/sonic3k.asm:70052-70056; button masks at
        // docs/skdisasm/sonic3k.constants.asm:167-169). The high byte may
        // contain held A/B/C, but held-only values such as $4808 must fall
        // through to loc_33B1E; only a low-byte A/B/C press launches the
        // rider.
        if (!player.isJumpJustPressed()) {
            return false;
        }
        releaseWithJumpImpulse(vIntRunCount, player, state);
        return true;
    }

    private void releaseWithJumpImpulse(int vIntRunCount, AbstractPlayableSprite player, CageState state) {
        int xSpeed = player.getCentreX() >= spawn.x() ? JUMP_RELEASE_X_SPEED : -JUMP_RELEASE_X_SPEED;
        player.setAir(true);
        player.setJumping(false);
        player.setXSpeed((short) xSpeed);
        player.setYSpeed((short) JUMP_RELEASE_Y_SPEED);
        player.setDirection(xSpeed < 0 ? Direction.LEFT : Direction.RIGHT);
        release(player, state, COOLDOWN_AFTER_RELEASE);
    }

    private void updateCaptureRide(AbstractPlayableSprite player, CageState state, int vertical) {
        int nextY = player.getCentreY();
        if (verticalVelocity >= 0 || vertical > 0x10) {
            nextY += verticalVelocity;
            player.setCentreYPreserveSubpixel((short) nextY);
        }

        int clampedVertical = vertical - 0x10;
        if (clampedVertical < 0) {
            player.setCentreYPreserveSubpixel((short) (player.getCentreY() - clampedVertical));
        }

        applyCageX(player, state);
        player.setOnObject(true);
        player.setAir(false);
        player.setObjectMappingFrameControl(true);
        player.setSuppressGroundWallCollision(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.CNZ_WIRE_CAGE, this);
        markNormalLatchObjectControl(player);
        player.setAngle((byte) rideAngle(state));
        player.setHighPriority((state.phase & 0xFF) >= 0x80);
        applyMappingFrame(player, state.phase, CAPTURE_FRAMES);
    }

    private void markNormalLatchObjectControl(AbstractPlayableSprite player) {
        ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
    }

    private int currentStandingBit(boolean isSidekick, boolean leaderDplcClobberedD6) {
        if (!isSidekick) {
            return P1_STANDING_BIT;
        }
        return leaderDplcClobberedD6 ? DIRTY_P2_STANDING_BIT : P2_STANDING_BIT;
    }

    private AbstractPlayableSprite nativeP2From(ObjectServices services, AbstractPlayableSprite leader) {
        PlayableEntity queryMain = services.playerQuery().mainPlayerOrNull();
        for (PlayableEntity candidate : services.playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate instanceof AbstractPlayableSprite sprite && sprite != leader && sprite != queryMain) {
                return sprite;
            }
        }
        return null;
    }

    private void updateReleaseRide(AbstractPlayableSprite player, CageState state) {
        if (!player.getAir()) {
            int vertical = player.getCentreY() - spawn.y() + verticalOffset;
            if (vertical < verticalRange) {
                updateReleasePosition(player, state, vertical);
                return;
            }
            if (vertical == verticalRange && ((state.phase & 0x7F) == 0)) {
                int xSpeed = (state.phase & 0x80) != 0 ? -EDGE_RELEASE_X_SPEED : EDGE_RELEASE_X_SPEED;
                player.setXSpeed((short) xSpeed);
                player.setGSpeed((short) xSpeed);
                player.setYSpeed((short) 0);
                player.setAir(true);
            } else if (vertical == verticalRange) {
                // ROM loc_33B1E branches to loc_33BBA when the rider is
                // exactly at the range edge but the phase low bits are
                // nonzero (sonic3k.asm:70079-70081). That path updates the
                // x orbit/mapping only; it neither adds cage y_vel nor runs
                // the loc_33B62 release cleanup.
                updateReleaseOrbitFrame(player, state);
                return;
            }
        }
        release(player, state, COOLDOWN_AFTER_RELEASE);
    }

    private void updateReleasePosition(AbstractPlayableSprite player, CageState state, int vertical) {
        if (verticalVelocity >= 0 || vertical > 0x10) {
            player.setCentreYPreserveSubpixel((short) (player.getCentreY() + verticalVelocity));
        }
        applyCageX(player, state);
        updateReleaseOrbitFrameAfterX(player, state);
    }

    private void updateReleaseOrbitFrame(AbstractPlayableSprite player, CageState state) {
        applyCageX(player, state);
        updateReleaseOrbitFrameAfterX(player, state);
    }

    private void updateReleaseOrbitFrameAfterX(AbstractPlayableSprite player, CageState state) {
        player.setOnObject(true);
        player.setAir(false);
        player.setSuppressGroundWallCollision(true);
        player.setAngle((byte) rideAngle(state));
        player.setHighPriority((state.phase & 0xFF) >= 0x80);
        applyMappingFrame(player, state.phase, RELEASE_FRAMES);
    }

    private void applyCageX(AbstractPlayableSprite player, CageState state) {
        int phaseBefore = state.phase & 0xFF;
        state.phase = (state.phase + 4) & 0xFF;
        int cosine = TrigLookupTable.cosHex(phaseBefore);
        int x = spawn.x() + (cosine >> 2) + ((player.getYRadius() * cosine) >> 8);
        player.setCentreXPreserveSubpixel((short) x);
    }

    private int rideAngle(CageState state) {
        return state.rideAngle;
    }

    private void applyMappingFrame(AbstractPlayableSprite player, int phase, int[] frames) {
        int index = (((-(phase + 0x40)) & 0xFF) / 0x0B);
        if (index < 0) {
            index = 0;
        } else if (index >= frames.length) {
            index = frames.length - 1;
        }
        player.setMappingFrame(frames[index]);
    }

    private void release(AbstractPlayableSprite player, CageState state, int cooldown) {
        state.latched = false;
        state.cooldown = cooldown;

        if (player == null) {
            return;
        }

        short centreX = player.getCentreX();
        short centreY = player.getCentreY();
        player.setAngle((byte) 0);
        player.setRolling(false);
        // ROM (sonic3k.asm:69986-69987 loc_33A0E and sonic3k.asm:70095-70096
        // loc_33B62) hardcodes y_radius=$13 (19) and x_radius=9 on cage release,
        // regardless of character. Tails's default standing y_radius is $F (15)
        // (sonic3k.asm:26103 Tails_Init), but ROM cage's release does NOT
        // restore Tails-specific defaults — it writes Sonic-style 19/9 to the
        // player's radii. The taller hitbox lets Tails detect terrain landing
        // sooner after the cage's A/B/C launch, matching ROM at CNZ1 trace
        // F1815 (foot at y=0x0742 with y_radius=19 hits floor; foot at y=0x073E
        // with y_radius=15 misses the same floor by 1 pixel).
        player.applyCustomRadii(9, 19);
        player.setCentreXPreserveSubpixel(centreX);
        player.setCentreYPreserveSubpixel(centreY);
        // Both native release paths use move.w #1,anim(a1). On 68000's
        // big-endian layout that publishes anim=$00 and the adjacent
        // prev_anim=$01; it does not select animation 1.
        player.setAnimationId(RELEASE_ANIMATION);
        player.getAnimationManager().publishPreviousAnimationId(RELEASE_PREVIOUS_ANIMATION);
        player.setObjectMappingFrameControl(false);
        player.setSuppressGroundWallCollision(false);
        player.setOnObject(false);
        player.setControlLocked(false);
        player.setHighPriority(false);

        ObjectServices services = tryServices();
        if (services != null && services.objectManager() != null) {
            services.objectManager().clearRidingObject(player);
        }
        ObjectControlState.none().applyTo(player);
    }

    private static final class CageState {
        private boolean latched;
        private int phase;
        private int rideAngle;
        private int cooldown;
        private int standingBit;
    }
}

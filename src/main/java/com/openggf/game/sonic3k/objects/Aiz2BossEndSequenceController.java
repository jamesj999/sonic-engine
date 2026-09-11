package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * AIZ2 post-boss controller for the Sonic/Tails route.
 *
 * <p>ROM reference: loc_694D4 onward.
 *
 * <p>Sequence:
 * <ol>
 *   <li>Wait for egg capsule release (results screen finished)</li>
 *   <li>Play level music, force Sonic right until X &ge; stop coordinate</li>
 *   <li>Stop Sonic, spawn cutscene Knuckles</li>
 *   <li>Wait for Knuckles to finish his laugh/jump/button sequence</li>
 *   <li>Bridge collapses, Sonic falls in hurt animation</li>
 *   <li>Transition to HCZ when Sonic falls past Y threshold</li>
 * </ol>
 */
public class Aiz2BossEndSequenceController extends AbstractObjectInstance
        implements SpawnCoordinateRewindRecreatable {

    // ROM: Camera_stored_max_X_pos = _unkFA84 + $158
    private static final int MAX_X_TARGET_OFFSET = 0x158;
    // ROM: loc_69526 — stop walking when x_pos >= _unkFA84 + $1F8
    private static final int PLAYER_STOP_X_OFFSET = 0x1F8;
    // ROM: loc_695A8 — transition when y_pos >= _unkFA86 + $1E6
    private static final int NEXT_LEVEL_Y_OFFSET = 0x1E6;
    private static final int POST_RESULTS_CONTROL_RESTORE_DELAY = 1;
    private static final int POST_BUTTON_CAMERA_MAX_Y_TARGET = 0x1000;
    private static final int INC_LEVEL_END_Y_GRADUAL_STEP = 0x8000;
    private static final int AIRBORNE_CAMERA_TARGET_OFFSET = 0x80;

    // Non-final so the generic rewind field capturer reapplies them after a
    // generic recreate. The captured spawn x/y make these correct before reapply.
    private int arenaMaxX;
    private int arenaBaseY;
    private boolean initialized;
    private boolean postCapsuleSequenceStarted;
    private boolean pendingButtonInputRelease;
    private boolean knucklesSpawned;
    private boolean buttonHandled;
    private boolean transitionRequested;
    private boolean pendingLookUpInputAfterStop;
    private boolean postButtonMaxYReleaseActive;
    private int postButtonMaxYAccumulator;
    private int postResultsControlRestoreDelay = -1;

    public Aiz2BossEndSequenceController(int arenaMaxX, int arenaBaseY) {
        super(new ObjectSpawn(arenaMaxX, arenaBaseY, Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, 0),
                "AIZ2BossEndSequence");
        this.arenaMaxX = arenaMaxX;
        this.arenaBaseY = arenaBaseY;
    }

    Aiz2BossEndSequenceController(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y());
    }

    @Override
    public int getX() {
        return arenaMaxX;
    }

    @Override
    public int getY() {
        return arenaBaseY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        if (!initialized) {
            initialize(player);
        }

        // Wait for results screen to finish (egg capsule sets this flag)
        if (!Aiz2BossEndSequenceState.isEggCapsuleReleased()) {
            player.clearForcedInputMask();
            player.setForceInputRight(false);
            return;
        }

        if (postResultsControlRestoreDelay < 0) {
            postResultsControlRestoreDelay = postResultsControlRestoreDelay();
        }
        if (postResultsControlRestoreDelay > 0) {
            postResultsControlRestoreDelay--;
            clearPositiveLockedSidekickLogicalWord(player);
            if (postResultsControlRestoreDelay == 0) {
                // loc_694D4 calls Restore_PlayerControl/2 after the results
                // owner clears _unkFAA8.  That entry clears object_control
                // and publishes WAIT, but leaves Ctrl_1_locked set; the
                // following loc_69526 entry publishes the first RIGHT word
                // after the next player pass.
                restoreNativePlayerControlsAfterResults(player);
            } else {
                holdEndingPose(player);
            }
            return;
        }

        if (pendingButtonInputRelease) {
            pendingButtonInputRelease = false;
            player.clearForcedInputMask();
            player.setForceInputRight(false);
        }

        // Start post-capsule sequence (music + walk right)
        boolean startedPostCapsuleSequenceNow = !postCapsuleSequenceStarted;
        if (startedPostCapsuleSequenceNow) {
            startPostCapsuleSequence(player);
        }
        clearPositiveLockedSidekickLogicalWord(player);
        if (pendingLookUpInputAfterStop) {
            pendingLookUpInputAfterStop = false;
            player.setForceInputRight(false);
            player.clearForcedInputMask();
            player.setForcedInputMask(AbstractPlayableSprite.INPUT_UP);
        }

        // Phase: Walk right until reaching stop coordinate
        if (!knucklesSpawned) {
            int stopX = arenaMaxX + PLAYER_STOP_X_OFFSET;
            if (player.getCentreX() < stopX) {
                // ROM: loc_69526 — force right until x_pos >= threshold
                player.setControlLocked(true);
                forceRightLogicalInput(player);
                setSidekickControlLocked(player, true);
                return;
            }

            // ROM: loc_69546 — Stop_Object and spawn Knuckles
            knucklesSpawned = true;
            player.setControlLocked(true);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            // ROM loc_69546 only runs Stop_Object and advances the controller.
            // loc_69588 writes UP on the next object pass, after the next
            // player physics tick has consumed the previous RIGHT logical word.
            pendingLookUpInputAfterStop = true;
            setSidekickControlLocked(player, true);
            spawnDynamicObject(CutsceneKnucklesAiz2Instance.createDefault());
        }

        // Phase: Wait for button press (triggered by Knuckles animation)
        if (!buttonHandled && Aiz2BossEndSequenceState.isButtonPressed()) {
            buttonHandled = true;
            // Bridge collapses — release all player locks so the bridge's
            // ejectStandingPlayers() can set the hurt-fall state and the
            // animation system doesn't overwrite it.
            // Obj_CutsceneButton clears Ctrl_1_locked in its earlier object
            // slot. This controller then observes the shared button flag and
            // clears its engine-side forced word so the next player dispatch
            // reads the unlocked raw input, matching loc_65C56/loc_69588.
            // Obj_CutsceneButton clears Ctrl_1_locked from an object slot, but
            // Player_1 is SST slot 0 and consumed the retained logical UP word
            // earlier in this same scan. Keep the engine's late-write
            // representation through that player dispatch and release it on the
            // next controller entry (sonic3k.asm:133968-133970, 138317-138323).
            pendingButtonInputRelease = true;
            player.setControlLocked(false);
            services().camera().setMaxYTarget((short) POST_BUTTON_CAMERA_MAX_Y_TARGET);
            postButtonMaxYReleaseActive = true;
            postButtonMaxYAccumulator = 0;
        }
        // Phase: Wait for player to fall past Y threshold, then transition
        if (buttonHandled && !transitionRequested) {
            int transitionY = arenaBaseY + NEXT_LEVEL_Y_OFFSET;
            if ((player.getCentreY() & 0xFFFF) >= transitionY) {
                transitionRequested = true;
                // StartNewLevel is entered from this later object slot after
                // the player moved, but before the normal DeformLayers camera
                // pass. Preserve the camera target derived from the position
                // visible at the start of player physics; the transition load
                // will clear the temporary freeze with the fresh level state.
                Camera camera = services().camera();
                camera.setY((short) ((player.getPrePhysicsCentreY() & 0xFFFF)
                        - AIRBORNE_CAMERA_TARGET_OFFSET));
                camera.setFrozen(true);
                services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
                services().requestZoneAndAct(Sonic3kZoneIds.ZONE_HCZ, 0, true);
                // StartNewLevel stops the current object pass. The separately
                // allocated Obj_IncLevEndYGradual child is in a later slot, so
                // it cannot add its accumulator high word on the handoff frame.
                return;
            }
        }
        updatePostButtonCameraMaxYRelease();
    }

    private void initialize(AbstractPlayableSprite player) {
        initialized = true;
        Aiz2BossEndSequenceState.triggerBridgeDrop();
        player.clearForcedInputMask();
        player.setForceInputRight(false);
    }

    private void holdEndingPose(AbstractPlayableSprite player) {
        player.setControlLocked(true);
        player.clearForcedInputMask();
        player.setForceInputRight(false);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
    }

    private int postResultsControlRestoreDelay() {
        // AIZEndBoss_StartPostDefeatCutscene polls tst.b (_unkFAA8).w from the
        // boss's own SST slot and only restores control on the entry that first
        // reads it clear (sonic3k.asm:138263-138268). The clearing owner is
        // Obj_LevelResultsWait2, created through AllocateObject
        // (sonic3k.asm:62700-62712, 181967-181971), so whether the boss sees
        // the clear in the same scan or on its next entry depends on the
        // lowest free SST slot AllocateObject happened to return -- runtime
        // allocation state the engine's folded controller does not model.
        //
        // This controller is allocated below the egg capsule that publishes the
        // engine-side release, so its first entry that observes the release
        // already stands for the boss entry that first read _unkFAA8 clear: no
        // further entry is retained. The previous two-way selector keyed this
        // off the bridge/button dispatch marker, which answers an unrelated
        // ordering question and is gone.
        return POST_RESULTS_CONTROL_RESTORE_DELAY;
    }

    private void restoreNativePlayerControlsAfterResults(AbstractPlayableSprite player) {
        for (PlayableEntity candidate : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (!(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            ObjectControlState.none().applyTo(sprite);
            sprite.setAir(false);
            sprite.setForcedAnimationId(-1);
            sprite.setAnimationId(Sonic3kAnimationIds.WAIT);
            sprite.getAnimationManager().publishPreviousAnimationId(
                    Sonic3kAnimationIds.WAIT.id());
            sprite.setAnimationFrameIndex(0);
            sprite.setAnimationTick(0);
        }
        // Restore_PlayerControl does not clear Ctrl_1_locked; loc_694D4
        // explicitly leaves the main input latch asserted for loc_69526.
        player.setControlLocked(true);
        spawnChild(() -> new PostResultsGradualMaxX(
                arenaMaxX + MAX_X_TARGET_OFFSET));
    }

    private void startPostCapsuleSequence(AbstractPlayableSprite player) {
        postCapsuleSequenceStarted = true;
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(true);
        forceRightLogicalInput(player);
        restoreSidekickPostResultsControl(player);
        setSidekickControlLocked(player, true);
    }

    private void forceRightLogicalInput(AbstractPlayableSprite player) {
        // ROM writes Ctrl_1_logical after both playable slots have already run.
        // The next Sonic_RecordPos call therefore records this word; do not
        // overwrite the current follower-history slot here.
        player.setForceInputRight(false);
        player.setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
    }

    private void updatePostButtonCameraMaxYRelease() {
        if (!postButtonMaxYReleaseActive) {
            return;
        }

        Camera camera = services().camera();
        if (camera == null) {
            return;
        }

        postButtonMaxYAccumulator = (postButtonMaxYAccumulator + INC_LEVEL_END_Y_GRADUAL_STEP) & 0xFFFFFFFF;
        int yDelta = (postButtonMaxYAccumulator >>> 16) & 0xFFFF;
        int nextMaxY = (camera.getMaxY() & 0xFFFF) + yDelta;
        if (nextMaxY >= POST_BUTTON_CAMERA_MAX_Y_TARGET) {
            camera.setMaxY((short) POST_BUTTON_CAMERA_MAX_Y_TARGET);
            postButtonMaxYReleaseActive = false;
            return;
        }

        camera.setMaxY((short) nextMaxY);
        camera.setMaxYTarget((short) POST_BUTTON_CAMERA_MAX_Y_TARGET);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }

    private void setSidekickControlLocked(AbstractPlayableSprite player, boolean locked) {
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> services().playerQuery().sidekicks());
        for (PlayableEntity sidekick : query.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekick == player) {
                continue;
            }
            if (sidekick instanceof AbstractPlayableSprite sprite) {
                sprite.setControlLocked(locked);
                if (!locked) {
                    sprite.clearForcedInputMask();
                }
            }
        }
    }

    private void clearPositiveLockedSidekickLogicalWord(AbstractPlayableSprite player) {
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> services().playerQuery().sidekicks());
        for (PlayableEntity sidekick : query.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekick == player) {
                continue;
            }
            if (sidekick instanceof AbstractPlayableSprite sprite
                    && sprite.getCpuController() != null) {
                // ROM loc_863C0 runs after Player_2 and uses a positive
                // Ctrl_2_locked byte: CPU control still executes, then this
                // object clears Ctrl_2_logical before the frame is observed.
                sprite.getCpuController().clearController2LogicalLatch();
            }
        }
    }

    /** ROM {@code Child6_IncLevX}/{@code Obj_IncLevEndXGradual}. */
    static final class PostResultsGradualMaxX extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private static final int ACCELERATION = 0x4000;

        private int targetMaxX;
        private int accumulator;

        PostResultsGradualMaxX(int targetMaxX) {
            super(new ObjectSpawn(targetMaxX, 0, 0, 0, 0, false, 0),
                    "AIZ2PostResultsGradualMaxX");
            this.targetMaxX = targetMaxX;
        }

        PostResultsGradualMaxX(ObjectSpawn spawn) {
            this(spawn.x());
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            Camera camera = services().camera();
            accumulator += ACCELERATION;
            int next = (camera.getMaxX() & 0xFFFF) + (accumulator >>> 16);
            if (next >= targetMaxX) {
                camera.setMaxX((short) targetMaxX);
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            camera.setMaxX((short) next);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private void restoreSidekickPostResultsControl(AbstractPlayableSprite player) {
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> services().playerQuery().sidekicks());
        for (PlayableEntity sidekick : query.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekick == player) {
                continue;
            }
            if (sidekick instanceof AbstractPlayableSprite sprite) {
                ObjectControlState.none().applyTo(sprite);
                sprite.setForcedAnimationId(-1);
            }
        }
    }
}

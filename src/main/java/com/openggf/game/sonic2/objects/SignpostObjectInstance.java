package com.openggf.game.sonic2.objects;
import com.openggf.audio.GameMusic;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.SignpostSparkleObjectInstance;

import com.openggf.camera.Camera;
import com.openggf.game.CollisionModel;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PostPlayerUpdateHook;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * End of level signpost (Object 0D).
 * <p>
 * Behavior from s2.asm:
 * <ol>
 * <li>Wait for player to pass (Obj0D_Main)</li>
 * <li>On pass: lock screen, play signpost sound, start spinning
 * (Obj0D_Main_State2)</li>
 * <li>Spawn sparkle effects while spinning</li>
 * <li>When spin completes, lock player controls (Obj0D_Main_State3)</li>
 * <li>Player walks off-screen automatically</li>
 * <li>Spawn results screen (Obj3A), play end-level jingle</li>
 * </ol>
 */
public class SignpostObjectInstance extends BoxObjectInstance implements PostPlayerUpdateHook, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(SignpostObjectInstance.class.getName());

    // Routine states (matching ROM)
    private static final int STATE_IDLE = 0;
    private static final int STATE_SPINNING = 2;
    private static final int STATE_WALK_OFF = 4;
    private static final int STATE_DONE = 6;

    // Animation IDs from Ani_obj0D.
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_SPIN_1 = 1;
    private static final int ANIM_SPIN_2 = 2;
    private static final int ANIM_FINAL_SONIC = 3;
    private static final int ANIM_FINAL_TAILS = 4;

    // Mapping frame 2 is Eggman front-face in the signpost mapping table.
    private static final int FRAME_EGGMAN = 2;

    // ROM: Obj0D_Main_State2 resets obj0D_spinframe to 60 after every expiry.
    // Because the counter starts at 0 on activation, the first step happens on
    // the activation frame, then the next two advances occur 61 frames apart.
    private static final int SPIN_CYCLE_FRAMES = 60;

    // ROM: Obj0D_Main_State3 - player must pass Camera_Max_X_pos + $128 to trigger results
    private static final int WALK_OFF_OFFSET = 0x128;

    // Sparkle effect timing and positions (from s2.asm Obj0D_RingSparklePositions)
    private static final int SPARKLE_SPAWN_DELAY = 11;
    private static final int[][] SPARKLE_POSITIONS = {
            { -24, -16 }, { 8, 8 }, { -16, 0 }, { 24, -8 },
            { 0, -8 }, { 16, 0 }, { -24, 8 }, { 24, 16 }
    };

    private int routineState = STATE_IDLE;
    private int mappingFrame = FRAME_EGGMAN;
    private ObjectAnimationState animationState;
    private int currentAnimId = ANIM_IDLE;
    private int finalAnimId = ANIM_FINAL_SONIC;
    private int spinTimer = 0;
    private int sparkleTimer = 0;
    private int sparkleIndex = 0;
    private int walkOffEnteredVIntRunCount = Integer.MIN_VALUE;

    private boolean resultsSpawned = false;
    private boolean initialized;

    public SignpostObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 24, 40, 0.3f, 0.8f, 0.3f, false);
    }

    @Override
    public SignpostObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SignpostObjectInstance(ctx.spawn(), "Signpost");
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // ROM: Obj0D_Init (s2.asm:34412-34418)
        // Signpost is disabled in Act 2+ except for MTZ Act 2
        // ROM sets x_pos to 0, causing MarkObjGone to delete it and clear respawn bit
        int currentAct = services().currentAct();
        int currentZone = services().currentZone();

        // ROM check: if (Current_Act != 0 && Current_ZoneAndAct != metropolis_zone_act_2)
        // metropolis_zone_act_2 = (7 << 8) | 1 = 0x0701
        if (currentAct > 0) {
            boolean isMTZAct2 = (currentZone == Sonic2ZoneConstants.ROM_ZONE_MTZ && currentAct == 1);
            if (!isMTZAct2) {
                // Mark as remembered to prevent respawning, then destroy
                // This prevents the spawn-destroy cycle every frame
                ObjectManager objMgr = services().objectManager();
                ObjectLifetimeOps.markSpawnRemembered(objMgr, spawn);
                setDestroyed(true);
            }
        }

        finalAnimId = resolveFinalAnimId();
        ObjectRenderManager renderManager = services().renderManager();
        animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSignpostAnimations() : null,
                ANIM_IDLE,
                FRAME_EGGMAN);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Don't update if destroyed (disabled in Act 2+)
        if (isDestroyed()) {
            return;
        }

        if (player == null) {
            return;
        }

        switch (routineState) {
            case STATE_IDLE -> {
                // Obj0D_Main falls through into Obj0D_Main_State2 on the same
                // frame that the signpost is activated.
                if (checkPlayerPass(player)) {
                    updateSpinning(vIntRunCount);
                }
            }
            case STATE_SPINNING -> updateSpinning(vIntRunCount);
            case STATE_WALK_OFF -> {
                // Inline-order modules (S2/S3K collision-model path) already run
                // ExecuteObjects after playable movement, so the regular object
                // update sees Sonic's post-physics state directly.
                if (usesInlineWalkOffUpdate(player)) {
                    updateWalkOff(player);
                }
            }
            case STATE_DONE -> {
                // ROM: Obj0D_Main_StateNull. The signpost remains visible but
                // no longer drives control or spawns follow-up objects.
            }
        }

        if (animationState != null) {
            animationState.update();
            mappingFrame = animationState.getMappingFrame();
        }
    }

    /**
     * ROM: Obj0D_Main (s2.asm:34449-34456)
     * move.w x_pos(a1),d0 ; player center X
     * sub.w  x_pos(a0),d0 ; d0 = player_center - signpost_center
     * bcs.s  ...           ; skip if negative (player left of signpost)
     * cmpi.w #$20,d0
     * bhs.s  ...           ; skip if >= $20 (player too far past)
     */
    private boolean checkPlayerPass(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();
        if (dx >= 0 && dx < 0x20) {
            activateSignpost();
            return true;
        }
        return false;
    }

    private void activateSignpost() {
        LOGGER.info("Signpost activated at X=" + spawn.x());

        try {
            services().playSfx(Sonic2Sfx.SIGNPOST.id);
        } catch (Exception e) {
            LOGGER.warning("Failed to play signpost sound: " + e.getMessage());
        }

        lockCamera();

        // Freeze the level timer when passing the signpost
        var levelGamestate = services().levelGamestate();
        if (levelGamestate != null) {
            levelGamestate.pauseTimer();
        }

        routineState = STATE_SPINNING;
        spinTimer = 0;
        sparkleTimer = 0;
        sparkleIndex = 0;
        setAnimationId(ANIM_IDLE);
    }

    /**
     * ROM: move.w (Camera_Max_X_pos).w,(Camera_Min_X_pos).w
     * Sets Camera_Min to Camera_Max, locking the camera at the level's right boundary.
     * Camera_Max_X_pos is NOT changed — the player's right movement limit in
     * Sonic_LevelBound still uses the original level boundary value.
     */
    private void lockCamera() {
        Camera camera = services().camera();
        if (camera != null) {
            camera.setMinX(camera.getMaxX());
            LOGGER.fine("Camera locked: minX set to maxX=" + camera.getMaxX());
        }
    }

    private void updateSpinning(int vIntRunCount) {
        // ROM: subq.w #1,obj0D_spinframe(a0) / bpl.s ...
        spinTimer--;
        if (spinTimer < 0) {
            spinTimer = SPIN_CYCLE_FRAMES;
            int nextAnimId = currentAnimId + 1;
            if (nextAnimId >= ANIM_FINAL_SONIC) {
                routineState = STATE_WALK_OFF;
                walkOffEnteredVIntRunCount = vIntRunCount;
                setAnimationId(finalAnimId);
                LOGGER.fine("Signpost spin complete, entering walk-off state");
            } else {
                setAnimationId(nextAnimId);
            }
        }

        // ROM continues into the sparkle countdown even on the frame that
        // routine_secondary advances out of State2.
        updateSparkleCountdown();
    }

    @Override
    public void updatePostPlayer(int frameCounter, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (usesInlineWalkOffUpdate(player)) {
            return;
        }
        if (routineState != STATE_WALK_OFF || frameCounter <= walkOffEnteredVIntRunCount) {
            return;
        }
        updateWalkOff(player);
    }

    private boolean usesInlineWalkOffUpdate(AbstractPlayableSprite player) {
        return player != null
                && player.getGameRules() != null
                && player.getGameRules().collision() != null
                && player.getGameRules().collision().collisionModel() == CollisionModel.DUAL_PATH;
    }

    private void updateSparkleCountdown() {
        // ROM: subq.w #1,objoff_32(a0) / move.w #$B,objoff_32(a0) when expired.
        sparkleTimer--;
        if (sparkleTimer >= 0) {
            return;
        }
        sparkleTimer = SPARKLE_SPAWN_DELAY;

        int[] offset = SPARKLE_POSITIONS[sparkleIndex];
        int sparkleX = spawn.x() + offset[0];
        int sparkleY = spawn.y() + offset[1];

        spawnFreeChild(() -> new SignpostSparkleObjectInstance(sparkleX, sparkleY));

        // ROM stores the byte offset into Obj0D_RingSparklePositions, so
        // addq.b #2 / andi.b #$E advances through the eight x/y pairs.
        sparkleIndex = (sparkleIndex + 1) % SPARKLE_POSITIONS.length;
    }

    private void setAnimationId(int animId) {
        currentAnimId = animId;
        if (animationState != null) {
            animationState.setAnimId(animId);
            animationState.resetFrameIndex();
        }
    }

    private int resolveFinalAnimId() {
        String mainCharacter = ActiveGameplayTeamResolver.resolveMainCharacterCode(services().configuration());
        return "tails".equalsIgnoreCase(mainCharacter) ? ANIM_FINAL_TAILS : ANIM_FINAL_SONIC;
    }

    /**
     * ROM: Obj0D_Main_State3 (s2.asm:34593-34621)
     * <p>
     * Each frame:
     * 1. If player is airborne, skip the Control_Locked / forced-right writes
     *    (fixBugs = 0 branch) but still run the x_pos test
     * 2. Otherwise set Control_Locked and force right input
     * 3. Check: player_center_x >= Camera_Max_X_pos + $128 → trigger end of act
     */
    private void updateWalkOff(AbstractPlayableSprite player) {
        // fixBugs conditional (docs/s2disasm/s2.asm:34815-34838). The
        // disassembly is built with fixBugs = 0 (s2.asm:27), which is what the
        // shipped REV01 ROM does, so the engine takes the UNFIXED branch:
        //
        //   fixBugs = 0 (taken): "btst #status.player.in_air,... / bne.s
        //       loc_19434" -- an airborne player skips ONLY the Control_Locked /
        //       Ctrl_1_Logical writes and falls straight through to the x_pos
        //       test, so Load_EndOfAct can and does trigger mid-air.
        //   fixBugs = 1 (not taken): "bne.w return_194D0" -- returns outright,
        //       so both the control lock and the results trigger wait for a
        //       landing. The fix exists because the un-fixed path lets the
        //       player dodge the control lock by jumping at the right edge.
        //
        // The engine previously returned early here, i.e. it implemented the
        // bug-fixed branch, which deferred Load_EndOfAct until Sonic landed.
        if (!player.getAir()) {
            // Obj0D runs after Sonic's own slot in ExecuteObjects, so its
            // Control_Locked / Ctrl_1_Logical writes affect the next frame's
            // player control pass, not the current one.
            player.queueForceInputRightForNextFrame(true);
            player.queueControlLockedForNextFrame(true);
        }

        // ROM: move.w (MainCharacter+x_pos).w,d0
        //      move.w (Camera_Max_X_pos).w,d1
        //      addi.w #$128,d1
        //      cmp.w  d1,d0
        //      blo.w  return
        Camera camera = services().camera();
        if (camera != null && !resultsSpawned) {
            int triggerX = camera.getMaxX() + WALK_OFF_OFFSET;
            if (player.getCentreX() >= triggerX) {
                spawnResultsScreen(player);
            }
        }
    }

    private void spawnResultsScreen(AbstractPlayableSprite player) {
        if (!queueResultsPlc()) {
            return;
        }
        resultsSpawned = true;
        routineState = STATE_DONE;
        LOGGER.info("Player off-screen, triggering end of act sequence");

        // ROM Obj0D_Main_State3 sets global Control_Locked and Ctrl_1_Logical
        // to force the walk-off, then jumps straight into Load_EndOfAct without
        // clearing either latch. The forced-right deceleration therefore
        // persists into the first results frames until later gameplay code
        // overwrites the logical pad state. Keep the engine's stored walk-off
        // state latched here instead of clearing it at results spawn.

        try {
            services().playMusic(GameMusic.ACT_CLEAR);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }
        var levelGamestate = services().levelGamestate();
        int elapsedSeconds = levelGamestate != null ? levelGamestate.getElapsedSeconds() : 0;
        int ringCount = player.getRingCount();
        int actNumber = services().currentAct() + 1; // 1-indexed for display
        boolean allRingsCollected = services().areAllRingsCollected();

        // Spawn the results screen
        if (services().objectManager() != null) {
            spawnFreeChild(() -> new ResultsScreenObjectInstance(
                    elapsedSeconds, ringCount, actNumber, allRingsCollected));
            LOGGER.info("Results screen spawned");
        }
    }

    private boolean queueResultsPlc() {
        try {
            Sonic2PlcService plc = services().gameModule().getGameService(Sonic2PlcService.class);
            if (plc != null) {
                Sonic2LevelEventManager events = services().gameModule()
                        .getGameService(Sonic2LevelEventManager.class);
                plc.transact(Sonic2PlcService.replaceOperation(events != null && events.getPlayerCharacter() == PlayerCharacter.TAILS_ALONE
                        ? 66 : 38));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getSignpostRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }
}

package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.level.BigRingReturnState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 3&K Special Stage Entry Flash (Obj_SSEntryFlash).
 * <p>
 * Spawned by {@link Sonic3kSSEntryRingObjectInstance} when the player touches
 * the big ring. Spends its first frame in the ROM's routine-0 init, plays a
 * flash animation at the ring's position, marks the parent ring for deletion
 * mid-animation, waits 33 frames, then triggers the special stage transition.
 * <p>
 * The whole sequence is 43 frames of object execution counted from the
 * ring-touch frame inclusive: 1 init + 9 {@code SSEntryFlash_Main} calls
 * (8 mapping-frame advances plus the terminating {@code $F4}) + 33
 * {@code Obj_Wait} ticks.
 * <p>
 * Animation (AniRaw_SSEntryFlash):
 * delay=0, frames: 0, 0, 1, 2, 3(hflip), 3, 2, 1, 0, then $F4 (call finished routine).
 * Total: 9 animation frames at 1 game frame each.
 * <p>
 * At anim_frame index 3 (when mapping_frame changes for the 4th time):
 * marks parent ring for deletion (ROM: bset #5,$38(a1)).
 * <p>
 * After animation: Obj_Wait counts $2E down with subq/bmi, so the $20 it is
 * armed with elapses on the 33rd tick, then play sfx_EnterSS and
 * trigger fade-to-white special stage entry.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm lines 128330-128423
 */
public class Sonic3kSSEntryFlashObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSSEntryFlashObjectInstance.class.getName());

    // AniRaw_SSEntryFlash: dc.b 0, 0, 0, 1, 2, 3|$40, 3, 2, 1, 0, $F4
    // Delay=0 (1 game frame per anim frame), bit 6 ($40) = h-flip toggle on frame 3.
    // Animate_RawAdjustFlipX uses bchg (toggle), so once $40 fires at index 4,
    // render_flags bit 0 stays set for all subsequent frames.
    private static final int[] ANIM_FRAMES = {0, 0, 1, 2, 3, 3, 2, 1, 0};
    private static final boolean[] ANIM_HFLIP = {false, false, false, false, true, true, true, true, true};

    // At this anim_frame index, mark parent ring for deletion
    // ROM: cmpi.b #3,anim_frame(a0) — checks the 0-based frame counter
    private static final int RING_DELETE_ANIM_INDEX = 3;

    // ROM: move.w #$20,$2E(a0) (skdisasm/sonic3k.asm:128383). Obj_Wait's
    // subq/bmi pair spends this on the 33rd tick, not the 32nd -- see updateWait.
    private static final int POST_ANIM_WAIT = 0x20;

    private enum State { INIT, ANIMATING, WAITING, DONE }
    private Sonic3kSSEntryRingObjectInstance parentRing;

    // ROM Obj_SSEntryFlash (skdisasm/sonic3k.asm:128332-128348) dispatches on
    // routine(a0) ONCE per frame: routine 0 runs SSEntryFlash_Init, whose
    // SetUp_ObjAttributesSlotted sets routine to 2, so SSEntryFlash_Main -- and
    // therefore the first Animate_RawAdjustFlipX advance -- does not run until
    // the NEXT frame. Obj_SSEntryFlash is allocated by SSEntryRing's touch
    // response through `jsr (AllocateObject).l` (:128306-128309), which
    // rescans Dynamic_object_RAM from its base (:37911-37914) and returns the
    // LOWEST free SST -- possibly below the ring's own slot. Process_Sprites
    // walks Object_RAM upwards (:35965-35992), so whether this object's
    // routine-0 init runs on the ring-touch frame or on the frame after is
    // decided by the allocated slot, not by a fixed assumption here; see the
    // AllocateObject call site in Sonic3kSSEntryRingObjectInstance.
    private State state = State.INIT;
    private int animIndex = 0;
    private int waitTimer = 0;
    private boolean ringDeleteTriggered = false;

    /**
     * @param parentRing  the parent ring object to mark for deletion
     * @param x           center X position
     * @param y           center Y position
     */
    public Sonic3kSSEntryFlashObjectInstance(
            Sonic3kSSEntryRingObjectInstance parentRing,
            int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "SSEntryFlash");
        this.parentRing = parentRing;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic3kSSEntryRingObjectInstance ring = nearestLiveParentRing(ctx);
        if (ring == null || ctx.spawn() == null) {
            return null;
        }
        return new Sonic3kSSEntryFlashObjectInstance(ring, ctx.spawn().x(), ctx.spawn().y());
    }

    private static Sonic3kSSEntryRingObjectInstance nearestLiveParentRing(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null
                || ctx.spawn() == null) {
            return null;
        }
        return nearestLiveParentRing(ctx.objectServices().objectManager(), ctx.spawn().x(), ctx.spawn().y());
    }

    private static Sonic3kSSEntryRingObjectInstance nearestLiveParentRing(
            ObjectManager objectManager, int targetX, int targetY) {
        Sonic3kSSEntryRingObjectInstance nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof Sonic3kSSEntryRingObjectInstance ring && !ring.isDestroyed()) {
                ObjectSpawn ringSpawn = ring.getSpawn();
                long dx = (long) ringSpawn.x() - targetX;
                long dy = (long) ringSpawn.y() - targetY;
                long distance = dx * dx + dy * dy;
                if (distance < nearestDistance) {
                    nearest = ring;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    @Override
    protected void afterRewindRestoreSettled() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null || spawn == null) {
            parentRing = null;
            return;
        }
        parentRing = nearestLiveParentRing(objectManager, spawn.x(), spawn.y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            // ROM routine 0: SSEntryFlash_Init consumes this frame on its own.
            case INIT -> state = State.ANIMATING;
            case ANIMATING -> updateAnimation();
            case WAITING -> updateWait(player);
            case DONE -> { }
        }
    }

    private void updateAnimation() {
        // Check for ring deletion trigger (ROM: at anim_frame == 3)
        if (animIndex == RING_DELETE_ANIM_INDEX && !ringDeleteTriggered) {
            ringDeleteTriggered = true;
            if (parentRing != null) {
                parentRing.markForDeletion();
            }
        }

        animIndex++;
        if (animIndex >= ANIM_FRAMES.length) {
            // Animation complete → enter wait state
            // ROM: SSEntryFlash_Finished → Obj_Wait with $20 frames
            state = State.WAITING;
            waitTimer = POST_ANIM_WAIT;
        }
    }

    private void updateWait(AbstractPlayableSprite player) {
        waitTimer--;
        // ROM Obj_Wait (skdisasm/sonic3k.asm:177947-177950):
        //     subq.w  #1,$2E(a0)
        //     bmi.s   loc_84892
        // The branch is bmi (< 0), not a zero test. Armed with $20 by
        // SSEntryFlash_Finished (:128381-128385), the $34 handler therefore runs
        // on the 33rd invocation -- when the counter passes below zero -- not on
        // the 32nd, when it reaches zero.
        if (waitTimer < 0) {
            // ROM: SSEntryFlash_GoSS — plays sfx_EnterSS then enters special stage.
            // The SFX is played by GameLoop.enterSpecialStage() via
            // Sonic3kSpecialStageProvider.getTransitionSfxId() (sfx_EnterSS = $AF),
            // so we just trigger the request here.
            state = State.DONE;
            // ROM SSEntryFlash_GoSS (sonic3k.asm:128387-128392) runs
            // Clear_SpriteRingMem then `jsr (Save_Level_Data2).l` BEFORE the
            // destination branch, so both the ordinary special stage and the
            // Super Emerald arena restart go through it.
            saveLevelData2(player);
            if (routesToSuperEmeraldArena()) {
                // ROM loc_618AC (skdisasm/sonic3k.asm:128411-128417):
                //   move.b #2,(Special_bonus_entry_flag).w
                //   move.w #$1701,(Current_zone_and_act).w
                //   move.w #$1701,(Apparent_zone_and_act).w
                //   move.b #0,(Last_star_post_hit).w
                //   move.b #1,(Restart_level_flag).w
                //   move.b #1,(Respawn_table_keep).w
                // Zone $17 act 1 is the Super Emerald special-stage arena
                // (Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA); it is reached by a
                // level restart, not the Game_mode $34 special-stage entry.
                // move.b #0,(Last_star_post_hit).w (sonic3k.asm:128414) — the
                // arena load must place the player from Sonic_Start_Locations,
                // not from the Saved2_ block Save_Level_Data2 just wrote.
                services().clearLastStarPostHit();
                LOGGER.fine("SSEntryFlash: restarting into the Super Emerald arena");
                services().requestZoneAndAct(
                        Sonic3kZoneIds.ZONE_DEZ_BOSS_SS_ARENA, 1, true);
            } else {
                // ROM loc_61892 (sonic3k.asm:128405-128410): Game_mode $34, the
                // ordinary special-stage entry. sfx_EnterSS is played by
                // GameLoop.enterSpecialStage() via
                // Sonic3kSpecialStageProvider.getTransitionSfxId().
                services().requestSpecialStageEntry();
                LOGGER.fine("SSEntryFlash: triggering special stage entry");
            }
            setDestroyed(true);
        }
    }

    /**
     * ROM {@code Save_Level_Data2} (skdisasm/sonic3k.asm:61735-61753), called by
     * {@code SSEntryFlash_GoSS} at sonic3k.asm:128392.
     * <p>
     * The call site leaves {@code a0} pointing at the FLASH object -- it is the
     * object currently being executed -- so
     * <pre>
     *   move.w  x_pos(a0),(Saved2_X_pos).w
     *   move.w  y_pos(a0),(Saved2_Y_pos).w
     * </pre>
     * (sonic3k.asm:61738-61739) records the flash's position, which
     * {@code SSEntryFlash_Init} copied verbatim from the parent giant ring
     * (sonic3k.asm:128353-128355). Every other field is read from a named global
     * ({@code Player_1+art_tile}, {@code Ring_count}, {@code Camera_X_pos}, ...),
     * so the player's own coordinates are never stored.
     * <p>
     * {@code Load_Starpost_Settings}'s {@code loc_2D2C2}
     * (sonic3k.asm:61817-61836) then writes {@code Saved2_X_pos}/{@code Saved2_Y_pos}
     * straight into {@code Player_1+x_pos}/{@code y_pos} on the return leg: the
     * player resumes standing at the ring, not where he touched it.
     */
    private void saveLevelData2(AbstractPlayableSprite player) {
        var camera = services().camera();
        var zoneRuntimeState = services().zoneRuntimeState();
        int resizeRoutine = zoneRuntimeState instanceof S3kZoneRuntimeState s3kState
                ? s3kState.getDynamicResizeRoutine()
                : 0;
        int meanWaterLevel = 0;
        var waterSystem = services().waterSystem();
        int featureZone = services().currentZone();
        int featureAct = services().currentAct();
        if (waterSystem != null && waterSystem.hasWater(featureZone, featureAct)) {
            meanWaterLevel = waterSystem.getWaterLevelY(featureZone, featureAct);
        }
        var levelGamestate = services().levelGamestate();
        long savedTimerFrames = levelGamestate == null ? 0L : levelGamestate.getTimerFrames();
        services().saveBigRingReturn(new BigRingReturnState(
                getX(), getY(),
                camera.getX(), camera.getY(), player.getRingCount(),
                player.getTopSolidBit(), player.getLrbSolidBit(),
                camera.getMaxY(), resizeRoutine, meanWaterLevel,
                savedTimerFrames));
    }

    /**
     * ROM {@code SSEntryFlash_GoSS}'s destination branch
     * (skdisasm/sonic3k.asm:128393-128401):
     * <pre>
     *   tst.b   subtype(a0)
     *   bmi.s   loc_618AC          ; negative subtype always restarts into $1701
     *   tst.w   (SK_alone_flag).w
     *   bne.s   loc_61892          ; S&amp;K alone -> ordinary special stage
     *   bsr.w   SSEntry_CheckLevel
     *   beq.s   loc_61892          ; S3 level -> ordinary special stage
     *   cmpi.b  #7,(Chaos_emerald_count).w
     *   bne.s   loc_61892          ; not all Chaos Emeralds -> ordinary stage
     *   bra.w   loc_618AC
     * </pre>
     * {@code SK_alone_flag} is always zero because the engine only models the
     * locked-on ROM ({@code Sonic3k.java:424-427}). The flash's own subtype is
     * copied from the parent ring by {@code SSEntryFlash_Init}
     * (sonic3k.asm:128357), which is where this reads it from.
     */
    private boolean routesToSuperEmeraldArena() {
        if (parentRing == null) {
            return false;
        }
        if (parentRing.hasNegativeSubtype()) {
            return true;
        }
        return parentRing.isSonicAndKnucklesHalfLevel()
                && services().gameState().hasAllEmeralds();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (state == State.DONE || state == State.WAITING) {
            return;
        }
        if (animIndex < 0 || animIndex >= ANIM_FRAMES.length) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SS_ENTRY_FLASH);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        int mappingFrame = ANIM_FRAMES[animIndex];
        // ROM: h-flip is internal to flash via bchg toggle, not affected by approach direction
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), ANIM_HFLIP[animIndex], false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: priority = $280 (bucket 5), same as ring
        return RenderPriority.clamp(5);
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return true;
    }
}

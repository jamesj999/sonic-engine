package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 3&K Special Stage Entry Ring (Obj_SSEntryRing, object ID 0x85).
 * <p>
 * The giant gold ring that warps the player into a Special Stage when touched.
 * Each ring has a subtype (0-31) used as a bit index into the 32-bit
 * {@code Collected_special_ring_array} bitfield. If the ring's bit is already
 * set, it is deleted immediately on spawn.
 * <p>
 * Animation (AniRaw_SSEntryRing):
 * <ul>
 *   <li>Formation (anim 0): delay=4, frames 0,0,1,2,3,4,5,6,7, loop</li>
 *   <li>Idle (anim 1): delay=6, frames 10,9,8,11, loop</li>
 * </ul>
 * <p>
 * On touch (SSEntryRing_Main collision):
 * <ol>
 *   <li>Play sfx_BigRing</li>
 *   <li>Branch based on emerald state:
 *     <ul>
 *       <li>Chaos emeralds &lt; 7: enter Special Stage (full flash sequence)</li>
 *       <li>All emeralds collected: award 50 rings, ring vanishes immediately</li>
 *       <li>Hidden Palace routes are disabled until HPZ is registered as a loadable level</li>
 *     </ul>
 *   </li>
 *   <li>For Special Stage path: lock player (hidden + object controlled +
 *       anim $1C), spawn {@link Sonic3kSSEntryFlashObjectInstance} which
 *       handles the flash animation, wait, sfx_EnterSS, and transition</li>
 * </ol>
 * <p>
 * Art: ArtUnc_SSEntryRing (9984 bytes), Map_SSEntryRing (12 frames),
 * DPLC_SSEntryRing (12 frames). art_tile = make_art_tile(ArtTile_Explosion,1,0).
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm Obj_SSEntryRing (lines 128211-128530)
 */
public class Sonic3kSSEntryRingObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSSEntryRingObjectInstance.class.getName());

    // Collision extents from center (ROM: SSEntry_Range: dc.w -$18, $30, -$28, $50)
    // Check_PlayerInRange interprets as {offset, span} pairs:
    //   left  = ring_x + (-$18),  right  = left + $30 = ring_x + $18
    //   top   = ring_y + (-$28),  bottom = top  + $50 = ring_y + $28
    // Symmetric box: X [-24, +24), Y [-40, +40) relative to ring center
    private static final int COLLISION_X_MIN = -0x18;  // -24
    private static final int COLLISION_X_MAX =  0x18;  //  24  (-$18 + $30)
    private static final int COLLISION_Y_MIN = -0x28;  // -40
    private static final int COLLISION_Y_MAX =  0x28;  //  40  (-$28 + $50)

    // ROM: render_flags = 4 (on-screen check), priority = $280 (bucket 5)
    private static final int RENDER_PRIORITY = 5;

    // Formation animation: mapping frames 0-7, delay=4 game frames per anim frame
    // ROM: AniRaw_SSEntryRing anim 0: dc.b 4, 0, 0, 1, 2, 3, 4, 5, 6, 7, $F8, $0C
    // ROM Animate_Raw uses down-counter: delay N means N+1 game frames per anim frame
    private static final int FORMATION_DELAY = 4;
    private static final int[] FORMATION_FRAMES = {0, 0, 1, 2, 3, 4, 5, 6, 7};

    // Idle animation: mapping frames 8-11, delay=6 game frames per anim frame
    // ROM: AniRaw_SSEntryRing anim 1: dc.b 6, $A, 9, 8, $B, $FC
    private static final int IDLE_DELAY = 6;
    private static final int[] IDLE_FRAMES = {10, 9, 8, 11};

    // ROM collision gate: mapping_frame must be >= 8 for collision to be active.
    // Formation frames are 0-7, idle frames are 8-11. This is the definitive guard
    // matching sonic3k.asm line 128261: cmpi.b #8,mapping_frame(a0) / blo.s locret_61708
    private static final int COLLISION_FRAME_THRESHOLD = 8;

    // Ring award when all emeralds already collected
    private static final int RING_REWARD = 50;

    /** Object states matching ROM routine progression. */
    private enum State {
        /**
         * Main state: ring animates (formation then idle) and checks collision.
         * Matches ROM's SSEntryRing_Main (routine 2) which handles BOTH formation
         * and idle via Animate_Raw + mapping_frame >= 8 gate.
         */
        MAIN,
        /** Player touched ring, flash animation playing, awaiting deletion mark. */
        ENTERED,
        /** Ring marked for deletion by flash (bit 5 in ROM). */
        MARKED_DELETE
    }

    /** Subtype low bits are the bit index (0-31) into Collected_special_ring_array. */
    private int bitIndex;
    private boolean hiddenPalaceRoute;

    private State state;
    private boolean initialized;

    /**
     * Animation down-counter matching ROM's Animate_Raw.
     * Starts at the delay value and decrements each frame.
     * When it underflows below 0, reload from current delay and advance frame.
     * This gives delay+1 game frames per animation frame (ROM-accurate).
     */
    private int animTimer;

    /** Current index into the active animation frame array. */
    private int animIndex;

    /** Current mapping frame to display. */
    private int mappingFrame;

    /** Which animation is active: formation (false) or idle (true). */
    private boolean inIdleAnim;
    /** ROM: Obj_WaitOffscreen has released the ring into its own routine. */
    private boolean displayReleased;
    /**
     * ROM {@code loc_85B02} (sonic3k.asm:180300-180302) restores the object's
     * code pointer and then {@code rts}es straight back to the object loop, so
     * on the release frame neither {@code SSEntryRing_Init}/{@code _Main} nor
     * {@code SSEntryRing_Display} runs. The ring's first {@code Animate_Raw}
     * happens on the frame after the release.
     */
    private boolean releasedThisFrame;

    public Sonic3kSSEntryRingObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SSEntryRing");
        this.bitIndex = spawn.subtype() & 0x1F;
        this.hiddenPalaceRoute = (spawn.subtype() & 0x80) != 0;

        // Default to MAIN state; ensureInitialized will check collection status
        this.state = State.MAIN;
        this.inIdleAnim = false;
        this.animTimer = 0;
        this.animIndex = 0;
        this.mappingFrame = FORMATION_FRAMES[0];
    }

    @Override
    public Sonic3kSSEntryRingObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic3kSSEntryRingObjectInstance(ctx.spawn());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        // ROM pre-check: if already collected, delete immediately
        var gameState = services().gameState();
        if (gameState.isSpecialRingCollected(bitIndex)) {
            setDestroyed(true);
            this.state = State.MARKED_DELETE;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        releasedThisFrame = false;
        switch (state) {
            case MAIN -> updateMain(player);
            case ENTERED -> { /* Ring continues displaying; flash controls deletion */ }
            case MARKED_DELETE -> { /* Retired by the display pass below, as in ROM */ }
        }
        // ROM Obj_SSEntryRing runs the routine then falls through to
        // SSEntryRing_Display in the SAME frame (sonic3k.asm:128229-128230);
        // loc_61794 ends `jmp (AddRings)`, whose rts lands on that bra. So a
        // touch that sets bit 5 of $38 is seen by the display pass immediately.
        if (releasedThisFrame) {
            // ROM loc_85B02 rts'd back to the object loop; SSEntryRing_Display
            // is not reached on the release frame.
            return;
        }
        runDisplayPass();
    }

    /**
     * ROM {@code SSEntryRing_Display} (sonic3k.asm:128449-128451): the
     * retirement bit is tested first and jumps straight to {@code loc_6196A};
     * otherwise the off-screen bands at {@code loc_61928} decide.
     */
    private void runDisplayPass() {
        if (isDestroyed()) {
            return;
        }
        if (state == State.MARKED_DELETE) {
            retireRing();
            return;
        }
        checkDisplayOffscreenRetire();
    }

    /**
     * ROM {@code loc_6196A}: the retirement tail shared by the flash-driven
     * {@code btst #5,$38} branch and the off-screen branch of
     * {@code SSEntryRing_Display} (sonic3k.asm:128448-128490). It restores the
     * two palette longs the ring overwrote and re-queues
     * {@code ArtKosM_BadnikExplosion} to {@code ArtTile_Explosion} before
     * {@code Go_Delete_SpriteSlotted}.
     *
     * <p>The engine renders the ring from a standalone {@code Pattern[]}, so it
     * never corrupts the shared explosion tiles and does not need the
     * decompressed payload. The ROM still performs the decompression, and the
     * hardware-timing ledger compares it, so the submission itself must exist.
     */
    private void retireRing() {
        queueBadnikExplosionArt();
        setDestroyedByOffscreen();
    }

    /**
     * ROM {@code loc_61928}: once {@code Obj_WaitOffscreen} has released the
     * ring, a frame in which it is not drawn tests the coarse horizontal band
     * ({@code (x & $FF80) - Camera_X_pos_coarse_back > $280}) and the vertical
     * band ({@code y - Camera_Y_pos + $80 > $200}), both unsigned. Either one
     * retires the ring through {@code loc_6196A}.
     */
    private void checkDisplayOffscreenRetire() {
        if (isDestroyed() || !displayReleased) {
            return;
        }
        if (isWithinRenderSpriteBounds(OFFSCREEN_HALF_EXTENT, OFFSCREEN_HALF_EXTENT)) {
            return;
        }
        Camera camera = services().camera();
        if (camera == null) {
            return;
        }
        int coarseBack = (camera.getX() - 0x80) & 0xFF80;
        int bandX = ((getX() & 0xFF80) - coarseBack) & 0xFFFF;
        if (bandX > OFFSCREEN_BAND_X) {
            retireRing();
            return;
        }
        int bandY = ((getY() - camera.getY()) + 0x80) & 0xFFFF;
        if (bandY > OFFSCREEN_BAND_Y) {
            retireRing();
        }
    }

    private void queueBadnikExplosionArt() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null
                && renderManager.getArtProvider()
                instanceof Sonic3kObjectArtProvider provider) {
            provider.queueBadnikExplosionArt();
        }
    }

    /**
     * Combined formation + idle handler matching ROM's SSEntryRing_Main.
     * <p>
     * ROM flow (sonic3k.asm line 128257-128269):
     * <ol>
     *   <li>{@code jsr (Animate_Raw).l} — advance animation</li>
     *   <li>{@code cmpi.b #8,mapping_frame(a0) / blo.s locret} — gate collision on frame number</li>
     *   <li>{@code jsr (Check_PlayerInRange).l} — manual range check</li>
     * </ol>
     * <p>
     * ROM also calls {@code Obj_WaitOffscreen} before this routine, which prevents
     * ALL processing while the ring is off-screen. We replicate this by skipping
     * the entire update when the ring is not visible.
     */
    private void updateMain(AbstractPlayableSprite player) {
        // ROM: Obj_WaitOffscreen installs a 0x20 x 0x20 render box and does not
        // restore the normal object routine until Render_Sprites sets bit 7.
        // The ring therefore starts forming only when that box overlaps the
        // viewport, rather than at the wider placement/spawn boundary.
        if (!displayReleased) {
            if (!isWithinRenderSpriteBounds(OFFSCREEN_HALF_EXTENT, OFFSCREEN_HALF_EXTENT)) {
                return;
            }
            // ROM loc_85B02 (sonic3k.asm:180300-180302) only writes the saved
            // code pointer back into (a0) and rts'es; Obj_SSEntryRing itself is
            // not entered until the following frame. Consuming the release
            // frame here keeps the formation animation - and therefore the
            // `cmpi.b #8,mapping_frame` collision gate at sonic3k.asm:128266 -
            // on the ROM's schedule instead of opening it a frame early.
            displayReleased = true;
            releasedThisFrame = true;
            return;
        }

        // ROM: jsr (Animate_Raw).l — advance animation using down-counter
        advanceAnimation();

        // ROM: tst.w (Debug_placement_mode).w / bne.s locret_61708
        // ROM explicitly disables big ring collision in debug mode.
        if (player != null && player.isDebugMode()) {
            return;
        }

        // ROM: cmpi.b #8,mapping_frame(a0) / blo.s locret_61708
        // If ring hasn't finished forming (mapping_frame < 8), don't allow collision.
        if (mappingFrame < COLLISION_FRAME_THRESHOLD) {
            return;
        }

        // ROM: Check_PlayerInRange — manual range collision
        if (player != null) {
            checkCollision(player);
        }
    }

    /** ROM Obj_WaitOffscreen width_pixels/height_pixels values. */
    private static final int OFFSCREEN_HALF_EXTENT = 0x20;
    /** ROM loc_61928 coarse horizontal retirement band. */
    private static final int OFFSCREEN_BAND_X = 0x280;
    /** ROM loc_61928 vertical retirement band. */
    private static final int OFFSCREEN_BAND_Y = 0x200;

    /**
     * Advances animation matching ROM's Animate_Raw down-counter pattern.
     * Timer starts at delay value, decrements each frame. When it underflows
     * below 0, the delay is reloaded and the frame index advances.
     * This gives delay+1 game frames per animation frame (ROM-accurate).
     */
    private void advanceAnimation() {
        animTimer--;
        if (animTimer >= 0) {
            return; // Still counting down — hold current frame
        }

        // Timer underflowed: advance to next frame
        if (!inIdleAnim) {
            // Currently in formation animation
            animIndex++;
            if (animIndex >= FORMATION_FRAMES.length) {
                // Formation complete → transition to idle animation
                // ROM: $F8,$0C command jumps to idle animation data
                inIdleAnim = true;
                animIndex = 0;
                animTimer = IDLE_DELAY;
                mappingFrame = IDLE_FRAMES[0];
                return;
            }
            animTimer = FORMATION_DELAY;
            mappingFrame = FORMATION_FRAMES[animIndex];
        } else {
            // Currently in idle animation (looping)
            animIndex++;
            if (animIndex >= IDLE_FRAMES.length) {
                animIndex = 0; // ROM: $FC = loop to start
            }
            animTimer = IDLE_DELAY;
            mappingFrame = IDLE_FRAMES[animIndex];
        }
    }

    /**
     * Checks collision between the player and this ring.
     * Uses center-to-center distance matching the ROM's SSEntry_Range box.
     */
    private void checkCollision(AbstractPlayableSprite player) {
        // ROM: skip if player dead (routine >= 6)
        if (player.getDead()) {
            return;
        }

        int playerCX = player.getCentreX();
        int playerCY = player.getCentreY();
        int ringX = spawn.x();
        int ringY = spawn.y();

        int dx = playerCX - ringX;
        int dy = playerCY - ringY;

        if (dx >= COLLISION_X_MIN && dx < COLLISION_X_MAX
                && dy >= COLLISION_Y_MIN && dy < COLLISION_Y_MAX) {
            onTouched(player);
        }
    }

    /**
     * Handle ring touch. Branches based on emerald state.
     * <p>
     * ROM branching (SSEntryRing_Main lines 128272-128323):
     * <ul>
     *   <li>Chaos emeralds &lt; 7 → enter Special Stage</li>
     *   <li>SK_alone + 7 chaos → award 50 rings</li>
     *   <li>S3 level + 7 chaos → award 50 rings</li>
     *   <li>SK level + 7 chaos, super &lt; 7 → enter the capture sequence
     *       (Super Emerald stage)</li>
     *   <li>SK level + 7 chaos + 7 super → award 50 rings</li>
     *   <li>Subtype bit 7 set → Hidden Palace after the flash sequence, once HPZ is loadable</li>
     * </ul>
     */
    private void onTouched(AbstractPlayableSprite player) {
        LOGGER.fine(() -> String.format(
                "SSEntryRing #%d TOUCHED at (%d,%d) — mappingFrame=%d, inIdleAnim=%b, player(%d,%d)",
                bitIndex, spawn.x(), spawn.y(), mappingFrame, inIdleAnim,
                player.getCentreX(), player.getCentreY()));
        var gameState = services().gameState();

        // Play sfx_BigRing ($B3) — always plays on touch
        services().playSfx(Sonic3kSfx.BIG_RING.id);

        if (shouldRouteToHiddenPalace(gameState) && hiddenPalaceRouteAvailable()) {
            LOGGER.fine("SSEntryRing #" + bitIndex + " - routing to Hidden Palace");
            gameState.markSpecialRingCollected(bitIndex);
            setDestroyed(true);
            services().requestZoneAndAct(Sonic3kZoneIds.ZONE_HPZ, 1, true);
            return;
        }

        if (awardsFiftyRingsInsteadOfCapture(gameState)) {
            // Path B: ROM loc_61794 (sonic3k.asm:128325-128333) marks the ring
            // collected, sets the retirement bit and awards 50 rings. It does
            // not delete here — the following display pass sees
            // btst #5,$38 and retires through loc_6196A, which re-queues
            // ArtKosM_BadnikExplosion. Deleting outright loses that submission.
            LOGGER.fine("SSEntryRing #" + bitIndex + " — all emeralds, awarding 50 rings");
            gameState.markSpecialRingCollected(bitIndex);
            player.addRings(RING_REWARD);
            state = State.MARKED_DELETE;
        } else {
            // Path A: Enter Special Stage — full flash sequence
            // ROM: loc_61774 — lock player, spawn flash
            LOGGER.fine("SSEntryRing #" + bitIndex + " — entering Special Stage sequence");
            enterSpecialStageSequence(player);
        }
    }

    /**
     * ROM {@code SSEntryRing_Main}'s collision branch
     * (skdisasm/sonic3k.asm:128283-128291):
     * <pre>
     *   cmpi.b  #7,(Chaos_emerald_count).w
     *   bne.s   loc_6173A          ; fewer than 7 Chaos Emeralds -> capture sequence
     *   tst.w   (SK_alone_flag).w
     *   bne.s   loc_61794          ; S&amp;K alone -> award 50 rings
     *   bsr.w   SSEntry_CheckLevel
     *   beq.s   loc_61794          ; d1 = 0, an S3 level -> award 50 rings
     *   cmpi.b  #7,(Super_emerald_count).w
     *   beq.s   loc_61794          ; S&amp;K level with all Super Emeralds -> 50 rings
     *   ; falls through to loc_6173A: capture sequence for the Super Emerald stage
     * </pre>
     * The previous engine predicate stopped at "all Chaos Emeralds collected",
     * so an S&amp;K-half level ring was awarding 50 rings where the ROM starts the
     * Super Emerald capture sequence.
     * <p>
     * {@code SK_alone_flag} is always zero here: the engine only models the
     * locked-on Sonic 3 &amp; Knuckles ROM (see {@code Sonic3k.java:424-427}),
     * so the S&amp;K-alone branch is unreachable and is not modelled.
     * <p>
     * The ROM's touch branch does not test {@code subtype} at all — the
     * Hidden-Palace/arena decision belongs to {@code SSEntryFlash_GoSS}
     * (sonic3k.asm:128388-128400), after the capture sequence. The engine's
     * so a subtype-bit-7 ring is not a 50-ring award here either. MHZ's ring is
     * one of those, which is why the fixture shows it entering the capture
     * sequence with fewer than 7 Super Emeralds.
     */
    private boolean awardsFiftyRingsInsteadOfCapture(com.openggf.game.GameStateManager gameState) {
        if (!gameState.hasAllEmeralds()) {
            return false;
        }
        return isSonic3HalfLevel() || gameState.hasAllSuperEmeralds();
    }

    /**
     * ROM {@code SSEntry_CheckLevel} (skdisasm/sonic3k.asm:128433-128443):
     * {@code Current_zone} &gt;= 7, or exactly 4, returns 1 (an S&amp;K level);
     * every other zone returns 0 (an S3 level).
     */
    private boolean isSonic3HalfLevel() {
        int zone = services().currentZone();
        return zone < 0x07 && zone != 0x04;
    }

    /**
     * ROM {@code SSEntryFlash_GoSS} reads {@code subtype(a0)} of the flash,
     * which {@code SSEntryFlash_Init} copies from its parent ring
     * (skdisasm/sonic3k.asm:128357). Exposed so the flash can evaluate the
     * ROM's destination branch.
     */
    boolean hasNegativeSubtype() {
        return hiddenPalaceRoute;
    }

    /** @see #isSonic3HalfLevel() */
    boolean isSonicAndKnucklesHalfLevel() {
        return !isSonic3HalfLevel();
    }

    private boolean shouldRouteToHiddenPalace(com.openggf.game.GameStateManager gameState) {
        return hiddenPalaceRoute || (gameState.hasAllEmeralds() && gameState.hasAllSuperEmeralds());
    }

    private boolean hiddenPalaceRouteAvailable() {
        // ROM loc_618AC restarts into HPZ, but the engine's S3K zone registry
        // currently indexes through zone 0x15. Requesting 0x16 would crash the
        // fade callback before HPZ LevelData exists.
        return false;
    }

    /**
     * Initiates the special stage entry sequence.
     * ROM: SSEntryRing_Main lines 128287-128305
     * <ol>
     *   <li>Lock player: object_control=$53, anim=$1C, Player_prev_frame=-1</li>
     *   <li>Freeze camera at current position</li>
     *   <li>Spawn Obj_SSEntryFlash child</li>
     *   <li>Ring enters ENTERED state (continues displaying until flash marks it)</li>
     * </ol>
     */
    private void enterSpecialStageSequence(AbstractPlayableSprite player) {
        state = State.ENTERED;

        // ROM: the touch response (loc_6173A, sonic3k.asm:128290-128306) does NOT
        // save the return state. Save_Level_Data2 is called 42 frames later by
        // SSEntryFlash_GoSS (sonic3k.asm:128392), with a0 pointing at the FLASH
        // object -- so `move.w x_pos(a0),(Saved2_X_pos).w` (sonic3k.asm:61738-61739)
        // stores the RING's position, not the player's. The save therefore lives in
        // Sonic3kSSEntryFlashObjectInstance#saveLevelData2.

        // Lock player: hidden + object controlled
        // ROM loc_6173A (sonic3k.asm:128292-128304) writes, in order:
        //   move.b #-1,(Player_prev_frame).w   ; make the player disappear
        //   move.b #0,mapping_frame(a1)
        //   move.b #$1C,anim(a1)               ; AniSonic1C: dc.b $77,0,$FF
        //   move.b #$53,object_control(a1)
        // and repeats the mapping_frame/anim/object_control triple on Player_2
        // only when (Flying_carrying_Sonic_flag) is set.
        player.setHidden(true);
        lockCapturedPlayerAnimation(player);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        for (PlayableEntity sidekickEntity : sidekickParticipants(player)) {
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick
                    && sidekick.getCpuController() != null
                    && sidekick.getCpuController().isFlyingCarrying()) {
                lockCapturedPlayerAnimation(sidekick);
                ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(sidekick);
            }
        }

        // ROM loc_6173A does NOT touch the camera. Camera_X_pos keeps being
        // driven by ScrollHoriz from DeformLayers for the rest of the entry
        // sequence; it simply stops advancing because object_control $53 bit 0
        // stops the player moving (sonic3k.asm:21973-21977), so the camera
        // settles on its own once the follow offset is satisfied. Freezing it
        // here dropped the final ScrollHoriz step of the capture frame itself.

        // Spawn flash child object
        // ROM: direction bit is set on the ring (not flash) based on player approach,
        // but has no visual effect since flash uses internal h-flip toggle.
        //
        // ROM loc_61778 (docs/skdisasm/sonic3k.asm:128306-128309) calls
        // `jsr (AllocateObject).l`, NOT AllocateObjectAfterCurrent.
        // AllocateObject (sonic3k.asm:37911-37914) rescans Dynamic_object_RAM
        // from its base and returns the LOWEST free SST, so the flash can land
        // below the ring's own slot; Process_Sprites walks Object_RAM upwards
        // (sonic3k.asm:35965-35992), so in that case the flash's routine-0 init
        // does not run until the next frame. spawnDynamicObject would instead
        // pin AllocateObjectAfterCurrent semantics and always dispatch the
        // init on the touch frame, shortening the whole entry sequence by one
        // Level main-loop iteration.
        spawnDynamicObjectLowestFreeSlot(new Sonic3kSSEntryFlashObjectInstance(
                this, spawn.x(), spawn.y()));
    }

    /**
     * ROM {@code loc_6173A} (sonic3k.asm:128295-128297): {@code mapping_frame}
     * is zeroed and {@code anim} set to {@code $1C} before the object-control
     * byte is written. {@code AniSonic1C} is {@code dc.b $77,0,$FF} — a single
     * frame 0 held for $78 frames — so the mapping frame stays 0 for the whole
     * entry sequence whether or not object_control bit 1 suppresses
     * {@code Animate_Sonic} (sonic3k.asm:22008-22010).
     */
    private List<PlayableEntity> sidekickParticipants(AbstractPlayableSprite player) {
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> player,
                () -> services().playerQuery().sidekicks());
        return query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS).stream()
                .filter(candidate -> candidate != player)
                .toList();
    }

    private void lockCapturedPlayerAnimation(AbstractPlayableSprite captured) {
        captured.setForcedAnimationId(-1);
        // ROM writes mapping_frame(a1) = 0 itself rather than waiting for the
        // animation script, because Animate_Sonic has already run for this
        // frame by the time the ring's routine executes.
        captured.setMappingFrame(0);
        captured.setAnimationId(Sonic3kAnimationIds.BLANK);
    }

    /**
     * Called by {@link Sonic3kSSEntryFlashObjectInstance} at anim_frame 3
     * to mark this ring for deletion.
     * ROM: bset #5,$38(a1) — sets deletion flag on parent ring.
     */
    public void markForDeletion() {
        state = State.MARKED_DELETE;
        services().gameState().markSpecialRingCollected(bitIndex);
        LOGGER.fine("SSEntryRing #" + bitIndex + " marked for deletion by flash");
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || state == State.MARKED_DELETE) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SS_ENTRY_RING);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
                return;
            }
        }

        // Fallback wireframe if art not loaded
        appendFallbackRing(commands);
    }

    private void appendFallbackRing(List<GLCommand> commands) {
        int cx = spawn.x();
        int cy = spawn.y();
        int hw = 24, hh = 40;
        float r = 1.0f, g = 0.85f, b = 0.2f;
        addLine(commands, cx, cy - hh, cx + hw, cy, r, g, b);
        addLine(commands, cx + hw, cy, cx, cy + hh, r, g, b);
        addLine(commands, cx, cy + hh, cx - hw, cy, r, g, b);
        addLine(commands, cx - hw, cy, cx, cy - hh, r, g, b);
    }

    private void addLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(RENDER_PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // Ring must persist while flash animation is playing
        return state == State.ENTERED;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return state == State.ENTERED;
    }

    // --- Package-visible accessors for testing ---

    /** Returns the current mapping frame index (for verifying animation state). */
    int getMappingFrame() {
        return mappingFrame;
    }

    /** Returns true if the ring is in formation (mapping_frame < 8, collision disabled). */
    boolean isForming() {
        return state == State.MAIN && mappingFrame < COLLISION_FRAME_THRESHOLD;
    }

    /** Returns true if the ring is in the main state (formation or idle). */
    boolean isMainState() {
        return state == State.MAIN;
    }
}

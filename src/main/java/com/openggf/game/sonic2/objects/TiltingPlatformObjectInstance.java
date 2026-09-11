package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0xB6 - Tilting platform from Wing Fortress Zone.
 * A platform that tilts/spins based on various triggers (timer, player standing,
 * countdown). Found in WFZ Act 1 with 5 instances.
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 79331-79629 (ObjB6).
 * <p>
 * Subtype structure:
 * - Bits 1-2 (subtype & 6): Behavior type (0, 2, 4, or 6)
 * - Bits 4-7 (subtype & 0xF0): Timer/sync value
 * <p>
 * Behavior types (from routine index set by init):
 * - Type 0 (routine 2): Timed tilt - waits for frame counter sync, animates tilt, resets
 * - Type 2 (routine 4): Countdown + fire - counts down, plays fire SFX, spawns laser, resets
 * - Type 4 (routine 6): Player-triggered tilt - tilts toward standing player direction
 * - Type 6 (routine 8): Vertical countdown tilt - vertical platform, counts down, tilts
 * <p>
 * Mappings (4 frames from Map_objB6):
 * - Frame 0: Horizontal flat (48x8 px, two 3x1 pieces)
 * - Frame 1: Tilted right (16x24 px, two 2x3 pieces)
 * - Frame 2: Vertical (8x48 px, two 1x3 pieces)
 * - Frame 3: Tilted left (16x24 px, two 2x3 pieces)
 */
public class TiltingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // ==================== Behavior Types ====================
    // From ObjB6_Init: andi.b #6,d0 / addq.b #2,d0
    private static final int BEHAVIOR_TIMED_TILT = 0;           // subtype & 6 == 0 -> routine 2
    private static final int BEHAVIOR_COUNTDOWN_FIRE = 2;       // subtype & 6 == 2 -> routine 4
    private static final int BEHAVIOR_PLAYER_TRIGGERED = 4;     // subtype & 6 == 4 -> routine 6
    private static final int BEHAVIOR_VERTICAL_COUNTDOWN = 6;   // subtype & 6 == 6 -> routine 8

    // ==================== Solid Object Parameters ====================
    // From loc_3B790: horizontal solid (types 0, 2, 4)
    // d1 = $23 (half-width), d2 = 4 (air height), d3 = 4 (ground height)
    private static final SolidObjectParams HORIZONTAL_PARAMS = new SolidObjectParams(0x23, 4, 4);

    // From loc_3B7A6: vertical solid (type 6)
    // d1 = $F (half-width), d2 = $18 (air height), d3 = $18 (ground height)
    private static final SolidObjectParams VERTICAL_PARAMS = new SolidObjectParams(0x0F, 0x18, 0x18);

    // ==================== Timer Constants ====================
    // From loc_3B64E / loc_3B6C8: move.w #$C0,objoff_2A(a0) - reset timer
    private static final int RESET_TIMER = 0xC0;

    // From loc_3B6FE: move.w #$10,objoff_2A(a0) - player trigger delay
    private static final int PLAYER_TRIGGER_DELAY = 0x10;

    // From loc_3B6A6 sub: move.b #$20,objoff_2A(a0) - fire animation countdown
    private static final int FIRE_ANIM_COUNTDOWN = 0x20;

    // ==================== Animation Data ====================
    // From Ani_objB6 (s2.asm lines 79610-79624)
    //
    // ROM animation format: first byte = delay, then frame indices, then terminal byte.
    // Terminal bytes: $FF=loop, $FE=loop_back, $FD=switch_anim, $FA=advance routine_secondary
    //
    // Anim 0 (TILT_RIGHT): delay=3, frames=[1,2], $FD->switch to anim 1
    // Anim 1 (HOLD_VERT): delay=$3F, frames=[2], $FD->switch to anim 2
    // Anim 2 (UNTILT_RIGHT): delay=3, frames=[2,1,0], $FA->advance routine_secondary
    // Anim 3 (FIRE_CYCLE): delay=1, frames=[0,1,2,3], $FF->loop
    // Anim 4 (TILT_LEFT): delay=3, frames=[1,0], $FD->switch to anim 5
    // Anim 5 (HOLD_HORIZ): delay=$3F, frames=[0], $FD->switch to anim 6
    // Anim 6 (UNTILT_LEFT): delay=3, frames=[0,1,2], $FA->advance routine_secondary
    //
    // The SWITCH ($FD) chain for right tilt: anim 0 -> 1 -> 2 -> $FA (advance sub-routine)
    // The SWITCH ($FD) chain for left tilt: anim 4 -> 5 -> 6 -> $FA (advance sub-routine)

    // Animation sequences: {delay, frame0, frame1, ..., END_MARKER, param}
    private static final int END_SWITCH = 0xFD;   // Switch to animation param
    private static final int END_ADVANCE = 0xFA;  // Advance routine_secondary by 2
    private static final int END_LOOP = 0xFF;      // Loop from start

    // ==================== State ====================
    private int behaviorType;
    private int subRoutine;           // routine_secondary(a0) - sub-state within behavior
    private int timer;                // objoff_2A(a0) - countdown/sync timer
    private int mappingFrame;         // mapping_frame(a0) - current display frame
    private boolean xFlipStatus;      // status.npc.x_flip in status(a0)
    private boolean initFrame = true; // True on first update frame (ROM spends one frame in ObjB6_Init)

    // Animation state (manual implementation for $FA support)
    private int animId = -1;          // Current animation ID
    private int animDelay;            // Frame delay between advances
    private int animFrameTick;        // Current tick counter (anim_frame_duration)
    private int animFrameIndex;       // Current index into frame array (anim_frame)
    private int[] animFrames;         // Frame indices for current animation
    private int animEndAction;        // Terminal action ($FF/$FD/$FA)
    private int animEndParam;         // Parameter for terminal action
    private boolean animAdvanced;     // Set when $FA fires - signals sub-routine advance

    public TiltingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "TiltingPlatform");

        // ObjB6_Init: andi.b #6,d0 - extract bits 1-2
        this.behaviorType = spawn.subtype() & 6;

        // ROM: ObjB6_Init sets routine but returns (rts) - the first frame is spent
        // in Init with no solid collision and no active timer. Sub-routine stays 0
        // until the first update advances it.
        this.subRoutine = 0;

        // Behavior type 4 (player-triggered) starts directly at sub 0 and has no
        // init-then-advance pattern, so it doesn't need the initFrame delay.
        // Behavior type 6 (vertical countdown) needs mapping_frame=2 set on init,
        // but that also happens in the first-frame advance.
        if (behaviorType == BEHAVIOR_PLAYER_TRIGGERED) {
            initFrame = false;
        }
    }

    @Override
    public TiltingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new TiltingPlatformObjectInstance(ctx.spawn());
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Fix #3: One-frame-early initialization for behavior types 0, 2, and 6.
        // ROM: ObjB6_Init returns after setting routine. On the NEXT frame, the object
        // enters its behavior routine and hits sub 0 (loc_3B61C) which advances to sub 2
        // and initializes the timer. This first frame has no solid collision.
        if (initFrame) {
            initFrame = false;
            switch (behaviorType) {
                case BEHAVIOR_TIMED_TILT, BEHAVIOR_COUNTDOWN_FIRE -> {
                    // loc_3B61C: addq.b #2,routine_secondary -> sub 2
                    // then loc_3B77E: set timer from subtype upper nibble
                    subRoutine = 2;
                    initTimerFromSubtype();
                }
                case BEHAVIOR_VERTICAL_COUNTDOWN -> {
                    // loc_3B756: set initial mapping frame to 2 (vertical)
                    // then loc_3B77E: set timer from subtype upper nibble
                    subRoutine = 2;
                    mappingFrame = 2;
                    initTimerFromSubtype();
                }
            }
            return;
        }

        switch (behaviorType) {
            case BEHAVIOR_TIMED_TILT -> updateTimedTilt(vIntRunCount);
            case BEHAVIOR_COUNTDOWN_FIRE -> updateCountdownFire();
            case BEHAVIOR_PLAYER_TRIGGERED -> updatePlayerTriggered(player);
            case BEHAVIOR_VERTICAL_COUNTDOWN -> updateVerticalCountdown();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = services().renderManager() != null
                ? services().renderManager().getRenderer(Sonic2ObjectArtKeys.WFZ_TILT_PLATFORM)
                : null;
        if (renderer == null || !renderer.isReady()) {
            appendDebugBox(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), xFlipStatus, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjB6_SubObjData priority=4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzTiltingPlatform,1,1)
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Vertical type uses different collision box
        return behaviorType == BEHAVIOR_VERTICAL_COUNTDOWN ? VERTICAL_PARAMS : HORIZONTAL_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Fix #1: ROM uses JmpTo27_SolidObject (full solid - all sides), NOT PlatformObject.
        // See loc_3B790: jmpto JmpTo27_SolidObject and loc_3B7A6: jmpto JmpTo27_SolidObject.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid state is driven by ObjectManager standing checks
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return isSolidInCurrentState();
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        // ROM: SolidObject_Landed re-reads width_pixels(a0). ObjB6_SubObjData
        // stores width_pixels=$10, while loc_3B790 passes d1=$23 for the wider
        // side/body collision envelope.
        return 0x10;
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // ROM: loc_3B790 jumps into S2 SolidObject_cont, which reads
        // y_radius(a1), adds it to d2, and doubles that same d2 for the
        // lower-half overlap range (s2.asm:35156-35169).
        return true;
    }

    // ==================== Behavior: Timed Tilt (Type 0, Routine 2) ====================
    // s2.asm loc_3B602-3B654
    // Waits for frame counter sync, then plays tilt animation sequence, advances via $FA

    private void updateTimedTilt(int vIntRunCount) {
        switch (subRoutine) {
            case 2 -> {
                // loc_3B624: solid platform, check frame counter sync
                runSolidCheckpoint();
                // move.b (Vint_runcount+3).w,d0 / andi.b #$F0,d0 / cmp.b subtype(a0),d0
                int maskedFrame = (vIntRunCount & 0xFF) & 0xF0;
                int subtypeMasked = spawn.subtype() & 0xF0;
                if (maskedFrame == subtypeMasked) {
                    // loc_3B638: trigger tilt animation
                    subRoutine = 4;
                    setAnimation(0); // Start anim 0 (tilt right chain)
                    // loc_3B7BC: kick players off
                    dropRidingPlayers();
                }
            }
            case 4 -> {
                // loc_3B644: animate (tilt right -> hold vert -> untilt right -> $FA)
                updateAnimation();
                if (animAdvanced) {
                    // $FA fired: advance routine_secondary
                    subRoutine = 6;
                    animAdvanced = false;
                }
            }
            case 6 -> {
                // loc_3B64E: reset to sub-routine 2 with timer $C0
                subRoutine = 2;
                timer = RESET_TIMER;
            }
        }
    }

    // ==================== Behavior: Countdown + Fire (Type 2, Routine 4) ====================
    // s2.asm loc_3B65C-3B6C6
    // Counts down, fires laser with SFX, plays animation, resets

    private void updateCountdownFire() {
        switch (subRoutine) {
            case 2 -> {
                // loc_3B674: solid platform, countdown timer
                runSolidCheckpoint();
                timer--;
                if (timer < 0) {
                    // Timer expired -> start fire sequence
                    subRoutine = 4;
                    // move.b #$20,objoff_2A(a0)
                    timer = FIRE_ANIM_COUNTDOWN;
                    // move.b #3,anim(a0) / clr.b anim_frame / clr.b anim_frame_duration
                    // ROM explicitly clears anim_frame and anim_frame_duration even if anim==3,
                    // so force-reset to ensure setAnimation() reloads on repeat fire cycles
                    animId = -1;
                    setAnimation(3); // Fire cycle animation
                    // loc_3B7BC: kick players off
                    dropRidingPlayers();
                    // loc_3B7F8: spawn VerticalLaser child (ObjB7)
                    spawnVerticalLaser();
                    // moveq #signextendB(SndID_Fire),d0 / jmpto JmpTo12_PlaySound
                    services().playSfx(Sonic2AudioConstants.SFX_FIRE);
                }
            }
            case 4 -> {
                // loc_3B6A6: countdown animation frames
                timer--;
                if (timer < 0) {
                    // Animation done -> reset
                    // move.b #2,routine_secondary(a0)
                    subRoutine = 2;
                    // clr.b mapping_frame(a0)
                    mappingFrame = 0;
                    // move.w #$C0,objoff_2A(a0)
                    timer = RESET_TIMER;
                } else {
                    // Animate fire cycle (anim 3: frames [0,1,2,3] loop)
                    updateAnimation();
                }
            }
        }
    }

    // ==================== Behavior: Player-Triggered (Type 4, Routine 6) ====================
    // s2.asm loc_3B6C8-3B73C
    // Waits for player standing, delays, determines direction, tilts toward player

    private void updatePlayerTriggered(AbstractPlayableSprite player) {
        switch (subRoutine) {
            case 0 -> {
                // loc_3B6E2: solid platform, check if player is standing
                runSolidCheckpoint();
                if (isAnyPlayerRiding()) {
                    // addq.b #2,routine_secondary(a0)
                    subRoutine = 2;
                    // move.w #$10,objoff_2A(a0)
                    timer = PLAYER_TRIGGER_DELAY;
                }
            }
            case 2 -> {
                // loc_3B6FE: solid platform, countdown delay
                runSolidCheckpoint();
                timer--;
                if (timer < 0) {
                    // Timer expired -> determine direction and tilt
                    subRoutine = 4;
                    // Obj_GetOrientationToPlayer: check if player is to left or right
                    // bclr #status.npc.x_flip,status(a0) / tst.w d0
                    // d0 == 0 -> player to the right -> bne fails -> bset x_flip
                    // d0 != 0 -> player to the left -> x_flip stays clear
                    xFlipStatus = false;
                    boolean playerToLeft = isPlayerToLeft(player);
                    if (playerToLeft) {
                        // Player is to the LEFT of object -> d0 = 0 in ROM
                        // ROM: tst.w d0 / bne.s + (d0 = 0, so bne NOT taken)
                        // -> bset x_flip
                        xFlipStatus = true;
                    }
                    // Choose animation based on x_flip
                    // ROM: move.b #0,anim(a0) for both cases
                    // But the animation renders differently based on x_flip through
                    // AnimateSprite's status->render_flags copy
                    setAnimation(0); // Tilt right animation chain
                    // loc_3B7BC: kick players off
                    dropRidingPlayers();
                }
            }
            case 4 -> {
                // loc_3B72C: animate tilt
                updateAnimation();
                if (animAdvanced) {
                    // $FA fired: advance routine_secondary to sub 6
                    subRoutine = 6;
                    animAdvanced = false;
                }
            }
            case 6 -> {
                // loc_3B736: clr.b routine_secondary(a0) - reset to start
                subRoutine = 0;
            }
        }
    }

    // ==================== Behavior: Vertical Countdown (Type 6, Routine 8) ====================
    // s2.asm loc_3B73C-3B77E
    // Starts vertical, counts down, tilts, resets

    private void updateVerticalCountdown() {
        switch (subRoutine) {
            case 2 -> {
                // loc_3B764: vertical solid, countdown timer
                runSolidCheckpoint();
                timer--;
                if (timer < 0) {
                    // loc_3B770: timer expired -> start animation
                    subRoutine = 4;
                    // move.b #4,anim(a0) - tilt left animation chain
                    setAnimation(4);
                    // loc_3B7BC: kick players off
                    dropRidingPlayers();
                }
            }
            case 4 -> {
                // Reuses loc_3B644: animate (tilt left -> hold horiz -> untilt left -> $FA)
                updateAnimation();
                if (animAdvanced) {
                    // $FA fired: advance routine_secondary
                    subRoutine = 6;
                    animAdvanced = false;
                }
            }
            case 6 -> {
                // Reuses loc_3B64E: reset to sub-routine 2 with timer $C0
                subRoutine = 2;
                timer = RESET_TIMER;
            }
        }
    }

    // ==================== Animation Engine ====================
    // Manual animation implementation that supports the $FA (advance routine_secondary)
    // end action used by Sonic 2's AnimateSprite routine.

    /**
     * Set the current animation by ID. Resets frame index and tick counter.
     * <p>
     * Fix #4: ROM AnimateSprite does NOT immediately set mapping_frame when a new
     * animation is loaded. It stores the animation ID and resets anim_frame and
     * anim_frame_duration to 0. The first frame of the animation is loaded on the
     * NEXT call to AnimateSprite (when anim_frame_duration underflows from 0 to -1).
     * <p>
     * We emulate this by setting animFrameTick = 0 (so the next updateAnimation()
     * call will decrement it to -1, triggering immediate frame load) and leaving
     * mappingFrame at its current value.
     */
    private void setAnimation(int newAnimId) {
        if (newAnimId == animId) {
            return; // Same animation, don't reset (ROM: cmp.b prev_anim check)
        }
        animId = newAnimId;
        animFrameIndex = 0;
        animAdvanced = false;
        loadAnimationData(newAnimId);
        // Fix #4: Don't immediately set mappingFrame. Set tick to 0 so next
        // updateAnimation() will underflow and load the first frame.
        animFrameTick = 0;
    }

    /**
     * Load animation data for the given animation ID.
     * From Ani_objB6 (s2.asm lines 79610-79624).
     */
    private void loadAnimationData(int id) {
        switch (id) {
            case 0 -> { // byte_3B830: 3, 1, 2, $FD, 1
                animDelay = 3;
                animFrames = new int[]{1, 2};
                animEndAction = END_SWITCH;
                animEndParam = 1;
            }
            case 1 -> { // byte_3B836: $3F, 2, $FD, 2
                animDelay = 0x3F;
                animFrames = new int[]{2};
                animEndAction = END_SWITCH;
                animEndParam = 2;
            }
            case 2 -> { // byte_3B83A: 3, 2, 1, 0, $FA, 0
                animDelay = 3;
                animFrames = new int[]{2, 1, 0};
                animEndAction = END_ADVANCE;
                animEndParam = 0;
            }
            case 3 -> { // byte_3B840: 1, 0, 1, 2, 3, $FF
                animDelay = 1;
                animFrames = new int[]{0, 1, 2, 3};
                animEndAction = END_LOOP;
                animEndParam = 0;
            }
            case 4 -> { // byte_3B846: 3, 1, 0, $FD, 5
                animDelay = 3;
                animFrames = new int[]{1, 0};
                animEndAction = END_SWITCH;
                animEndParam = 5;
            }
            case 5 -> { // byte_3B84C: $3F, 0, $FD, 6
                animDelay = 0x3F;
                animFrames = new int[]{0};
                animEndAction = END_SWITCH;
                animEndParam = 6;
            }
            case 6 -> { // byte_3B850: 3, 0, 1, 2, $FA, 0
                animDelay = 3;
                animFrames = new int[]{0, 1, 2};
                animEndAction = END_ADVANCE;
                animEndParam = 0;
            }
            default -> {
                animDelay = 0;
                animFrames = new int[]{0};
                animEndAction = END_LOOP;
                animEndParam = 0;
            }
        }
    }

    /**
     * Advance animation by one tick. Matches ROM AnimateSprite logic:
     * <p>
     * ROM flow per call:
     * 1. subq.b #1,anim_frame_duration -> decrement tick
     * 2. bpl Anim_Wait -> if tick >= 0, return (wait)
     * 3. Reset tick to delay
     * 4. Read byte at anim_frame offset
     * 5. If byte >= $F0 -> handle terminal action ($FF/$FD/$FA)
     * 6. Otherwise -> set mapping_frame, increment anim_frame
     * <p>
     * The key timing properties:
     * - After setAnimation(), first updateAnimation() immediately loads frame[0]
     *   (tick underflows from 0 to -1)
     * - Terminal action is processed when the read position reaches the terminal byte,
     *   which is one tick after the last normal frame was displayed
     * - $FD (switch) just stores the new anim ID; the new animation's first frame
     *   loads on the NEXT updateAnimation call
     */
    private void updateAnimation() {
        if (animFrames == null || animFrames.length == 0) {
            return;
        }

        // ROM: subq.b #1,anim_frame_duration(a0) / bpl.s Anim_Wait
        animFrameTick--;
        if (animFrameTick >= 0) {
            // Still waiting on current frame
            return;
        }

        // Time to advance: reset tick counter
        animFrameTick = animDelay;

        // ROM reads the byte at the current anim_frame position.
        // If we're past the frame array, we've reached the terminal byte.
        if (animFrameIndex >= animFrames.length) {
            handleAnimationEnd();
            return;
        }

        // Normal frame: load mapping_frame and advance
        mappingFrame = animFrames[animFrameIndex];
        animFrameIndex++;
    }

    /**
     * Handle animation terminal action.
     * Called when the read position reaches past the last normal frame.
     */
    private void handleAnimationEnd() {
        switch (animEndAction) {
            case END_LOOP -> {
                // $FF: Anim_End_FF - restart from frame 0
                // ROM resets anim_frame to 0 and reads the first frame immediately
                animFrameIndex = 0;
                mappingFrame = animFrames[0];
                animFrameIndex = 1;
            }
            case END_SWITCH -> {
                // $FD: Anim_End_FD - switch to new animation.
                // Fix #5: ROM just stores the new anim ID and returns.
                // The actual animation data isn't loaded until the NEXT AnimateSprite call,
                // which detects anim != prev_anim, resets frame state, and loads frame[0].
                // We emulate this by setting up the new animation data but NOT loading
                // the first frame (leave mappingFrame showing the last frame of the old anim).
                // The animFrameTick = 0 ensures the next updateAnimation() call will
                // underflow and load frame[0] of the new animation.
                int nextAnim = animEndParam;
                animId = nextAnim;
                animFrameIndex = 0;
                animAdvanced = false;
                loadAnimationData(nextAnim);
                animFrameTick = 0;
                // mappingFrame intentionally NOT updated - shows last frame of old animation
            }
            case END_ADVANCE -> {
                // $FA: Anim_End_FA - advance routine_secondary by 2
                animAdvanced = true;
                // Keep showing last frame
                animFrameIndex = animFrames.length;
            }
            default -> {
                // Default: loop (same as END_LOOP)
                animFrameIndex = 0;
                if (animFrames.length > 0) {
                    mappingFrame = animFrames[0];
                    animFrameIndex = 1;
                }
            }
        }
    }

    // ==================== Shared Helpers ====================

    /**
     * loc_3B77E: Initialize timer from subtype upper nibble.
     * move.b subtype(a0),d0 / andi.w #$F0,d0 / move.w d0,objoff_2A(a0)
     */
    private void initTimerFromSubtype() {
        timer = spawn.subtype() & 0xF0;
    }

    private void runSolidCheckpoint() {
        services().solidExecution().resolveSolidNowAll();
    }

    /**
     * loc_3B7BC-3B7F6: Drop all riding players into the air.
     * Clears standing status bits and sets in_air for both players.
     * ROM: bclr p1_standing_bit/p2_standing_bit, then bclr on_object + bset in_air.
     */
    private void dropRidingPlayers() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectManager objectManager = services().objectManager();

        for (PlayableEntity candidate : services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (objectManager.isRidingObject(candidate, this)) {
                objectManager.clearRidingObject(candidate);
                candidate.setOnObject(false);
                candidate.setAir(true);
            }
        }
    }

    /**
     * Spawn VerticalLaser child (ObjB7) at this platform's position.
     * From loc_3B7F8: AllocateObjectAfterCurrent, set ObjID_VerticalLaser with subtype $72.
     * <p>
     * ObjB7 behavior (s2.asm lines 79632-79663):
     * - Init: LoadSubObject (subtype $72 -> ObjB7_SubObjData), set objoff_2A = $20 (32 frames)
     * - Main: decrement timer, delete at 0. Toggle objoff_2B bit 0 each frame;
     *   when clear, skip MarkObjGone (invisible). When set, call MarkObjGone (visible).
     * - SubObjData: mappings=ObjB7_MapUnc_3B8E4, art=ArtNem_WfzVrtclLazer (palette 2, priority),
     *   render_flags=level_fg, priority=4, width_pixels=$18, collision_flags=$A9.
     */
    private void spawnVerticalLaser() {
        if (services().objectManager() == null) {
            return;
        }

        spawnChild(() -> new VerticalLaserObjectInstance(spawn, spawn.x(), spawn.y()));
    }

    /**
     * Determine if the nearest player is to the left of this object.
     * Matches Obj_GetOrientationToPlayer (s2.asm loc_366D6):
     * <p>
     * Fix #6: ROM checks BOTH MainCharacter AND Sidekick and selects the CLOSER one
     * (by absolute horizontal distance). We replicate this by checking both players.
     * <p>
     * ROM logic:
     * d2 = x_pos(object) - x_pos(player)
     * If d2 >= 0 -> player is to the LEFT (or same X) -> d0 = 0
     * If d2 < 0 -> player is to the RIGHT -> d0 = 2
     * <p>
     * Then at call site:
     * bclr x_flip / tst.w d0 / bne.s + (skip bset if d0 != 0)
     * bset x_flip
     * -> x_flip is SET when d0 == 0 (player to the LEFT)
     */
    private boolean isPlayerToLeft(AbstractPlayableSprite player) {
        ObjectPlayerQuery.NearestPlayerX nearest = services().playerQuery()
                .nearestByRomX(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS, spawn.x());
        AbstractPlayableSprite closest = nearest.player() instanceof AbstractPlayableSprite sprite ? sprite : null;

        if (closest == null) {
            // Fallback to passed-in player if no main/sidekick found
            if (player == null) {
                return false;
            }
            closest = player;
        }

        int diff = spawn.x() - closest.getCentreX();
        return diff >= 0;
    }

    private boolean isAnyPlayerRiding() {
        if (services().objectManager() == null) {
            return false;
        }
        return services().objectManager().isAnyPlayerRiding(this);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("bt=%d sub=%d timer=%d frame=%d anim=%d tick=%d flip=%d solid=%d init=%d",
                behaviorType,
                subRoutine,
                timer,
                mappingFrame,
                animId,
                animFrameTick,
                xFlipStatus ? 1 : 0,
                isSolidInCurrentState() ? 1 : 0,
                initFrame ? 1 : 0);
    }

    /**
     * Check if the current state should provide solid collision.
     * Platform is solid during waiting/countdown sub-routines, not during animation playback.
     * During the init frame (initFrame=true), no solid collision is provided (ROM: ObjB6_Init
     * returns before any solid object call).
     */
    private boolean isSolidInCurrentState() {
        if (initFrame) {
            return false;
        }
        return switch (behaviorType) {
            case BEHAVIOR_TIMED_TILT -> subRoutine == 2;
            case BEHAVIOR_COUNTDOWN_FIRE -> subRoutine == 2;
            case BEHAVIOR_PLAYER_TRIGGERED -> subRoutine == 0 || subRoutine == 2;
            case BEHAVIOR_VERTICAL_COUNTDOWN -> subRoutine == 2;
            default -> true;
        };
    }

    private void appendDebugBox(List<GLCommand> commands) {
        SolidObjectParams params = getSolidParams();
        int halfW = params.halfWidth();
        int halfH = params.airHalfHeight();
        int cx = spawn.x();
        int cy = spawn.y();
        int left = cx - halfW;
        int right = cx + halfW;
        int top = cy - halfH;
        int bottom = cy + halfH;
        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.7f, 0.4f, 0.9f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.7f, 0.4f, 0.9f, x2, y2, 0, 0));
    }
}

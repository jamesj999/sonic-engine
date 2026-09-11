package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Act 2 Boss (Object 0x52) - Lava flamethrower boss.
 * ROM Reference: s2.asm:63619-64207 (Obj52)
 *
 * The boss rises from lava on either side of the arena, hovers while firing
 * a flamethrower upward, then descends and spawns lava balls before sinking
 * back into the lava. It alternates between left and right positions based
 * on player location.
 *
 * State Machine (boss_routine mapped to angle field in ROM):
 * - SUB0: Rising from lava
 * - SUB2: Hovering and firing flamethrower
 * - SUB4: Beginning descent
 * - SUB6: Descending and spawning lava balls
 * - SUB8: Defeated (explosion sequence)
 */
public class Sonic2HTZBossInstance extends AbstractBossInstance implements RewindRecreatable {

    // State machine constants (ROM: boss_routine values)
    private static final int SUB0_RISING = 0x00;
    private static final int SUB2_FLAMETHROWER = 0x02;
    private static final int SUB4_BEGIN_LOWER = 0x04;
    private static final int SUB6_LOWERING = 0x06;
    private static final int SUB8_DEFEATED = 0x08;

    // Position constants (ROM: s2.asm:63637-63667)
    /** Right side spawn position X (ROM: move.w #$3040,x_pos(a0)) */
    private static final int RIGHT_X = 0x3040;
    /** Left side spawn position X (ROM: move.w #$2F40,x_pos(a0)) */
    private static final int LEFT_X = 0x2F40;
    /** Initial spawn Y position (ROM: move.w #$580,y_pos(a0)) */
    private static final int INITIAL_Y = 0x0580;
    /** Left-side hover target Y (ROM: cmpi.w #$518,(Boss_Y_pos).w; boss_defeated=0) */
    private static final int LEFT_HOVER_Y = 0x0518;
    /** Right-side hover target Y (ROM: cmpi.w #$4FC,(Boss_Y_pos).w; boss_defeated=1) */
    private static final int RIGHT_HOVER_Y = 0x04FC;
    /** Left-side lower threshold Y (ROM: cmpi.w #$538,(Boss_Y_pos).w; boss_defeated=0) */
    private static final int LEFT_LOWER_Y = 0x0538;
    /** Right-side lower threshold Y (ROM: cmpi.w #$548,(Boss_Y_pos).w; boss_defeated=1) */
    private static final int RIGHT_LOWER_Y = 0x0548;
    /** Left-side bottom Y position (ROM: move.w #$5A0,(Boss_Y_pos).w; boss_defeated=0) */
    private static final int LEFT_BOTTOM_Y = 0x05A0;
    /** Player X threshold for side selection (ROM: subi.w #$2FC0,d0) */
    private static final int PLAYER_X_THRESHOLD = 0x2FC0;

    // Velocity constants
    /** Initial upward velocity when rising (ROM: move.w #-$E0,(Boss_Y_vel).w) */
    private static final int RISE_VELOCITY = -0xE0;
    /** Downward velocity when descending (ROM: move.w #$E0,(Boss_Y_vel).w) */
    private static final int LOWER_VELOCITY = 0xE0;

    // Timing constants
    /** Flamethrower phase duration (ROM: move.b #60,objoff_3E(a0)) */
    private static final int FLAMETHROWER_DURATION = 60;
    /** Flamethrower spawn timing (ROM: cmpi.b #-$18,objoff_3E(a0)) = -24 */
    private static final int FLAMETHROWER_SPAWN_TIME = -24;
    /** Defeat flee threshold (ROM: cmpi.w #-$3C,(Boss_Countdown).w) */
    private static final int DEFEAT_FLEE_TIME = -0x3C;

    // Custom memory offsets (ROM objoff_XX pattern)
    private static final int OBJOFF_SIDE_FLAG = 0x2C;   // boss_defeated - 0=left side, 1=right side
    private static final int OBJOFF_LAVA_SPAWNED = 0x38; // lava ball spawned this cycle flag

    // Animation frame indices (from ROM Ani_obj52 / Obj52_MapUnc_302BC)
    /** Main boss body frame (ROM: frame 1) */
    private static final int FRAME_BODY = 1;
    /** Eye frame 1 - open (ROM: frame 2, tile $83) */
    private static final int FRAME_EYE_OPEN = 2;
    /** Eye frame 2 - closed (ROM: frame 3, tile $85) */
    private static final int FRAME_EYE_CLOSED = 3;
    /** Defeated body frame (ROM: frame 16, smaller cockpit top) */
    private static final int FRAME_DEFEATED = 16;

    // Eye animation constants (ROM: Boss_AnimationArray - frames 2,3 with delay 6)
    private static final int EYE_ANIM_DELAY = 6;

    // Flame spreading animation (ROM: Ani_obj52 chain anim 1→2→3→4)
    // Mapping frames 4-11 show the flame progressively extending from the nozzle.
    // Each pair has increasing duration (speed+1 ticks per frame).
    private static final int[] FLAME_FRAMES =    {4,  5,  6,  7,  8,  9, 10, 11};
    private static final int[] FLAME_DURATIONS = {3,  3,  4,  4,  5,  5,  6,  6};
    private static final int FLAME_LOOP_START = 6; // Loop back to frames 10,11

    // Internal state
    private int actionTimer;
    private int defeatTimer;
    private int sineCounter;
    private int currentVIntRunCount;
    private boolean defeatFleeStarted;

    // Child sprite state for eye animation (ROM: sub2_* fields, Boss_AnimationArray)
    private int eyeAnimTimer;
    private int eyeFrame;

    // Flame spreading animation state
    private boolean flameActive;
    private int flameAnimIndex;   // index into FLAME_FRAMES/FLAME_DURATIONS
    private int flameAnimTimer;

    public Sonic2HTZBossInstance(ObjectSpawn spawn) {
        super(spawn, "HTZ Boss");
    }

    @Override
    public Sonic2HTZBossInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic2HTZBossInstance(ctx.spawn());
    }

    @Override
    protected void initializeBossState() {
        // ROM: Obj52_Init (s2.asm:63637-63667)
        // Boss starts on right side
        state.x = RIGHT_X;
        state.y = INITIAL_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        // Initial upward velocity
        state.yVel = RISE_VELOCITY;

        // Initialize state machine
        state.routineSecondary = SUB0_RISING;

        // Start on right side (boss_defeated = 1 in ROM)
        setCustomFlag(OBJOFF_SIDE_FLAG, 1);

        // Clear lava ball spawned flag
        setCustomFlag(OBJOFF_LAVA_SPAWNED, 0);

        // Initialize counters
        sineCounter = 4;  // ROM: move.b #4,boss_sine_count(a0)
        actionTimer = 0;
        defeatTimer = 0;
        defeatFleeStarted = false;

        // Initialize eye animation (ROM: Boss_AnimationArray, sub2_mapframe)
        // ROM: move.b #2,sub2_mapframe(a0) - starts with eye open
        eyeFrame = FRAME_EYE_OPEN;
        eyeAnimTimer = EYE_ANIM_DELAY;

        // Flame starts inactive
        flameActive = false;
        flameAnimIndex = 0;
        flameAnimTimer = 0;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        currentVIntRunCount = vIntRunCount;

        // Update eye animation (ROM: Boss_AnimationArray cycles frames 2,3 with delay 6)
        // ROM: s2.asm:63671-63676 - Boss_AnimationArray setup
        if (!state.defeated) {
            eyeAnimTimer--;
            if (eyeAnimTimer <= 0) {
                eyeAnimTimer = EYE_ANIM_DELAY;
                // Toggle between eye open (frame 2) and closed (frame 3)
                eyeFrame = (eyeFrame == FRAME_EYE_OPEN) ? FRAME_EYE_CLOSED : FRAME_EYE_OPEN;
            }
        }

        switch (state.routineSecondary) {
            case SUB0_RISING -> updateRising();
            case SUB2_FLAMETHROWER -> updateFlamethrower(player);
            case SUB4_BEGIN_LOWER -> updateBeginLower();
            case SUB6_LOWERING -> updateLowering(player);
            case SUB8_DEFEATED -> updateDefeated();
        }
    }

    /**
     * SUB0: Boss rising from lava.
     * ROM: Obj52_Mobile_Raise (s2.asm:63695-63718)
     */
    private void updateRising() {
        // Disable collision while rising
        // ROM: move.b #0,(Boss_CollisionRoutine).w

        // Apply velocity
        applyBossMovement();

        // Check target Y based on side (ROM: boss_defeated != 0 = right side)
        int targetY = (getCustomFlag(OBJOFF_SIDE_FLAG) != 0) ? RIGHT_HOVER_Y : LEFT_HOVER_Y;

        if (state.y <= targetY) {
            // Reached hover height
            state.yVel = 0;
            state.y = targetY;
            state.yFixed = state.y << 16;

            // ROM: move.b #4,boss_sine_count(a0)
            sineCounter = 4;

            // ROM: addq.b #2,boss_routine(a0)
            state.routineSecondary = SUB2_FLAMETHROWER;

            // ROM: move.b #60,objoff_3E(a0)
            actionTimer = FLAMETHROWER_DURATION;
        }
    }

    /**
     * SUB2: Hovering and firing flamethrower.
     * ROM: Obj52_Mobile_Flamethrower (s2.asm:63722-63755)
     */
    private void updateFlamethrower(AbstractPlayableSprite player) {
        // Decrement timer
        actionTimer--;

        // Check if timer is positive (still in delay phase)
        if (actionTimer >= 0) {
            // Just hover, don't fire yet
            updateHover();
            return;
        }

        // Timer is negative - active flamethrower phase
        // ROM: move.b #1,(Boss_CollisionRoutine).w - enable collision
        // (Collision is now enabled via getCollisionFlags() when actionTimer < 0)

        // Start flame spreading animation on first negative tick
        // ROM: AnimateBoss processes chained Ani_obj52 animations 1→2→3→4
        // which progressively render frames 4-11 (flame growing from nozzle)
        if (!flameActive) {
            flameActive = true;
            flameAnimIndex = 0;
            flameAnimTimer = FLAME_DURATIONS[0];
        }

        updateFlameAnimation();

        // ROM: cmpi.b #-$18,objoff_3E(a0) - spawn flamethrower at -24
        if (actionTimer == FLAMETHROWER_SPAWN_TIME) {
            spawnFlamethrower();
            // ROM: move.b #$2F,objoff_3E(a0) - reset timer to 0x2F (47)
            actionTimer = 0x2F;
            state.routineSecondary = SUB4_BEGIN_LOWER;
        }

        updateHover();
    }

    /**
     * SUB4: Begin lowering phase (pause before descent).
     * ROM: Obj52_Mobile_BeginLower (s2.asm:63759-63769)
     */
    private void updateBeginLower() {
        // ROM: move.b #0,(Boss_CollisionRoutine).w - disable collision
        // (Collision is disabled via getCollisionFlags() for this state)

        // Continue flame animation during the hover-before-descent phase
        if (flameActive) {
            updateFlameAnimation();
        }

        // Decrement timer
        actionTimer--;

        if (actionTimer > 0) {
            // Continue hovering until timer expires
            updateHover();
            return;
        }

        // Stop flame when starting descent
        flameActive = false;

        // ROM: move.w #$E0,(Boss_Y_vel).w
        state.yVel = LOWER_VELOCITY;

        // ROM: addq.b #2,boss_routine(a0)
        state.routineSecondary = SUB6_LOWERING;
    }

    /**
     * SUB6: Lowering and spawning lava balls.
     * ROM: Obj52_Mobile_Lower (s2.asm:63773-63846)
     */
    private void updateLowering(AbstractPlayableSprite player) {
        // Apply velocity
        applyBossMovement();

        int sideFlag = getCustomFlag(OBJOFF_SIDE_FLAG);
        int lowerThreshold = (sideFlag != 0) ? RIGHT_LOWER_Y : LEFT_LOWER_Y;
        int bottomY = (sideFlag != 0) ? INITIAL_Y : LEFT_BOTTOM_Y;

        // Check if reached lava ball spawn threshold
        if (state.y >= lowerThreshold) {
            // Spawn lava balls if not already done this cycle
            if (getCustomFlag(OBJOFF_LAVA_SPAWNED) == 0) {
                setCustomFlag(OBJOFF_LAVA_SPAWNED, 1);
                spawnLavaBalls();
                services().playSfx(Sonic2Sfx.ARROW_FIRING.id);
            }
        }

        // Check if reached bottom
        if (state.y >= bottomY) {
            state.y = bottomY;
            state.yFixed = state.y << 16;

            // Reset for next cycle
            // ROM: move.w #-$E0,(Boss_Y_vel).w
            state.yVel = RISE_VELOCITY;

            // ROM: move.b #0,boss_routine(a0)
            state.routineSecondary = SUB0_RISING;

            // Clear lava ball flag
            setCustomFlag(OBJOFF_LAVA_SPAWNED, 0);

            // Select side based on player position
            selectSideBasedOnPlayer(player);
        }
    }

    /**
     * SUB8: Defeated sequence.
     * ROM: Obj52_Mobile_Defeated (s2.asm:64047-64112)
     */
    private void updateDefeated() {
        defeatTimer--;

        if (defeatTimer >= 0) {
            // Explosion phase
            if (defeatTimer % 8 == 0) {
                spawnDefeatExplosion();
            }

            // Switch to explosion frame at 30 frames remaining
            // ROM: cmpi.w #$1E,(Boss_Countdown).w
            if (defeatTimer <= 0x1E) {
                // Show explosion frame
            }
            return;
        }

        // Smoke phase (defeatTimer < 0)
        // ROM: Spawn smoke every 32 frames
        if ((currentVIntRunCount & 0x1F) == 0) {
            spawnDefeatSmoke();
        }

        // Check if time to flee
        // ROM: cmpi.w #-$3C,(Boss_Countdown).w
        if (defeatTimer <= DEFEAT_FLEE_TIME) {
            // ROM: Obj52_Mobile_Flee (docs/s2disasm/s2.asm:64598-64606) plays the level
            // music, queues the animal/explosion PLC and sets Boss_defeated_flag, then
            // falls straight through to loc_30170 (s2.asm:64608-64612) in the SAME frame.
            // No staging here: the previously observed one-frame handoff skew came from
            // the missing routine-read-once defeat deferral, now modelled by
            // defeatDeferralAppliesToThisBoss().
            if (!defeatFleeStarted) {
                if (!Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                        Sonic2Constants.PLC_EXPLOSION)) return;
                defeatFleeStarted = true;
                services().gameState().setBossDefeatedFlag(true);
                services().playMusic(Sonic2Music.HILL_TOP.id);
            }

            // Flee - sink into lava
            state.y += 2;
            state.yFixed = state.y << 16;

            Camera camera = services().camera();
            if (camera.getMaxX() < 0x3160) {
                camera.setMaxX((short) (camera.getMaxX() + 2));
                return;
            }

            // ROM keeps the boss alive until it is off-screen OR low enough in lava.
            if (isOnScreen()) {
                int deleteY = (getCustomFlag(OBJOFF_SIDE_FLAG) != 0) ? 0x588 : 0x578;
                if (state.y <= deleteY) {
                    return;
                }
            }

            camera.setMaxX((short) 0x3160);
            // ROM: Current_Boss_ID is NEVER cleared in Sonic 2. It is written only by
            // the boss-arena setup routines (`move.b #N,(Current_Boss_ID).w`, ids 1-9)
            // and read by `tst.b`; docs/s2disasm/s2.asm contains no `clr.b` or
            // `move.b #0` for it, so it resets only via the level-load RAM clear and
            // persists to the end of the act. Sonic_Boundary's right-hand test widens
            // the side boundary by $40 only when it is zero (s2.asm:37243-37251), so
            // clearing it here let the character run 64px past the ROM's clamp.
            // Contrast S1, which DOES clear at the Egg Prison
            // (s1disasm/_incObj/3E Prison Capsule.asm:97), and S3K, which clears
            // Boss_flag at 31 sites. S2 is the exception.
            setDestroyed(true);
        }
    }

    /**
     * Apply sine wave hover oscillation.
     * ROM: Obj52_Mobile_Hover (s2.asm:63757)
     */
    private void updateHover() {
        // ROM: move.b boss_sine_count(a0),d0
        // jsr (CalcSine).l
        // asr.w #7,d1
        int sine = TrigLookupTable.sinHex(sineCounter & 0xFF);
        int offset = sine >> 7;

        // Apply to Y position
        int baseY = (getCustomFlag(OBJOFF_SIDE_FLAG) != 0) ? RIGHT_HOVER_Y : LEFT_HOVER_Y;
        state.y = baseY + offset;
        state.yFixed = state.y << 16;

        // ROM: addq.b #4,boss_sine_count(a0)
        sineCounter = (sineCounter + 4) & 0xFF;
    }

    /**
     * Advance the flame spreading animation.
     * ROM: Ani_obj52 chain anim 1→2→3→4 with mapping frames 4-11.
     * Each pair of frames plays at increasing speed (longer duration),
     * then the final pair (10,11) loops.
     */
    private void updateFlameAnimation() {
        flameAnimTimer--;
        if (flameAnimTimer <= 0) {
            flameAnimIndex++;
            if (flameAnimIndex >= FLAME_FRAMES.length) {
                // Loop back to frames 10,11 (ROM: anim 4 loops)
                flameAnimIndex = FLAME_LOOP_START;
            }
            flameAnimTimer = FLAME_DURATIONS[flameAnimIndex];
        }
    }

    /**
     * Apply boss velocity to position.
     */
    private void applyBossMovement() {
        // ROM: Boss_MoveObject equivalent
        state.yFixed += (state.yVel << 8);
        state.updatePositionFromFixed();
    }

    /**
     * Select which side to appear on based on player position.
     * ROM: s2.asm:63810-63830
     */
    private void selectSideBasedOnPlayer(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // ROM: move.w (MainCharacter+x_pos).w,d0
        // subi.w #$2FC0,d0
        int playerX = player.getCentreX();
        int threshold = PLAYER_X_THRESHOLD;

        if (playerX >= threshold) {
            // Player on right - boss appears on right
            state.x = RIGHT_X;
            state.y = INITIAL_Y;
            setCustomFlag(OBJOFF_SIDE_FLAG, 1);
        } else {
            // Player on left - boss appears on left
            state.x = LEFT_X;
            state.y = LEFT_BOTTOM_Y;
            setCustomFlag(OBJOFF_SIDE_FLAG, 0);
        }

        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
    }

    /**
     * Spawn flamethrower projectile.
     * ROM: s2.asm:63730-63752
     */
    private void spawnFlamethrower() {
        if (services().objectManager() == null) {
            return;
        }

        boolean flipped = (getCustomFlag(OBJOFF_SIDE_FLAG) == 0); // Left side = flipped

        HTZBossFlamethrower flamethrower = spawnChild(() -> new HTZBossFlamethrower(
                this,
                state.x,
                state.y - 0x1C,  // ROM: subi.w #$1C,y_pos(a1)
                flipped
        ));

        childComponents.add(flamethrower);
    }

    /**
     * Spawn lava ball projectiles (two balls).
     * ROM: s2.asm:63900-64006
     */
    private void spawnLavaBalls() {
        if (services().objectManager() == null) {
            return;
        }

        // ROM Obj52_CreateLavaBall allocates one subtype-6 child. That child
        // runs loc_2FF78 in its own slot, initializes itself as ball 0, then
        // allocates ball 1 from the same starting coordinates.
        HTZBossLavaBall firstBall = spawnFreeChild(() ->
                HTZBossLavaBall.createInitialPairSpawner(this, state.x, state.y));
        childComponents.add(firstBall);
    }

    /**
     * Spawn defeat smoke particle.
     * ROM: Obj52_CreateSmoke (s2.asm:64116-64135)
     */
    private void spawnDefeatSmoke() {
        if (services().objectManager() == null) {
            return;
        }

        spawnFreeChild(() -> new HTZBossSmokeParticle(
                state.x,
                state.y - 0x28
        ));
    }

    // Note: The ROM does NOT spawn an EggPrison from the HTZ boss code.
    // PLCID_Capsule (line 64062) loads capsule art into VRAM, and the actual
    // EggPrison object is pre-placed in the level's object layout data.

    @Override
    protected int getInitialHitCount() {
        return 8;  // ROM: move.b #8,boss_hitcount2(a0)
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // No special action on hit for HTZ boss
    }

    @Override
    protected int getCollisionSizeIndex() {
        // ROM: move.b #$32,collision_flags(a0)
        return 0x32;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;  // HTZ boss has custom defeat logic
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // Obj52_Mobile reads boss_routine(a0) ONCE at the top of the object's update and
        // jumps through off_2FD0E (docs/s2disasm/s2.asm:64194-64206). The hit handler
        // loc_300A4 runs from inside the already-selected routine
        // (docs/s2disasm/s2.asm:64528-64533), and Obj52_Defeat sets Boss_Countdown=$B3 /
        // boss_routine=8 and returns (docs/s2disasm/s2.asm:64559-64566) — so
        // Obj52_Mobile_Defeated's first `subi_.w #1,(Boss_Countdown).w`
        // (docs/s2disasm/s2.asm:64570-64572) lands on the NEXT frame. The engine runs
        // touch responses before this object's own update(), so defer the first defeat
        // dispatch by one frame to restore that offset.
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        // ROM: s2.asm:64036-64043
        // Initialize defeat timer
        defeatTimer = DEFEAT_TIMER_START;
        defeatFleeStarted = false;

        // Transition to defeated state
        state.routineSecondary = SUB8_DEFEATED;
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
    }

    // Note: getCollisionFlags() is inherited from AbstractBossInstance which
    // handles invulnerability/defeat gating. The ROM's Boss_CollisionRoutine
    // (0=disabled during rising/lowering, 1=enabled during flamethrower) controls
    // additional HTZ-specific collision behavior, but the base class gating via
    // invulnerability state is sufficient for this implementation.

    @Override
    public int getPriorityBucket() {
        return 4;  // ROM: move.b #4,priority(a0)
    }

    @Override
    public boolean isPersistent() {
        // Obj52's defeated flee routine is the owner of the post-boss camera
        // expansion: it increments Camera_Max_X_pos until $3160, then deletes
        // itself (docs/s2disasm/s2.asm:64084-64112). The generic object-side
        // out-of-range unload must not retire the event-spawned boss before that
        // loop completes.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(
                Sonic2ObjectArtKeys.HTZ_BOSS);
        if (renderer == null) return;

        // Determine flip based on which side the boss is on
        // Left side = flipped (facing right), right side = not flipped (facing left)
        boolean flipped = (getCustomFlag(OBJOFF_SIDE_FLAG) == 0);

        // ROM renders as multi-sprite: main body + eye overlay
        // ROM: s2.asm:63663-63665 - sub2_x_pos, sub2_y_pos, sub2_mapframe
        // ROM: mainspr_childsprites = 1 during flamethrower phase

        if (state.defeated) {
            // Frame 16: Defeated body (smaller cockpit, no eye animation)
            renderer.drawFrameIndex(FRAME_DEFEATED, state.x, state.y, flipped, false);
        } else {
            // Render main boss body (frame 1)
            renderer.drawFrameIndex(FRAME_BODY, state.x, state.y, flipped, false);

            // Render eye overlay as child sprite (frames 2-3)
            // ROM: sub2_x_pos = x_pos, sub2_y_pos = y_pos (same position, different mapping)
            // Eye frames have offset built into their mappings (-$28, -$21)
            renderer.drawFrameIndex(eyeFrame, state.x, state.y, flipped, false);

            // Render flame spreading (frames 4-11) during flamethrower phase
            // ROM: AnimateBoss renders this as a child sprite using the body's art_tile,
            // so tile indices in frames 4-11 map directly to our combined pattern array.
            if (flameActive) {
                int flameFrame = FLAME_FRAMES[flameAnimIndex];
                renderer.drawFrameIndex(flameFrame, state.x, state.y, flipped, false);
            }
        }
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic2Sfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic2Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return com.openggf.game.sonic2.constants.Sonic2ObjectIds.BOSS_EXPLOSION;
    }
}

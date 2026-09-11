package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1SeesawObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x7B — SLZ Boss Spikeball.
 * ROM: _incObj/7B SLZ Boss Spikeball.asm
 *
 * Dropped by the SLZ boss (0x7A) onto seesaws. Falls under gravity,
 * lands on seesaw surface, waits for tilt, launches when player jumps
 * on the other end. If it hits the boss while flying, the boss takes damage.
 *
 * While resting, a self-destruct countdown runs from $F0 (240) frames.
 * The ball flashes increasingly fast ($78→faster, $3C→fastest) and
 * self-destructs at 0, spawning 4 fragments.
 *
 * Routines (BossSpikeball_Index):
 *   0: Init          — Setup, transitions to FALLING
 *   2: FALLING       — Gravity fall toward target seesaw
 *   4: RESTING       — Sitting on seesaw, self-destruct countdown, flashing
 *   6: FLYING        — Launched by seesaw, check boss collision
 *   8: EXPLODING     — Spawn explosion; fragments only on self-destruct
 *  10: FRAGMENT      — Fragment movement with rotation
 */
public class Sonic1SLZBossSpikeball extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // Ball Y offsets per seesaw frame (word_19018 / See_Speeds table)
    // dc.w -8, -$1C, -$2F, -$1C, -8
    private static final int[] BALL_Y_OFFSETS = {-8, -0x1C, -0x2F, -0x1C, -8};

    // Launch velocities (from BossSpikeball code)
    // Angle delta = 1
    private static final int LAUNCH_Y_SMALL = -0x818;
    private static final int LAUNCH_X_SMALL = -0x114;
    // Angle delta = 2, speed < $9C0
    private static final int LAUNCH_Y_MEDIUM = -0x960;
    private static final int LAUNCH_X_MEDIUM = -0xF4;
    // Angle delta = 2, speed >= $9C0
    private static final int LAUNCH_Y_HEAVY = -0xA20;
    private static final int LAUNCH_X_HEAVY = -0x80;
    // Heavy landing threshold
    private static final int HEAVY_LANDING_THRESHOLD = 0x9C0;

    // Boss hitbox for collision detection (BossSpikeball_BossHitbox)
    private static final int BOSS_HITBOX_HALF = 24; // -24 to +24
    // Ball hitbox (BossSpikeball_BallHitbox)
    private static final int BALL_HITBOX_HALF = 8; // -8 to +8

    // ROM: obColType = $8B (enemy type $8, size $B)
    private static final int COLLISION_FLAGS = 0x8B;

    // ROM: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;
    // ROM: fragments use priority 3
    private static final int FRAGMENT_PRIORITY = 3;

    // ROM: move.b #24/2,obActWid(a1) for both the ball (BossSpikeball_Main) and its
    // fragments (BossSpikeball_MakeFrag). This is the BuildSprites X half-extent.
    private static final int RENDER_HALF_WIDTH = 24 / 2;

    // ROM BuildSprites .assumeHeight band (docs/s1disasm/_inc/BuildSprites.asm:84-91):
    // neither the ball nor its fragments set sprite_customheight_bit, so the Y
    // visibility band is a fixed 32px either side of the 224-line viewport.
    private static final int RENDER_ASSUMED_HALF_HEIGHT = 32;

    // Standard gravity (ObjectFall: addi.w #$38,obVelY(a0))
    private static final int GRAVITY = 0x38;

    // Fragment gravity (lighter: addi.w #$18,obVelY)
    private static final int FRAGMENT_GRAVITY = 0x18;

    // Ball X offset from seesaw center
    private static final int BALL_X_OFFSET = 0x28;

    // Self-destruct timer constants (ROM: obSubtype during RESTING)
    private static final int SELF_DESTRUCT_INITIAL = 0xF0;     // 240 frames
    private static final int SELF_DESTRUCT_FAST_FLASH = 0x78;  // speed up at 120
    private static final int SELF_DESTRUCT_FASTEST_FLASH = 0x3C; // fastest at 60

    // Frame toggle durations for flashing
    private static final int FLASH_DURATION_SLOW = 10;
    private static final int FLASH_DURATION_FAST = 5;
    private static final int FLASH_DURATION_FASTEST = 2;

    // Subtype value that triggers fragment spawning
    private static final int SUBTYPE_SPAWN_FRAGMENTS = 0x20;

    // ROM Obj3F explosion timing (docs/s1disasm/_incObj/27, 3F Explosions.asm).
    // S1 loads obTimeFrame = 8-1 = 7 (Sonic1GameModule.explosionInitialAnimDuration),
    // advances obFrame every 8 frames, and DeleteObjects when obFrame reaches 5.
    private static final int EXPLOSION_INITIAL_ANIM_DURATION = 7;
    private static final int EXPLOSION_FINAL_FRAME = 5;

    // Fragment velocities (BossSpikeball_FragSpeed)
    private static final int[][] FRAGMENT_SPEEDS = {
            {-0x100, -0x340},  // left-up fast
            {-0xA0,  -0x240},  // left-up slow
            { 0x100, -0x340},  // right-up fast
            { 0xA0,  -0x240},  // right-up slow
    };

    private enum State {
        FALLING,    // Routine 2: gravity fall
        RESTING,    // Routine 4: sitting on seesaw, self-destruct countdown
        FLYING,     // Routine 6: launched, check boss collision
        EXPLODING,  // Routine 8: hit boss or self-destruct, spawn explosion
        FRAGMENT    // Routine 10: fragment movement
    }

    private State currentState;

    // References
    private final Sonic1SLZBossInstance boss;
    private final Sonic1SeesawObjectInstance seesaw;

    // Position (16.16 fixed-point)
    private int xPos;
    private int yPos;
    private int xVel;
    private int yVel;

    // Original seesaw position (see_origX/see_origY)
    private int seesawX;
    private int seesawY;

    // Stored seesaw frame when landing (see_frame / objoff_3A)
    private int storedFrame;

    // Display frame for rendering
    private int displayFrame = 1; // ROM: obFrame = 1 (silver ball)

    // Self-destruct countdown (mirrors ROM obSubtype during RESTING)
    private int subtypeCounter;

    // Frame toggle animation (mirrors ROM obTimeFrame/obDelayAni)
    private int frameToggleTimer;
    private int frameToggleDuration;

    // Fragment animation counter
    private int fragmentAnimCounter;

    // ROM Obj3F explosion state. BossSpikeball_Explode does an IN-PLACE obID
    // change (move.b #id_Explosion,obID(a0)) so the ball BECOMES the explosion in
    // its own slot, keeping its objoff_3C (= linked seesaw address) for the whole
    // ~41-frame explosion lifetime. The FixBugs=0 BSLZ_MakeBall .checkForBall scan
    // (slots 1..63, raw objoff_3C compare) therefore still matches this lingering
    // explosion and aborts a fresh drop on the same seesaw. The engine models the
    // explosion in place (rather than destroying the ball + spawning a separate
    // Explosion object) so the duplicate-scan sees the retained seesaw link, and
    // so the boss's drop cadence stays ROM-faithful.
    private boolean explosionStarted;
    private int explosionAnimFrame;
    private int explosionAnimDuration = -1;

    /**
     * Create a boss spikeball spawned by the SLZ boss above a target seesaw.
     *
     * @param boss   The parent boss (for hit detection)
     * @param seesaw The target seesaw to land on
     * @param startX Boss X position at spawn time
     * @param startY Boss Y position at spawn time (already includes +$20 offset)
     */
    public Sonic1SLZBossSpikeball(
            Sonic1SLZBossInstance boss,
            Sonic1SeesawObjectInstance seesaw,
            int startX,
            int startY) {
        super(new ObjectSpawn(startX, startY,
                Sonic1ObjectIds.SLZ_BOSS_SPIKEBALL, 0, 0, false, 0), "BossSpikeball");

        this.boss = boss;
        this.seesaw = seesaw;
        this.seesawX = seesaw.getSpawn().x();
        this.seesawY = seesaw.getSpawn().y();

        // Start at boss position
        this.xPos = startX << 16;
        this.yPos = startY << 16;
        this.xVel = 0;
        this.yVel = 0;

        this.currentState = State.FALLING;

        // ROM: bset/bclr #0,obStatus(a0) — determine initial side
        if (startX > seesawX) {
            storedFrame = 0;
        } else {
            storedFrame = 2;
        }
    }

    /**
     * Fragment constructor — creates a fragment with specified velocity.
     * ROM: BossSpikeball_MakeFrag — priority 3, obColType = $98.
     */
    private Sonic1SLZBossSpikeball(int x, int y, int fragXVel, int fragYVel) {
        super(new ObjectSpawn(x, y,
                Sonic1ObjectIds.SLZ_BOSS_SPIKEBALL, 0, 0, false, 0), "BossSpikeFrag");
        this.boss = null;
        this.seesaw = null;
        this.seesawX = 0;
        this.seesawY = 0;
        this.xPos = x << 16;
        this.yPos = y << 16;
        this.xVel = fragXVel;
        this.yVel = fragYVel;
        this.currentState = State.FRAGMENT;
        this.displayFrame = 0;
        this.fragmentAnimCounter = 0;
    }

    /** Target seesaw this ball was dropped on (ROM objoff_3C). Null for fragments. */
    public Sonic1SeesawObjectInstance getTargetSeesaw() {
        return seesaw;
    }

    /** True while this object is an explosion fragment (no seesaw linkage). */
    public boolean isFragment() {
        return currentState == State.FRAGMENT;
    }

    /** ROM id_Explosion (docs/s1disasm/_inc/"Object Pointers.asm":79). */
    private static final int ROM_ID_EXPLOSION = 0x3F;

    /**
     * ROM {@code BossSpikeball_Explode} rewrites the ball's own SST id in place --
     * {@code move.b #id_Explosion,obID(a0)} -- so from routine 8 onward the slot reads
     * {@code id_Explosion} even though the object, its slot and its objoff_3C seesaw link are
     * unchanged (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":883-887).
     * Fragments are separately allocated and keep {@code id_BossSpikeball} (same file, :896).
     */
    @Override
    public int getLiveObjectId() {
        if (currentState == State.EXPLODING) {
            return ROM_ID_EXPLOSION;
        }
        return super.getLiveObjectId();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        seedCapturedScalars(ctx);
        if (currentState == State.FRAGMENT) {
            return new Sonic1SLZBossSpikeball(xPos >> 16, yPos >> 16, xVel, yVel);
        }

        Sonic1SLZBossInstance restoredBoss = findLiveBossForRewind(ctx);
        Sonic1SeesawObjectInstance restoredSeesaw =
                findLiveSeesawForRewind(ctx, seesawX, seesawY);
        if (restoredBoss == null || restoredSeesaw == null) {
            return null;
        }

        ObjectSpawn spawn = ctx != null && ctx.spawn() != null
                ? ctx.spawn()
                : getSpawn();
        return new Sonic1SLZBossSpikeball(restoredBoss, restoredSeesaw, spawn.x(), spawn.y());
    }

    private void seedCapturedScalars(RewindRecreateContext ctx) {
        if (ctx == null || ctx.state() == null || ctx.state().compactGenericState() == null) {
            return;
        }
        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(this, ctx.state().compactGenericState());
    }

    private static Sonic1SLZBossInstance findLiveBossForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerFor(ctx);
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1SLZBossInstance boss && !boss.isDestroyed()) {
                return boss;
            }
        }
        return null;
    }

    private static Sonic1SeesawObjectInstance findLiveSeesawForRewind(
            RewindRecreateContext ctx,
            int capturedSeesawX,
            int capturedSeesawY) {
        ObjectManager objectManager = objectManagerFor(ctx);
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof Sonic1SeesawObjectInstance liveSeesaw) || liveSeesaw.isDestroyed()) {
                continue;
            }
            ObjectSpawn spawn = liveSeesaw.getSpawn();
            if (spawn.x() == capturedSeesawX && spawn.y() == capturedSeesawY) {
                return liveSeesaw;
            }
        }
        return null;
    }

    private static ObjectManager objectManagerFor(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null) {
            return null;
        }
        return ctx.objectServices().objectManager();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (currentState) {
            case FALLING -> updateFalling();
            case RESTING -> updateResting();
            case FLYING -> updateFlying();
            case EXPLODING -> updateExploding();
            case FRAGMENT -> updateFragment(vIntRunCount);
        }
    }

    // === FALLING — gravity fall toward seesaw ===
    // ROM: BossSpikeball_Fall (routine 2)
    private void updateFalling() {
        objectFall();

        // Check if reached seesaw surface
        int parentMappingFrame = seesaw.getMappingFrame();
        int offsetIndex = parentMappingFrame;

        int currentX = xPos >> 16;
        if (currentX < seesawX) {
            offsetIndex += 2;
        }
        offsetIndex = clampIndex(offsetIndex);

        int landingY = seesawY + BALL_Y_OFFSETS[offsetIndex];
        int currentY = yPos >> 16;

        if (currentY >= landingY) {
            // Landed on seesaw
            yPos = landingY << 16;
            // ROM BossSpikeball_Fall does NOT clear obVelY before loc_18FA2 — the
            // launch reads the ball's post-ObjectFall vertical speed and only
            // loc_19008 (after the launch) clears it. transitionToSeesawResting
            // performs that post-launch clear, so the falling speed survives into
            // launchStandingPlayer (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and
            // Spike Balls.asm:546-573,776-806).

            // Determine landing side and set seesaw tilt
            int landingSide = (currentX >= seesawX) ? 0 : 2;
            storedFrame = landingSide;

            // ROM: move.w #$F0,obSubtype(a0) — self-destruct timer
            subtypeCounter = SELF_DESTRUCT_INITIAL;
            // ROM: move.b #10,obDelayAni(a0) — initial flash duration
            frameToggleDuration = FLASH_DURATION_SLOW;
            frameToggleTimer = frameToggleDuration;

            // Set seesaw tilt and potentially launch player (shared code at loc_18FA2)
            transitionToSeesawResting(landingSide);
        }
    }

    // === RESTING — sitting on seesaw, waiting for tilt change ===
    // ROM: loc_18DC6 (routine 4)
    private void updateResting() {
        int parentFrame = seesaw.getTargetFrame();
        int angleDelta = storedFrame - parentFrame;

        if (angleDelta != 0) {
            // Seesaw tilted — calculate launch velocity and transition to FLYING
            launchFromSeesaw(angleDelta);
            return;
        }

        // No tilt change — position on seesaw surface and run self-destruct countdown
        positionOnSeesaw();

        // ROM: subq.w #1,obSubtype(a0)
        subtypeCounter--;
        if (subtypeCounter <= 0) {
            // Self-destruct! Set subtype to $20 (triggers fragment spawn) and explode
            subtypeCounter = SUBTYPE_SPAWN_FRAGMENTS;
            currentState = State.EXPLODING;
            return;
        }

        // ROM: speed up flashing at thresholds
        if (subtypeCounter == SELF_DESTRUCT_FAST_FLASH) {
            frameToggleDuration = FLASH_DURATION_FAST;
        } else if (subtypeCounter == SELF_DESTRUCT_FASTEST_FLASH) {
            frameToggleDuration = FLASH_DURATION_FASTEST;
        }

        // Frame toggle animation (ROM: loc_18E96)
        updateFlashingAnimation();
    }

    /**
     * Launch ball from seesaw when tilt changes.
     * ROM: loc_18DDA — computes launch velocities from angle delta and player landing speed.
     */
    private void launchFromSeesaw(int angleDelta) {
        int absDelta = Math.abs(angleDelta);

        int launchY, launchX;
        if (absDelta == 1) {
            launchY = LAUNCH_Y_SMALL;
            launchX = LAUNCH_X_SMALL;
        } else {
            int parentStoredVel = seesaw.getStoredPlayerYVel();
            if (parentStoredVel >= HEAVY_LANDING_THRESHOLD) {
                launchY = LAUNCH_Y_HEAVY;
                launchX = LAUNCH_X_HEAVY;
            } else {
                launchY = LAUNCH_Y_MEDIUM;
                launchX = LAUNCH_X_MEDIUM;
            }
        }

        yVel = launchY;
        xVel = launchX;

        // Negate X velocity if ball is on left side of seesaw
        int currentX = xPos >> 16;
        if (currentX < seesawX) {
            xVel = -xVel;
        }

        displayFrame = 1; // ROM: move.b #1,obFrame(a0)
        // ROM: move.w #$20,obSubtype(a0) — set for potential fragment spawn on self-destruct
        subtypeCounter = SUBTYPE_SPAWN_FRAGMENTS;
        currentState = State.FLYING;

        // ROM BossSpikeball_Bounce ends with `addq.b #2,obRoutine(a0) / bra.w
        // BossSpikeball_HitBoss` (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and
        // Spike Balls.asm:610-614): the launch frame falls straight through into the
        // flying routine, so the ball already applies its first ObjectFall step (and
        // the ascending double-gravity past the apex) on the frame it launches.
        // Returning here instead left the ball stationary for one frame, so its whole
        // flight — and the landing that springs the standing player — lagged ROM by
        // one frame (SLZ3 f6507: ROM springs the player at f6507, the engine sprang
        // at f6508). Mirrors the same fall-through fix on the seesaw's own spikeball
        // (Sonic1SeesawBallObjectInstance.updateResting).
        updateFlying();
    }

    // === FLYING — launched, check boss collision and landing ===
    // ROM: loc_18EAA (routine 6)
    private void updateFlying() {
        boolean hitBoss = false;

        // Check collision with boss.
        // ROM BossSpikeball_HitBoss (loc_18EAA/loc_18EC0) scans for id_BossStarLight
        // and tests the ball/boss hitbox overlap WITHOUT ever checking the boss's
        // defeated flag (obStatus bit 7) or its obColType. A ball flying into the
        // boss therefore still explodes on contact even AFTER the boss is defeated
        // (the boss main object persists at its position through the Explode/Recover
        // phases until it escapes). Gating the overlap on !defeated made a ball that
        // the player launched just after the final hit sail straight past the
        // defeated boss, arc back down, and spuriously hurt the standing player
        // (SLZ3 f12712: engine ball slot 41 descended onto the player; ROM's
        // equivalent exploded against the defeated boss instead, ~f12646).
        // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:665-734)
        if (boss != null && !boss.isDestroyed()) {
            if (checkBossCollision()) {
                // ROM: addq.b #2,obRoutine(a0) — advance to EXPLODING
                // ROM: clr.w obSubtype(a0) — no fragments on boss hit
                boolean bossWasDefeated = boss.getState().defeated;
                subtypeCounter = 0;
                hitBoss = true;

                // ROM loc_18EC0: clr.b obColType(a1) / subq.b #1,obBossHits(a1).
                // When the boss is already defeated, processHit is a no-op (it early
                // returns on state.defeated), matching ROM's subq that simply runs
                // obBossHits negative without re-defeating (bne loc_18F38).
                boss.onSpikeballHit();

                // ROM clears the ball velocity only on the hit that DEFEATS the boss
                // (the `else` branch: bset #7 / clr obVelX / clr obVelY). A hit on an
                // already-defeated boss leaves the ball's velocity for its final
                // pre-explosion physics step (loc_18F38).
                if (!bossWasDefeated && boss.getState().defeated) {
                    xVel = 0;
                    yVel = 0;
                }
            }
        }

        // Continue flying physics even after hit (ROM continues to loc_18F38)
        if (yVel < 0) {
            // Ascending
            objectFall();

            // Double gravity when below upper bound
            int upperBound = seesawY - 0x2F;
            int currentY = yPos >> 16;
            if (currentY >= upperBound) {
                objectFall();
            }
        } else {
            // Descending
            objectFall();
            if (checkFlyingLanding()) {
                return; // landed — state already changed to EXPLODING
            }
        }

        // Frame toggle flashing animation (ROM: bra.w loc_18E7A at end of flying)
        updateFlashingAnimation();

        // Remove if off-screen (ROM: BossStarLight_Delete check after DisplaySprite)
        if (!isOnScreen()) {
            setDestroyed(true);
            return;
        }

        // Apply deferred state change after all physics for this frame
        if (hitBoss) {
            currentState = State.EXPLODING;
        }
    }

    // === EXPLODING — the ball becomes the bomb explosion IN PLACE ===
    // ROM: BossSpikeball_Explode (routine 8) does `move.b #id_Explosion,obID(a0)`
    // (an in-place obID change, NOT a fresh object) and then runs the standard
    // Obj3F explosion (Expl_Main + ExItem_Animate). Because it is an in-place
    // change, the object keeps its slot and its objoff_3C (= linked seesaw) for
    // the whole explosion lifetime, which is exactly why BSLZ_MakeBall's broken
    // FixBugs=0 .checkForBall scan still finds it and aborts a fresh drop on that
    // seesaw. We therefore keep this object alive (rather than destroying it and
    // spawning a separate Explosion) so the boss's duplicate-scan stays faithful.
    // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:833-864;
    //  Obj3F lifetime docs/s1disasm/_incObj/27, 3F Explosions.asm:62-85)
    private void updateExploding() {
        if (!explosionStarted) {
            explosionStarted = true;
            int bx = xPos >> 16;
            int by = yPos >> 16;

            // ROM BossSpikeball_MakeFrag (loc_19086) runs `move.w objoff_34(a0),obY(a0)`
            // BEFORE spawning the four fragments, so on a self-destruct
            // (obSubtype == $20) both the (now-explosion) self and the fragments come
            // out at the seesaw's origin Y, not the offset resting position the ball
            // was flashing at. objoff_34 holds the linked seesaw's obY
            // (BossSpikeball_Main: `move.w obY(a1),objoff_34(a0)`) = seesawY here.
            // (SLZ3 f7405.)
            if (subtypeCounter == SUBTYPE_SPAWN_FRAGMENTS && seesaw != null) {
                by = seesawY;
                yPos = seesawY << 16;
            }

            // ROM Obj3F Expl_Main: sfx_Bomb ($C4), obFrame = 0, obTimeFrame = 8-1.
            try {
                services().playSfx(Sonic1Sfx.BOSS_EXPLOSION.id);
            } catch (Exception ignored) {
                // audio failure must not break game logic
            }

            // ROM: cmpi.w #$20,obSubtype(a0) / beq.s BossSpikeball_MakeFrag.
            // Fragments only on self-destruct (subtype == $20), not on boss hit (0).
            // The fragments are SEPARATE FindFreeObj objects with NO seesaw link
            // (objoff_3C = 0), so they never count toward the duplicate-scan.
            if (subtypeCounter == SUBTYPE_SPAWN_FRAGMENTS) {
                spawnFragments(bx, by);
            }

            explosionAnimFrame = 0;
            explosionAnimDuration = EXPLOSION_INITIAL_ANIM_DURATION; // S1 = 7
        }

        // ROM ExItem_Animate: subq obTimeFrame; bpl display; else reload, advance
        // frame; delete when frame reaches 5 (~41 frames total).
        explosionAnimDuration--;
        if (explosionAnimDuration >= 0) {
            return;
        }
        explosionAnimDuration = EXPLOSION_INITIAL_ANIM_DURATION;
        explosionAnimFrame++;
        if (explosionAnimFrame >= EXPLOSION_FINAL_FRAME) {
            // ROM DeleteObject — slot freed, objoff_3C cleared, drops allowed again.
            setDestroyed(true);
        }
    }

    // === FRAGMENT — fragment movement with gravity and rotation ===
    // ROM: BossSpikeball_MoveFrag (routine 10)
    private void updateFragment(int vIntRunCount) {
        // Apply velocity (SpeedToPos)
        xPos += (xVel << 8);
        yPos += (yVel << 8);

        // Fragment gravity (lighter than standard)
        yVel += FRAGMENT_GRAVITY;

        // ROM: moveq #4,d0 / and.w (v_vbla_word).w,d0 / lsr.w #2,d0
        // Rotating animation: bit 2 of frame counter, shifted right by 2
        fragmentAnimCounter++;
        displayFrame = (fragmentAnimCounter >> 2) & 1;

        // ROM BossSpikeball_MoveFrag ends with:
        //     tst.b   obRender(a0)
        //     bpl.w   BossStarLight_Delete        ; FixBugs = 0 path
        // (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":932-941).
        // The test reads obRender bit 7 (sprite_rendered_bit), which the PREVIOUS
        // frame's BuildSprites pass set only if the fragment survived its bounds
        // tests, so the fragment is freed one frame after it first fails them.
        // The bounds are BuildSprites' own: X within obActWid (= 24/2) of the
        // 320px viewport, Y within the fixed 32px .assumeHeight band of the 224-line
        // viewport, both with exclusive far edges
        // (docs/s1disasm/_inc/BuildSprites.asm:47-58,84-91).
        //
        // FixBugs = 1 would instead `bmi.s .return / addq.l #4,sp / bra.w
        // BossStarLight_Delete`, i.e. pop the caller's return address so the deleted
        // fragment is never handed to DisplaySprite (the display-and-delete bug). The
        // shipped ROM takes the =0 path, which still queues the just-deleted slot for
        // display that frame; the engine models the =0 lifetime.
        //
        // Using the engine's generic isOnScreen() point test here kept fragments alive
        // far longer than the ROM: on SLZ3 the two slow fragments (BossSpikeball_FragSpeed
        // entries 1 and 3, vel_y -$240) fall back past the bottom of the viewport and ROM
        // frees their slots by f6246, while the engine still held them at f6247. The lost
        // rings Sonic spills on that frame therefore landed in different FindFreeObj slots
        // (engine 47-50 vs ROM 44,46,47,48), and RLoss_Bounce's `(v_vblank_byte + d7) & 3`
        // floor probe -- d7 = 127 - slot -- gave them a different bounce cadence, so one
        // engine ring bounced back into Sonic at f6358.
        if (!isPreUpdateWithinRenderSpriteBounds(
                RENDER_HALF_WIDTH, RENDER_ASSUMED_HALF_HEIGHT)) {
            setDestroyed(true);
        }
    }

    /**
     * ObjectFall: apply velocity and add gravity.
     */
    private void objectFall() {
        xPos += (xVel << 8);
        yPos += (yVel << 8);
        yVel += GRAVITY;
    }

    /**
     * Position ball on seesaw surface based on parent's visual mapping frame.
     * ROM: loc_18E2A pattern (shared with seesaw ball).
     */
    private void positionOnSeesaw() {
        int parentMappingFrame = seesaw.getMappingFrame();

        int offsetIndex = parentMappingFrame;
        int xOffset = BALL_X_OFFSET;

        int currentX = xPos >> 16;
        if (currentX < seesawX) {
            xOffset = -BALL_X_OFFSET;
            offsetIndex += 2;
        }
        offsetIndex = clampIndex(offsetIndex);

        yPos = (seesawY + BALL_Y_OFFSETS[offsetIndex]) << 16;
        xPos = (seesawX + xOffset) << 16;
    }

    /**
     * Check if flying ball has landed on seesaw surface.
     * ROM: loc_18F5C (descending path) — if landed, transitions to EXPLODING (routine += 2).
     * Returns true if landed.
     */
    private boolean checkFlyingLanding() {
        int parentMappingFrame = seesaw.getMappingFrame();
        int offsetIndex = parentMappingFrame;

        int currentX = xPos >> 16;
        if (currentX < seesawX) {
            offsetIndex += 2;
        }
        offsetIndex = clampIndex(offsetIndex);

        int landingY = seesawY + BALL_Y_OFFSETS[offsetIndex];
        int currentY = yPos >> 16;

        if (currentY < landingY) {
            return false; // Haven't reached surface
        }

        // Ball landed — determine side and set seesaw frame
        yPos = landingY << 16;
        int landingAngle = (xVel < 0) ? 2 : 0;

        // ROM: move.w #0,obSubtype(a0) — no fragments on landing
        subtypeCounter = 0;

        // Set seesaw tilt and potentially launch player (shared code at loc_18FA2)
        // ROM: addq.b #2,obRoutine(a0) from loc_19008 — routine 6 → 8 (EXPLODING)
        transitionToSeesawResting(landingAngle);

        // ROM: from FLYING (routine 6), routine += 2 = 8 (EXPLODING), not RESTING
        currentState = State.EXPLODING;
        return true;
    }

    /**
     * Shared seesaw landing logic (ROM: loc_18FA2).
     * Sets seesaw tilt angle, checks if player should be launched, clears velocity.
     */
    private void transitionToSeesawResting(int landingAngle) {
        // ROM loc_18FA2 guards the player launch with TWO checks, evaluated AFTER
        // writing the landing angle d1 into both objoff_3A slots:
        //   cmp.b   obFrame(a1),d1   ; compare the seesaw's CURRENT visual frame to d1
        //   beq.s   loc_19008        ; tilt did not change -> skip the launch
        //   bclr    #3,obStatus(a1)  ; clear the "player standing" bit, test the old value
        //   beq.s   loc_19008        ; player was not registered standing -> skip the launch
        // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:776-806).
        // The first guard (tilt actually changed) was missing: the ball that lands on
        // the side the seesaw is ALREADY tilted toward (landingAngle == obFrame) does
        // NOT flip the seesaw, so it must not catapult the standing player — instead the
        // resting spikeball's col_hurt collision simply spikes whoever is on the seesaw.
        // Omitting it made the engine launch the player on the landing frame whenever
        // they were standing, so a standing Sonic was flung upward (y_speed -07E0) one
        // frame before the ROM, where the ball instead lands harmlessly that frame and
        // hurts him the next (Sonic_Hurt: y_speed -0400). Compare against getMappingFrame()
        // (obFrame) BEFORE setTargetFrame, which only updates the tilt TARGET (objoff_3A).
        // (SLZ3 trace f7198/f7199.)
        boolean tiltChanged = (landingAngle != seesaw.getMappingFrame());
        seesaw.setTargetFrame(landingAngle);
        storedFrame = landingAngle;

        if (tiltChanged && seesaw.isPlayerStanding()) {
            launchStandingPlayer();
        }

        // ROM: loc_19008 — clear velocity
        xVel = 0;
        yVel = 0;

        // For FALLING → RESTING, set state here (FLYING → EXPLODING overrides after return)
        if (currentState == State.FALLING) {
            currentState = State.RESTING;
        }
    }

    /**
     * Check AABB overlap between boss and ball for hit detection.
     * ROM: uses BossSpikeball_BossHitbox (-24..+24) and BossSpikeball_BallHitbox (-8..+8).
     */
    private boolean checkBossCollision() {
        int bossX = boss.getX();
        int bossY = boss.getY();
        int ballX = xPos >> 16;
        int ballY = yPos >> 16;

        // AABB overlap check — combined half-widths = 24 + 8 = 32
        int dx = Math.abs(ballX - bossX);
        int dy = Math.abs(ballY - bossY);

        return dx < (BOSS_HITBOX_HALF + BALL_HITBOX_HALF)
                && dy < (BOSS_HITBOX_HALF + BALL_HITBOX_HALF);
    }

    /**
     * Launch any player standing on the seesaw (spring effect).
     * ROM: loc_18FA2 — inverse ball Y velocity applied to player.
     */
    private void launchStandingPlayer() {
        AbstractPlayableSprite player = seesaw.getStandingPlayer();
        if (player == null) {
            return;
        }

        // ROM loc_18FA2: player velY = -ball velY, then halved (asr.w) when the
        // seesaw is in its flat mapping frame (obFrame == 1). asr.w is a signed
        // 16-bit arithmetic shift, so cast to short before shifting.
        // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:786-790)
        short launchVel = (short) -yVel;
        if (seesaw.getMappingFrame() == 1) {
            launchVel = (short) (launchVel >> 1);
        }
        // move.w obVelY(a0),obVelY(a2) / neg.w obVelY(a2)
        player.setYSpeed(launchVel);
        // ROM loc_18FDC: bset #1 (in air), bclr #3 (off object), clr.b jumping.
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(false);

        // ROM: jsr Sonic_ChkRoll — the boss seesaw puts Sonic into ball form (not a
        // spring) so he can damage Robotnik. Sonic_ChkRoll only rolls when Sonic is
        // not already rolling (btst #2,obStatus) and, on entry, shifts y_pos DOWN by
        // the standing/rolling radius difference (addq.w #sonic_height-sonic_roll_height,
        // obY) so his feet stay planted, and forces inertia to $200 when stopped.
        // (docs/s1disasm/_incObj/01 Sonic.asm:1143-1171). Without the y_pos shift the
        // box shrank in place and Sonic launched 5px too high (SLZ3 f6113).
        if (!player.getRolling()) {
            short preRollCentreX = player.getCentreX();
            player.setRolling(true);
            NativePositionOps.writeXPosPreserveSubpixel(player, preRollCentreX);
            player.setY((short) (player.getY() + player.getRollHeightAdjustment()));
            player.setAnimationId(Sonic1AnimationIds.ROLL);
            if (player.getGSpeed() == 0) {
                player.setGSpeed((short) 0x200);
            }
        }
        seesaw.clearPlayerStanding();

        try {
            services().playSfx(Sonic1Sfx.SPRING.id);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    /**
     * Frame toggle flashing animation.
     * ROM: loc_18E96 — decrements obTimeFrame, toggles obFrame between 0 and 1 at zero.
     */
    private void updateFlashingAnimation() {
        frameToggleTimer--;
        if (frameToggleTimer <= 0) {
            displayFrame ^= 1; // ROM: bchg #0,obFrame(a0)
            frameToggleTimer = frameToggleDuration;
        }
    }

    /**
     * Spawn 4 fragments from explosion point.
     * ROM: BossSpikeball_MakeFrag with BossSpikeball_FragSpeed velocities.
     */
    private void spawnFragments(int x, int y) {
        if (services().objectManager() == null) {
            return;
        }

        for (int[] speed : FRAGMENT_SPEEDS) {
            final int xv = speed[0];
            final int yv = speed[1];
            spawnFreeChild(() -> new Sonic1SLZBossSpikeball(x, y, xv, yv));
        }
    }

    private int clampIndex(int index) {
        if (index < 0) return 0;
        if (index >= BALL_Y_OFFSETS.length) return BALL_Y_OFFSETS.length - 1;
        return index;
    }

    @Override
    protected boolean isOnScreen() {
        var camera = services().camera();
        if (camera == null) {
            return true;
        }
        int screenX = (xPos >> 16) - camera.getX();
        int screenY = (yPos >> 16) - camera.getY();
        return screenX >= -64 && screenX <= 384 && screenY >= -64 && screenY <= 288;
    }

    // ---- TouchResponseProvider ----

    @Override
    public int getCollisionFlags() {
        if (currentState == State.EXPLODING || currentState == State.FRAGMENT) {
            // ROM: fragments use obColType = $98
            return (currentState == State.FRAGMENT) ? 0x98 : 0;
        }
        return COLLISION_FLAGS; // $8B — hurts player
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Position ----

    // ROM obX/obY are the object CENTRE (sub ReactToItem.asm uses obX(a1)/obY(a1)
    // directly against the ball's React_Sizes half-extents). obActWid ($C) is the
    // render-cull half-width only, NOT a position offset — getX()/getY() must
    // return the centre so the shared touch overlap (which reads getX()/getY() as
    // the object centre, matching every other S1 object) places the hurt box
    // correctly. Subtracting WIDTH_PIXELS shifted the col_16x16 hurt box 12px
    // up-left, so a rolling player rising into the falling ball missed the hurt
    // (SLZ3 trace f5917).
    @Override
    public int getX() {
        return xPos >> 16;
    }

    @Override
    public int getY() {
        return yPos >> 16;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(xPos >> 16, yPos >> 16);
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        if (currentState == State.FRAGMENT) {
            // ROM: BossSpikeball_MakeFrag uses bomb shrapnel art (Ani_Bomb .shrapnel: frames $A, $B)
            PatternSpriteRenderer bombRenderer = renderManager.getRenderer(ObjectArtKeys.BOMB);
            if (bombRenderer != null && bombRenderer.isReady()) {
                int shrapnelFrame = 10 + displayFrame; // map 0→10, 1→11
                bombRenderer.drawFrameIndex(shrapnelFrame, xPos >> 16, yPos >> 16, false, false);
            }
            return;
        }

        if (currentState == State.EXPLODING) {
            // ROM: the in-place obID change makes this object Obj3F (bomb explosion,
            // Map_ExplodeBomb + ArtTile_Explosion), shared with the engine's
            // ExplosionObjectInstance via the explosion renderer.
            PatternSpriteRenderer explosionRenderer = renderManager.getExplosionRenderer();
            if (explosionRenderer != null && explosionRenderer.isReady()
                    && explosionAnimFrame < EXPLOSION_FINAL_FRAME) {
                explosionRenderer.drawFrameIndex(explosionAnimFrame, xPos >> 16, yPos >> 16, false, false);
            }
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SLZ_SEESAW_BALL);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(displayFrame, xPos >> 16, yPos >> 16, false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: fragments use priority 3, ball uses priority 4
        if (currentState == State.FRAGMENT) {
            return RenderPriority.clamp(FRAGMENT_PRIORITY);
        }
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // ROM Obj3F (the in-place explosion) has no off-screen delete — it runs a
        // fixed ~41-frame animation timer then DeleteObjects. Keep the exploding
        // ball alive regardless of screen position so its slot (and seesaw link
        // for the boss duplicate-scan) persists for the ROM-accurate window.
        if (currentState == State.EXPLODING) {
            return !isDestroyed();
        }
        return !isDestroyed() && isOnScreen();
    }
}

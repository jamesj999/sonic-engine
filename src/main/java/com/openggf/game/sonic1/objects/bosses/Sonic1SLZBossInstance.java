package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.objects.Sonic1SeesawObjectInstance;
import com.openggf.graphics.GLCommand;

import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x7A — Star Light Zone Boss (Eggman with seesaw bomb launcher).
 * ROM: _incObj/7A Boss - Star Light.asm
 *
 * Eggman oscillates horizontally over 3 seesaws, periodically dropping
 * spikeballs onto them. The player must jump on the other end of a seesaw
 * to launch the spikeball into Eggman.
 *
 * State machine (BossStarLight_ShipIndex, routineSecondary):
 *   0: ENTRANCE     — Approach from right at -0x100, sine Y oscillation, stop at X = 0x2120
 *   2: SCANNING     — Patrol at ±0x200, scan for seesaw alignment
 *   4: BALL_SPAWN   — Timer 0x28, drop spikeball on matched seesaw, then back to 2
 *   6: DEFEAT_WAIT  — Timer 0x78 countdown with explosions
 *   8: EXIT_JUMP    — Multi-stage upward bounce, play zone music
 *  10: ESCAPE       — X vel 0x400, Y vel -0x40, extend camera right, delete when off-screen
 *
 * Face, flame, and tube are rendered as overlays on the ship (not separate object instances).
 */
public class Sonic1SLZBossInstance extends AbstractS1EggmanBossInstance implements RewindRecreatable {

    // State machine constants (routineSecondary values, matching ROM's even-numbered index)
    private static final int STATE_ENTRANCE = 0;
    private static final int STATE_SCANNING = 2;
    private static final int STATE_BALL_SPAWN = 4;
    private static final int STATE_DEFEAT_WAIT = 6;
    private static final int STATE_EXIT_JUMP = 8;
    private static final int STATE_ESCAPE = 10;

    // Position constants from DynamicLevelEvents.asm / Constants.asm
    private static final int BOSS_SLZ_X = 0x2000;
    private static final int BOSS_SLZ_Y = 0x210;
    private static final int BOSS_SLZ_END = BOSS_SLZ_X + 0x160; // 0x2160

    // Entrance stop: boss_slz_x + $120
    private static final int ENTRANCE_STOP_X = BOSS_SLZ_X + 0x120;

    // Scanning boundaries (left/right patrol limits)
    private static final int SCAN_LEFT = BOSS_SLZ_X + 0x8;
    private static final int SCAN_RIGHT = BOSS_SLZ_X + 0x138;

    // Seesaw alignment offset: $28 pixels
    private static final int SEESAW_ALIGN_OFFSET = 0x28;

    // Timers
    private static final int BALL_SPAWN_DELAY = 0x28; // 40 frames
    private static final int DEFEAT_TIMER = 0x78;     // 120 frames

    // Velocities (8.8 fixed-point)
    private static final int ENTRANCE_X_VEL = -0x100;
    private static final int SCANNING_X_VEL = 0x200;
    private static final int ESCAPE_X_VEL = 0x400;
    private static final int ESCAPE_Y_VEL = -0x40;

    // Sine oscillation counter — objoff_3F
    private int sineAngle;

    // General-purpose timer — objoff_3C
    private int timer;

    // Seesaw references (objoff_2A array, up to 3 seesaws). This cache is an
    // engine-side implementation detail (the ROM caches the same addresses
    // once, in BossStarLight_ShipInit, not lazily); the object-reference list
    // is not covered by the default rewind field capture, so a rewind
    // restore always recreates this instance from its spawn (see
    // ObjectManager.isRewindInPlaceReuseSafeClass) with an empty list. Gating
    // the (re-)scan on emptiness rather than a separate "already scanned"
    // flag makes the cache self-healing across a restore instead of leaving
    // it permanently empty (Robotnik would never drop another spikeball
    // after any rewind: the alignment loop in updateScanning() has nothing
    // to iterate).
    private final List<Sonic1SeesawObjectInstance> seesaws = new ArrayList<>();

    // Target seesaw index for ball spawn (obSubtype stores seesaw index 0-2)
    private int targetSeesawIndex;

    // ROM BossStarLight_Main builds the boss from a 4-entry table
    // (BossStarLight_ObjData) with `moveq #3,d1` / `dbf d1,BossStarLight_Loop`
    // (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":30-74).
    // The first pass writes into the boss's own slot (movea.l a0,a1); the three
    // remaining passes each call FindNextFreeObj and claim a further object RAM
    // slot -- the face (routine 4), flame (routine 6) and pipe (routine 8).
    // This engine draws face/flame/pipe as overlays on the single ship instance,
    // so those three slots would otherwise stay free and every later dynamically
    // allocated object would sit three slots low. Slot number is observable
    // behaviour: RLoss_Bounce probes the floor only when
    // (v_vblank_byte + d7) & 3 == 0 with d7 = 127 - slot
    // (docs/s1disasm/_incObj/"25, 37 Rings.asm":334-339,
    // docs/s1disasm/_inc/ExecuteObjects.asm:10-30), so a shifted slot changes
    // each spilled ring's bounce cadence and the frame it is collected on.
    private static final int ROM_CHILD_OBJECT_COUNT = 3;
    private boolean childSlotsReserved;

    public Sonic1SLZBossInstance(ObjectSpawn spawn) {
        super(spawn, "SLZ Boss");
    }

    @Override
    public Sonic1SLZBossInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1SLZBossInstance(ctx.spawn());
    }

    @Override
    protected void initializeBossState() {
        state.routineSecondary = STATE_ENTRANCE;
        state.xVel = ENTRANCE_X_VEL; // -$100 — move left
        state.yVel = 0;

        // Store initial position in fixed-point (objoff_30/objoff_38)
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        sineAngle = 0;
        timer = 0;
        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
        flameAnim = Sonic1BossAnimations.ANIM_FLAME_1;
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // obColProp = 8
    }

    @Override
    protected int getCollisionSizeIndex() {
        // ROM: obColType = $F (category 0, size index 0x0F = 24x24)
        return 0x0F;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // SLZ boss has custom defeat logic in states 6-10
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // ROM: the killing hit only sets obStatus bit 7 on the boss; the boss acts on
        // it when its own routine reaches BSLZ_StatusUpdate (run from the end of
        // BossStarLight_ShipMain), where BSLZ_Defeated does
        //   move.b #6,ob2ndRout(a0)   ; select BSLZ_Explode
        //   move.b #120,BossStarLight_GenericTimer(a0)
        //   clr.w obVelX(a0)
        //   rts
        // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:186-192,
        // loc_18A46). BSLZ_Defeated returns WITHOUT falling through to BSLZ_Explode, so
        // the newly selected secondary routine -- and its first defeat-timer decrement
        // (BSLZ_Explode subq.b #1,GenericTimer at loc_18B48, lines 313-314) -- is not
        // dispatched until the next frame, when BossStarLight_ShipMain re-reads
        // ob2ndRout at the top via BossStarLight_ShipIndex (lines 102-104). The engine
        // selects the defeat routine during the spikeball's update / touch-response pass
        // that runs before this boss's own update(), so without this one-frame deferral
        // updateDefeatWait() decrements the $78 timer on the same frame the routine
        // changed. The deferral restores that settle frame, which propagates through the
        // exit jump (BSLZ_Recover) to BSLZ_Escape so the `addq.w #2,(v_limitright2)`
        // camera scroll (runCameraExpandEscape) starts on the correct frame (SLZ3 trace
        // f12785, not f12784). Same ROM dispatch shape as the GHZ, SYZ, and MZ bosses
        // (Sonic1GHZBossInstance / Sonic1SYZBossInstance / Sonic1MZBossInstance
        // .defeatDeferralAppliesToThisBoss).
        return true;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // ROM: sfx_HitBoss played by BossHitHandler
        faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
    }

    @Override
    protected void onDefeatStarted() {
        // ROM: AddPoints 100, then transition to post-defeat pause
        services().gameState().addScore(100);
        state.routineSecondary = STATE_DEFEAT_WAIT;
        state.xVel = 0;
        timer = DEFEAT_TIMER; // $78 = 120 frames
    }

    /**
     * Claims the three sub-object RAM slots BossStarLight_Main allocates on its
     * first pass. The ROM uses FindNextFreeObj -- an ascending scan starting at
     * the parent's own slot (docs/s1disasm/_incObj/"sub FindFreeObj.asm":36-51)
     * -- restarted from the parent for each of the three children; because each
     * child fills the slot it found, that is the same sequence as chaining the
     * scan from the previous child, which is what allocateChildSlotsAfter does.
     * A full object RAM aborts the loop in both.
     */
    private void reserveChildSlots() {
        if (childSlotsReserved) {
            return;
        }
        childSlotsReserved = true;
        com.openggf.level.objects.ObjectServices svc = tryServices();
        if (getSpawn() == null || svc == null || svc.objectManager() == null) {
            return;
        }
        svc.objectManager().allocateChildSlotsAfter(
                getSpawn(), ROM_CHILD_OBJECT_COUNT, getSlotIndex());
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM BossStarLight_Main runs once, on the boss's first ExecuteObjects
        // pass, and claims its three sub-object slots there.
        reserveChildSlots();
        // Lazy seesaw scanning — ensures seesaws are loaded before scanning.
        // Re-scan whenever the cache is empty (not a separate "already
        // scanned" flag) so a rewind-recreated instance repopulates it
        // instead of staying permanently unaligned.
        if (seesaws.isEmpty()) {
            scanForSeesaws();
        }

        // Track whether BossMove and sine should run after the state handler.
        // ROM: BossMove + sine (loc_189CA) only for specific states.
        boolean runBossMove = true;
        boolean runSine = true;

        switch (state.routineSecondary) {
            case STATE_ENTRANCE -> updateEntrance();
            case STATE_SCANNING -> updateScanning();
            case STATE_BALL_SPAWN -> {
                boolean timerExpired = updateBallSpawn();
                // ROM: only runs BossMove+sine on final frame (loc_18B40 -> loc_189CA)
                // Countdown frames branch to loc_189FE (no BossMove, no sine)
                runBossMove = timerExpired;
                runSine = timerExpired;
            }
            case STATE_DEFEAT_WAIT -> {
                updateDefeatWait(vIntRunCount);
                runBossMove = false;
                runSine = false;
            }
            case STATE_EXIT_JUMP -> {
                updateExitJump();
                // ROM: loc_189EE — BossMove but NO sine (Y set directly from yFixed)
                runSine = false;
            }
            case STATE_ESCAPE -> updateEscape();
        }

        if (runBossMove) {
            bossMove();
        }

        if (runSine) {
            updateSineOscillation();
        } else if (runBossMove) {
            // EXIT_JUMP/ESCAPE without sine: set display position directly
            updatePositionNoSine();
        }

        // Update face animation based on state
        updateFaceAnimation(player);

        // Update flame animation based on state
        updateFlameAnimation();
    }

    // === State 0: ENTRANCE ===
    // ROM: loc_189B8 — approach from right with sine Y oscillation
    private void updateEntrance() {
        state.xVel = ENTRANCE_X_VEL; // -$100

        // ROM: cmpi.w #boss_slz_x+$120,objoff_30(a0) / bhs.s loc_189CA
        // bhs = unsigned higher or same; stays while >= stop, advances when < stop
        if ((state.xFixed >> 16) < ENTRANCE_STOP_X) {
            state.routineSecondary = STATE_SCANNING;
        }
        // BossMove + sine called centrally after state handler
    }

    // === State 2: SCANNING ===
    // ROM: loc_18A5E — patrol left/right, scan for seesaw alignment
    private void updateScanning() {
        // ROM: move.w #$200,obVelX(a0) then negate if moving left
        state.xVel = SCANNING_X_VEL;

        // ROM: btst #0,obStatus(a0) / bne.s loc_18A7C
        if ((state.renderFlags & 1) == 0) {
            // Moving left
            state.xVel = -SCANNING_X_VEL;
            // ROM: cmpi.w #boss_slz_x+8,d0 / bgt.s loc_18A88
            if ((state.xFixed >> 16) <= SCAN_LEFT) {
                state.renderFlags ^= 1;
            }
        } else {
            // Moving right
            // ROM: cmpi.w #boss_slz_x+$138,d0 / blt.s loc_18A88
            if ((state.xFixed >> 16) >= SCAN_RIGHT) {
                state.renderFlags ^= 1;
            }
        }

        // Check seesaw alignment BEFORE BossMove (ROM order: loc_18A88 uses obX from previous frame)
        // ROM: d4 = $28, negated if obVelX < 0 (moving left)
        // Then checks: seesawX + d4 - bossX == 0
        int d4 = SEESAW_ALIGN_OFFSET;
        if (state.xVel < 0) {
            d4 = -d4;
        }
        int bossX = state.x; // display X from previous frame (matches ROM's obX)
        for (int i = 0; i < seesaws.size(); i++) {
            Sonic1SeesawObjectInstance seesaw = seesaws.get(i);
            if (seesaw.isDestroyed()) {
                continue;
            }
            // ROM: btst #3,obStatus(a3) — skip if player standing on seesaw
            if (seesaw.isPlayerStanding()) {
                continue;
            }
            int seesawX = seesaw.getSpawn().x();
            // ROM: exact match (beq.s loc_18AC0)
            if (seesawX + d4 == bossX) {
                // Matched seesaw — advance to ball spawn. ROM .prepDrop only arms
                // the timer and advances the routine here; the ball itself is
                // created one frame later on the first MakeBall frame (see
                // updateBallSpawn), so it inherits the boss X/Y AFTER this match
                // frame's BossMove (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main
                // and Spike Balls.asm:251-255).
                targetSeesawIndex = i;
                state.routineSecondary = STATE_BALL_SPAWN;
                timer = BALL_SPAWN_DELAY; // $28 = 40 frames
                return;
            }
        }
        // BossMove + sine called centrally after state handler
    }

    // === State 4: BALL_SPAWN ===
    // ROM: BossStarLight_MakeBall — timer countdown, then back to scanning
    // Returns true on timer expiry (BossMove + sine should run via loc_189CA)
    private boolean updateBallSpawn() {
        // ROM BSLZ_MakeBall: on the first MakeBall frame (timer still == 40) it
        // allocates the spikeball via FindNextFreeObj, THEN decrements the timer.
        // Creating the ball here (rather than on the SCANNING match frame) makes it
        // spawn at the boss's post-match-move X/Y and fall the ROM-correct number of
        // gravity steps (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike
        // Balls.asm:259-302).
        if (timer == BALL_SPAWN_DELAY) {
            // ROM BSLZ_MakeBall .checkForBall: before allocating the ball, scan
            // object RAM for an object whose objoff_3C already points at the target
            // seesaw (i.e. a boss spikeball already in flight toward it) and abort
            // the drop if found (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and
            // Spike Balls.asm:280-285,307-309). The released REV00/REV01 ROM builds
            // with FixBugs=0, so the scan covers only object slots 1..63 (the buggy
            // half-pool range at lines 275-278) -- a duplicate ball that spilled into
            // a slot >= 64 is NOT detected, which is exactly how the ROM ends up with
            // two balls on one seesaw. On abort the routine returns to ShipMove
            // (.abortDrop subtracts 2 from ob2ndRout and branches to BSLZ_ShipUpdate,
            // which runs BossMove + sine), so model it as an immediate return to
            // SCANNING with BossMove/sine enabled.
            if (targetSeesawHasPendingBall()) {
                state.routineSecondary = STATE_SCANNING;
                return true;
            }
            spawnBossSpikeball();
        }
        timer--;
        if (timer <= 0) {
            // ROM: subq.b #2,ob2ndRout(a0) — back to scanning (loc_18B40 -> loc_189CA)
            state.routineSecondary = STATE_SCANNING;
            return true;
        }
        // ROM: countdown frames branch to loc_189FE (no BossMove, no sine)
        return false;
    }

    // === State 6: DEFEAT_WAIT ===
    // ROM: loc_18B48
    private void updateDefeatWait(int vIntRunCount) {
        timer--;
        if (timer < 0) {
            // Timer expired — start exit jump (loc_18B52)
            state.routineSecondary = STATE_EXIT_JUMP;
            state.renderFlags |= 1;     // bset #0,obStatus — face right
            state.renderFlags &= ~0x80; // bclr #7,obStatus
            state.xVel = 0;
            state.yVel = 0;
            timer = -0x18; // Start exit jump counter at -$18

            // ROM: v_bossstatus = 1 (boss defeated flag)
            services().gameState().setCurrentBossId(0);
        } else {
            // Spawn explosions every 8 frames (BossDefeated)
            if ((vIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
        }
    }

    // === State 8: EXIT_JUMP ===
    // ROM: loc_18B80 — multi-stage upward movement
    // Uses loc_189EE (BossMove WITHOUT sine)
    private void updateExitJump() {
        timer++;

        if (timer == 0) {
            // loc_18B90: clear Y velocity
            state.yVel = 0;
        } else if (timer < 0) {
            // ROM: addi.w #$18,obVelY(a0) — pull toward zero
            state.yVel += 0x18;
        } else if (timer < 0x20) {
            // loc_18BAE: decelerate upward
            state.yVel -= 8;
        } else if (timer == 0x20) {
            // loc_18BB4: stop and play zone music
            state.yVel = 0;
            services().playMusic(Sonic1Music.SLZ.id);
        } else if (timer >= 0x2A) {
            // Advance to escape
            state.routineSecondary = STATE_ESCAPE;
        }
        // BossMove (no sine) called centrally after state handler
    }

    // === State 10: ESCAPE ===
    // ROM: loc_18BC6
    private void updateEscape() {
        state.xVel = ESCAPE_X_VEL;  // $400
        state.yVel = ESCAPE_Y_VEL;  // -$40

        runCameraExpandEscape(BOSS_SLZ_END);
        // BossMove + sine called centrally after state handler
    }

    /**
     * Apply sine oscillation to Y position and update display coordinates.
     * ROM: loc_189CA — CalcSine(objoff_3F) >> 6 + objoff_38 -> obY
     */
    private void updateSineOscillation() {
        int sinVal = TrigLookupTable.sinHex(sineAngle & 0xFF);
        int yOffset = sinVal >> 6;

        state.y = (state.yFixed >> 16) + yOffset;
        state.x = state.xFixed >> 16;

        sineAngle = (sineAngle + 2) & 0xFF;
    }

    /**
     * Update display position from fixed-point without sine oscillation.
     * ROM: loc_189EE — sets obY directly from objoff_38, obX from objoff_30.
     */
    private void updatePositionNoSine() {
        state.y = state.yFixed >> 16;
        state.x = state.xFixed >> 16;
    }

    /**
     * Update face animation based on boss state.
     * ROM: BossStarLight_FaceMain (routine 4)
     */
    private void updateFaceAnimation(AbstractPlayableSprite player) {
        if (state.routineSecondary >= STATE_DEFEAT_WAIT) {
            // ROM: ob2ndRout >= 6: anim = $A (facedefeat)
            // Then: if ob2ndRout == $A (ESCAPE): anim = 6 (facepanic)
            if (state.routineSecondary == STATE_ESCAPE) {
                faceAnim = Sonic1BossAnimations.ANIM_FACE_PANIC;
            } else {
                faceAnim = Sonic1BossAnimations.ANIM_FACE_DEFEAT;
            }
            return;
        }

        // During combat: check if being hit (invulnerable = flash)
        // ROM: tst.b obColType(a1) / bne.s — if collision disabled, show hit face
        if (state.invulnerable) {
            faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
            return;
        }

        // ROM: check if player is hurt (routine >= 4) — laugh
        if (player != null && player.isHurt()) {
            faceAnim = Sonic1BossAnimations.ANIM_FACE_LAUGH;
            return;
        }

        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
    }

    /**
     * Update flame animation based on boss state.
     * ROM: BossStarLight_FlameMain (routine 6)
     *
     * Default = anim 8 (flame1).
     * If ob2ndRout == $A (ESCAPE): anim = $B (escape flame).
     * If ob2ndRout >= 4 AND ob2ndRout <= 8: anim = 7 (blank) — flame OFF.
     */
    private void updateFlameAnimation() {
        if (state.routineSecondary == STATE_ESCAPE) {
            flameAnim = Sonic1BossAnimations.ANIM_ESCAPE_FLAME;
        } else if (state.routineSecondary >= STATE_BALL_SPAWN
                && state.routineSecondary <= STATE_EXIT_JUMP) {
            // ROM: ob2ndRout between 4 and 8 inclusive — flame off
            flameAnim = Sonic1BossAnimations.ANIM_BLANK;
        } else {
            flameAnim = Sonic1BossAnimations.ANIM_FLAME_1;
        }
    }

    /**
     * Scan active objects for seesaws with subtype != 0 (no pre-spawned ball).
     * ROM: loc_18968 — scans all object RAM for id_Seesaw with obSubtype != 0.
     * Stores up to 3 seesaw references in objoff_2A array.
     */
    private void scanForSeesaws() {
        seesaws.clear();

        if (services().objectManager() == null) {
            return;
        }

        for (ObjectInstance obj : services().objectManager().getActiveObjects()) {
            if (obj instanceof Sonic1SeesawObjectInstance seesaw) {
                // ROM: tst.b obSubtype(a1) / beq.s .next — only seesaws with subtype != 0
                if (seesaw.getSpawn().subtype() != 0) {
                    seesaws.add(seesaw);
                    if (seesaws.size() >= 3) {
                        break; // Max 3 seesaws
                    }
                }
            }
        }
    }

    // ROM FixBugs=0 BSLZ_MakeBall .checkForBall scans object slots 1..63 only
    // (docs/s1disasm/_incObj/7A, 7B Boss - SLZ Main and Spike Balls.asm:275-285).
    private static final int FIXBUGS_DUP_SCAN_LAST_SLOT = 63;

    /**
     * ROM BSLZ_MakeBall .checkForBall: is there already an object pointing at the
     * target seesaw (objoff_3C == seesaw address) within the FixBugs=0 half-pool
     * scan range (slots 1..63)? Balls in slots >= 64 are invisible to the buggy
     * scan and therefore do NOT block a new drop.
     */
    private boolean targetSeesawHasPendingBall() {
        if (targetSeesawIndex < 0 || targetSeesawIndex >= seesaws.size()) {
            return false;
        }
        if (services().objectManager() == null) {
            return false;
        }
        Sonic1SeesawObjectInstance target = seesaws.get(targetSeesawIndex);
        for (ObjectInstance obj : services().objectManager().getActiveObjects()) {
            if (!(obj instanceof Sonic1SLZBossSpikeball ball) || ball.isDestroyed()) {
                continue;
            }
            if (ball.isFragment() || ball.getTargetSeesaw() != target) {
                continue;
            }
            // FixBugs=0: only slots 1..63 are scanned.
            if (ball.getSlotIndex() >= 0 && ball.getSlotIndex() <= FIXBUGS_DUP_SCAN_LAST_SLOT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spawn a boss spikeball aimed at the target seesaw.
     * ROM: creates BossSpikeball (id_BossSpikeball) with seesaw reference.
     */
    private void spawnBossSpikeball() {
        if (targetSeesawIndex < 0 || targetSeesawIndex >= seesaws.size()) {
            return;
        }

        Sonic1SeesawObjectInstance targetSeesaw = seesaws.get(targetSeesawIndex);
        if (targetSeesaw.isDestroyed()) {
            return;
        }

        // ROM: move.w obY(a0),obY(a1) / addi.w #$20,obY(a1) — spawn +$20 below boss
        //
        // Slot allocation is FindNextFreeObj scanning forward from the TARGET SEESAW's slot,
        // not FindFreeObj and not the boss's own slot: BSLZ_MakeBall pushes the boss pointer,
        // does "lea (a2),a0" with a2 = the matched seesaw, calls FindNextFreeObj, then restores
        // a0 (docs/s1disasm/_incObj/"7A, 7B Boss - SLZ Main and Spike Balls.asm":291-295).
        // In SLZ3 the three seesaws sit at slots 33/37/38 and dynamic allocation starts at 42,
        // so both rules currently pick the same slot: this is measurably a no-op on the
        // committed fixture (probe-verified, byte-identical slot occupancy). It is landed as a
        // correctness fix regardless, because slot number is observable -- RLoss_Bounce probes
        // the floor only when (VBlank byte + d7) & 3 == 0 with d7 = 127 - slot
        // (_incObj/"25, 37 Rings.asm":320-324) -- so any layout where a lower dynamic slot
        // frees while a seesaw is live would diverge under the FindFreeObj rule.
        if (services().objectManager() != null) {
            spawnChildAfterSlot(targetSeesaw.getSlotIndex(), () -> new Sonic1SLZBossSpikeball(
                    this,
                    targetSeesaw,
                    state.x,
                    state.y + 0x20));
        }
    }

    /**
     * Called by BossSpikeball when it collides with the boss.
     * Triggers a hit via the standard hit handler.
     */
    public void onSpikeballHit() {
        hitHandler.processHit(null);
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM: obPriority = 3
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        renderEggmanShip();

        // Draw tube/jet pipe (Map_BossItems frame 3 = .widepipe) — SLZ-specific overlay
        // ROM: BossStarLight_TubeMain — uses Map_BossItems with ArtTile_Eggman_Weapons
        if (state.routineSecondary != STATE_ESCAPE || isBossOnScreen()) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            boolean flipped = (state.renderFlags & 1) != 0;
            PatternSpriteRenderer weaponsRenderer = renderManager.getRenderer(ObjectArtKeys.BOSS_WEAPONS);
            if (weaponsRenderer != null && weaponsRenderer.isReady()) {
                weaponsRenderer.drawFrameIndex(3, state.x, state.y, flipped, false);
            }
        }
    }

    @Override
    public int getCollisionFlags() {
        // No collision during defeat states
        if (state.routineSecondary >= STATE_DEFEAT_WAIT) {
            return 0;
        }
        return super.getCollisionFlags();
    }
}

package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Music;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Object 0x75 — Spring Yard Zone Boss (Eggman with spike, grabs blocks).
 * ROM: docs/s1disasm/_incObj/75 Boss - Spring Yard.asm
 *
 * State machine (ob2ndRout / routineSecondary):
 *   0: APPROACH      — Fly in from right with sine Y oscillation
 *   2: PATROL        — Move left/right, look for block to grab (player column match)
 *   4: BLOCK_DROP    — 4 sub-phases: drop, hold, rise, settle/release
 *   6: DEFEAT_WAIT   — Timer $B4 countdown with explosions
 *   8: ASCENT        — Bounce/rise sequence, play zone music at timer $20
 *  10: ESCAPE        — Fly off right at high speed, expand camera
 *
 * Sub-objects (rendered as overlays + spike child):
 *  - Ship body (Eggman frame 0)
 *  - Face overlay (animation-driven)
 *  - Flame overlay (movement-driven)
 *  - Spike (child component with collision, extends below ship)
 */
public class Sonic1SYZBossInstance extends AbstractS1EggmanBossInstance
        implements SpawnConstructionContextRewindRecreatable {

    // State machine constants (routineSecondary, even-numbered to match ROM).
    // STATE_DEFEAT_WAIT/STATE_ESCAPE are package-private: SYZBossSpike reads the boss's
    // routineSecondary directly (ROM: BossSpringYard_SpikeMain dispatches independently
    // every frame off the boss's ob2ndRout) and needs the same named thresholds.
    private static final int STATE_APPROACH = 0;
    private static final int STATE_PATROL = 2;
    private static final int STATE_BLOCK_DROP = 4;
    static final int STATE_DEFEAT_WAIT = 6;
    private static final int STATE_ASCENT = 8;
    static final int STATE_ESCAPE = 10;

    // Arena constants from DynamicLevelEvents.asm / Constants.asm
    private static final int BOSS_SYZ_X = 0x2C00;
    private static final int BOSS_SYZ_Y = 0x4CC;
    private static final int BOSS_SYZ_END = BOSS_SYZ_X + 0x140; // $2D40

    // Spawn position: boss_syz_x + $1B0 = $2DB0, boss_syz_y + $E = $4DA
    private static final int SPAWN_X = BOSS_SYZ_X + 0x1B0;
    private static final int SPAWN_Y = BOSS_SYZ_Y + 0x0E;

    // Approach stop X: boss_syz_x + $138 = $2D38
    private static final int APPROACH_STOP_X = BOSS_SYZ_X + 0x138;

    // Patrol boundaries
    private static final int PATROL_LEFT_X = BOSS_SYZ_X + 0x08;
    private static final int PATROL_RIGHT_X = BOSS_SYZ_X + 0x138;

    // Drop target Y: boss_syz_y + $8A = $556
    private static final int DROP_TARGET_Y = BOSS_SYZ_Y + 0x8A;

    // Default hover Y: boss_syz_y + $E = $4DA
    private static final int HOVER_Y = BOSS_SYZ_Y + 0x0E;
    // Hover Y when holding block: $4DA - $18 = $4C2
    private static final int HOVER_Y_WITH_BLOCK = HOVER_Y - 0x18;

    // Timers
    private static final int HOLD_TIMER = 0x32;           // 50 frames hold
    private static final int SETTLE_TIMER_NO_BLOCK = 0x08;
    private static final int SETTLE_TIMER_WITH_BLOCK = 0x2D;
    private static final int DEFEAT_TIMER = 0xB4;         // 180 frames
    private static final int RETURN_DELAY = -0x1E;        // frames after release before returning to patrol

    // Velocities (8.8 fixed-point)
    private static final int APPROACH_X_VEL = -0x100;
    private static final int PATROL_X_VEL = 0x140;
    private static final int DROP_Y_VEL = 0x180;
    private static final int RISE_Y_VEL = -0x800;
    private static final int RISE_Y_VEL_NO_BLOCK = -0x400;
    private static final int RISE_GRAVITY = 0x0C;
    private static final int RISE_TERMINAL_VEL = -0x40;
    private static final int ESCAPE_X_VEL = 0x400;
    private static final int ESCAPE_Y_VEL = -0x40;
    private static final int ASCENT_ACCEL = 0x18;
    private static final int ASCENT_DECEL = 8;

    // Block drop sub-phases (obSubtype values)
    private static final int DROP_SUB_DESCEND = 0;
    private static final int DROP_SUB_HOLD = 2;
    private static final int DROP_SUB_RISE = 4;
    private static final int DROP_SUB_SETTLE = 6;

    // Private state fields
    private int sineAngle;      // objoff_3F — sine counter for Y oscillation
    private int timer;          // objoff_3C — multi-purpose timer
    private int dropSubPhase;   // obSubtype — sub-phase during block drop
    private int columnIndex;    // objoff_34 — current column (X - boss_syz_x) >> 5
    private int holdingFlag;    // objoff_29 — -1 when holding a block
    private int justReturnedFlag; // objoff_3D — set after returning from drop (also used for shake bits)

    // Block reference (objoff_36 in ROM — pointer to grabbed block)
    private Sonic1BossBlockInstance grabbedBlock;

    // Spike child component
    private SYZBossSpike spikeChild;

    public Sonic1SYZBossInstance(ObjectSpawn spawn) {
        super(spawn, "SYZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // ROM: BossSpringYard_Main
        state.x = SPAWN_X;
        state.y = SPAWN_Y;
        state.xFixed = SPAWN_X << 16;
        state.yFixed = SPAWN_Y << 16;
        state.routineSecondary = STATE_APPROACH;
        state.xVel = 0;
        state.yVel = 0;

        sineAngle = 0;
        timer = 0;
        dropSubPhase = 0;
        columnIndex = 0;
        holdingFlag = 0;
        justReturnedFlag = 0;
        grabbedBlock = null;

        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
        flameAnim = Sonic1BossAnimations.ANIM_BLANK;

        // Spawn spike child component
        spawnSpikeChild();
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // ROM: obColProp = 8
    }

    @Override
    protected int getCollisionSizeIndex() {
        return 0x0F; // ROM: obColType = $F
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat logic in states 6-10
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // ROM: the killing hit sets obStatus bit 7; the boss only acts on it when its
        // own routine reaches BSYZ_StatusUpdate, where BSYZ_Defeated does
        //   move.b #6,ob2ndRout(a0)   ; select BSYZ_Explode
        //   move.w #180,BossSpringYard_GenericTimer(a0)
        //   rts
        // (docs/s1disasm/_incObj/75, 76 Boss - SYZ Main and Blocks.asm:154-160).
        // BSYZ_Defeated returns WITHOUT falling through to BSYZ_Explode, so the newly
        // selected secondary routine — and its first defeat-timer decrement
        // (BSYZ_Explode subq.w #1,GenericTimer, asm:434) — is not dispatched until the
        // next frame (BSYZ_ShipMain re-reads ob2ndRout at the top, asm:70-74). The engine
        // selects the defeat routine during the touch-response pass that runs before this
        // object's own update(), so without this one-frame deferral updateDefeatWait()
        // decrements the $B4 timer on the same frame the routine changed. The deferral
        // restores that settle frame, which propagates through ascent (BSYZ_Recover) to
        // BSYZ_Escape so the `addq.w #2,(v_limitright2)` camera scroll starts on the
        // correct frame (SYZ3 trace f12491, not f12490). Same ROM dispatch shape as the
        // GHZ boss (Sonic1GHZBossInstance.defeatDeferralAppliesToThisBoss).
        return true;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
    }

    @Override
    protected void onDefeatStarted() {
        // ROM: loc_19258
        state.routineSecondary = STATE_DEFEAT_WAIT;
        timer = DEFEAT_TIMER; // $B4
        state.xVel = 0;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state.routineSecondary) {
            case STATE_APPROACH -> updateApproach();
            case STATE_PATROL -> updatePatrol(player);
            case STATE_BLOCK_DROP -> updateBlockDrop();
            case STATE_DEFEAT_WAIT -> updateDefeatWait(vIntRunCount);
            case STATE_ASCENT -> updateAscent();
            case STATE_ESCAPE -> updateEscape();
        }

        // Update face/flame animations
        updateFaceAnimation(player);
        updateFlameAnimation();
    }

    // ========================================================================
    // State 0: APPROACH — fly in from right with sine Y oscillation
    // ROM: loc_191CC
    // ========================================================================
    private void updateApproach() {
        state.xVel = APPROACH_X_VEL; // -$100

        if ((state.xFixed >> 16) < APPROACH_STOP_X) {
            state.routineSecondary = STATE_PATROL;
        }

        // ROM: loc_191DE — Sine Y oscillation
        applySineYVelocity();

        // ROM: loc_191F2 — BossMove + copy to display
        bossMove();
        copyFixedToDisplay();

        // ROM: loc_19202 — Column index + hit check
        updateColumnIndex();
    }

    // ========================================================================
    // State 2: PATROL — move left/right, look for block alignment with player
    // ROM: loc_19270
    // ========================================================================
    private void updatePatrol(AbstractPlayableSprite player) {
        int posX = state.xFixed >> 16;

        // ROM: Horizontal movement
        state.xVel = PATROL_X_VEL; // $140
        if ((state.renderFlags & 1) == 0) {
            state.xVel = -state.xVel; // negate if facing left
        }

        // ROM: Boundary flipping
        if ((state.renderFlags & 1) != 0) {
            // Moving right: check right boundary
            if (posX >= PATROL_RIGHT_X) {
                state.renderFlags ^= 1; // toggle direction
                justReturnedFlag = 0;
            }
        } else {
            // Moving left: check left boundary
            if (posX <= PATROL_LEFT_X) {
                state.renderFlags ^= 1;
                justReturnedFlag = 0;
            }
        }

        // ROM: loc_1929E — Column alignment check for block targeting
        int d0 = posX - (BOSS_SYZ_X + 0x10);
        d0 &= 0x1F; // mod 32
        d0 -= 0x1F;
        if (d0 < 0) {
            d0 = -d0; // abs
        }
        d0 -= 1;

        if (d0 <= 0 && justReturnedFlag == 0 && player != null) {
            // Check if player is in the same column
            int playerColumn = (player.getCentreX() - BOSS_SYZ_X) >> 5;
            if (playerColumn == columnIndex) {
                // ROM: Snap X to column center and start block drop
                int snappedX = (columnIndex << 5) + BOSS_SYZ_X + 0x10;
                state.xFixed = snappedX << 16;

                findAndTargetBlock();

                state.routineSecondary = STATE_BLOCK_DROP;
                dropSubPhase = DROP_SUB_DESCEND;
                state.xVel = 0;
            }
        }

        // ROM: loc_191DE — Sine Y oscillation + BossMove
        applySineYVelocity();
        bossMove();
        copyFixedToDisplay();
        updateColumnIndex();
    }

    // ========================================================================
    // State 4: BLOCK_DROP — 4 sub-phases for block grabbing
    // ROM: loc_192EC
    // ========================================================================
    private void updateBlockDrop() {
        switch (dropSubPhase) {
            case DROP_SUB_DESCEND -> updateDropDescend();
            case DROP_SUB_HOLD -> updateDropHold();
            case DROP_SUB_RISE -> updateDropRise();
            case DROP_SUB_SETTLE -> updateDropSettle();
        }
    }

    /**
     * Sub-phase 0: Descend to block level.
     * ROM: loc_19302
     */
    private void updateDropDescend() {
        state.yVel = DROP_Y_VEL; // $180

        int posY = state.yFixed >> 16;
        if (posY >= DROP_TARGET_Y) {
            // Reached bottom — clamp position
            state.yFixed = DROP_TARGET_Y << 16;
            timer = 0;

            // Try to grab the targeted block
            if (grabbedBlock != null && !grabbedBlock.isDestroyed()) {
                grabbedBlock.setGrabbedByBoss(this);
                holdingFlag = -1;
                timer = HOLD_TIMER; // $32
            }

            state.yVel = 0;
            dropSubPhase = DROP_SUB_HOLD;
        }

        // ROM: loc_191F2 — BossMove + display
        bossMove();
        copyFixedToDisplay();
        updateColumnIndex();
    }

    /**
     * Sub-phase 2: Hold block with shake effect.
     * ROM: loc_19348
     */
    private void updateDropHold() {
        timer--;

        int yOffset = 0;

        if (timer < 0) {
            // Timer expired — launch upward
            dropSubPhase = DROP_SUB_RISE;
            state.yVel = (grabbedBlock != null) ? RISE_Y_VEL : RISE_Y_VEL_NO_BLOCK;
        } else {
            // Timer >= 0: shake when timer <= $1E
            // ROM BSYZ_Lift (loc_19366): cmpi.w #30,GenericTimer / bgt (no shake);
            // moveq #2,d0; btst #1,PhaseTimer; beq (+2); neg.w d0 (-2).
            // PhaseTimer (objoff_3D) is the LOW BYTE of the word timer GenericTimer
            // (objoff_3C); the preceding subq.w #1,GenericTimer overwrites it each
            // frame, so the shake direction is bit 1 of the (decrementing) timer,
            // NOT a separate persistent flag.
            // (75 Boss - SYZ Main and Blocks.asm:264-295, with objoff_3C/3D
            // word/low-byte aliasing documented at lines 20-21.)
            if (timer <= 0x1E) {
                yOffset = 2;
                if ((timer & 2) != 0) {
                    yOffset = -yOffset;
                }
            }
        }

        // ROM: loc_1937C — Direct position set (no BossMove)
        state.y = (state.yFixed >> 16) + yOffset;
        state.x = state.xFixed >> 16;
        updateColumnIndex();
    }

    /**
     * Sub-phase 4: Rise back up after grabbing.
     * ROM: loc_1938E
     */
    private void updateDropRise() {
        int targetY = (grabbedBlock != null) ? HOVER_Y_WITH_BLOCK : HOVER_Y;

        int posY = state.yFixed >> 16;
        if (posY <= targetY) {
            // Reached target — stop and start settle phase
            timer = (grabbedBlock != null) ? SETTLE_TIMER_WITH_BLOCK : SETTLE_TIMER_NO_BLOCK;
            dropSubPhase = DROP_SUB_SETTLE;
            state.yVel = 0;
        } else {
            // ROM: loc_193BE — Apply gravity deceleration (cap at terminal velocity)
            if (state.yVel < RISE_TERMINAL_VEL) {
                state.yVel += RISE_GRAVITY; // $C
            }
        }

        // ROM: loc_191F2 — BossMove
        bossMove();
        copyFixedToDisplay();
    }

    /**
     * Sub-phase 6: Settle, release block, return to patrol.
     * ROM: loc_193D0
     */
    private void updateDropSettle() {
        timer--;

        if (timer == 0) {
            // ROM: Timer == 0 — release block
            if (grabbedBlock != null && !grabbedBlock.isDestroyed()) {
                grabbedBlock.releaseAndBreak();
            }
            grabbedBlock = null;
        } else if (timer < 0) {
            // ROM: loc_193EE — Check for return to patrol
            if (timer <= RETURN_DELAY) { // timer <= -$1E
                holdingFlag = 0;
                state.routineSecondary = STATE_PATROL;
                justReturnedFlag = -1; // prevent immediate re-targeting ($FF = all bits set)
                // ROM: bra.s loc_19446 → loc_19202
                updateColumnIndex();
                return;
            }
        }

        // ROM: loc_19406 — Y bob toward center (always applied, speed depends on block)
        int bobSpeed = (grabbedBlock != null) ? 2 : 1;
        int posY = state.yFixed >> 16;
        if (posY < HOVER_Y) {
            // ROM: Above hover — d0 positive → move yFixed down (toward center)
            state.yFixed += (bobSpeed << 16);
        } else if (posY > HOVER_Y) {
            // ROM: Below hover — neg.w d0 → move yFixed up (toward center)
            state.yFixed -= (bobSpeed << 16);
        }

        // ROM loc_19424 — Y display shake when holding block: btst #0,PhaseTimer.
        // Same objoff_3C/3D word/low-byte aliasing as the hold phase above — the
        // BSYZ_BreakBlock subq.w #1,GenericTimer overwrites PhaseTimer each frame,
        // so the shake direction is bit 0 of the (decrementing) timer.
        // (75 Boss - SYZ Main and Blocks.asm:334,378-386.)
        int yShake = 0;
        if (grabbedBlock != null) {
            yShake = 2;
            if ((timer & 1) != 0) {
                yShake = -yShake;
            }
        }

        state.y = (state.yFixed >> 16) + yShake;
        state.x = state.xFixed >> 16;
        updateColumnIndex();
    }

    // ========================================================================
    // State 6: DEFEAT_WAIT — explosions countdown
    // ROM: loc_19474
    // ========================================================================
    private void updateDefeatWait(int vIntRunCount) {
        timer--;
        if (timer < 0) {
            // ROM: loc_1947E — Start ascent
            state.routineSecondary = STATE_ASCENT;
            state.yVel = 0;
            state.renderFlags |= 1;    // face right
            state.renderFlags &= ~0x80;
            state.xVel = 0;
            timer = -1;

            // ROM: BSYZ_Explode .transition (asm:440-453) clears velocities and the
            // defeated status bit but does NOT touch f_lockscreen — the screen lock
            // stays set through the ascent/escape and the run to the egg capsule, and
            // is cleared only by the Egg Prison (Obj3E: clr.b (f_lockscreen).w). The
            // engine models f_lockscreen via currentBossId, which the Egg Prison clears
            // (Sonic1EggPrisonObjectInstance). Clearing it here dropped the strict
            // right-boundary (RIGHT_EXTRA +0x40) one phase early, letting the player run
            // past v_limitright2+0x128 to the egg capsule (SYZ3 trace f12767: ROM stops
            // x_speed at the boundary x=0x2E68, engine kept running).
        } else {
            // ROM: BossDefeated — spawn explosions every 8 frames
            if ((vIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
        }

        updateColumnIndex();
    }

    // ========================================================================
    // State 8: ASCENT — multi-stage upward movement
    // ROM: loc_194AC → all paths branch to loc_194EE → loc_191F2 (BossMove + copy, NO sine)
    // ========================================================================
    private void updateAscent() {
        timer++;

        if (timer == 0) {
            // ROM: loc_194BC — Clear velocity at timer 0
            state.yVel = 0;
        } else if (timer < 0) {
            // ROM: Timer negative — downward acceleration (brief dip)
            state.yVel += ASCENT_ACCEL; // $18
        } else if (timer < 0x20) {
            // ROM: loc_194DA — Decelerate (rise)
            state.yVel -= ASCENT_DECEL; // 8
        } else if (timer == 0x20) {
            // ROM: loc_194E0 — Stop and play zone music
            state.yVel = 0;
            services().playMusic(Sonic1Music.SYZ.id);
        } else if (timer >= 0x2A) {
            // ROM: Advance to escape
            state.routineSecondary = STATE_ESCAPE;
        }
        // else timer $21-$29: just BossMove

        // ROM: loc_194EE → loc_191F2 (single BossMove + copy, no sine)
        bossMove();
        copyFixedToDisplay();
    }

    // ========================================================================
    // State 10: ESCAPE — fly off right
    // ROM: loc_194F2 → loc_19512: BossMove, then bra loc_191DE (sine → BossMove → copy)
    // Two BossMoves per frame: first with escape vel, second with sine yVel.
    // ========================================================================
    private void updateEscape() {
        state.xVel = ESCAPE_X_VEL;   // $400
        state.yVel = ESCAPE_Y_VEL;   // -$40

        if (runCameraExpandEscape(BOSS_SYZ_END)) {
            return; // Destroyed (off-screen)
        }

        // ROM: loc_19512 — first BossMove with escape velocities
        bossMove();
        // ROM: loc_191DE — sine overwrites yVel, then second BossMove + copy
        applySineYVelocity();
        bossMove();
        copyFixedToDisplay();
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Apply sine oscillation to Y velocity.
     * ROM: loc_191DE — CalcSine(angle) >> 2 → obVelY; angle += 2
     */
    private void applySineYVelocity() {
        int sinVal = TrigLookupTable.sinHex(sineAngle & 0xFF);
        sineAngle = (sineAngle + 2) & 0xFF;
        state.yVel = sinVal >> 2;
    }

    /**
     * Copy fixed-point positions to display coordinates.
     * ROM: loc_191F2 end — objoff_38 → obY, objoff_30 → obX
     */
    private void copyFixedToDisplay() {
        state.y = state.yFixed >> 16;
        state.x = state.xFixed >> 16;
    }

    /**
     * Update column index from current X position.
     * ROM: loc_19202 — (obX - boss_syz_x) >> 5
     */
    private void updateColumnIndex() {
        int d0 = state.x - BOSS_SYZ_X;
        columnIndex = d0 >> 5;
    }

    /**
     * Find a block in the current column to target.
     * ROM: BossSpringYard_FindBlocks
     */
    private void findAndTargetBlock() {
        grabbedBlock = null;
        if (services().objectManager() == null) {
            return;
        }

        // ROM: Linear search through object RAM for matching block
        for (ObjectInstance obj : services().objectManager().getActiveObjects()) {
            if (obj instanceof Sonic1BossBlockInstance block) {
                if (!block.isDestroyed() && !block.isGrabbed()
                        && block.getBlockColumn() == columnIndex) {
                    grabbedBlock = block;
                    return;
                }
            }
        }
    }

    /**
     * Spawn the spike child component.
     */
    private void spawnSpikeChild() {
        if (services().objectManager() != null) {
            spikeChild = spawnFreeChild(() -> new SYZBossSpike(this));
        } else {
            spikeChild = new SYZBossSpike(this);
        }
        childComponents.add(spikeChild);
    }

    /**
     * ROM: {@code BossSpringYard_SpikeMain} (routine 8) is a fully independent SST object
     * that reads {@code ob2ndRout}/{@code obSubtype}/{@code BossSpringYard_GenericTimer} off
     * the boss every frame via its own dispatch — it is never driven by the boss's own
     * routine handler. {@link SYZBossSpike#update} now mirrors that by pulling this state
     * directly (see the accessors below) instead of the boss pushing it in from
     * {@code updateBossLogic()}, which is skipped for one frame during the post-hit defeat
     * dispatch deferral ({@link #defeatDeferralAppliesToThisBoss()}). Pushing from there left
     * the spike's collision/extension frozen at their pre-defeat values for that one frame —
     * a real, if narrow, divergence from ROM's independently-dispatched spike. Pulling from
     * the spike's own {@code update()} (called unconditionally every frame via
     * {@code AbstractBossInstance.updateChildren()}) removes that dependency entirely.
     */
    int getDropSubPhase() {
        return dropSubPhase;
    }

    int getHoldingFlag() {
        return holdingFlag;
    }

    int getGenericTimer() {
        return timer;
    }

    /**
     * Update face animation based on boss state.
     * ROM: BossSpringYard_FaceMain (routine 4)
     */
    private void updateFaceAnimation(AbstractPlayableSprite player) {
        // ROM: off_19546 dispatch table
        switch (state.routineSecondary) {
            case STATE_APPROACH, STATE_PATROL -> {
                // ROM: loc_19574 — normal face, check for hit/laugh
                faceAnim = resolveNormalFace(player);
            }
            case STATE_BLOCK_DROP -> {
                // ROM: loc_1955A — depends on drop sub-phase
                if (dropSubPhase == DROP_SUB_HOLD) {
                    // During hold: panic face
                    faceAnim = Sonic1BossAnimations.ANIM_FACE_PANIC;
                } else {
                    faceAnim = resolveNormalFace(player);
                }
            }
            case STATE_DEFEAT_WAIT, STATE_ASCENT -> {
                // ROM: loc_19552 — defeat face ($A)
                faceAnim = Sonic1BossAnimations.ANIM_FACE_DEFEAT;
            }
            case STATE_ESCAPE -> {
                // ROM: loc_19556 — panic face (6)
                faceAnim = Sonic1BossAnimations.ANIM_FACE_PANIC;
            }
        }
    }

    /**
     * Resolve face animation for normal states.
     * ROM: loc_19574 — check invulnerability and player state.
     */
    private int resolveNormalFace(AbstractPlayableSprite player) {
        // ROM: tst.b obColType(a1) / bne.s — if invulnerable, show hit face
        if (state.invulnerable) {
            return Sonic1BossAnimations.ANIM_FACE_HIT;
        }
        // ROM: cmpi.b #4,(v_player+obRoutine).w — if player hurt, laugh
        if (player != null && player.isHurt()) {
            return Sonic1BossAnimations.ANIM_FACE_LAUGH;
        }
        return Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
    }

    /**
     * Update flame animation based on movement.
     * ROM: BossSpringYard_FlameMain (routine 6)
     */
    private void updateFlameAnimation() {
        if (state.routineSecondary == STATE_ESCAPE) {
            // ROM: cmpi.b #$A,ob2ndRout → heavy escape flame
            flameAnim = Sonic1BossAnimations.ANIM_ESCAPE_FLAME;
        } else if (state.xVel != 0) {
            // ROM: tst.w obVelX → active flame
            flameAnim = Sonic1BossAnimations.ANIM_FLAME_1;
        } else {
            flameAnim = Sonic1BossAnimations.ANIM_BLANK;
        }
    }

    @Override
    public int getPriorityBucket() {
        return 5; // ROM: obPriority = 5 (BossSpringYard_ObjData)
    }

    @Override
    public int getCollisionFlags() {
        if (state.routineSecondary >= STATE_DEFEAT_WAIT) {
            return 0;
        }
        return super.getCollisionFlags();
    }
}

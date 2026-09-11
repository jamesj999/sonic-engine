package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.objects.Sonic1LavaBallObjectInstance;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;

import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x73 — Marble Zone Boss (Eggman dropping lava balls).
 * ROM: docs/s1disasm/_incObj/73 Boss - Marble.asm
 *
 * State machine (ob2ndRout):
 *   0: DESCENT      — Enter from right, sine velocity, stop at arena center X
 *   2: COMBAT       — obSubtype dispatches: 0,4=movement; 2,6=return-to-center
 *   4: DEFEAT_WAIT  — Timer 0xB4 countdown with explosions
 *   6: ASCENT       — Bounce down then rise, play MZ music, advance
 *   8: ESCAPE       — Fly off right, extend boundary, delete when off-screen
 *
 * Key difference from GHZ boss: NO sine display offset.
 * MZ boss uses loc_1833E (direct fixed-point → display copy).
 * Sine is only applied as VELOCITY during descent via CalcSine >> 2.
 */
public class Sonic1MZBossInstance extends AbstractS1EggmanBossInstance
        implements SpawnConstructionContextRewindRecreatable {

    // State machine constants (routineSecondary values)
    private static final int STATE_DESCENT = 0;
    private static final int STATE_COMBAT = 2;
    private static final int STATE_DEFEAT_WAIT = 4;
    private static final int STATE_ASCENT = 6;
    private static final int STATE_ESCAPE = 8;

    // Position constants from DynamicLevelEvents.asm
    private static final int BOSS_MZ_X = 0x1800;
    private static final int BOSS_MZ_Y = 0x210;
    private static final int BOSS_MZ_END = 0x1960;

    // Arena positions
    private static final int ARENA_CENTER_X = BOSS_MZ_X + 0x110;  // $1910
    private static final int ARENA_CENTER_Y = BOSS_MZ_Y + 0x1C;   // $22C
    private static final int LAVA_LEVEL_Y = BOSS_MZ_Y + 0xD8;     // $2E8
    private static final int ASCENT_LIMIT_Y = BOSS_MZ_Y + 0x60;   // $270

    // Arena horizontal boundaries
    private static final int ARENA_LEFT_X = BOSS_MZ_X + 0x30;     // $1830
    private static final int ARENA_RIGHT_X = BOSS_MZ_X + 0x110;   // $1910

    // Lava drop X range: boss_mz_x + $78, span $50
    private static final int LAVA_DROP_BASE_X = BOSS_MZ_X + 0x78;
    private static final int LAVA_DROP_SPAN = 0x50;

    // Timers
    private static final int DEFEAT_TIMER = 0xB4; // 180 frames
    private static final int INVULNERABILITY_DURATION_MZ = 0x28; // 40 frames
    private static final int RETURN_WAIT_TIMER = 0x50; // 80 frames

    // ROM: objoff_3F — sine angle for descent oscillation
    private int sineAngle;

    // ROM: objoff_3C — timer (defeat countdown, ascent counter, return wait)
    private int timer;

    // ROM: objoff_34 — lava drop countdown timer
    private int lavaDropTimer;

    // ROM: obSubtype — combat sub-state (0,2,4,6)
    // 0,4 = movement (loc_183CA); 2,6 = return-to-center (BossMarble_MakeLava2)
    private int combatSubtype;

    public Sonic1MZBossInstance(ObjectSpawn spawn) {
        super(spawn, "MZ Boss");
    }

    @Override
    protected void initializeBossState() {
        state.routineSecondary = STATE_DESCENT;
        state.xVel = 0;
        state.yVel = 0;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        sineAngle = 0;
        timer = 0;
        // ROM Obj73 init draws no RandomNumber; BossMarble_ParentObj stays 0
        // until the descent's per-frame store (docs/s1disasm/_incObj/73, 74
        // Boss - MZ Main and Fire.asm:107-109) overwrites it on frame one.
        lavaDropTimer = 0;
        combatSubtype = 0;
        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
        flameAnim = Sonic1BossAnimations.ANIM_BLANK;
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // obColProp = 8
    }

    @Override
    protected int getCollisionSizeIndex() {
        return 0x0F; // ROM: obColType = $F
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULNERABILITY_DURATION_MZ;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        // ROM: the killing hit sets obStatus bit 7; the boss only acts on it when its
        // own routine reaches BMZ_ShipUpdate, where BMZ_Defeated does
        //   move.b #4,ob2ndRout(a0)   ; select BMZ_Explode
        //   move.w #180,BossMarble_GenericTimer(a0)
        //   clr.w obVelX(a0)
        //   rts
        // (docs/s1disasm/_incObj/73, 74 Boss - MZ Main and Fire.asm:146-153, loc_18392).
        // BMZ_Defeated returns WITHOUT falling through to BMZ_Explode, so the newly
        // selected secondary routine — and its first defeat-timer decrement
        // (BMZ_Explode subq.w #1,GenericTimer at loc_184F6) — is not dispatched until the
        // next frame (BMZ_ShipMain re-reads ob2ndRout at the top via BMZ_ShipMove_Index).
        // The engine selects the defeat routine during the touch-response pass that runs
        // before this object's own update(), so without this one-frame deferral
        // updateDefeatWait() decrements the $B4 timer on the same frame the routine
        // changed. The deferral restores that settle frame, which propagates through the
        // recover fall (BMZ_Recover) to BMZ_Escape so the `addq.w #2,(v_limitright2)`
        // camera scroll starts on the correct frame (MZ3 trace f16869, not f16868). Same
        // ROM dispatch shape as the GHZ and SYZ bosses
        // (Sonic1GHZBossInstance / Sonic1SYZBossInstance.defeatDeferralAppliesToThisBoss).
        return true;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
    }

    @Override
    protected void onDefeatStarted() {
        // ROM: loc_18392
        state.routineSecondary = STATE_DEFEAT_WAIT;
        timer = DEFEAT_TIMER;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state.routineSecondary) {
            case STATE_DESCENT -> updateDescent();
            case STATE_COMBAT -> updateCombat();
            case STATE_DEFEAT_WAIT -> updateDefeatWait(vIntRunCount);
            case STATE_ASCENT -> updateAscent();
            case STATE_ESCAPE -> updateEscape();
        }

        // loc_1833E: copy fixed-point positions to display (NO sine offset)
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // Update face and flame animations
        updateFaceAnimation(player);
        updateFlameAnimation();
    }

    // === State 0: DESCENT ===
    // ROM: loc_18302
    private void updateDescent() {
        // Sine applied as VELOCITY, not display offset
        // ROM: CalcSine(objoff_3F) >> 2 → obVelY
        int sinVal = TrigLookupTable.sinHex(sineAngle & 0xFF);
        sineAngle = (sineAngle + 2) & 0xFF;
        state.yVel = sinVal >> 2;
        state.xVel = -0x100;
        bossMove();

        // ROM: cmpi.w #boss_mz_x+$110,objoff_30(a0) / bne.s loc_18334
        if ((state.xFixed >> 16) == ARENA_CENTER_X) {
            state.routineSecondary = STATE_COMBAT;
            combatSubtype = 0;
            // ROM: clr.l obVelX(a0) — clears both xVel and yVel
            state.xVel = 0;
            state.yVel = 0;
        }

        // ROM: loc_18334 — every ShipStart frame draws RandomNumber and stores
        // the RAW low byte as the lava countdown (move.b d0,BossMarble_ParentObj,
        // docs/s1disasm/_incObj/73, 74 Boss - MZ Main and Fire.asm:107-109) —
        // range 0-255, unlike the $40-$5F reroll used after each lava spawn
        // (.generateTimer, :227-231). The last descent frame's byte is the
        // countdown the combat phase starts with.
        lavaDropTimer = services().rng().nextByte();
    }

    // === State 2: COMBAT ===
    // ROM: loc_183AA — dispatch via obSubtype jump table
    private void updateCombat() {
        // ROM: off_183C2 — 0,4 = movement; 2,6 = return-to-center
        switch (combatSubtype & 6) {
            case 0, 4 -> updateCombatMovement();
            case 2, 6 -> updateCombatReturn();
        }
        // ROM: andi.b #6,obSubtype(a0)
        combatSubtype &= 6;
    }

    /**
     * loc_183CA: Movement sub-phase.
     * If xVel != 0: horizontal movement with Y deceleration + lava spawning.
     * If xVel == 0: vertical toward center Y, then start horizontal.
     */
    private void updateCombatMovement() {
        if (state.xVel != 0) {
            // loc_183FE: Horizontal movement
            updateHorizontalMovement();
            return;
        }

        // Vertical movement toward center Y
        int currentY = state.yFixed >> 16;
        if (currentY == ARENA_CENTER_Y) {
            // loc_183E6: At center — start horizontal
            state.xVel = 0x200;
            state.yVel = 0x100;
            // ROM: btst #0,obStatus / bne.s — negate if facing left
            if ((state.renderFlags & 1) == 0) {
                state.xVel = -state.xVel;
            }
            // Fall through to horizontal movement
            updateHorizontalMovement();
        } else {
            // Move toward center: +$40 if below, -$40 if above
            // ROM: bcs (unsigned less than) = Y < center → d0 stays +$40
            int vel = 0x40;
            if (currentY > ARENA_CENTER_Y) {
                vel = -vel;
            }
            state.yVel = vel;
            bossMove();
        }
    }

    /**
     * loc_183FE: Horizontal movement with Y deceleration and lava spawning.
     * ROM: Skip BossMove when invulnerability timer >= $18.
     */
    private void updateHorizontalMovement() {
        // ROM: cmpi.b #$18,objoff_3E(a0) / bhs.s BossMarble_MakeLava
        if (state.invulnerabilityTimer < 0x18) {
            bossMove();
            // ROM: subq.w #4,obVelY(a0) — Y deceleration per frame
            state.yVel -= 4;
        }

        // BossMarble_MakeLava: lava drop timer
        lavaDropTimer--;
        if (lavaDropTimer < 0) {
            spawnLavaBall();
            lavaDropTimer = randomLavaDelay();
        }

        // loc_1845C: Boundary check
        checkHorizontalBounds();
    }

    /**
     * loc_1845C: Check arena horizontal boundaries.
     * ROM: Clamps position, reverses with yVel toward center, advances subtype.
     */
    private void checkHorizontalBounds() {
        int currentX = state.xFixed >> 16;

        if ((state.renderFlags & 1) != 0) {
            // Facing right: check right boundary
            if (currentX < ARENA_RIGHT_X) {
                return;
            }
            state.xFixed = ARENA_RIGHT_X << 16;
        } else {
            // Facing left: check left boundary
            if (currentX > ARENA_LEFT_X) {
                return;
            }
            state.xFixed = ARENA_LEFT_X << 16;
        }

        // loc_18482: Reversal — stop horizontal, set vertical toward center
        state.xVel = 0;
        state.yVel = -0x180;
        // ROM: cmpi.w #boss_mz_y+$1C,objoff_38 / bhs → if Y >= center, keep -$180 (up)
        if ((state.yFixed >> 16) < ARENA_CENTER_Y) {
            state.yVel = 0x180; // Below center in screen coords? No — Y < center means ABOVE
        }
        // ROM: addq.b #2,obSubtype
        combatSubtype += 2;
    }

    /**
     * BossMarble_MakeLava2: Return-to-center sub-phase.
     * Boss moves vertically back to center Y, spawns BossFire, waits, then advances.
     */
    private void updateCombatReturn() {
        bossMove();

        // ROM: move.w objoff_38(a0),d0 / subi.w #boss_mz_y+$1C,d0 / bgt.s locret
        int diff = (state.yFixed >> 16) - ARENA_CENTER_Y;
        if (diff > 0) {
            return; // Still below center, keep moving
        }

        // At or above center
        if (state.yVel != 0) {
            // First time reaching center: stop, set timer, toggle facing, spawn fire
            state.yVel = 0;
            timer = RETURN_WAIT_TIMER; // $50 = 80 frames

            // ROM: bchg #0,obStatus — toggle facing direction
            state.renderFlags ^= 1;

            // Spawn BossFire (0x74) at boss position + $18 Y, subtype 1
            spawnBossFireAtPosition();
        }

        // Timer countdown
        timer--;
        if (timer == 0) {
            // ROM: addq.b #2,obSubtype — advance to next movement phase
            combatSubtype += 2;
        }
    }

    /**
     * Spawn a lava ball at random X position in the arena.
     * ROM: BossMarble_MakeLava — spawns id_LavaBall with obSubtype word $00FF.
     */
    private void spawnLavaBall() {
        if (services().objectManager() == null) {
            return;
        }
        // ROM: Random X in range [boss_mz_x+$78, boss_mz_x+$78+$4F]
        int randomX = services().rng().nextWord() % LAVA_DROP_SPAN;
        int lavaX = LAVA_DROP_BASE_X + randomX;

        ObjectSpawn fireSpawn = new ObjectSpawn(
                lavaX, LAVA_LEVEL_Y,
                Sonic1ObjectIds.LAVA_BALL, 0xFF, 0, false, 0);
        spawnFreeChild(() -> new Sonic1LavaBallObjectInstance(fireSpawn));
    }

    /**
     * Spawn BossFire at boss position during return-to-center phase.
     * ROM: BossMarble_MakeLava2 — spawns id_BossFire with subtype 1 at boss pos + $18 Y.
     */
    private void spawnBossFireAtPosition() {
        if (services().objectManager() == null) {
            return;
        }
        int fireX = state.xFixed >> 16;
        int fireY = (state.yFixed >> 16) + 0x18;

        ObjectSpawn fireSpawn = new ObjectSpawn(
                fireX, fireY,
                Sonic1ObjectIds.BOSS_FIRE, 1, 0, false, 0);
        spawnFreeChild(() -> new Sonic1BossFireInstance(fireSpawn));
    }

    // === State 4: DEFEAT_WAIT ===
    // ROM: loc_184F6
    private void updateDefeatWait(int vIntRunCount) {
        timer--;
        if (timer < 0) {
            // loc_18500: Timer expired — start ascent
            state.renderFlags |= 1;    // bset #0,obStatus — face right
            state.renderFlags &= ~0x80; // bclr #7,obStatus
            state.xVel = 0;
            state.routineSecondary = STATE_ASCENT;
            timer = -0x26; // Start ascent counter at -$26
            // ROM: v_bossstatus = 1
            services().gameState().setCurrentBossId(0);
            state.yVel = 0;
        } else {
            // BossDefeated: Spawn explosions every 8 frames
            if ((vIntRunCount & 7) == 0) {
                spawnDefeatExplosion();
            }
        }
    }

    // === State 6: ASCENT ===
    // ROM: loc_1852C — multi-stage upward movement
    private void updateAscent() {
        timer++;

        if (timer == 0) {
            // loc_18544: Timer reached 0 — clear velocity and timer
            state.yVel = 0;
            timer = 0;
        } else if (timer < 0) {
            // Timer negative: downward bounce with Y limit check
            // ROM: cmpi.w #boss_mz_y+$60,objoff_38 / bhs.s loc_18544
            if ((state.yFixed >> 16) >= ASCENT_LIMIT_Y) {
                // Y >= $270: stop bouncing, clear velocity and timer
                state.yVel = 0;
                timer = 0;
            } else {
                // Y < $270: continue downward acceleration
                state.yVel += 0x18;
            }
        } else if (timer < 0x30) {
            // loc_18566: Timer 1-$2F — decelerate (yVel -= 8 per frame)
            state.yVel -= 8;
        } else if (timer == 0x30) {
            // loc_1856C: Timer = $30 — stop, play zone music
            state.yVel = 0;
            services().playMusic(Sonic1Music.MZ.id);
        } else if (timer >= 0x38) {
            // Timer >= $38 — advance to escape
            state.routineSecondary = STATE_ESCAPE;
        }
        // else: timer $31-$37 — just BossMove

        // loc_1857A: BossMove
        bossMove();
    }

    // === State 8: ESCAPE ===
    // ROM: loc_18582
    private void updateEscape() {
        state.xVel = 0x500;
        state.yVel = -0x40;

        if (runCameraExpandEscape(BOSS_MZ_END)) {
            return; // Destroyed (off-screen)
        }

        bossMove();
    }

    private void updateFaceAnimation(AbstractPlayableSprite player) {
        if (state.routineSecondary >= STATE_DEFEAT_WAIT) {
            if (state.routineSecondary >= STATE_ASCENT) {
                faceAnim = Sonic1BossAnimations.ANIM_FACE_PANIC;
            } else {
                faceAnim = Sonic1BossAnimations.ANIM_FACE_DEFEAT;
            }
            return;
        }
        if (state.invulnerable) {
            faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
            return;
        }
        if (player != null && player.isHurt()) {
            faceAnim = Sonic1BossAnimations.ANIM_FACE_LAUGH;
            return;
        }
        // ROM: Laugh during return-to-center when yVel == 0 (waiting)
        if (state.routineSecondary == STATE_COMBAT
                && (combatSubtype & 2) != 0 && state.yVel == 0) {
            faceAnim = Sonic1BossAnimations.ANIM_FACE_LAUGH;
            return;
        }
        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
    }

    private void updateFlameAnimation() {
        if (state.routineSecondary == STATE_ESCAPE) {
            flameAnim = Sonic1BossAnimations.ANIM_ESCAPE_FLAME;
        } else if (state.xVel != 0) {
            flameAnim = Sonic1BossAnimations.ANIM_FLAME_1;
        } else {
            flameAnim = Sonic1BossAnimations.ANIM_BLANK;
        }
    }

    /**
     * ROM: andi.b #$1F,d0 / addi.b #$40,d0 → random delay 64-95 frames.
     */
    private int randomLavaDelay() {
        return 0x40 + services().rng().nextBits(0x1F);
    }

    @Override
    public int getPriorityBucket() {
        return 3;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        renderEggmanShip();

        // Exhaust tube (Boss Items frame 4) — MZ-specific overlay
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        boolean flipped = (state.renderFlags & 1) != 0;
        PatternSpriteRenderer weaponsRenderer = renderManager.getRenderer(ObjectArtKeys.BOSS_WEAPONS);
        if (weaponsRenderer != null && weaponsRenderer.isReady()) {
            weaponsRenderer.drawFrameIndex(4, state.x, state.y, flipped, false);
        }
    }

    @Override
    public int getCollisionFlags() {
        if (state.routineSecondary >= STATE_DEFEAT_WAIT) {
            return 0;
        }
        return super.getCollisionFlags();
    }
}

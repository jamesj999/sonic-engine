package com.openggf.game.sonic1.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Music;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Object 0x77 — Labyrinth Zone Boss (Eggman rising water chase).
 * ROM: _incObj/77 Boss - Labyrinth.asm
 *
 * Unlike other S1 bosses, the LZ boss is a chase sequence:
 * Eggman starts below and escapes upward through rising water
 * while Sonic pursues. The boss has 8 sub-states controlling
 * a waypoint-based escape path with a sine-wave oscillation
 * chase phase (state 6).
 *
 * Sub-objects (face/flame) are rendered as overlays, matching
 * the GHZ/MZ/SYZ boss pattern.
 *
 * State machine (ob2ndRout / routineSecondary):
 *   0: ENTRY        — Wait for player X >= boss_lz_x - $40, then start escaping
 *   2: WAYPOINT_1   — Move to (boss_lz_x+$68, boss_lz_y+$440)
 *   4: WAYPOINT_2   — Move to (boss_lz_x+$90, boss_lz_y+$400)
 *   6: SINE_CHASE   — Sine-wave X oscillation + player Y tracking
 *   8: WAYPOINT_3   — Move to (boss_lz_x+$16C, boss_lz_y)
 *  10: MUSIC_TRIGGER — When player reaches top-right, play LZ music
 *  12: COOLDOWN      — Timer countdown, then high-speed escape
 *  14: CAMERA_EXPAND — Expand right camera boundary, delete when off-screen
 */
public class Sonic1LZBossInstance extends AbstractS1EggmanBossInstance implements SpawnRewindRecreatable {

    // State constants (routineSecondary, incremented by 2 matching ROM)
    private static final int STATE_ENTRY = 0;
    private static final int STATE_WAYPOINT_1 = 2;
    private static final int STATE_WAYPOINT_2 = 4;
    private static final int STATE_SINE_CHASE = 6;
    private static final int STATE_WAYPOINT_3 = 8;
    private static final int STATE_MUSIC_TRIGGER = 10;
    private static final int STATE_COOLDOWN = 12;
    private static final int STATE_CAMERA_EXPAND = 14;

    // Arena position constants (from Constants.asm)
    // boss_lz_x = $1DE0, boss_lz_y = $C0, boss_lz_end = boss_lz_x + $250 = $2030
    private static final int BOSS_LZ_X = 0x1DE0;
    private static final int BOSS_LZ_Y = 0xC0;
    private static final int BOSS_LZ_END = BOSS_LZ_X + 0x250; // $2030

    // State 0: Entry trigger
    // ROM: cmpi.w #boss_lz_x-$40,d0 — checks player X
    private static final int ENTRY_TRIGGER_X = BOSS_LZ_X - 0x40; // $1DA0

    // State 2: First waypoint targets
    private static final int WP1_TARGET_X = BOSS_LZ_X + 0x68;  // $1E48
    private static final int WP1_TARGET_Y = BOSS_LZ_Y + 0x440; // $500

    // State 4: Second waypoint targets
    private static final int WP2_TARGET_X = BOSS_LZ_X + 0x90;  // $1E70
    private static final int WP2_TARGET_Y = BOSS_LZ_Y + 0x400; // $4C0

    // State 6: Sine chase Y limit
    private static final int SINE_CHASE_Y_LIMIT = BOSS_LZ_Y + 0x40; // $100

    // State 8: Third waypoint targets
    private static final int WP3_TARGET_X = BOSS_LZ_X + 0x16C; // $1F4C
    private static final int WP3_TARGET_Y = BOSS_LZ_Y;          // $C0

    // State 10: Player position check for music trigger
    private static final int MUSIC_TRIGGER_PLAYER_X = BOSS_LZ_X + 0xE8; // $1EC8
    private static final int MUSIC_TRIGGER_PLAYER_Y = BOSS_LZ_Y + 0x30; // $F0

    // Cooldown timer value
    private static final int COOLDOWN_TIMER = 0x32; // 50 frames

    // Sine counter for state 6
    private int sineCounter; // objoff_3F

    // Timer for cooldown state
    private int timer; // objoff_3C

    // Defeated flag — separate from base class 'defeated' for ROM accuracy
    // ROM: objoff_3D — set to -1 ($FF) when boss is hit to 0 HP
    private boolean bossDefeatedFlag; // objoff_3D

    // Hit flash timer
    // ROM: objoff_3E — counts down from $20 during flash, re-enables collision at 0
    private int hitFlashTimer; // objoff_3E

    public Sonic1LZBossInstance(ObjectSpawn spawn) {
        super(spawn, "LZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // ROM: BossLabyrinth_Main (routine 0)
        // Initial position: boss_lz_x + $30, boss_lz_y + $500
        state.x = BOSS_LZ_X + 0x30;
        state.y = BOSS_LZ_Y + 0x500;

        // ROM stores positions as words in objoff_30/objoff_38
        // In our engine, fixed-point is 16.16 format
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        state.xVel = 0;
        state.yVel = 0;
        state.routineSecondary = STATE_ENTRY;

        sineCounter = 0;
        timer = 0;
        bossDefeatedFlag = false;
        hitFlashTimer = 0;

        faceAnim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;
        flameAnim = Sonic1BossAnimations.ANIM_BLANK;
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // ROM: obColProp = 8
    }

    @Override
    protected int getCollisionSizeIndex() {
        // ROM: obColType = $F (size index 0x0F = 24x24)
        return 0x0F;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        // LZ boss has fully custom defeat logic — explosions spawn while
        // the boss continues its state machine movement
        return false;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        faceAnim = Sonic1BossAnimations.ANIM_FACE_HIT;
    }

    @Override
    protected void onDefeatStarted() {
        // ROM: loc_17F92 — award 100 points (= 1000 in display), set objoff_3D = -1
        bossDefeatedFlag = true;

        // ROM: v_bossstatus = 1 — clear boss fight active flag
        // (matches GHZ/MZ/SYZ pattern; without this, doLevelBoundary restricts
        // Sonic's rightward movement and he can't run off-screen at act end)
        services().gameState().setCurrentBossId(0);
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state.routineSecondary) {
            case STATE_ENTRY -> updateEntry(player);
            case STATE_WAYPOINT_1 -> updateWaypoint1();
            case STATE_WAYPOINT_2 -> updateWaypoint2();
            case STATE_SINE_CHASE -> updateSineChase(player);
            case STATE_WAYPOINT_3 -> updateWaypoint3();
            case STATE_MUSIC_TRIGGER -> updateMusicTrigger(player);
            case STATE_COOLDOWN -> updateCooldown();
            case STATE_CAMERA_EXPAND -> updateCameraExpand();
        }

        // Update face animation based on state
        updateFaceAnimation(player);

        // Update flame animation based on movement
        updateFlameAnimation();
    }

    // ========================================================================
    // State 0: ENTRY — Wait for player to approach, then start escaping
    // ROM: loc_17F1E
    // ========================================================================
    private void updateEntry(AbstractPlayableSprite player) {
        if (player != null) {
            int playerX = player.getCentreX() & 0xFFFF;
            // ROM: cmpi.w #boss_lz_x-$40,d0
            if (playerX >= ENTRY_TRIGGER_X) {
                state.yVel = -0x180;
                state.xVel = 0x60;
                state.routineSecondary = STATE_WAYPOINT_1;
            }
        }

        // ROM: loc_17F38 — apply BossMove and update display position
        bossMove();
        updateDisplayPosition();

        // ROM: loc_17F48 — collision/hit check
        checkCollision();
    }

    // ========================================================================
    // State 2: WAYPOINT_1 — Move to first waypoint
    // ROM: loc_17FA0
    // When both X >= target and Y <= target, advance to state 4
    // ========================================================================
    private void updateWaypoint1() {
        // ROM uses d0 as a -2 counter, increments when each axis reaches target
        // When d0 reaches 0 (both targets hit), transitions
        int d0 = -2;

        // ROM: cmpi.w #boss_lz_x+$68,objoff_30(a0) / blo.s loc_17FB6
        if ((state.xFixed >> 16) >= WP1_TARGET_X) {
            state.xFixed = WP1_TARGET_X << 16;
            state.xVel = 0;
            d0++;
        }

        // ROM: cmpi.w #boss_lz_y+$440,objoff_38(a0) / bgt.s loc_17FCA
        if ((state.yFixed >> 16) <= WP1_TARGET_Y) {
            state.yFixed = WP1_TARGET_Y << 16;
            state.yVel = 0;
            d0++;
        }

        // ROM: bne.s loc_17FDC — if d0 != 0, both targets not reached
        if (d0 == 0) {
            state.xVel = 0x140;
            state.yVel = -0x200;
            state.routineSecondary = STATE_WAYPOINT_2;
        }

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // State 4: WAYPOINT_2 — Move to second waypoint
    // ROM: loc_17FE0
    // ========================================================================
    private void updateWaypoint2() {
        int d0 = -2;

        // ROM: cmpi.w #boss_lz_x+$90,objoff_30(a0)
        if ((state.xFixed >> 16) >= WP2_TARGET_X) {
            state.xFixed = WP2_TARGET_X << 16;
            state.xVel = 0;
            d0++;
        }

        // ROM: cmpi.w #boss_lz_y+$400,objoff_38(a0)
        if ((state.yFixed >> 16) <= WP2_TARGET_Y) {
            state.yFixed = WP2_TARGET_Y << 16;
            state.yVel = 0;
            d0++;
        }

        if (d0 == 0) {
            state.yVel = -0x180;
            state.routineSecondary = STATE_SINE_CHASE;
            sineCounter = 0; // ROM: clr.b objoff_3F(a0)
        }

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // State 6: SINE_CHASE — Sine-wave X oscillation with player Y tracking
    // ROM: loc_1801E (entry check) + loc_1804E (oscillation)
    //
    // This is the core chase mechanic: the boss oscillates horizontally
    // with a sine wave while vertically tracking the player's Y position.
    // ========================================================================
    private void updateSineChase(AbstractPlayableSprite player) {
        // ROM: loc_1801E — check if Y has reached the limit
        if ((state.yFixed >> 16) <= SINE_CHASE_Y_LIMIT) {
            // Clamp Y and transition to waypoint 3
            state.yFixed = SINE_CHASE_Y_LIMIT << 16;
            state.xVel = 0x140;
            state.yVel = -0x80;

            // ROM: tst.b objoff_3D(a0) / beq.s loc_18046
            // If defeated, double both velocities
            if (bossDefeatedFlag) {
                state.xVel <<= 1; // asl obVelX(a0)
                state.yVel <<= 1; // asl obVelY(a0)
            }

            state.routineSecondary = STATE_WAYPOINT_3;
            bossMove();
            updateDisplayPosition();
            checkCollision();
            return;
        }

        // ROM: loc_1804E — sine-wave oscillation
        // bset #0,obStatus(a0) — default facing right
        state.renderFlags |= 1;

        // ROM: addq.b #2,objoff_3F(a0) — increment sine counter
        sineCounter = (sineCounter + 2) & 0xFF;

        // ROM: CalcSine(objoff_3F) — returns sine in d0, cosine in d1
        int sinVal = TrigLookupTable.sinHex(sineCounter);
        int cosVal = TrigLookupTable.cosHex(sineCounter);

        // ROM: tst.w d1 / bpl.s loc_1806C — if cosine negative, face left
        if (cosVal < 0) {
            state.renderFlags &= ~1; // bclr #0,obStatus(a0)
        }

        // ROM: asr.w #4,d0 — shift sine right by 4 (divide by 16)
        // swap d0 / clr.w d0 — convert to fixed-point offset
        // add.l objoff_30(a0),d0 — add to base X position
        // swap d0 / move.w d0,obX(a0)
        int sineOffset = sinVal >> 4;
        // Convert to fixed-point and add to base X
        long xDisplay = (long) (state.xFixed) + ((long) sineOffset << 16);
        state.x = (int) (xDisplay >> 16);

        // ROM: Vertical Y tracking — adjusts velocity based on player distance
        // move.w obVelY(a0),d0
        int yVelAdjusted = state.yVel;

        if (player != null) {
            // ROM: move.w (v_player+obY).w,d1 / sub.w obY(a0),d1
            int playerY = player.getCentreY() & 0xFFFF;
            int diff = playerY - (state.yFixed >> 16);

            // ROM: bcs.s loc_180A2 — if player above boss, skip adjustments
            if (diff >= 0) {
                // ROM: subi.w #$48,d1 / bcs.s loc_180A2
                diff -= 0x48;
                if (diff >= 0) {
                    yVelAdjusted >>= 1; // asr.w #1,d0
                    // ROM: subi.w #$28,d1 / bcs.s loc_180A2
                    diff -= 0x28;
                    if (diff >= 0) {
                        yVelAdjusted >>= 1; // asr.w #1,d0
                        // ROM: subi.w #$28,d1 / bcs.s loc_180A2
                        diff -= 0x28;
                        if (diff >= 0) {
                            yVelAdjusted = 0; // moveq #0,d0
                        }
                    }
                }
            }
        }

        // ROM: loc_180A2 — apply adjusted Y velocity
        // ext.l d0 / asl.l #8,d0
        long yVelFixed = ((long) yVelAdjusted) << 8;

        // ROM: tst.b objoff_3D(a0) / beq.s loc_180AE — if defeated, double
        if (bossDefeatedFlag) {
            yVelFixed <<= 1; // add.l d0,d0
        }

        // ROM: add.l d0,objoff_38(a0) / move.w objoff_38(a0),obY(a0)
        state.yFixed += (int) yVelFixed;
        state.y = state.yFixed >> 16;

        checkCollision();
    }

    // ========================================================================
    // State 8: WAYPOINT_3 — Move to third waypoint (top-right area)
    // ROM: loc_180BC
    // ========================================================================
    private void updateWaypoint3() {
        int d0 = -2;

        // ROM: cmpi.w #boss_lz_x+$16C,objoff_30(a0)
        if ((state.xFixed >> 16) >= WP3_TARGET_X) {
            state.xFixed = WP3_TARGET_X << 16;
            state.xVel = 0;
            d0++;
        }

        // ROM: cmpi.w #boss_lz_y,objoff_38(a0)
        if ((state.yFixed >> 16) <= WP3_TARGET_Y) {
            state.yFixed = WP3_TARGET_Y << 16;
            state.yVel = 0;
            d0++;
        }

        if (d0 == 0) {
            // ROM: addq.b #2,ob2ndRout(a0) / bclr #0,obStatus(a0)
            state.routineSecondary = STATE_MUSIC_TRIGGER;
            state.renderFlags &= ~1; // face left
        }

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // State 10: MUSIC_TRIGGER — Wait for player to reach top-right, play LZ music
    // ROM: loc_180F6
    //
    // This is unusual: the boss plays normal LZ music here, not boss music.
    // When defeated (objoff_3D set), skips the player position check.
    // ========================================================================
    private void updateMusicTrigger(AbstractPlayableSprite player) {
        if (!bossDefeatedFlag) {
            // ROM: tst.b objoff_3D(a0) / bne.s loc_18112
            // Check if player has reached the trigger zone
            if (player != null) {
                int playerX = player.getCentreX() & 0xFFFF;
                int playerY = player.getCentreY() & 0xFFFF;

                // ROM: cmpi.w #boss_lz_x+$E8,obX(a1)
                if (playerX < MUSIC_TRIGGER_PLAYER_X) {
                    bossMove();
                    updateDisplayPosition();
                    checkCollision();
                    return;
                }
                // ROM: cmpi.w #boss_lz_y+$30,obY(a1)
                if (playerY > MUSIC_TRIGGER_PLAYER_Y) {
                    bossMove();
                    updateDisplayPosition();
                    checkCollision();
                    return;
                }
            } else {
                bossMove();
                updateDisplayPosition();
                checkCollision();
                return;
            }
            // ROM: move.b #$32,objoff_3C(a0) — set cooldown timer
            timer = COOLDOWN_TIMER;
        }

        // ROM: loc_18112 — play LZ music and advance
        services().playMusic(Sonic1Music.LZ.id);

        // ROM (Revision != 0): clr.b (f_lockscreen).w — unlock horizontal scrolling
        // (s1disasm/_incObj/77 Boss - LZ Main.asm:288). Clear the left boundary
        // lock set at boss spawn, allowing free camera movement, and release the
        // persistent screen lock that Sonic_LevelBound consumes for its +64
        // right-boundary extension gate.
        services().camera().setMinX((short) 0);
        services().gameState().setScreenLocked(false);

        // ROM: bset #0,obStatus(a0) — face right
        state.renderFlags |= 1;

        state.routineSecondary = STATE_COOLDOWN;

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // State 12: COOLDOWN — Timer countdown, then high-speed escape
    // ROM: loc_1812A
    // ========================================================================
    private void updateCooldown() {
        if (!bossDefeatedFlag) {
            // ROM: tst.b objoff_3D(a0) / bne.s loc_18136
            timer--;
            if (timer > 0) {
                // ROM: bne.s loc_1814E — timer not expired, just move
                bossMove();
                updateDisplayPosition();
                checkCollision();
                return;
            }
        }

        // ROM: loc_18136 — timer expired or defeated
        timer = 0;
        state.xVel = 0x400;
        state.yVel = -0x40;

        // ROM: clr.b objoff_3D(a0) — clear defeated flag for this state
        // Note: this clears the local defeated flag to stop re-entering this branch,
        // but the defeat explosions continue via checkCollision -> BossDefeated
        bossDefeatedFlag = false;

        state.routineSecondary = STATE_CAMERA_EXPAND;

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // State 14: CAMERA_EXPAND — Expand right boundary, delete when off-screen
    // ROM: loc_18152
    // ========================================================================
    private void updateCameraExpand() {
        if (runCameraExpandEscape(BOSS_LZ_END)) {
            return; // Destroyed (off-screen)
        }

        bossMove();
        updateDisplayPosition();
        checkCollision();
    }

    // ========================================================================
    // Collision / Hit Handling
    // ROM: loc_17F48 — custom hit handling inline in the boss
    //
    // The LZ boss handles hits differently from other bosses:
    // - obColType is cleared when a hit registers (by ReactToItem)
    // - objoff_3E timer counts down the flash duration ($20 = 32 frames)
    // - Palette flash toggles v_palette+$22 between 0 and cWhite
    // - When timer expires, obColType is restored to $F
    // - When objoff_3D is set (defeated), spawns explosions via BossDefeated
    // ========================================================================
    private void checkCollision() {
        // ROM: tst.b objoff_3D(a0) / bne.s loc_17F8E (BossDefeated)
        if (bossDefeatedFlag) {
            handleBossDefeated();
            return;
        }

        // ROM: tst.b obStatus(a0) / bmi.s loc_17F92
        // Check if status bit 7 is set (marked for defeat by collision system)
        if ((state.renderFlags & 0x80) != 0) {
            // ROM: loc_17F92 — award points and set defeated flag
            services().gameState().addScore(1000);
            bossDefeatedFlag = true;
            state.defeated = true;
            return;
        }

        // The base class handles the actual hit processing via BossHitHandler.
        // The flash timer logic from the ROM is handled by the base class's
        // BossPaletteFlasher and BossHitHandler.
    }

    /**
     * ROM: BossDefeated subroutine (sonic.asm)
     * Spawns explosion every 8 frames while boss continues moving.
     * The boss is NOT deleted here — it continues through its state machine.
     */
    private void handleBossDefeated() {
        int vIntRunCount = state.lastUpdatedVIntRunCount;
        // ROM: move.b (v_vbla_byte).w,d0 / andi.b #7,d0 / bne.s .noexplosion
        if ((vIntRunCount & 7) == 0) {
            spawnDefeatExplosion();
        }
    }

    /**
     * Update display position from fixed-point coordinates.
     * ROM: loc_17F38
     *   move.w objoff_38(a0),obY(a0)
     *   move.w objoff_30(a0),obX(a0)
     */
    private void updateDisplayPosition() {
        state.y = state.yFixed >> 16;
        state.x = state.xFixed >> 16;
    }

    /**
     * Update face animation based on boss and game state.
     * ROM: BossLabyrinth_FaceMain (routine 4)
     *
     * The ROM processes animation selection in two phases:
     * Phase 1 — priority cascade sets base animation (d1):
     *   1. Defeated (objoff_3D) -> ANIM_FACE_DEFEAT (animation $A = 10)
     *   2. Being hit (obColType == 0) -> ANIM_FACE_HIT (animation 5)
     *   3. Player hurt (routine >= 4) -> ANIM_FACE_LAUGH (animation 4)
     *   4. Default -> ANIM_FACE_NORMAL_1 (animation 1)
     *
     * Phase 2 — escape state override (AFTER phase 1):
     *   If ship state == $E: override to ANIM_FACE_PANIC (animation 6)
     */
    private void updateFaceAnimation(AbstractPlayableSprite player) {
        // Phase 1: Priority cascade (ROM lines 336-353)
        // moveq #1,d1 — default animation
        int anim = Sonic1BossAnimations.ANIM_FACE_NORMAL_1;

        // ROM: tst.b objoff_3D(a0) — check defeated first
        if (bossDefeatedFlag) {
            anim = Sonic1BossAnimations.ANIM_FACE_DEFEAT;
        }
        // ROM: tst.b obColType(a1) / bne.s loc_18196
        // When obColType is 0 (cleared by hit), show hit face
        else if (state.invulnerable) {
            anim = Sonic1BossAnimations.ANIM_FACE_HIT;
        }
        // ROM: cmpi.b #4,(v_player+obRoutine).w / blo.s loc_181A0
        else if (player != null && player.isHurt()) {
            anim = Sonic1BossAnimations.ANIM_FACE_LAUGH;
        }

        // Phase 2: Escape state override (ROM lines 356-360)
        // ROM: cmpi.b #$E,d0 / bne.s loc_181B6
        // This OVERRIDES whatever animation was chosen above
        if (state.routineSecondary == STATE_CAMERA_EXPAND) {
            anim = Sonic1BossAnimations.ANIM_FACE_PANIC;
        }

        faceAnim = anim;
    }

    /**
     * Update flame animation based on movement state.
     * ROM: BossLabyrinth_FlameMain (routine 6)
     *
     * Default: ANIM_BLANK (animation 7)
     * If ship in escape state ($E): ANIM_ESCAPE_FLAME (animation $B)
     * If ship has X velocity: ANIM_FLAME_1 (animation 8)
     *
     * Note: In the ROM, the flame code at loc_181E2 has a dead code section
     * (the velocity check after the escape check is unreachable due to a
     * bra.s that always branches past it). We implement the intended behavior.
     */
    private void updateFlameAnimation() {
        if (state.routineSecondary == STATE_CAMERA_EXPAND) {
            flameAnim = Sonic1BossAnimations.ANIM_ESCAPE_FLAME;
        } else if (state.xVel != 0) {
            flameAnim = Sonic1BossAnimations.ANIM_FLAME_1;
        } else {
            flameAnim = Sonic1BossAnimations.ANIM_BLANK;
        }
    }

    @Override
    public int getCollisionFlags() {
        // No collision once defeated
        if (bossDefeatedFlag || state.defeated) {
            return 0;
        }
        return super.getCollisionFlags();
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ROM: obPriority = 4
    }

}

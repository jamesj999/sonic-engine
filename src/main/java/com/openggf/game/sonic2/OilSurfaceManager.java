package com.openggf.game.sonic2;

import com.openggf.audio.GameSound;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Manages OOZ oil surface collision and oil slides.
 * <p>
 * ROM equivalent: Obj07 (s2.asm:49659-49749) for oil surface,
 * OilSlides (s2.asm:5533-5650) for slide chunks.
 * <p>
 * Oil surface: invisible platform at Y=0x758. When a character lands on it,
 * a submersion counter decrements from 0x30 (48) each frame. At 0,
 * the character suffocates (instant death). Jumping off lets the counter recover.
 * <p>
 * The ROM maintains two independent submersion counters
 * ({@code oil_char1submersion}, {@code oil_char2submersion}) and runs the
 * full platform-landing pipeline for both Player 1 (Sonic) and Player 2
 * (Tails) every frame. The engine mirrors this with one {@link PlayerOilState}
 * per playable character, keyed by sprite identity so the multi-sidekick
 * novelty feature works as well.
 * <p>
 * Oil slides: 32 specific block IDs cause automatic acceleration with
 * direction-based speed targets.
 */
public class OilSurfaceManager {

    // Oil surface constants (ROM: Obj07_Init at s2.asm:49671-49677)
    private int oilY = Sonic2Constants.OIL_SURFACE_Y;
    private final int submersionMax = Sonic2Constants.OIL_SUBMERSION_MAX;

    // Per-player oil state, keyed by sprite identity.
    private final Map<AbstractPlayableSprite, PlayerOilState> playerStates =
            new IdentityHashMap<>();

    // Internal frame counter for slide sound timing
    private int frameCounter = 0;
    private boolean frameAdvancedThisTick = false;
    private int registeredOilPlayers = 0;

    // OilSlides_Chunks table (ROM: s2.asm:5647-5650)
    // 32 block IDs that trigger oil slide behavior
    private static final int[] OIL_CHUNKS = {
            0x2F, 0x30, 0x31, 0x33, 0x35, 0x38, 0x3A, 0x3C,
            0x63, 0x64, 0x83, 0x90, 0x91, 0x93, 0xA1, 0xA3,
            0xBD, 0xC7, 0xC8, 0xCE, 0xD7, 0xD8, 0xE6, 0xEB,
            0xEC, 0xED, 0xF1, 0xF2, 0xF3, 0xF4, 0xFA, 0xFD
    };

    // OilSlides_Speeds table (ROM: s2.asm:5642-5644)
    // Speed target for each corresponding chunk: -8, 0, or +8
    private static final int[] OIL_SPEEDS = {
            -8, -8, -8,  8,  8,  0,  0,  0, -8, -8,  0,  8,  8,  8,  0,  8,
             8,  8,  0, -8,  0,  0, -8,  8, -8, -8, -8,  8,  8,  8, -8, -8
    };

    /**
     * Per-player oil state mirroring ROM's {@code oil_charNsubmersion}
     * counter and standing flag for one character.
     */
    private static final class PlayerOilState {
        int submersion;
        boolean standingOnOil;

        PlayerOilState(int initialSubmersion) {
            this.submersion = initialSubmersion;
            this.standingOnOil = false;
        }
    }

    private PlayerOilState stateFor(AbstractPlayableSprite player) {
        PlayerOilState existing = playerStates.get(player);
        if (existing != null) {
            return existing;
        }
        int initialSubmersion = registeredOilPlayers == 0 ? submersionMax : 0;
        registeredOilPlayers++;
        PlayerOilState created = new PlayerOilState(initialSubmersion);
        playerStates.put(player, created);
        return created;
    }

    /**
     * Called every frame while in OOZ for each playable character.
     * ROM equivalent: a single tick of Obj07_Main processes both P1 and P2.
     * This method must be invoked once per playable character (Sonic and
     * each active sidekick) so both halves of the ROM routine are mirrored.
     * <p>
     * The frame counter advances on the first per-frame invocation only, so
     * the oil-slide SFX cadence stays at one tick per game frame regardless
     * of how many characters share the call.
     */
    public void update(AbstractPlayableSprite player) {
        // ROM order: OilSlides is called from NonWaterEffects (character processing),
        // Obj07 runs during object processing (after character). The two halves run
        // in different phases of the ROM main loop, so callers should invoke
        // updateSlides() pre-physics and updateSurface() post-physics. This combined
        // entry is retained for tests/legacy callers that tick both at once.
        updateSlides(player);
        updateOilSurface(player);
    }

    /**
     * Oil-slide half of the routine (ROM {@code OilSlides}, called from
     * {@code NonWaterEffects} BEFORE {@code RunObjects} — docs/s2disasm/s2.asm:
     * 5301,5537-5642). Runs pre-physics so the {@code sliding} status bit it
     * sets is visible to the player's friction/move code the same frame, and so
     * the chunk lookup uses the previous frame's player position.
     */
    public void updateSlides(AbstractPlayableSprite player) {
        if (!frameAdvancedThisTick) {
            frameCounter++;
            frameAdvancedThisTick = true;
        }
        updateOilSlides(player);
    }

    /**
     * Oil-surface half of the routine (ROM {@code Obj07}, run during object
     * processing — docs/s2disasm/s2.asm:49659-49749). Runs post-physics.
     */
    public void updateSurface(AbstractPlayableSprite player) {
        updateOilSurface(player);
    }

    /**
     * Called by the OOZ events handler at the end of each frame so the next
     * frame's first character tick will advance the slide SFX cadence.
     */
    public void endFrame() {
        frameAdvancedThisTick = false;
    }

    /**
     * Resets oil state (e.g. on level load or player respawn).
     */
    public void reset() {
        playerStates.clear();
        oilY = Sonic2Constants.OIL_SURFACE_Y;
        frameCounter = 0;
        frameAdvancedThisTick = false;
        registeredOilPlayers = 0;
    }

    /**
     * Mirrors event-script writes to {@code (Oil+y_pos).w}, such as the OOZ2
     * boss approach lowering the oil support plane to {@code $2D8}.
     */
    public void setOilSurfaceY(int oilY) {
        this.oilY = oilY & 0xFFFF;
    }

    // =========================================================================
    // Oil Surface (Obj07 equivalent)
    // ROM: s2.asm:49659-49749
    // =========================================================================

    private void updateOilSurface(AbstractPlayableSprite player) {
        PlayerOilState state = stateFor(player);
        if (player.getDead() || player.isDebugMode()) {
            clearOilTrackingOnly(state);
            return;
        }

        if (state.standingOnOil) {
            // ROM: Obj07_CheckKillChar1 (s2.asm:49695-49698)
            if (state.submersion <= 0) {
                // Suffocate - instant death (ROM: JmpTo3_KillCharacter)
                clearOilTrackingOnly(state);
                player.applyOilSuffocateDeath();
                return;
            }

            // Sink 1 pixel per frame (ROM: subq.b #1, oil_char1submersion)
            state.submersion--;

            if (player.isObjectControlSuppressesMovement()) {
                // Obj07 updates the submersion counter before the PlatformObject
                // call, then PlatformObject_ChkYRange returns when obj_control
                // bit 7 is set (s2.asm:50182-50195,35978-35981).
                clearOilTrackingOnly(state);
                return;
            }
            if (isDeadFallingCpuSidekick(player)) {
                // ROM PlatformObject_ChkYRange aborts when routine(a1) >= 6
                // after Obj07 has already ticked the standing counter
                // (docs/s2disasm/s2.asm:50182-50195,35978-35981).
                clearOilSupport(player, state);
                player.setAir(true);
                return;
            }
            if (player.isHurt() && player.getAir()) {
                // Hurt recoil owns the airborne state after the boss touch; Obj07 must not re-seat it.
                clearOilSupport(player, state);
                return;
            }

            // Movement runs before this manager and can temporarily set air=true.
            // Only release support when the player is actually moving upward.
            if (shouldExitOilSupport(player)) {
                clearOilSupport(player, state);
                return;
            }

            // ROM: when already standing (status bit set), the standing branch of
            // PlatformObject_SingleCharacter calls MvSonicOnPtfm (s2.asm:35402-35421),
            // which positions y_pos(a1) = y_pos(a0) - d3 - y_radius
            // = oilY - submersion - yRadius. MvSonicOnPtfm does NOT touch x_vel,
            // inertia, or y_vel - those evolve through normal player physics.
            //
            // ROM writes only the integer half (move.w to y_pos), so the
            // sub-pixel fraction is preserved. Use the preserve-subpixel setter
            // so engine sub-pixel accumulators stay aligned with ROM.
            int targetY = oilY - state.submersion - player.getYRadius();
            clearAirForOilSupport(player);
            player.setOnObject(true);
            latchOilSupport(player);
            player.setCentreYPreserveSubpixel((short) targetY);
        } else {
            // Not on oil - recover submersion counter
            // ROM: Obj07_Main (s2.asm:49689-49692)
            //   cmpi.b #$30, oil_char1submersion ; beq + ; addq.b #1, oil_char1submersion
            if (state.submersion < submersionMax) {
                state.submersion++;
            }

            if (player.isObjectControlSuppressesMovement()) {
                // PlatformObject_ChkYRange returns before support when
                // obj_control bit 7 is set (s2.asm:35978-35981). This matters
                // after TailsCPU_Despawn writes $81: Obj07 runs later in the same
                // object phase and must not re-seat Tails on the oil surface.
                clearOilTrackingOnly(state);
                return;
            }
            if (isDeadFallingCpuSidekick(player)) {
                // S2's deferred dead-fall sidekick path represents Obj02_Dead
                // via DEAD_FALLING before obj_control bit 7 is set.
                clearOilSupport(player, state);
                player.setAir(true);
                return;
            }

            // Check if player should land on oil surface
            // ROM: PlatformObject_SingleCharacter does the landing check
            if (shouldLandOnOil(player, state)) {
                boolean wasHurt = player.isHurt();
                state.standingOnOil = true;
                clearAirForOilSupport(player);
                player.setOnObject(true);
                latchOilSupport(player);

                // ROM RideObject_SetRide (s2.asm:35741-35743):
                //   move.b #0, angle(a1)
                //   move.w #0, y_vel(a1)
                //   move.w x_vel(a1), inertia(a1)
                // i.e. on the landing frame ROM zeroes y_vel and copies x_vel
                // to inertia (gSpeed) - it does NOT clobber inertia to zero.
                player.setYSpeed((short) 0);
                player.setGSpeed(player.getXSpeed());
                player.setAngle((byte) 0);

                // ROM: PlatformObject_ChkYRange (s2.asm:35696-35712) snaps the
                // player so that y_pos(a1) + y_radius + 4 = platform_top + 3,
                // i.e. y_pos(a1) = oilY - submersion - yRadius - 1.
                // (The "+3" in ROM applies after the +4 grace was subtracted, so
                // the net offset versus MvSonicOnPtfm is -1 pixel on the landing
                // frame; subsequent frames use MvSonicOnPtfm's exact -yRadius snap.)
                //
                // Preserve sub-pixel: ROM writes only the integer half
                // (move.w d2,y_pos(a1)).
                int targetY = oilY - state.submersion - player.getYRadius() - 1;
                player.setCentreYPreserveSubpixel((short) targetY);

                // ROM Sonic_ResetOnFloor_Part2 (s2.asm:37780-37786): when
                // landing while rolling, clear the rolling bit, restore the
                // standing collision radii, and lift y_pos by 5 (the radius
                // diff between rolling y_radius=14 and standing y_radius=19)
                // so the new larger hitbox does not clip into the platform.
                // ROM does `subq.w #5, y_pos(a0)` - a word write that leaves
                // the sub-pixel low word untouched.
                boolean wasRolling = player.getRolling();
                if (wasRolling) {
                    player.setRolling(false);
                    player.setY((short) (player.getY() - player.getRollHeightAdjustment()));
                }

                // HurtStop owns the raw Hurt byte through this post-player Obj07
                // landing row; its next player dispatch publishes Walk. Ordinary
                // fresh landings still take ResetOnFloor_Part2's Walk write.
                if (!wasHurt) {
                    player.setAnimationId(Sonic2AnimationIds.WALK);
                }
            }
        }
    }

    /**
     * Check if the player should land on the oil surface.
     * ROM: PlatformObject_cont + PlatformObject_ChkYRange (s2.asm:35681-35712).
     * Player must be falling (y_vel >= 0), inside the X range of the platform
     * (handled implicitly by the proximity check), and within the 16-pixel
     * tolerance band ABOVE the platform top, measured from feetY + 4.
     */
    private boolean shouldLandOnOil(AbstractPlayableSprite player, PlayerOilState state) {
        // ROM PlatformObject_cont:35682 - tst.w y_vel ; bmi return.
        // y_vel must be >= 0 (still falling). bmi triggers only on negative.
        if (!player.getAir() || player.getYSpeed() < 0) {
            return false;
        }

        // Already on oil or dead
        if (player.getDead() || player.isOnObject()) {
            return false;
        }

        // ROM PlatformObject_ChkYRange (s2.asm:35696-35705):
        //   d0 = y_pos(a0) - d3 - (y_pos(a1) + y_radius + 4)
        //   bhi  return        ; d0 unsigned > 0 -> player too high above top, abort
        //   cmpi.w #-$10, d0
        //   blo  return        ; word-unsigned cmp vs $FFF0. The carry flag is
        //                      ; set when d0_unsigned < 0xFFF0, which covers
        //                      ; d0 >= 0 AND d0 < -16. The only frames that
        //                      ; land are those with d0 in [-16, -1].
        // Net acceptance window: feetY in [platform_top-19, platform_top-5].
        int feetY = player.getCentreY() + player.getYRadius();
        int platformTop = oilY - state.submersion;
        int d0 = platformTop - (feetY + 4);
        if (d0 >= 0) {
            return false;  // ROM bhi (d0>0) plus blo (d0==0 has C=1) both abort
        }
        if (d0 < -0x10) {
            return false;  // ROM blo -- d0 < -16
        }
        return true;
    }

    private boolean isDeadFallingCpuSidekick(AbstractPlayableSprite player) {
        if (!player.isCpuControlled()) {
            return false;
        }
        SidekickCpuController cpu = player.getCpuController();
        return cpu != null && cpu.getState() == SidekickCpuController.State.DEAD_FALLING;
    }

    private void clearAirForOilSupport(AbstractPlayableSprite player) {
        if (player.isHurt()) {
            // Obj07 support clears Status_InAir during object processing, but
            // S2 Obj02_Hurt still owns the next tick's Tails_HurtStop recovery:
            // after Tails_DoLevelCollision sees Status_InAir clear, it zeroes
            // y_vel/x_vel/inertia and returns to routine 2
            // (docs/s2disasm/s2.asm:41063-41118).
            player.setAirAfterObjectHurtLanding();
        } else {
            player.setAir(false);
        }
    }

    /**
     * End support only when the player is actively moving upward.
     */
    private boolean shouldExitOilSupport(AbstractPlayableSprite player) {
        return player.getYSpeed() < 0 || player.isJumping();
    }

    private void clearOilSupport(AbstractPlayableSprite player, PlayerOilState state) {
        clearOilTrackingOnly(state);
        player.setOnObject(false);
    }

    private void clearOilTrackingOnly(PlayerOilState state) {
        state.standingOnOil = false;
    }

    private void latchOilSupport(AbstractPlayableSprite player) {
        // Obj07 calls PlatformObject_SingleCharacter (s2.asm:50157,50189),
        // whose RideObject_SetRide tail writes interact(a1) to Obj07's SST slot
        // before TailsCPU_CheckDespawn re-dereferences that slot on the next
        // CPU tick (s2.asm:35999-36006,39409-39429). Obj07 is manager-hosted
        // in the engine, so publish a synthetic live id for the same compare.
        player.setSyntheticLatchedSolidObject(Sonic2ObjectIds.OIL);
    }

    // =========================================================================
    // Oil Slides (OilSlides routine equivalent)
    // ROM: s2.asm:5533-5650
    // =========================================================================

    private void updateOilSlides(AbstractPlayableSprite player) {
        if (player.getDead() || player.isDebugMode()) {
            return;
        }

        // ROM: btst #status.player.in_air,status(a1) / bne.s +
        if (player.getAir()) {
            // In air - if was sliding, set move lock and clear flag
            if (player.isSliding()) {
                player.setSliding(false);
                setMoveLock(player, 5);
            }
            return;
        }

        // Look up block ID at player position
        // ROM: uses centre coordinates (x_pos, y_pos)
        LevelManager levelManager = player.currentLevelManagerIfAvailable();
        if (levelManager == null) {
            levelManager = GameServices.levelOrNull();
        }
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            exitSlide(player);
            return;
        }
        int blockId = levelManager.getBlockIdAt(player.getCentreX(), player.getCentreY());
        if (blockId < 0) {
            exitSlide(player);
            return;
        }

        // Search for matching chunk in the oil chunks table
        // ROM: searches OilSlides_Chunks backwards with dbeq loop
        int chunkIndex = findChunkIndex(blockId);
        if (chunkIndex < 0) {
            // Not on an oil slide chunk
            exitSlide(player);
            return;
        }

        // Found a match - apply oil slide physics
        int speed = OIL_SPEEDS[chunkIndex];
        if (speed != 0) {
            applyDirectionalSlide(player, speed);
        } else {
            applyFrictionSlide(player);
        }
    }

    /**
     * Search OIL_CHUNKS for a matching block ID.
     * @return index into OIL_CHUNKS/OIL_SPEEDS, or -1 if not found
     */
    private int findChunkIndex(int blockId) {
        for (int i = 0; i < OIL_CHUNKS.length; i++) {
            if (OIL_CHUNKS[i] == blockId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Apply directional oil slide (speed != 0).
     * ROM: loc_4712 (s2.asm:5566-5596)
     * Accelerates inertia toward target speed at ±0x40/frame.
     */
    private void applyDirectionalSlide(AbstractPlayableSprite player, int targetSpeed) {
        short inertia = player.getGSpeed();
        int inertiaHigh = inertia >> 8; // ROM compares high byte of inertia

        // ROM: Accelerate toward target speed
        // Speed values are in pixels (-8 or +8), compared against inertia high byte
        if (targetSpeed < 0) {
            // ROM: cmp.b d0,d1 / ble.s ++ / subi.w #$40,inertia
            if (inertiaHigh > targetSpeed) {
                player.setGSpeed((short) (inertia - 0x40));
            }
        } else {
            // ROM: cmp.b d0,d1 / bge.s + / addi.w #$40,inertia
            if (inertiaHigh < targetSpeed) {
                player.setGSpeed((short) (inertia + 0x40));
            }
        }

        // ROM: Set facing direction based on inertia sign
        // bclr #status.player.x_flip / tst.b d1 / bpl.s + / bset #status.player.x_flip
        // Note: d1 still holds the original inertia high byte
        if (inertiaHigh < 0) {
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
        }

        // Set slide animation
        player.setAnimationId(Sonic2AnimationIds.SLIDE);
        player.setSliding(true);

        // ROM: Play oil slide sound every 32 frames
        // andi.b #$1F,d0 / bne.s + (d0 = Vint_runcount low byte)
        if ((frameCounter & 0x1F) == 0) {
            GameServices.audio().playSfx(GameSound.OIL_SLIDE);
        }
    }

    /**
     * Apply friction oil slide (speed == 0).
     * ROM: loc_476E (s2.asm:5599-5638)
     * Applies friction ±4/frame toward 0, allows manual L/R input.
     */
    private void applyFrictionSlide(AbstractPlayableSprite player) {
        int friction = 4;
        int inertia = player.getGSpeed();

        // ROM OilSlides copies Ctrl_1_Held_Logical/Ctrl_2_Held_Logical into d2
        // before the player slot refreshes input (s2.asm:5537-5540).
        // Use the published logical word, not the current raw pressed state.
        // ROM: Process left input (s2.asm:5602-5609)
        // sub.w d1,d0 / tst.w d0 / bpl.s + / sub.w d1,d0
        // Extra friction only when result is negative (already moving left or crossed zero)
        if (logicalLeftPressed(player)) {
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setDirection(Direction.LEFT);
            inertia -= friction;
            if (inertia < 0) {
                inertia -= friction;
            }
        }

        // ROM: Process right input (s2.asm:5611-5618)
        // add.w d1,d0 / tst.w d0 / bmi.s + / add.w d1,d0
        // Extra acceleration only when result is positive (already moving right or crossed zero)
        if (logicalRightPressed(player)) {
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setDirection(Direction.RIGHT);
            inertia += friction;
            if (inertia >= 0) {
                inertia += friction;
            }
        }

        // ROM: Apply friction toward zero (s2.asm:5620-5635)
        // tst.w d0 / beq.s +++ / bmi.s ++ / sub.w d1,d0 / bhi.s + / move.w #0,d0 / wait anim
        if (inertia > 0) {
            inertia -= friction;
            if (inertia <= 0) {
                inertia = 0;
                player.setAnimationId(Sonic2AnimationIds.WAIT);
            }
        } else if (inertia < 0) {
            inertia += friction;
            if (inertia >= 0) {
                inertia = 0;
                player.setAnimationId(Sonic2AnimationIds.WAIT);
            }
        } else {
            player.setAnimationId(Sonic2AnimationIds.WAIT);
        }

        player.setGSpeed((short) inertia);
        player.setSliding(true);
    }

    private boolean logicalLeftPressed(AbstractPlayableSprite player) {
        return (player.getLogicalInputState() & AbstractPlayableSprite.INPUT_LEFT) != 0;
    }

    private boolean logicalRightPressed(AbstractPlayableSprite player) {
        return (player.getLogicalInputState() & AbstractPlayableSprite.INPUT_RIGHT) != 0;
    }

    /**
     * Exit oil slide state - set move lock and clear sliding flag.
     * ROM: s2.asm:5559-5563
     */
    private void exitSlide(AbstractPlayableSprite player) {
        if (player.isSliding()) {
            setMoveLock(player, 5);
            player.setSliding(false);
        }
    }

    private void setMoveLock(AbstractPlayableSprite player, int frames) {
        // ROM: move.w #5,move_lock(a1)
        player.setMoveLockTimer(frames);
    }

    /**
     * Returns whether the camera-focused (or supplied) character is standing
     * on the oil surface. Used by zone diagnostics; defaults to checking the
     * first registered player's state.
     */
    public boolean isStandingOnOil() {
        for (PlayerOilState state : playerStates.values()) {
            if (state.standingOnOil) {
                return true;
            }
        }
        return false;
    }

    public boolean isStandingOnOil(AbstractPlayableSprite player) {
        PlayerOilState state = playerStates.get(player);
        return state != null && state.standingOnOil;
    }

    public int getSubmersion(AbstractPlayableSprite player) {
        PlayerOilState state = playerStates.get(player);
        return state != null ? state.submersion : submersionMax;
    }

    /**
     * Returns the submersion counter for the first registered player.
     * Convenience accessor for tests that only register a single character;
     * matches ROM {@code oil_char1submersion} when only Sonic is active.
     */
    public int getSubmersion() {
        for (PlayerOilState state : playerStates.values()) {
            return state.submersion;
        }
        return submersionMax;
    }
}

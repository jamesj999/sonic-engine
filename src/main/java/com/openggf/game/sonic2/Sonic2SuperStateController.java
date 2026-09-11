package com.openggf.game.sonic2;

import com.openggf.audio.GameMusic;
import com.openggf.data.RomByteReader;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PowerUpRules;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.SuperSonicStarsObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperStateController;

import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Sonic 2 implementation of Super Sonic state management.
 *
 * <p>Handles transformation SFX, Super Sonic music, palette fade/cycling,
 * and ring drain at the S2-specific rate of 1 ring per second (60 frames).
 *
 * <p>Palette cycling uses ROM data from {@code CyclingPal_SSTransformation} (0x2246).
 * The 128-byte table contains 16 frames of 8 bytes each (4 Mega Drive colors per frame).
 * Colors are written to palette line 1 (index 0), color indices 2-5 (Sonic's blue shades).
 *
 * <p>ROM reference: {@code PalCycle_SuperSonic} in s2.asm (lines 3115-3229).
 *
 * <p>Palette states:
 * <ul>
 *   <li>0 = off (normal palette)</li>
 *   <li>1 = fading in (transformation) - frames 0x00-0x28, timer 3</li>
 *   <li>-1 = cycling (active Super Sonic) - frames 0x30-0x78, timer 7</li>
 *   <li>2 = fading out (reverting to normal) - frames decrement, timer 3</li>
 * </ul>
 */
public class Sonic2SuperStateController extends SuperStateController {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SuperStateController.class.getName());

    /** Palette fade state: 0=off, 1=fading in, -1=cycling, 2=fading out. */
    private int paletteState;
    /** Current palette frame byte offset into the transformation data (increments by 8). */
    private int paletteFrame;
    /** Countdown timer between palette frame advances. */
    private int paletteTimer;

    /**
     * Raw ROM palette data from CyclingPal_SSTransformation.
     * 128 bytes: 16 frames x 8 bytes (4 MD colors x 2 bytes each).
     * Frames 0-5 (offsets 0x00-0x28): fade from normal to super.
     * Frames 6-15 (offsets 0x30-0x78): cycling animation loop.
     */
    private byte[] paletteData;

    /** Super Sonic animation set (loaded from ROM). */
    private SpriteAnimationSet superAnimSet;
    /** Normal animation set (saved on activation, restored on revert). */
    private SpriteAnimationSet normalAnimSet;
    /** Super Sonic stars sparkle effect object (Obj7E). */
    private SuperSonicStarsObjectInstance starsObject;

    /** Palette line index where Sonic's colors reside (line 1 = index 0). */
    private static final int SONIC_PALETTE_INDEX = 0;
    /** First color index to write (Normal_palette+4 = 2 words from start). */
    private static final int FIRST_COLOR_INDEX = 2;
    /** Number of colors written per frame. */
    private static final int COLORS_PER_FRAME = 4;
    /** Byte offset at which fade-in is complete (frame 6). */
    private static final int FADE_COMPLETE_OFFSET = 0x30;
    /** Byte offset at which cycling wraps (frame 16, with fixBugs: inclusive). */
    private static final int CYCLE_WRAP_OFFSET = 0x78;

    public Sonic2SuperStateController(AbstractPlayableSprite player) {
        super(player);
    }

    @Override
    public void reset() {
        super.reset();
        paletteState = 0;
        paletteFrame = 0;
        paletteTimer = 0;
    }

    @Override
    public void loadRomData(RomByteReader reader) {
        int addr = Sonic2Constants.CYCLING_PAL_SS_TRANSFORMATION_ADDR;
        int len = Sonic2Constants.CYCLING_PAL_SS_TRANSFORMATION_LEN;
        if (addr == 0 || addr + len > reader.size()) {
            LOGGER.warning("Super Sonic palette data not available at ROM address 0x"
                    + Integer.toHexString(addr));
            return;
        }
        paletteData = reader.slice(addr, len);
        LOGGER.fine("Loaded Super Sonic palette data: " + len + " bytes from ROM 0x"
                + Integer.toHexString(addr));

        // Load Super Sonic animation set
        Sonic2PlayerArt playerArt = new Sonic2PlayerArt(reader);
        superAnimSet = playerArt.loadSuperSonicAnimationSet();
        if (superAnimSet != null) {
            LOGGER.fine("Loaded Super Sonic animation set");
        }
    }

    @Override
    protected int getRingDrainInterval() {
        return Sonic2Constants.SUPER_SONIC_RING_DRAIN_INTERVAL;
    }

    @Override
    protected int getMinRingsToTransform() {
        return Sonic2Constants.SUPER_SONIC_MIN_RINGS;
    }

    @Override
    protected PhysicsProfile getSuperProfile() {
        return PhysicsProfile.SONIC_2_SUPER_SONIC;
    }

    @Override
    protected PhysicsProfile getNormalProfile() {
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    protected void onTransformationStarted() {
        paletteState = 1;
        // ROM Sonic_CheckGoSuper (s2.asm:37476-37477) writes Super_Sonic_palette and
        // Palette_timer but deliberately does NOT write Palette_frame; the same holds
        // for the second entry point at s2.asm:26204-26205. Palette_frame therefore
        // carries over from whatever the previous cycle left - $0000 on a fresh level
        // (RAM clear), $00F8 after a revert on the shipped ROM (see updatePaletteFade).
        // Zeroing it here would be the fixBugs = 1 behaviour by proxy.
        // ROM Sonic_CheckGoSuper: move.b #$F,(Palette_timer).w (s2.asm:37477).
        // $F is the transformation's own first interval; 3 is only the value
        // PalCycle_SuperSonic reloads on every step AFTER the first
        // (s2.asm:3129). Seeding 3 here shortened the fade's opening step from
        // 16 frames to 4.
        paletteTimer = 0xF;
        // Play transformation SFX
        try {
            if (CrossGameFeatureProvider.isActive()) {
                GameServices.audio().playDonorSfx(
                        GameServices.crossGameFeatures().getDonorGameId(),
                        Sonic2AudioConstants.SFX_SUPER_TRANSFORM);
            } else {
                GameServices.audio().playSfx(Sonic2AudioConstants.SFX_SUPER_TRANSFORM);
            }
        } catch (Exception e) {
            LOGGER.fine("Could not play transformation SFX: " + e.getMessage());
        }
    }

    @Override
    protected boolean updateTransformationAnimation() {
        // The transformation ends when the PALETTE FADE ends, not when an
        // animation or a counter does. Nothing in Sonic's own code clears
        // obj_control after Sonic_CheckGoSuper sets it to $81 (s2.asm:37479):
        // there is no `clr.b obj_control(a0)` anywhere in s2.asm, and
        // SupSonAni_Transform terminates `$FD, 0` (:38818), which SAnim_End_FD
        // handles by writing anim(a0) and nothing else (:38439). The one clear
        // that runs on this path is PalCycle_SuperSonic's, on the pass that
        // takes Palette_frame to $30:
        //   move.b #0,(MainCharacter+obj_control).w  ; restore Sonic's movement
        // (s2.asm:3139). So the freeze's length is a consequence of $F, the
        // reloads of 3 and the six steps to $30 -- derived, never chosen. This
        // used to count down an invented 30-frame timer instead.
        updatePaletteFade();
        return paletteState != 1;
    }

    @Override
    protected void onSuperActivated() {
        paletteState = -1;
        paletteTimer = 7;
        // Play Super Sonic music (overrides zone music)
        try {
            if (CrossGameFeatureProvider.isActive()) {
                GameServices.audio().playDonorMusic(
                        GameServices.crossGameFeatures().getDonorGameId(),
                        GameMusic.SUPER);
            } else {
                GameServices.audio().playMusic(GameMusic.SUPER);
            }
        } catch (Exception e) {
            LOGGER.fine("Could not play Super Sonic music: " + e.getMessage());
        }
        // Clear invincibility timer - Super Sonic has inherent invincibility
        player.setInvincibleFrames(0);
        // Swap to Super Sonic animation set
        if (superAnimSet != null) {
            normalAnimSet = player.getAnimationSet();
            player.setAnimationSet(superAnimSet);
        }
        // Hide shield - Super Sonic doesn't display shields
        player.setShieldVisible(false);
        // Spawn Super Sonic stars sparkle effect (Obj7E)
        if (starsObject == null) {
            starsObject = new SuperSonicStarsObjectInstance(player);
            addStarsObject(starsObject);
        }
        LOGGER.info("Super Sonic activated (S2)");
    }

    /**
     * Places the super-form stars in the SST the game's ROM owns for it.
     *
     * <p>{@code ObjID_SuperSonicStars} is written straight into the fixed
     * {@code SuperSonicStars} SST and never runs {@code FindFreeObj}, so
     * allocating one from the dynamic pool would consume a level-object slot the
     * ROM never consumes and displace every later object -- and SST order is
     * execution order. See {@link PowerUpRules#superStarsFixedSlotIndex()}.
     * A negative index keeps ordinary dynamic allocation.
     */
    private void addStarsObject(SuperSonicStarsObjectInstance stars) {
        ObjectManager objects = GameServices.level().getObjectManager();
        GameRules rules = player != null ? player.getGameRules() : null;
        PowerUpRules powerUp = rules != null ? rules.powerUp() : null;
        int fixedSlot = powerUp != null ? powerUp.superStarsFixedSlotIndex() : -1;
        if (fixedSlot >= 0) {
            ObjectLifetimeOps.addDynamicAtReservedSlot(objects, stars, fixedSlot);
            return;
        }
        objects.addDynamicObject(stars);
    }

    @Override
    protected void updateSuperPalette() {
        // ROM: PalCycle_SuperSonic_normal (loc_21E6)
        if (paletteState != -1) return;

        paletteTimer--;
        if (paletteTimer >= 0) return;

        paletteTimer = 7;

        // Read current frame offset, then advance
        int frameOffset = paletteFrame;
        paletteFrame += 8;

        // fixBugs (s2.asm:27 `fixBugs = 0`, block at s2.asm:3210-3216): after
        // `addq.w #8,(Palette_frame)` the shipped branch is `cmpi.w #$78 / blo.s`, so the
        // cycle wraps as soon as the counter REACHES $78 and the $70 entry is the last
        // one ever displayed -- the $78 frame of the Super Sonic cycle is skipped. The
        // fixBugs=1 branch uses `bls.s`, which lets $78 display before wrapping. The
        // engine models the shipped branch.
        if (paletteFrame >= CYCLE_WRAP_OFFSET) {
            paletteFrame = FADE_COMPLETE_OFFSET;
        }

        applyPaletteFrame(frameOffset);
    }

    @Override
    protected void onRevertStarted() {
        paletteState = 2;
        // ROM: move.w #$28,(Palette_frame).w on revert
        paletteFrame = 0x28;
        paletteTimer = 3;
        // ROM: move.w #1,invincibility_time(a0). Besides the one-frame grace
        // period, this is how Sonic_RevertToNormal restores the zone music: it
        // plays none itself and lets Obj01_ChkInvin re-issue Level_Music when
        // the timer expires on the next tick.
        player.setInvincibleFrames(1);
        // Restore normal animation set
        if (normalAnimSet != null) {
            player.setAnimationSet(normalAnimSet);
            normalAnimSet = null;
        }
        // Re-show shield
        player.setShieldVisible(true);
        // Destroy Super Sonic stars
        if (starsObject != null) {
            starsObject.destroy();
            starsObject = null;
        }
        LOGGER.info("Super Sonic deactivated (S2)");
    }

    /**
     * Advances the palette fade animation (used during transformation and revert).
     *
     * <p>ROM reference: {@code PalCycle_SuperSonic} fade-in (state 1) and
     * {@code PalCycle_SuperSonic_revert} (state 2).
     *
     * <p>Fade-in (state 1): reads frame, then advances palette frame forward every 3 frames.
     * When frame reaches 0x30, fade-in is complete (state transitions to -1).
     *
     * <p>Fade-out (state 2): reads frame, then retreats palette frame backward every 3 frames.
     * When frame underflows below 0, palette cycling stops (state transitions to 0).
     */
    private void updatePaletteFade() {
        if (paletteState == 0) return;

        paletteTimer--;
        if (paletteTimer >= 0) return;

        paletteTimer = 3;

        if (paletteState == 1) {
            // ROM: move.w (Palette_frame).w,d0 / addq.w #8,(Palette_frame).w
            // Read current frame before incrementing
            int frameOffset = paletteFrame;
            paletteFrame += 8;

            // ROM: cmpi.w #$30,(Palette_frame).w / blo.s +
            // When paletteFrame reaches 0x30, fade-in is complete
            if (paletteFrame >= FADE_COMPLETE_OFFSET) {
                paletteState = -1;
            }

            applyPaletteFrame(frameOffset);

        } else if (paletteState == 2) {
            // ROM: move.w (Palette_frame).w,d0 / subq.w #8,(Palette_frame).w
            // Read current frame before decrementing
            int frameOffset = paletteFrame;
            paletteFrame -= 8;

            // ASSEMBLY FLAG: fixBugs (docs/s2disasm/s2.asm:27), 0 in the shipped ROM.
            // THE ENGINE IMPLEMENTS THE SHIPPED (UN-FIXED) BRANCH. `subq.w #8` from 0
            // borrows, and PalCycle_SuperSonic_revert then does
            //   move.b #0,(Palette_frame).w        (s2.asm:3180)
            // a BYTE write to a WORD variable, i.e. to the HIGH byte only, so the
            // underflowed $FFF8 becomes $00F8 and Palette_frame is left at $F8 rather
            // than 0 -- the disassembly's own note, "This does not clear the full
            // variable, causing this palette cycle to behave incorrectly the next time
            // it is activated." With fixBugs = 1 the instruction is
            // move.w #0,(Palette_frame).w (s2.asm:3176) and the variable is fully
            // cleared.
            //
            // The residual IS observable: Sonic_CheckGoSuper writes Super_Sonic_palette
            // and Palette_timer but NOT Palette_frame (s2.asm:37476-37481), so a second
            // transformation resumes PalCycle_SuperSonic_normal from $F8. Only
            // Sonic_RevertToNormal (s2.asm:37530, $28) and the title-card/Super seed at
            // s2.asm:13036 ($30) re-seed it, and neither runs on that path.
            //
            // Engine divergence noted, not modelled: at $F8 the ROM indexes 8 bytes past
            // the end of CyclingPal_SSTransformation for one frame; applyPaletteFrame
            // bounds-checks and leaves the palette untouched instead of reading whatever
            // follows the table in ROM.
            //
            // ROM: bcc.s + (branch if no borrow) -- the clear runs only on underflow.
            if (paletteFrame < 0) {
                paletteFrame = paletteFrame & 0x00FF;
                paletteState = 0;
            }

            applyPaletteFrame(frameOffset);
        }
    }

    /**
     * Applies a single palette frame from the ROM data to Sonic's palette.
     *
     * <p>ROM: Writes 8 bytes (4 MD colors) from {@code CyclingPal_SSTransformation + frameOffset}
     * to {@code Normal_palette+4} (palette line 1, colors 2-5).
     *
     * @param frameOffset byte offset into paletteData (must be 8-aligned, 0x00-0x78)
     */
    private void applyPaletteFrame(int frameOffset) {
        if (paletteData == null || paletteData.length == 0) return;
        if (frameOffset < 0 || frameOffset + 8 > paletteData.length) return;

        PaletteTarget target = resolvePaletteTarget(SONIC_PALETTE_INDEX);
        if (target == null) return;

        Palette palette = target.palette();

        // Write 4 colors at indices 2, 3, 4, 5 from ROM data
        // ROM: move.l (a0,d0.w),(a1)+ / move.l 4(a0,d0.w),(a1)
        // Each color is 2 bytes in Mega Drive format (0BBB0GGG0RRR0)
        for (int i = 0; i < COLORS_PER_FRAME; i++) {
            palette.getColor(FIRST_COLOR_INDEX + i)
                    .fromSegaFormat(paletteData, frameOffset + i * 2);
        }

        // Mark palette dirty for GPU re-upload
        GraphicsManager gfx = GameServices.graphics();
        if (gfx.isGlInitialized()) {
            gfx.cachePaletteTexture(palette, target.gpuLine());
        }
    }

    @Override
    protected void updatePostRevertEffects() {
        // Continue palette fade-out after state returns to NORMAL.
        // Only handle state 2 (fading out) - states 1 and -1 are handled
        // by updateTransformationAnimation() and updateSuperPalette() respectively.
        if (paletteState == 2) {
            updatePaletteFade();
        }
    }

    /** Returns the current palette state (0=off, 1=fading in, -1=cycling, 2=fading out). */
    public int getPaletteState() {
        return paletteState;
    }

    /** Returns the current palette frame byte offset. */
    public int getPaletteFrame() {
        return paletteFrame;
    }
}

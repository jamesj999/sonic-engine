package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.ButtonVineTriggerManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x80 - MovingVine (MCZ) / Hook on Chain (WFZ).
 * <p>
 * A vine/chain that the player can grab and hang from. When grabbed, the vine
 * extends or retracts based on its configuration. The player can release by
 * pressing any action button (A/B/C).
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 56145-56412 (Obj80 code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bit 7 (0x80): Button switch mode - triggers ButtonVine_Trigger on grab</li>
 *   <li>Bits 4-6 (0x70): Non-zero = start fully extended (reversed mode)</li>
 *   <li>Bits 0-3 (0x0F): Switch ID for button vine / WFZ max extension modifier</li>
 * </ul>
 * <p>
 * <b>Zone variants:</b>
 * <ul>
 *   <li>MCZ: Vine pulley - max extension 0xB0, frame = extension/32 + 1, palette 3</li>
 *   <li>WFZ: Hook on chain - max extension 0xA0 (or 0x60), frame = extension/16 + 1, palette 1</li>
 * </ul>
 */
public class MovingVineObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(MovingVineObjectInstance.class.getName());
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    /**
     * Zone variant configuration.
     * ROM: Obj80_Init checks Current_Zone == wing_fortress_zone
     */
    private enum ZoneVariant {
        /**
         * MCZ variant: Vine pulley.
         * ROM: Obj80_MCZ_Init at loc_29A1C
         * - Art: ArtNem_VinePulley, palette 3
         * - Max extension: 0xB0 (176 pixels)
         * - Frame calculation: extension / 32 + 1
         * - Art key: Sonic2ObjectArtKeys.VINE_PULLEY
         */
        MCZ(0xB0, 32, Sonic2ObjectArtKeys.VINE_PULLEY),

        /**
         * WFZ variant: Hook on chain.
         * ROM: Obj80_WFZ_Main initialization
         * - Art: ArtNem_WfzHook, palette 1
         * - Max extension: 0xA0 (160 pixels), or 0x60 (96) if subtype & 0x0F != 0
         * - Frame calculation: extension / 16 + 1
         * - Art key: Sonic2ObjectArtKeys.WFZ_HOOK
         */
        WFZ(0xA0, 16, Sonic2ObjectArtKeys.WFZ_HOOK);

        final int defaultMaxExtension;
        final int extensionDivider;
        final String artKey;

        ZoneVariant(int maxExt, int divider, String artKey) {
            this.defaultMaxExtension = maxExt;
            this.extensionDivider = divider;
            this.artKey = artKey;
        }
    }

    // === Object Configuration ===
    private ZoneVariant variant;
    private int initialY;             // objoff_3C: Saved initial Y position
    private int maxExtension;         // objoff_2E: Maximum extension distance
    private boolean reversedMode;     // objoff_36: If true, retract when player hanging
    private boolean buttonVineMode;   // objoff_34: Button switch mode flag
    private int buttonSwitchId;       // subtype & 0x0F: Switch ID for ButtonVine_Trigger

    // === Movement State ===
    private int currentExtension;           // objoff_38: Current extension value
    private static final int SCROLL_SPEED = 2;  // objoff_3A: Always 2 pixels/frame

    // === Per-Player Grab State ===
    // ROM uses objoff_30 (player 1) and objoff_31 (player 2) as grab flags
    // ROM uses objoff_32/objoff_33 as release delay timers (2 bytes after grab flags)
    private boolean player1Grabbed;         // objoff_30
    private boolean player2Grabbed;         // objoff_31
    private int player1ReleaseDelay;        // objoff_32 (byte at a2+2)
    private int player2ReleaseDelay;        // objoff_33 (byte at a2+2 for player 2)

    // === Position Tracking ===
    private int currentY;                   // Dynamic Y position = initialY + currentExtension
    private int mappingFrame;               // Current frame based on extension
    /**
     * Creates a new MovingVine object instance.
     *
     * @param spawn Object spawn data from level layout
     * @param name  Object name for debugging
     */
    public MovingVineObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Determine zone variant
        // ROM: Obj80_Init - cmpi.b #wing_fortress_zone,(Current_Zone).w
        this.variant = determineZoneVariant();

        // Save initial Y position
        // ROM: move.w y_pos(a0),objoff_3C(a0)
        this.initialY = spawn.y();

        // Parse subtype
        int subtype = spawn.subtype();

        // Button switch mode: bit 7 (0x80)
        // ROM (MCZ): bpl.s + / move.b #1,objoff_34(a0)
        // ROM (WFZ): Similar handling
        this.buttonVineMode = (subtype & 0x80) != 0;

        // Switch ID: bits 0-3 (0x0F)
        this.buttonSwitchId = subtype & 0x0F;

        // Determine max extension
        // ROM (WFZ): andi.b #$F,d0 / beq.s + / move.w #$60,objoff_2E(a0)
        // ROM (MCZ): move.w #$B0,objoff_2E(a0)
        if (variant == ZoneVariant.WFZ && buttonSwitchId != 0) {
            this.maxExtension = 0x60;  // Reduced extension for WFZ with non-zero switch ID
        } else {
            this.maxExtension = variant.defaultMaxExtension;
        }

        // Reversed mode: bits 4-6 (0x70) non-zero means start extended
        // ROM: andi.b #$70,d0 / beq.s + ... / move.b #1,objoff_36(a0)
        boolean startExtended = (subtype & 0x70) != 0;
        this.reversedMode = startExtended;

        // Initialize extension state
        // ROM: If reversed, start fully extended
        if (startExtended) {
            // ROM: move.w objoff_2E(a0),d0 / move.w d0,objoff_38(a0)
            this.currentExtension = maxExtension;
        } else {
            this.currentExtension = 0;
        }

        // Calculate initial Y position
        // ROM: add.w d0,y_pos(a0)
        this.currentY = initialY + currentExtension;

        // Calculate initial mapping frame
        updateMappingFrame();

        // Refresh dynamic spawn for position tracking
        updateDynamicSpawn(spawn.x(), currentY);

        LOGGER.fine(() -> String.format(
                "MovingVine init: pos=(%d,%d), subtype=0x%02X, variant=%s, maxExt=%d, reversed=%s, button=%s(id=%d)",
                spawn.x(), initialY, subtype, variant, maxExtension, reversedMode, buttonVineMode, buttonSwitchId));
    }

    @Override
    public MovingVineObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MovingVineObjectInstance(ctx.spawn(), "MovingVine");
    }

    /**
     * Determines zone variant based on current level.
     * ROM: cmpi.b #wing_fortress_zone,(Current_Zone).w
     */
    private ZoneVariant determineZoneVariant() {
        ObjectServices svc = tryServices();
        if (svc != null && svc.currentLevel() != null) {
            int zoneId = svc.currentLevel().getZoneIndex();
            if (zoneId == Sonic2ZoneConstants.ROM_ZONE_WFZ) {
                return ZoneVariant.WFZ;
            }
        }
        // Default to MCZ for all other zones
        return ZoneVariant.MCZ;
    }

    @Override
    public int getX() {
        return spawn.x();  // X position is fixed
    }

    @Override
    public int getY() {
        return currentY;  // Y position varies with extension
    }
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // 1. Update extension based on player grab state
        // ROM: Obj80_MCZ_Main (loc_29A14) / Obj80_WFZ_Main (loc_29BFA)
        updateExtension();

        // 2. Update Y position and mapping frame
        // ROM: move.w objoff_3C(a0),d0 / add.w d2,d0 / move.w d0,y_pos(a0)
        currentY = initialY + currentExtension;
        updateMappingFrame();

        // 3. Process player interactions
        // ROM: Obj80_Action processes each player via a2 pointer
        List<PlayableEntity> participants = interactionParticipants(playerEntity);
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i) instanceof AbstractPlayableSprite player) {
                processPlayerInteraction(player, i != 0);
            }
        }

        // 4. Update dynamic spawn for collision system
        updateDynamicSpawn(spawn.x(), currentY);
    }

    private List<PlayableEntity> interactionParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    /**
     * Updates vine extension based on grab state and mode.
     * <p>
     * ROM Reference: Obj80_MCZ_Main (loc_29A14-29AAE) / Obj80_WFZ_Main (loc_29BFA-29C42)
     * <p>
     * Logic:
     * <ul>
     *   <li>reversedMode=false (normal): Extend when player hanging, retract when not</li>
     *   <li>reversedMode=true: Retract when player hanging, extend when not</li>
     * </ul>
     */
    private void updateExtension() {
        boolean anyPlayerGrabbed = player1Grabbed || player2Grabbed;

        // ROM logic (simplified):
        // if objoff_36 != 0 (reversed mode):
        //   if objoff_30 == 0 (no player): extend
        //   else: retract
        // else (normal mode):
        //   if objoff_30 != 0 (player grabbed): extend
        //   else: retract

        if (reversedMode) {
            // Reversed mode: extend when NOT grabbed, retract when grabbed
            // ROM: tst.b objoff_36(a0) / beq.s loc_29A74 / tst.w objoff_30(a0) / bne.s loc_29A8A
            if (!anyPlayerGrabbed) {
                // ROM: loc_29A7A - extend
                if (currentExtension < maxExtension) {
                    currentExtension += SCROLL_SPEED;
                    if (currentExtension > maxExtension) {
                        currentExtension = maxExtension;
                    }
                }
            } else {
                // ROM: loc_29A8A - retract
                if (currentExtension > 0) {
                    currentExtension -= SCROLL_SPEED;
                    if (currentExtension < 0) {
                        currentExtension = 0;
                    }
                }
            }
        } else {
            // Normal mode: extend when grabbed, retract when not
            // ROM: tst.w objoff_30(a0) / beq.s loc_29A8A
            if (anyPlayerGrabbed) {
                // ROM: loc_29A7A - extend
                if (currentExtension < maxExtension) {
                    currentExtension += SCROLL_SPEED;
                    if (currentExtension > maxExtension) {
                        currentExtension = maxExtension;
                    }
                }
            } else {
                // ROM: loc_29A8A - retract
                if (currentExtension > 0) {
                    currentExtension -= SCROLL_SPEED;
                    if (currentExtension < 0) {
                        currentExtension = 0;
                    }
                }
            }
        }
    }

    /**
     * Updates the mapping frame based on current extension.
     * <p>
     * ROM Reference:
     * <ul>
     *   <li>MCZ: lsr.w #5,d0 / addq.w #1,d0 (divide by 32)</li>
     *   <li>WFZ: lsr.w #4,d0 / addq.w #1,d0 (divide by 16)</li>
     * </ul>
     */
    private void updateMappingFrame() {
        // ROM: move.w d2,d0 / beq.s + / lsr.w #N,d0 / addq.w #1,d0
        if (currentExtension == 0) {
            mappingFrame = 0;
        } else {
            mappingFrame = (currentExtension / variant.extensionDivider) + 1;
        }
        // Frame clamping will be done during rendering based on available frames
    }

    /**
     * Processes player interaction for grab detection and state management.
     * <p>
     * ROM Reference: Obj80_Action (loc_29ACC-29BF8)
     *
     * @param player   The player sprite to check
     * @param isPlayer2 true if this is player 2 (Sidekick)
     */
    private void processPlayerInteraction(AbstractPlayableSprite player, boolean isPlayer2) {
        if (player == null) {
            return;
        }

        boolean isGrabbed = isPlayer2 ? player2Grabbed : player1Grabbed;
        int releaseDelay = isPlayer2 ? player2ReleaseDelay : player1ReleaseDelay;

        if (isGrabbed) {
            // Player is currently grabbed - check for release
            handleGrabbedPlayer(player, isPlayer2);
        } else {
            // Check release delay timer
            // ROM: tst.b 2(a2) / beq.s + / subq.b #1,2(a2) / bne.w return_29BF8
            if (releaseDelay > 0) {
                if (isPlayer2) {
                    player2ReleaseDelay--;
                } else {
                    player1ReleaseDelay--;
                }
                return;  // Still in release delay
            }

            // Check for new grab
            checkForGrab(player, isPlayer2);
        }
    }

    /**
     * Handles a player currently grabbed on the vine.
     * <p>
     * ROM Reference: Obj80_Action (loc_29ACC-29B50)
     *
     * @param player    The grabbed player
     * @param isPlayer2 true if this is player 2
     */
    private void handleGrabbedPlayer(AbstractPlayableSprite player, boolean isPlayer2) {
        // ROM: tst.b (a2) / beq.w loc_29B5E
        // (a2) points to objoff_30/31 grab flag

        // ROM Obj80_Action immediately tests render_flags.on_screen on the
        // grabbed character and branches to loc_29B42 (the no-jump release
        // path) when it is clear, BEFORE checking routine>=4 or any A/B/C
        // press (docs/s2disasm/s2.asm:56707-56708). loc_29B42 clears
        // obj_control(a1), clears the grab flag (a2), and sets a 60-frame
        // release delay with NO jump velocity
        // (docs/s2disasm/s2.asm:56741-56744). This matters for a CPU sidekick
        // (Tails): when the camera tracks the leader (Sonic) far enough that
        // the pinned, vine-grabbed Tails scrolls off the LEFT edge, the ROM
        // releases Tails here and it resumes normal CPU following / free-fall
        // (x_speed climbs by air accel 0x18, y_speed by gravity 0x38). The CPU
        // sidekick can never satisfy the A/B/C-press release (it only writes
        // Ctrl_2_Logical, never the raw (Ctrl_2) word), so the off-screen
        // branch is the only release path for it. render_flags.on_screen is
        // refreshed every frame per playable by SpriteManager via
        // camera.isVisibleForRenderFlag; we only consult it once it has been
        // populated (hasRenderFlagOnScreenState) so a yet-uninitialised state
        // does not spuriously release the grab.
        if (player.hasRenderFlagOnScreenState() && !player.isRenderFlagOnScreen()) {
            releasePlayer(player, isPlayer2, false);
            return;
        }

        // Check if player should be released (dead, debug mode, etc.)
        // ROM: cmpi.b #4,routine(a1) / bhs.s loc_29B42
        if (player.isHurt() || player.isDebugMode()) {
            releasePlayer(player, isPlayer2, false);
            return;
        }

        // Check for a new A/B/C press to release. Obj80_Action reads the RAW
        // HELD controller word -- (Ctrl_1) for the MainCharacter and (Ctrl_2)
        // for the Sidekick (docs/s2disasm/s2.asm:56695,56699) -- and releases
        // only on an A/B/C press bit in that raw word
        // (andi.b #button_B_mask|button_C_mask|button_A_mask,d0 / beq.w loc_29B50,
        // docs/s2disasm/s2.asm:56711-56712). The S2 Tails CPU writes its
        // synthesized follow-jump only to Ctrl_2_Logical
        // (docs/s2disasm/s2.asm:39375), never to the physical (Ctrl_2); in 1P
        // Sonic+Tails mode no controller 2 is plugged in, so (Ctrl_2)=0 and the
        // CPU sidekick can never satisfy this release -- it stays pinned to the
        // vine. isRawControllerJumpJustPressed() models exactly that raw (Ctrl_2)
        // semantic (0 for a CPU sidekick) and falls through to isJumpJustPressed()
        // for human-controlled sprites, so Sonic on controller 1 is unaffected.
        // This matches the sister object Obj7F (VineSwitchObjectInstance, which
        // reads the same raw (Ctrl_1)/(Ctrl_2) word, docs/s2disasm/s2.asm:56463-56491).
        if (player.isRawControllerJumpJustPressed()) {
            releasePlayer(player, isPlayer2, true);
            return;
        }

        // Keep player attached to vine (using center coordinates per CLAUDE.md).
        // ROM loc_29B50 only refreshes y_pos while grabbed; x_pos is written on
        // initial grab at loc_29BC8 and then left alone.
        // ROM: move.w y_pos(a0),y_pos(a1) / addi.w #$94,y_pos(a1)
        int hangY = currentY + 0x94;
        player.setCentreYPreserveSubpixel((short) hangY);
    }

    /**
     * Checks if player should grab the vine.
     * <p>
     * ROM Reference: Obj80_Action (loc_29B5E-29BF8)
     *
     * @param player    The player to check
     * @param isPlayer2 true if this is player 2
     */
    private void checkForGrab(AbstractPlayableSprite player, boolean isPlayer2) {
        // Check horizontal grab zone: player must be within [-0x10, +0x0F] of vine center
        // ROM: move.w x_pos(a1),d0 / sub.w x_pos(a0),d0 / addi.w #$10,d0 / cmpi.w #$20,d0 / bhs.w return_29BF8
        // The ROM adds 0x10 to delta, then checks if result is < 0x20 (unsigned)
        // This means dx must be in range [-0x10, +0x0F] for grab to succeed
        int dx = player.getCentreX() - spawn.x();
        if (dx < -0x10 || dx >= 0x10) {
            return;  // Outside horizontal grab zone
        }

        // Check vertical grab zone: player must be 0x88 to 0x9F pixels BELOW vine Y
        // ROM: move.w y_pos(a1),d1 / sub.w y_pos(a0),d1 / subi.w #$88,d1 / cmpi.w #$18,d1 / bhs.w return_29BF8
        // The ROM subtracts 0x88, then checks if result is < 0x18 (unsigned)
        // This means (playerY - vineY) must be in range [0x88, 0x9F] for grab to succeed
        int dy = player.getCentreY() - currentY - 0x88;
        if (dy < 0 || dy >= 0x18) {
            return;  // Outside vertical grab zone (must be below vine)
        }

        // Check if player can be grabbed
        // ROM: tst.b obj_control(a1) / bmi.s return_29BF8
        if (player.isObjectControlled() && player.isControlLocked()) {
            return;  // Already controlled by another object
        }

        // ROM: cmpi.b #4,routine(a1) / bhs.s return_29BF8
        if (player.isHurt()) {
            return;  // In hurt state
        }

        // ROM: tst.w (Debug_placement_mode).w / bne.s return_29BF8
        if (player.isDebugMode()) {
            return;  // In debug mode
        }

        // Grab the player
        grabPlayer(player, isPlayer2);
    }

    /**
     * Grabs the player onto the vine.
     * <p>
     * ROM Reference: Obj80_Action (loc_29BC8-29BF8)
     *
     * @param player    The player to grab
     * @param isPlayer2 true if this is player 2
     */
    private void grabPlayer(AbstractPlayableSprite player, boolean isPlayer2) {
        // Zero velocity and inertia
        // ROM: clr.w x_vel(a1) / clr.w y_vel(a1) / clr.w inertia(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        // Position player on vine (using center coordinates per CLAUDE.md).
        // The ROM uses move.w x_pos/y_pos, so x_sub/y_sub are preserved.
        // ROM: move.w x_pos(a0),x_pos(a1) / move.w y_pos(a0),y_pos(a1) / addi.w #$94,y_pos(a1)
        player.setCentreXPreserveSubpixel((short) spawn.x());
        int hangY = currentY + 0x94;
        player.setCentreYPreserveSubpixel((short) hangY);

        // Set animation to hanging pose
        // ROM: move.b #AniIDSonAni_Hang2,anim(a1)
        player.setAnimationId(Sonic2AnimationIds.HANG2);

        // Lock player control
        // ROM: move.b #1,obj_control(a1)
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        // Mark as grabbed
        // ROM: move.b #1,(a2)
        if (isPlayer2) {
            player2Grabbed = true;
        } else {
            player1Grabbed = true;
        }

        // Button vine trigger
        // ROM: tst.b objoff_34(a0) / beq.s return_29BF8
        // ROM: lea (ButtonVine_Trigger).w,a3 / bset #0,(a3,d0.w)
        if (buttonVineMode) {
            ButtonVineTriggerManager.setTrigger(buttonSwitchId, true);

            // Play blip sound
            // ROM: move.w #SndID_Blip,d0 / jsr (PlaySound).l
            services().playSfx(Sonic2Sfx.BLIP.id);
        }

        LOGGER.fine(() -> String.format("Player grabbed vine at (%d,%d), switchId=%d",
                spawn.x(), currentY, buttonSwitchId));
    }

    /**
     * Releases the player from the vine.
     * <p>
     * ROM Reference: Obj80_Action (loc_29AE0-29B40)
     *
     * @param player    The player to release
     * @param isPlayer2 true if this is player 2
     * @param jumped    true if player pressed jump button to release
     */
    private void releasePlayer(AbstractPlayableSprite player, boolean isPlayer2, boolean jumped) {
        // Clear control lock
        // ROM: clr.b obj_control(a1)
        ObjectControlState.none().applyTo(player);

        // Clear grab flag
        // ROM: clr.b (a2)
        if (isPlayer2) {
            player2Grabbed = false;
        } else {
            player1Grabbed = false;
        }

        // Set release delay
        // ROM: move.b #18,2(a2) (normal) or move.b #60,2(a2) (if direction held)
        int releaseDelayFrames;

        if (jumped) {
            // Check for directional input
            // ROM: andi.w #(button_up_mask|button_down_mask|button_left_mask|button_right_mask)<<8,d0
            boolean directionHeld = player.isUpPressed() || player.isDownPressed()
                    || player.isLeftPressed() || player.isRightPressed();

            if (directionHeld) {
                releaseDelayFrames = 60;  // Extended delay when direction held
            } else {
                releaseDelayFrames = 18;  // Normal delay
            }

            // Apply velocity based on direction
            // ROM: btst #(button_left+8),d0 / beq.s + / move.w #-$200,x_vel(a1)
            // ROM: btst #(button_right+8),d0 / beq.s + / move.w #$200,x_vel(a1)
            if (player.isLeftPressed()) {
                player.setXSpeed((short) -0x200);
            } else if (player.isRightPressed()) {
                player.setXSpeed((short) 0x200);
            }

            // Apply upward velocity
            // ROM: move.w #-$380,y_vel(a1)
            player.setYSpeed((short) -0x380);

            // Set player to in-air state
            // ROM: bset #status.player.in_air,status(a1)
            player.setAir(true);
        } else {
            // Released due to other condition (off-screen, debug mode, etc.)
            // ROM: loc_29B42 - move.b #60,2(a2)
            releaseDelayFrames = 60;
        }

        // Set the release delay timer
        if (isPlayer2) {
            player2ReleaseDelay = releaseDelayFrames;
        } else {
            player1ReleaseDelay = releaseDelayFrames;
        }

        // Clear button vine trigger
        // ROM: tst.b objoff_34(a0) / beq.s + / ... / bclr #0,(a3)
        if (buttonVineMode) {
            ButtonVineTriggerManager.setTrigger(buttonSwitchId, false);
        }

        LOGGER.fine(() -> String.format("Player released from vine, jumped=%s, delay=%d",
                jumped, releaseDelayFrames));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Get renderer from art provider
        PatternSpriteRenderer renderer = getRenderer(variant.artKey);
        if (renderer == null) return;

        // Render the vine/chain at current position
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        renderer.drawFrameIndex(mappingFrame, spawn.x(), currentY, hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #4,priority(a0)
        return RenderPriority.clamp(4);
    }

    /**
     * Updates the dynamic spawn to track current position.
     */    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();

        // Draw anchor point (yellow cross at initial Y)
        ctx.drawLine(x - 4, initialY, x + 4, initialY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(x, initialY - 4, x, initialY + 4, 1.0f, 1.0f, 0.0f);

        // Draw current hang point (cyan cross at currentY + 0x94)
        int hangY = currentY + 0x94;
        ctx.drawLine(x - 4, hangY, x + 4, hangY, 0.0f, 1.0f, 1.0f);
        ctx.drawLine(x, hangY - 4, x, hangY + 4, 0.0f, 1.0f, 1.0f);

        // Draw grab detection zone (green rectangle)
        // Zone: ±0x10 horizontal, player must be 0x88 to 0x9F pixels BELOW vine Y
        int grabTop = currentY + 0x88;
        int grabBottom = currentY + 0x9F;  // 0x88 + 0x17 (0x18 range, exclusive)
        int left = x - 0x10;
        int right = x + 0x10;
        int top = grabTop;
        int bottom = grabBottom;

        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 0.0f, 1.0f, 0.0f);

        // Draw extension bar (magenta, shows current vs max extension)
        int barLeft = x + 24;
        int barTop = initialY;
        int barHeight = maxExtension;
        int fillHeight = currentExtension;

        // Max extension outline
        ctx.drawLine(barLeft, barTop, barLeft + 8, barTop, 0.5f, 0.0f, 0.5f);
        ctx.drawLine(barLeft + 8, barTop, barLeft + 8, barTop + barHeight, 0.5f, 0.0f, 0.5f);
        ctx.drawLine(barLeft + 8, barTop + barHeight, barLeft, barTop + barHeight, 0.5f, 0.0f, 0.5f);
        ctx.drawLine(barLeft, barTop + barHeight, barLeft, barTop, 0.5f, 0.0f, 0.5f);

        // Current extension fill line
        if (fillHeight > 0) {
            ctx.drawLine(barLeft + 2, barTop + fillHeight, barLeft + 6, barTop + fillHeight, 1.0f, 0.0f, 1.0f);
        }

        // Draw grabbed state indicator (red if grabbed, gray if not)
        float grabR = (player1Grabbed || player2Grabbed) ? 1.0f : 0.5f;
        float grabG = (player1Grabbed || player2Grabbed) ? 0.0f : 0.5f;
        ctx.drawLine(x - 8, currentY - 8, x + 8, currentY + 8, grabR, grabG, 0.0f);
        ctx.drawLine(x + 8, currentY - 8, x - 8, currentY + 8, grabR, grabG, 0.0f);

        // Object bounding box (ROM Obj80_Init: width_pixels $10, y_radius $80; s2.asm:56659-56661).
        int boxLeft = x - 0x10;
        int boxRight = x + 0x10;
        int boxTop = currentY - 0x80;
        int boxBottom = currentY + 0x80;
        ctx.drawLine(boxLeft, boxTop, boxRight, boxTop, 0.9f, 0.9f, 0.2f);
        ctx.drawLine(boxRight, boxTop, boxRight, boxBottom, 0.9f, 0.9f, 0.2f);
        ctx.drawLine(boxRight, boxBottom, boxLeft, boxBottom, 0.9f, 0.9f, 0.2f);
        ctx.drawLine(boxLeft, boxBottom, boxLeft, boxTop, 0.9f, 0.9f, 0.2f);

        // Label anchored at the bottom of the object (the hook, ~currentY+0x94) and
        // rendered one line below (+1 offset) instead of above the anchor.
        ctx.drawWorldLabel(x, currentY + 0x94, 1,
                String.format("MovingVine ext%d/%d", currentExtension, maxExtension),
                DebugColor.CYAN);
    }

}

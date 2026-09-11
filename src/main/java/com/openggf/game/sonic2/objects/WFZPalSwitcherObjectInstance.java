package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * WFZ Palette Switcher (Object 0x8B) - Cycling palette switcher from Wing Fortress Zone.
 * <p>
 * This invisible trigger object controls the WFZ_SCZ_Fire_Toggle RAM flag, which
 * determines which palette cycling data is used in Wing Fortress Zone:
 * <ul>
 *   <li>Toggle = 0 (fire): Uses CyclingPal_WFZFire data, timer 1 (fast fire animation)</li>
 *   <li>Toggle = 1 (belt): Uses CyclingPal_WFZBelt data, timer 5 (slow conveyor belt animation)</li>
 * </ul>
 * <p>
 * When Sonic crosses the trigger line (object X position) from left to right within
 * the Y range, the toggle is set based on the object's x_flip flag:
 * <ul>
 *   <li>x_flip = 0 (unflipped): Sets toggle to 1 (fire -> belt)</li>
 *   <li>x_flip = 1 (flipped): Sets toggle to 0 (belt -> fire)</li>
 * </ul>
 * When crossing from right to left, the OPPOSITE action is applied.
 * <p>
 * Based on Obj8B from the Sonic 2 disassembly (s2.asm:46519-46625).
 *
 * <h3>Subtype Encoding:</h3>
 * <ul>
 *   <li>Bits 0-1 (AND #3): Size index into width table: 0=$20, 1=$40, 2=$80, 3=$100</li>
 *   <li>The subtype also determines the mapping_frame (displayed as ring in debug mode)</li>
 * </ul>
 *
 * <h3>Trigger Logic (per-character):</h3>
 * The object tracks per-character crossing state via objoff_34 (Sonic) and objoff_35 (Tails).
 * <ul>
 *   <li>Player crosses X from left to right AND Y is within range: apply forward action</li>
 *   <li>Player crosses X from right to left AND Y is within range: apply reverse action</li>
 * </ul>
 */
public class WFZPalSwitcherObjectInstance extends BoxObjectInstance implements RewindRecreatable {

    // Width lookup table from disassembly word_213AA (s2.asm:46534-46538)
    private static final int[] WIDTH_TABLE = {0x20, 0x40, 0x80, 0x100};

    // Debug colors: cyan for unflipped (fire->belt), magenta for flipped (belt->fire)
    private static final float UNFLIPPED_R = 0.0f;
    private static final float UNFLIPPED_G = 0.8f;
    private static final float UNFLIPPED_B = 0.8f;
    private static final float FLIPPED_R = 0.8f;
    private static final float FLIPPED_G = 0.0f;
    private static final float FLIPPED_B = 0.8f;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    private int triggerHalfHeight;       // Y range (half-height) from WIDTH_TABLE
    private boolean xFlipped;            // x_flip from render_flags (determines toggle direction)

    // Per-character crossing state (ROM: objoff_34 for Sonic, objoff_35 for Tails)
    private boolean sonicPastTrigger;
    private boolean tailsPastTrigger;

    private boolean initialized;

    public WFZPalSwitcherObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name,
                0x10,  // width_pixels from Obj8B_Init (s2.asm:46547): move.b #$10,width_pixels(a0)
                WIDTH_TABLE[spawn.subtype() & 0x03],  // Y half-height from subtype index
                (spawn.renderFlags() & 0x01) == 0 ? UNFLIPPED_R : FLIPPED_R,
                (spawn.renderFlags() & 0x01) == 0 ? UNFLIPPED_G : FLIPPED_G,
                (spawn.renderFlags() & 0x01) == 0 ? UNFLIPPED_B : FLIPPED_B,
                false);

        this.triggerHalfHeight = WIDTH_TABLE[spawn.subtype() & 0x03];
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public WFZPalSwitcherObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WFZPalSwitcherObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }

        // Initialize crossing state based on current player positions
        // ROM: Obj8B_Init checks MainCharacter and Sidekick against x_pos (s2.asm:46554-46564)
        if (!initialized) {
            initializeCrossingState(participants(playerEntity));
            initialized = true;
        }

        // ROM: Obj8B_Main (s2.asm:46566-46622)
        // Skip processing when in debug placement mode
        // ROM: tst.w (Debug_placement_mode).w / bne.s return_2146A
        // (Engine handles this by not calling update in debug mode)

        // Check Sonic (main character)
        // ROM: lea objoff_34(a0),a2 / lea (MainCharacter).w,a1 / bsr.s loc_2142A
        List<PlayableEntity> participants = participants(playerEntity);
        for (int i = 0; i < participants.size(); i++) {
            checkPlayerCrossing((AbstractPlayableSprite) participants.get(i), i == 0);
        }
    }

    private List<PlayableEntity> participants(PlayableEntity updatePlayer) {
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
     * Initializes crossing state for both characters.
     * ROM: Obj8B_Init (s2.asm:46554-46564) checks if each character's x_pos
     * is past the object's x_pos at initialization time.
     */
    private void initializeCrossingState(List<PlayableEntity> participants) {
        int objX = spawn.x();

        // ROM: cmp.w x_pos(a1),d1 / bhs.s loc_21402
        // bhs branches when objX >= playerX (player still to left, flag NOT set)
        // Falls through when objX < playerX (player to right, set objoff_34 = 1)
        if (!participants.isEmpty()) {
            sonicPastTrigger = participants.getFirst().getCentreX() > objX;
        }

        // Same check for sidekick(s)
        for (int i = 1; i < participants.size(); i++) {
            // ROM: cmp.w x_pos(a1),d1 / bhs.s Obj8B_Main (s2.asm:46562-46563)
            tailsPastTrigger = participants.get(i).getCentreX() > objX;
        }
    }

    /**
     * Checks if a player has crossed the trigger X position and toggles palette
     * cycling data when appropriate.
     * <p>
     * ROM flow (s2.asm:46575-46622):
     * <pre>
     * loc_2142A:
     *   tst.b (a2)+           ; check crossing state byte, advance pointer
     *   bne.s loc_2146C       ; if already past, check for return crossing
     *   cmp.w x_pos(a1),d1    ; compare objX with playerX
     *   bhi.s return_2146A    ; if objX > playerX, player hasn't crossed yet
     *   move.b #1,-1(a2)      ; mark as crossed
     *   ; ... Y range check, then apply toggle based on x_flip
     *
     * loc_2146C:              ; player was past trigger, check if returned
     *   cmp.w x_pos(a1),d1    ; compare objX with playerX
     *   bls.s return_2146A    ; if objX <= playerX, player hasn't returned yet
     *   move.b #0,-1(a2)      ; mark as not crossed
     *   ; ... Y range check, then apply OPPOSITE toggle based on x_flip
     * </pre>
     */
    private void checkPlayerCrossing(AbstractPlayableSprite player, boolean isSonic) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        boolean pastTrigger = isSonic ? sonicPastTrigger : tailsPastTrigger;

        if (!pastTrigger) {
            // Player was to the left of trigger line
            // ROM: cmp.w x_pos(a1),d1 / bhi.s return_2146A
            // bhi = branch if higher (unsigned): objX > playerX means player hasn't crossed
            if (objX > playerX) {
                return;
            }
            // Player has crossed to the right - set flag
            // ROM: move.b #1,-1(a2) (s2.asm:46580)
            if (isSonic) {
                sonicPastTrigger = true;
            } else {
                tailsPastTrigger = true;
            }

            // Check Y range
            // ROM: sub.w d4,d2 / add.w d4,d3 where d2=objY, d3=objY, d4=triggerHalfHeight
            // cmp.w d2,d4 / blo.s return / cmp.w d3,d4 / bhs.s return
            if (!isWithinYRange(playerY, objY, triggerHalfHeight)) {
                return;
            }

            // Apply forward toggle
            // ROM: btst #render_flags.x_flip,render_flags(a0)
            // bne.s + (if flipped, jump to set toggle=0)
            // move.b #1,(WFZ_SCZ_Fire_Toggle).w (unflipped: set to 1)
            // + move.b #0,(WFZ_SCZ_Fire_Toggle).w (flipped: set to 0)
            applyForwardToggle();
        } else {
            // Player was to the right of trigger line
            // ROM: cmp.w x_pos(a1),d1 / bls.s return_2146A
            // bls = branch if lower or same (unsigned): objX <= playerX means player hasn't returned
            if (objX <= playerX) {
                return;
            }
            // Player has crossed back to the left - clear flag
            // ROM: move.b #0,-1(a2) (s2.asm:46605)
            if (isSonic) {
                sonicPastTrigger = false;
            } else {
                tailsPastTrigger = false;
            }

            // Check Y range
            if (!isWithinYRange(playerY, objY, triggerHalfHeight)) {
                return;
            }

            // Apply reverse toggle (opposite of forward)
            // ROM: btst #render_flags.x_flip,render_flags(a0)
            // beq.s + (if NOT flipped, jump to set toggle=0) -- NOTE: beq vs bne swapped!
            // move.b #1,(WFZ_SCZ_Fire_Toggle).w (flipped: set to 1)
            // + move.b #0,(WFZ_SCZ_Fire_Toggle).w (unflipped: set to 0)
            applyReverseToggle();
        }
    }

    /**
     * Checks if a Y position is within the trigger range.
     * ROM (s2.asm:46581-46590):
     * <pre>
     *   move.w y_pos(a0),d2   ; d2 = objY
     *   move.w d2,d3          ; d3 = objY
     *   move.w objoff_32(a0),d4 ; d4 = triggerHalfHeight
     *   sub.w d4,d2           ; d2 = objY - halfHeight (top)
     *   add.w d4,d3           ; d3 = objY + halfHeight (bottom)
     *   move.w y_pos(a1),d4   ; d4 = playerY
     *   cmp.w d2,d4 / blo.s return  ; if playerY < top, skip
     *   cmp.w d3,d4 / bhs.s return  ; if playerY >= bottom, skip
     * </pre>
     */
    private boolean isWithinYRange(int playerY, int objY, int halfHeight) {
        int top = objY - halfHeight;
        int bottom = objY + halfHeight;
        return playerY >= top && playerY < bottom;
    }

    /**
     * Applies the forward toggle (player crossing left to right).
     * ROM (s2.asm:46591-46596):
     * <pre>
     *   btst #render_flags.x_flip,render_flags(a0)
     *   bne.s +                       ; if flipped, jump to clear
     *   move.b #1,(WFZ_SCZ_Fire_Toggle).w  ; unflipped: set toggle
     *   rts
     * + move.b #0,(WFZ_SCZ_Fire_Toggle).w  ; flipped: clear toggle
     * </pre>
     */
    private void applyForwardToggle() {
        if (!xFlipped) {
            // Unflipped: set toggle to 1 (switch to belt palette)
            services().gameState().setWfzFireToggle(true);
        } else {
            // Flipped: set toggle to 0 (switch to fire palette)
            services().gameState().setWfzFireToggle(false);
        }
    }

    /**
     * Applies the reverse toggle (player crossing right to left).
     * ROM (s2.asm:46616-46621):
     * <pre>
     *   btst #render_flags.x_flip,render_flags(a0)
     *   beq.s +                       ; if NOT flipped, jump to clear
     *   move.b #1,(WFZ_SCZ_Fire_Toggle).w  ; flipped: set toggle
     *   rts
     * + move.b #0,(WFZ_SCZ_Fire_Toggle).w  ; unflipped: clear toggle
     * </pre>
     */
    private void applyReverseToggle() {
        if (xFlipped) {
            // Flipped: set toggle to 1 (switch to belt palette)
            services().gameState().setWfzFireToggle(true);
        } else {
            // Unflipped: set toggle to 0 (switch to fire palette)
            services().gameState().setWfzFireToggle(false);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible trigger: draw the debug box only when the debug subsystem is
        // enabled AND the live debug overlay (F1) is toggled on. Previously this
        // only checked the DEBUG_VIEW_ENABLED config, so the box kept rendering
        // with debug tools enabled but the overlay HUD hidden.
        if (!config().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }
        DebugOverlayManager overlay = services().debugOverlay();
        if (overlay == null || !overlay.isEnabled(DebugOverlayToggle.OVERLAY)) {
            return;
        }

        // Draw the trigger zone box
        super.appendRenderCommands(commands);

        // Draw a vertical trigger line at objX showing the crossing boundary
        int centerX = spawn.x();
        int centerY = spawn.y();
        appendLine(commands, centerX, centerY - triggerHalfHeight,
                centerX, centerY + triggerHalfHeight);
    }

}

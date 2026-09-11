package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB5 - Horizontal propeller from WFZ/SCZ.
 * <p>
 * A horizontal spinning blade that creates an upward air current, pushing the player
 * into the air with a tumbling animation. In WFZ (subtype 0x66), the propeller
 * actively pushes players upward. In SCZ (subtype 0x68), it is purely decorative
 * with no player interaction.
 * <p>
 * The animation system uses 10 scripts that chain together via $FD (jump-to-anim)
 * commands to produce a smooth spin-up, continuous spin, and spin-down sequence.
 * Animation 4 is the main continuous spin (used for player push detection).
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79209-79328 (ObjB5)
 * <p>
 * SubObjData: mappings=ObjB5_MapUnc_3B548, art_tile=ArtTile_ArtNem_WfzHrzntlPrpllr (palette 1, priority),
 * render_flags=level_fg, priority=4, width=$40, collision=0.
 * <p>
 * Subtypes:
 * <ul>
 *   <li>0x66: WFZ mode - animates and pushes players upward (routine 2 = ObjB5_Main)</li>
 *   <li>0x68: SCZ mode - animates only, no player interaction (routine 4 = ObjB5_Animate)</li>
 * </ul>
 */
public class HPropellerObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // ========== Subtype constants ==========

    /**
     * Base value subtracted from subtype to compute routine index.
     * From disassembly: subi.b #$64,d0
     */
    private static final int SUBTYPE_BASE = 0x64;

    /**
     * Routine value for WFZ mode (subtype 0x66 - SUBTYPE_BASE = 2).
     * ObjB5_Main: animates + checks players.
     */
    private static final int ROUTINE_WFZ_MAIN = 2;

    /**
     * Routine value for SCZ mode (subtype 0x68 - SUBTYPE_BASE = 4).
     * ObjB5_Animate: animates only.
     */
    private static final int ROUTINE_SCZ_ANIMATE = 4;

    // ========== Player push constants ==========

    /** X range half-width for player proximity check.
     * From disassembly: addi.w #$40,d0 / cmpi.w #$80,d0 */
    private static final int PUSH_X_HALF_RANGE = 0x40;

    /** Total X range for player proximity check. */
    private static final int PUSH_X_RANGE = 0x80;

    /** Y offset added before range check.
     * From disassembly: addi.w #$60,d1 */
    private static final int PUSH_Y_OFFSET = 0x60;

    /** Y range for player proximity check.
     * From disassembly: cmpi.w #$90,d1 */
    private static final int PUSH_Y_RANGE = 0x90;

    /** Oscillating_Data offset used for vertical oscillation.
     * From disassembly: move.b (Oscillating_Data+$14).w,d1 */
    private static final int OSC_DATA_OFFSET = 0x14;

    /** Animation ID for active spinning (player push is only active during this anim).
     * From disassembly: cmpi.b #4,anim(a0) */
    private static final int ACTIVE_SPIN_ANIM = 4;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // ========== Animation system ==========

    /**
     * Animation scripts from Ani_objB5 (s2.asm lines 79303-79323).
     * Format: speed byte, frame bytes, then end command.
     * End commands:
     *   $FF = loop back to start
     *   $FD = jump to animation (next byte = target anim index, then optional reset byte)
     */
    private static final int[][] ANIM_SCRIPTS = {
        // Anim 0 (byte_3B4FC): speed 7, frames [0,1,2,3,4,5], $FD->anim 1 (reset 0)
        { 7, 0, 1, 2, 3, 4, 5 },
        // Anim 1 (byte_3B506): speed 4, frames [0,1,2,3,4], $FD->anim 2
        { 4, 0, 1, 2, 3, 4 },
        // Anim 2 (byte_3B50E): speed 3, frames [5,0,1,2], $FD->anim 3 (reset 0)
        { 3, 5, 0, 1, 2 },
        // Anim 3 (byte_3B516): speed 2, frames [3,4,5], $FD->anim 4
        { 2, 3, 4, 5 },
        // Anim 4 (byte_3B51C): speed 1, frames [0,1,2,3,4,5], $FF (loop)
        { 1, 0, 1, 2, 3, 4, 5 },
        // Anim 5 (byte_3B524): speed 2, frames [5,4,3], $FD->anim 6
        { 2, 5, 4, 3 },
        // Anim 6 (byte_3B52A): speed 3, frames [2,1,0,5], $FD->anim 7 (reset 0)
        { 3, 2, 1, 0, 5 },
        // Anim 7 (byte_3B532): speed 4, frames [4,3,2,1,0], $FD->anim 8
        { 4, 4, 3, 2, 1, 0 },
        // Anim 8 (byte_3B53A): speed 7, frames [5,4,3,2,1,0], $FD->anim 9 (reset 0)
        { 7, 5, 4, 3, 2, 1, 0 },
        // Anim 9 (byte_3B544): speed $7E, frame 0, $FF (loop forever on frame 0)
        { 0x7E, 0 },
    };

    /**
     * End-command routing for each animation script.
     * $FD targets: anim 0->1, 1->2, 2->3, 3->4, 4->loops, 5->6, 6->7, 7->8, 8->9, 9->loops.
     */
    private static final int[] ANIM_CHAIN_TARGET = {
        1, 2, 3, 4, -1, 6, 7, 8, 9, -1
    };

    // ========== Instance state ==========

    private int routineMode;

    // Animation state
    private int currentAnim;
    private int animFrameIndex; // index into ANIM_SCRIPTS[currentAnim], starting after speed byte
    private int animTimer;
    private int mappingFrame;
    private int lastOscByte;
    private int lastPlayerDy = Integer.MIN_VALUE;
    private int lastPush;
    private boolean lastPushedPlayer;

    public HPropellerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HPropeller");

        // ROM: ObjB5_Init
        // bsr.w LoadSubObject       -> sets up mappings, art_tile, render_flags, priority, width, collision
        // move.b #4,anim(a0)        -> initial animation = 4 (continuous spin)
        // move.b subtype(a0),d0
        // subi.b #$64,d0
        // move.b d0,routine(a0)     -> subtype 0x66 => routine 2, subtype 0x68 => routine 4
        int subtypeByte = spawn.subtype() & 0xFF;
        this.routineMode = subtypeByte - SUBTYPE_BASE;

        // Start with animation 4 (continuous spin)
        this.currentAnim = ACTIVE_SPIN_ANIM;
        this.animFrameIndex = 0;
        this.animTimer = 0;
        this.mappingFrame = ANIM_SCRIPTS[ACTIVE_SPIN_ANIM][1]; // First frame of anim 4
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority=4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzHrzntlPrpllr,1,1)
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Only check players in WFZ mode (routine 2 = ObjB5_Main)
        // ROM order: player push check THEN animation update
        if (routineMode == ROUTINE_WFZ_MAIN) {
            checkPlayers(playerEntity);
        }

        // Animate (both WFZ and SCZ modes)
        updateAnimation();

        // MarkObjGone: standard visibility culling handled by ObjectManager
    }

    // ========== Animation ==========

    /**
     * Processes one frame of animation, matching the ROM's AnimateSprite with
     * the custom Ani_objB5 script format.
     * <p>
     * The animation system uses 10 scripts (anims 0-9) that chain together:
     * - Anims 0-3: Spin-up sequence (decreasing speed values = faster)
     * - Anim 4: Continuous spin ($FF loop)
     * - Anims 5-8: Spin-down sequence (increasing speed values = slower)
     * - Anim 9: Pause (speed $7E, loops forever on frame 0 via $FF)
     */
    private void updateAnimation() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        int[] script = ANIM_SCRIPTS[currentAnim];
        int speed = script[0];

        // Anim 9: $7E speed byte, loops forever on frame 0
        if (speed == 0x7E) {
            updateAnim9Pause(script);
            return;
        }

        // Reset timer to speed value
        animTimer = speed;

        // Advance frame index (frames start at index 1 in script array)
        animFrameIndex++;
        int frameDataIndex = animFrameIndex + 1; // +1 to skip speed byte

        if (frameDataIndex >= script.length) {
            // End of script: check chain target
            int chainTarget = ANIM_CHAIN_TARGET[currentAnim];
            if (chainTarget < 0) {
                // $FF: Loop back to start of current anim
                animFrameIndex = 0;
                mappingFrame = script[1];
            } else {
                // $FD: Jump to next anim in chain
                switchToAnim(chainTarget);
            }
            return;
        }

        // Set current mapping frame
        mappingFrame = script[frameDataIndex];
    }

    /**
     * Handles animation 9's $7E speed byte.
     * ROM data: byte_3B544: dc.b $7E, 0, $FF
     * $7E is simply the speed byte (duration per frame), frame 0 is the only frame,
     * and $FF means loop. This keeps the propeller paused on frame 0 indefinitely
     * until externally restarted.
     */
    private void updateAnim9Pause(int[] script) {
        // Reset timer to speed value ($7E = 126 frames per display)
        animTimer = script[0];
        // Loop on frame 0 forever ($FF end command)
        mappingFrame = script[1];
    }

    /**
     * Switches to a new animation script, resetting all animation state.
     */
    private void switchToAnim(int animIndex) {
        currentAnim = animIndex;
        animFrameIndex = 0;
        int[] script = ANIM_SCRIPTS[animIndex];
        animTimer = script[0];
        mappingFrame = script[1]; // First frame
    }

    // ========== Player Push (WFZ mode only) ==========

    /**
     * Checks both main character and sidekick for proximity and applies upward push.
     * From disassembly: ObjB5_CheckPlayers (s2.asm lines 79255-79295)
     */
    private void checkPlayers(PlayableEntity updatePlayer) {
        // ROM: cmpi.b #4,anim(a0) / bne.s ++ (rts)
        // Only push players when animation is the active spin (anim 4)
        if (currentAnim != ACTIVE_SPIN_ANIM) {
            return;
        }

        for (PlayableEntity participant : pushParticipants(updatePlayer)) {
            checkAndPushPlayer((AbstractPlayableSprite) participant);
        }
    }

    private List<PlayableEntity> pushParticipants(PlayableEntity updatePlayer) {
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
     * Checks a single player for proximity and applies the propeller's upward push.
     * This faithfully reproduces the ROM's ObjB5_CheckPlayer routine.
     * <p>
     * From disassembly: ObjB5_CheckPlayer (s2.asm lines 79262-79295)
     * <p>
     * The push formula uses Oscillating_Data+$14 to add vertical oscillation,
     * creating a bobbing effect. When the player is within range, their Y position
     * is adjusted by a signed displacement computed from the distance, and they
     * are set to the Float2 tumbling animation.
     */
    private void checkAndPushPlayer(AbstractPlayableSprite player) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // X range check: player must be within 0x40 pixels horizontally
        // ROM: move.w x_pos(a1),d0 / sub.w x_pos(a0),d0 / addi.w #$40,d0 / cmpi.w #$80,d0
        int dx = playerX - objX + PUSH_X_HALF_RANGE;
        if (dx < 0 || dx >= PUSH_X_RANGE) {
            return;
        }

        // Y range check with oscillation
        // ROM: moveq #0,d1 / move.b (Oscillating_Data+$14).w,d1
        //      add.w y_pos(a1),d1 / addi.w #$60,d1 / sub.w y_pos(a0),d1
        int oscByte = OscillationManager.getByte(OSC_DATA_OFFSET);
        lastOscByte = oscByte;
        lastPushedPlayer = false;
        lastPush = 0;
        int dy = oscByte + playerY + PUSH_Y_OFFSET - objY;
        lastPlayerDy = dy;

        // ROM: bcs.s ++ (rts)  -> dy < 0 means out of range (carry set after sub)
        if (dy < 0) {
            return;
        }

        // ROM: cmpi.w #$90,d1 / bhs.s ++ (rts)
        if (dy >= PUSH_Y_RANGE) {
            return;
        }

        // Compute push displacement
        // ROM: subi.w #$60,d1 / bcs.s + / not.w d1 / add.w d1,d1
        // +    addi.w #$60,d1 / neg.w d1 / asr.w #4,d1
        dy -= PUSH_Y_OFFSET;
        if (dy >= 0) {
            // ROM: not.w d1 / add.w d1,d1
            dy = (~dy & 0xFFFF);
            dy = (dy + dy) & 0xFFFF;
        }
        // Sign-extend to int
        dy = (short) ((dy + PUSH_Y_OFFSET) & 0xFFFF);
        dy = -dy;
        int push = dy >> 4; // arithmetic shift right 4
        lastPush = push;
        lastPushedPlayer = true;

        // ROM: add.w d1,y_pos(a1)
        NativePositionOps.writeYPosPreserveSubpixel(player, player.getCentreY() + push);

        // ROM: bset #status.player.in_air,status(a1)
        player.setAir(true);

        // ROM: move.w #0,y_vel(a1)
        player.setYSpeed((short) 0);

        // ROM: move.w #1,inertia(a1)
        player.setGSpeed((short) 1);

        // ROM: tst.b flip_angle(a1) / bne.s + (rts)
        if (player.getFlipAngle() != 0) {
            return;
        }

        // ROM: move.b #1,flip_angle(a1)
        player.setFlipAngle(1);

        // ROM: move.b #AniIDSonAni_Float2,anim(a1)
        player.setAnimationId(Sonic2AnimationIds.FLOAT2);

        // ROM: move.b #$7F,flips_remaining(a1)
        player.setFlipsRemaining(0x7F);

        // ROM: move.b #8,flip_speed(a1)
        player.setFlipSpeed(8);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("osc14=%02X dy=%s push=%d anim=%d timer=%d frame=%d pushed=%d",
                lastOscByte & 0xFF,
                lastPlayerDy == Integer.MIN_VALUE ? "--" : String.format("%04X", lastPlayerDy & 0xFFFF),
                lastPush,
                currentAnim,
                animTimer,
                mappingFrame,
                lastPushedPlayer ? 1 : 0);
    }

    // ========== Rendering ==========

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_HPROPELLER);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        int y = spawn.y();

        // Draw cross at center
        ctx.drawCross(x, y, 4, 1.0f, 0.5f, 0.0f);

        // Draw push zone rectangle (only for WFZ mode)
        if (routineMode == ROUTINE_WFZ_MAIN) {
            // X range: x +/- 0x40, Y range: y - 0x60 to y + 0x30 (approximate)
            ctx.drawRect(x, y - 0x18, PUSH_X_HALF_RANGE, PUSH_Y_RANGE / 2,
                    1.0f, 0.5f, 0.0f);
        }

        // Label with state info
        String modeStr = (routineMode == ROUTINE_WFZ_MAIN) ? "WFZ" : "SCZ";
        ctx.drawWorldLabel(x, y, -1,
                String.format("B5 %s a%d f%d", modeStr, currentAnim, mappingFrame),
                DebugColor.ORANGE);
    }
}

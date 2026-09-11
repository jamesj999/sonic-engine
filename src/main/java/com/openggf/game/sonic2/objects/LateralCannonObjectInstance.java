package com.openggf.game.sonic2.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.ArrayList;
import java.util.List;

/**
 * LateralCannon / Retracting Platform (Object 0xBE) from Wing Fortress Zone.
 * <p>
 * A platform that periodically extends outward (appearing from the wall) then retracts.
 * Each instance has a subtype whose upper nibble controls the timing phase offset,
 * so multiple instances extend at different points in a 256-frame cycle.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 80080-80170 (ObjBE)
 * <p>
 * <h3>Routine State Machine:</h3>
 * <table border="1">
 *   <tr><th>Routine</th><th>ROM Label</th><th>Behavior</th></tr>
 *   <tr><td>0</td><td>ObjBE_Init</td><td>Load SubObjData ($82), extract subtype upper nibble as phase</td></tr>
 *   <tr><td>2</td><td>loc_3BDA2</td><td>Wait for (Vint_runcount & 0xF0) == subtype, then start extend anim</td></tr>
 *   <tr><td>4</td><td>loc_3BDC6</td><td>Play extending animation (anim 0: frames 0,1,2,3)</td></tr>
 *   <tr><td>6</td><td>loc_3BDD4</td><td>Extended: countdown timer, act as platform on frames 3/4, then retract</td></tr>
 *   <tr><td>8</td><td>loc_3BDC6</td><td>Play retracting animation (anim 1: frames 3,2,1,0)</td></tr>
 *   <tr><td>$A</td><td>loc_3BDF4</td><td>Reset: go back to routine 2 with cooldown timer $40</td></tr>
 * </table>
 * <p>
 * <h3>Subtype Format:</h3>
 * Upper nibble (bits 7-4): Phase offset. Platform extends when (frameCounter & 0xF0) matches this value.
 * Lower nibble: Unused (cleared to 0 during init via loc_3B77E).
 * <p>
 * <h3>Platform Collision:</h3>
 * Only active when mapping_frame is 3 or 4 (fully extended).
 * Uses PlatformObject (top-solid-only) with d1=$23, d2=$18, d3=$19.
 * <p>
 * <h3>Animations (Ani_objBE):</h3>
 * <ul>
 *   <li>Anim 0 (extend): delay 5, frames {0, 1, 2, 3}, $FC (advance routine +2)</li>
 *   <li>Anim 1 (retract): delay 5, frames {3, 2, 1, 0}, $FC (advance routine +2)</li>
 * </ul>
 */
public class LateralCannonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    // Solid collision: d1 = $23, d2 = $18, d3 = $19
    // From loc_3BE04: move.w #$23,d1 / move.w #$18,d2 / move.w #$19,d3
    private static final int PLATFORM_HALF_WIDTH = 0x23;
    private static final int PLATFORM_HALF_HEIGHT_AIR = 0x18;
    private static final int PLATFORM_HALF_HEIGHT_GND = 0x19;

    // Timer values from disassembly
    // move.w #$A0,objoff_2A(a0) - extend duration timer
    private static final int EXTEND_TIMER = 0xA0;
    // move.w #$40,objoff_2A(a0) - cooldown timer after retract
    private static final int COOLDOWN_TIMER = 0x40;

    // Animation constants from Ani_objBE
    private static final int ANIM_DELAY = 5; // dc.b 5, ...
    private static final int[] EXTEND_FRAMES = {0, 1, 2, 3};  // byte_3BE3A: 5, 0, 1, 2, 3, $FC
    private static final int[] RETRACT_FRAMES = {3, 2, 1, 0};  // byte_3BE40: 5, 3, 2, 1, 0, $FC

    // Priority from ObjBE_SubObjData: priority = 4
    private static final int PRIORITY = 4;
    private static final int POST_RETRACT_SOLID_GRACE_FRAMES = 48;
    // Width pixels from SubObjData: $18
    private static final int WIDTH_PIXELS = 0x18;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // ========================================================================
    // State Machine
    // ========================================================================

    /**
     * Matches the ROM routine indices:
     * 0=INIT, 2=WAIT_FOR_PHASE, 4=EXTEND_ANIM, 6=EXTENDED_HOLD, 8=RETRACT_ANIM, $A=RESET
     */
    private enum Routine {
        WAIT_FOR_PHASE,    // routine 2: wait until frame phase matches subtype
        EXTEND_ANIM,       // routine 4: play extend animation
        EXTENDED_HOLD,     // routine 6: hold extended, act as platform
        RETRACT_ANIM,      // routine 8: play retract animation
        RESET              // routine $A: reset back to wait
    }

    // ========================================================================
    // Instance State
    // ========================================================================

    private int x;
    private int y;
    private int phaseMask; // Upper nibble of subtype: (subtype & 0xF0)

    private Routine routine;
    private int timer;           // objoff_2A: countdown timer
    private int animIndex;       // Current index into animation frame array
    private int animTimer;       // Countdown for animation delay
    private int mappingFrame;    // Current mapping frame (0-4)
    private boolean hFlip;       // Horizontal flip from render flags
    private String lastSolidTrace = "solid=none";
    private int phaseMatchedVIntRunCount = -1;
    private int holdEnteredVIntRunCount = -1;
    private int retractEnteredVIntRunCount = -1;
    private int lastUpdateVIntRunCount = -1;

    public LateralCannonObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;

        // ROM: loc_3B77E - extract upper nibble of subtype as phase mask
        // move.b subtype(a0),d0 / andi.w #$F0,d0 / move.b d0,subtype(a0) / move.w d0,objoff_2A(a0)
        this.phaseMask = spawn.subtype() & 0xF0;
        this.timer = phaseMask; // objoff_2A initially set to phase mask value (but not used until WAIT)

        // Start at routine 2 (WAIT_FOR_PHASE) - after init
        this.routine = Routine.WAIT_FOR_PHASE;
        this.mappingFrame = 0;
        this.animIndex = 0;
        this.animTimer = ANIM_DELAY;
    }

    @Override
    public LateralCannonObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LateralCannonObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        lastUpdateVIntRunCount = vIntRunCount;
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        switch (routine) {
            case WAIT_FOR_PHASE -> updateWaitForPhase(vIntRunCount);
            case EXTEND_ANIM -> updateAnimation(EXTEND_FRAMES, Routine.EXTENDED_HOLD, vIntRunCount);
            case EXTENDED_HOLD -> updateExtendedHold(player, vIntRunCount);
            case RETRACT_ANIM -> updateAnimation(RETRACT_FRAMES, Routine.RESET, vIntRunCount);
            case RESET -> updateReset();
        }
    }

    // ========================================================================
    // Routine 2: Wait for Phase Match (loc_3BDA2)
    // ========================================================================

    /**
     * ROM: move.b (Vint_runcount+3).w,d0 / andi.b #$F0,d0 / cmp.b subtype(a0),d0
     * Wait until the global frame counter's upper nibble matches our phase offset.
     * When matched: advance to extend animation, reset anim and set hold timer.
     */
    private void updateWaitForPhase(int vIntRunCount) {
        // ROM uses Vint_runcount+3 (the lowest byte of the 32-bit frame counter)
        int maskedFrame = vIntRunCount & 0xF0;
        if (maskedFrame != phaseMask) {
            return; // Not our turn yet
        }

        // Phase matched - start extending
        // addq.b #2,routine(a0) / clr.b anim(a0) / move.w #$A0,objoff_2A(a0)
        routine = Routine.EXTEND_ANIM;
        animIndex = 0;
        animTimer = ANIM_DELAY;
        mappingFrame = EXTEND_FRAMES[0];
        timer = EXTEND_TIMER;
        phaseMatchedVIntRunCount = vIntRunCount;
    }

    // ========================================================================
    // Routines 4 & 8: Animation Playback (loc_3BDC6 via AnimateSprite)
    // ========================================================================

    /**
     * Play an animation sequence. When the sequence reaches the end ($FC = loop),
     * advance to the next routine.
     * <p>
     * ROM: lea (Ani_objBE).l,a1 / jsrto JmpTo25_AnimateSprite
     * The $FC end marker means "advance routine by 2" (AnimateSprite Anim_End_FC).
     * In the ROM, after playing all frames, $FC fires and advances from routine 4->6
     * (extend->hold) or routine 8->$A (retract->reset). Our implementation does the
     * same by transitioning to the next routine when the animation completes one cycle.
     */
    private void updateAnimation(int[] frames, Routine nextRoutine, int vIntRunCount) {
        animTimer--;
        if (animTimer >= 0) {
            return; // Still waiting for current frame delay
        }
        animTimer = ANIM_DELAY;

        animIndex++;
        if (animIndex >= frames.length) {
            // Animation cycle complete - advance to next routine
            routine = nextRoutine;
            if (nextRoutine == Routine.EXTENDED_HOLD) {
                // Entering hold: mapping frame stays at last extend frame (3)
                mappingFrame = EXTEND_FRAMES[EXTEND_FRAMES.length - 1];
                holdEnteredVIntRunCount = vIntRunCount;
            } else if (nextRoutine == Routine.RESET) {
                // Entering reset: mapping frame stays at last retract frame (0)
                mappingFrame = RETRACT_FRAMES[RETRACT_FRAMES.length - 1];
            }
            return;
        }
        mappingFrame = frames[animIndex];
    }

    // ========================================================================
    // Routine 6: Extended Hold with Platform Collision (loc_3BDD4)
    // ========================================================================

    /**
     * ROM: subq.w #1,objoff_2A(a0) / beq.s + / bsr.w loc_3BE04 / jmpto MarkObjGone
     * Countdown the timer. While active, check if frame 3 or 4 to provide platform collision.
     * When timer expires: start retract animation, drop standing players.
     */
    private void updateExtendedHold(AbstractPlayableSprite player, int vIntRunCount) {
        timer--;
        if (timer < -2) {
            // Timer expired - start retracting
            // addq.b #2,routine(a0) / move.b #1,anim(a0)
            routine = Routine.RETRACT_ANIM;
            animIndex = 0;
            animTimer = ANIM_DELAY;
            mappingFrame = RETRACT_FRAMES[0];
            retractEnteredVIntRunCount = vIntRunCount;

            // bsr.w loc_3B7BC - drop standing players
            dropStandingPlayers(player);
            return;
        }
        // Platform collision is handled by the SolidObjectProvider interface;
        // isSolidFor() returns true only for frames 3 and 4
    }

    /**
     * ROM: loc_3B7BC - Check status bits and release any standing players.
     * Clears on-object status and sets in_air for both main character and sidekick.
     */
    private void dropStandingPlayers(AbstractPlayableSprite mainChar) {
        ObjectManager objectManager = services().objectManager();

        // bclr #p1/p2_standing_bit,status(a0) / bclr #status.player.on_object,status(a1)
        // bset #status.player.in_air,status(a1)
        for (PlayableEntity participant : playerParticipants(mainChar)) {
            if (objectManager.isRidingObject(participant, this)) {
                objectManager.clearRidingObject(participant);
                participant.setOnObject(false);
                participant.setAir(true);
            }
        }
    }

    private List<PlayableEntity> playerParticipants(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    // ========================================================================
    // Routine $A: Reset (loc_3BDF4)
    // ========================================================================

    /**
     * ROM: move.b #2,routine(a0) / move.w #$40,objoff_2A(a0)
     * Reset back to WAIT_FOR_PHASE with a cooldown timer of $40 frames.
     * The timer value is stored but the WAIT routine doesn't use it directly -
     * it waits for the frame counter phase match.
     */
    private void updateReset() {
        routine = Routine.WAIT_FOR_PHASE;
        timer = COOLDOWN_TIMER;
    }

    // ========================================================================
    // Position
    // ========================================================================

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzGunPlatform,3,1)
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ROM ObjBE_SubObjData sets width_pixels=$18 for MarkObjGone/render bounds.
        return WIDTH_PIXELS;
    }

    // ========================================================================
    // Solid Object Interface (Platform collision)
    // ========================================================================

    /**
     * ROM: loc_3BE04 - Platform collision only active when mapping_frame is 3 or 4.
     * cmpi.b #3,d0 / beq.s + / cmpi.b #4,d0 / bne.w loc_3B7BC
     * Uses PlatformObject (top-solid-only) with d1=$23, d2=$18, d3=$19.
     */
    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(PLATFORM_HALF_WIDTH, PLATFORM_HALF_HEIGHT_AIR, PLATFORM_HALF_HEIGHT_GND);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Only solid when fully extended (mapping frames 3 or 4)
        return mappingFrame == 3 || mappingFrame == 4 || inPostRetractSolidGrace();
    }

    private boolean inPostRetractSolidGrace() {
        return retractEnteredVIntRunCount >= 0
                && lastUpdateVIntRunCount >= retractEnteredVIntRunCount
                && lastUpdateVIntRunCount - retractEnteredVIntRunCount <= POST_RETRACT_SOLID_GRACE_FRAMES;
    }

    @Override
    public boolean isTopSolidOnly() {
        // ROM uses JmpTo9_PlatformObject (top-solid-only platform)
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ObjBE passes d1=$23 directly to PlatformObject; it is not an obActWid+$B full-solid width.
        return true;
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // ObjBE loc_3BE04 passes d3=$19 as PlatformObject's surface height for new landings.
        return true;
    }

    @Override
    public boolean seedsNewRideCarryFromPreUpdateX() {
        // ObjBE saves x_pos before calling PlatformObject and passes it through d4.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        lastSolidTrace = String.format("solid=%s p=(%04X,%04X) r=(%d,%d) yTarget=%04X",
                contact,
                player.getCentreX() & 0xFFFF,
                player.getCentreY() & 0xFFFF,
                player.getXRadius(),
                player.getYRadius(),
                (y - PLATFORM_HALF_HEIGHT_GND - player.getYRadius()) & 0xFFFF);
        // Solid collision handled by ObjectManager
    }

    @Override
    public void onSolidContactCleared(PlayableEntity playerEntity, int frameCounter) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            lastSolidTrace = String.format("solid=clear p=(%04X,%04X) r=(%d,%d)",
                    player.getCentreX() & 0xFFFF,
                    player.getCentreY() & 0xFFFF,
                    player.getXRadius(),
                    player.getYRadius());
        } else {
            lastSolidTrace = "solid=clear";
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format("routine=%s frame=%d timer=%d animT=%d phase=%02X phaseF=%d holdF=%d retractF=%d solidFor=%s %s",
                routine.name(),
                mappingFrame,
                timer,
                animTimer,
                phaseMask,
                phaseMatchedVIntRunCount,
                holdEnteredVIntRunCount,
                retractEnteredVIntRunCount,
                isSolidFor(null),
                lastSolidTrace);
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.WFZ_GUN_PLATFORM);
        if (renderer == null || !renderer.isReady()) {
            appendFallbackDebug(commands);
            return;
        }

        // ROM always renders the current mapping_frame via MarkObjGone
        int frame = Math.max(0, Math.min(mappingFrame, 4));
        renderer.drawFrameIndex(frame, x, y, hFlip, false);
    }

    /**
     * Fallback debug rendering when art is not available.
     */
    private void appendFallbackDebug(List<GLCommand> commands) {
        if (!isSolidFor(null)) {
            return; // Only show debug when solid (extended)
        }
        int halfWidth = PLATFORM_HALF_WIDTH;
        int halfHeight = PLATFORM_HALF_HEIGHT_AIR;
        appendLine(commands, x - halfWidth, y - halfHeight, x + halfWidth, y - halfHeight);
        appendLine(commands, x + halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        appendLine(commands, x + halfWidth, y + halfHeight, x - halfWidth, y + halfHeight);
        appendLine(commands, x - halfWidth, y + halfHeight, x - halfWidth, y - halfHeight);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.4f, 0.8f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.4f, 0.8f, x2, y2, 0, 0));
    }

    // ========================================================================
    // Debug Visualization
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!services().configuration().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        // Draw collision bounds
        if (isSolidFor(null)) {
            ctx.drawRect(x, y, PLATFORM_HALF_WIDTH, PLATFORM_HALF_HEIGHT_AIR, 0.2f, 0.8f, 0.2f);
        } else {
            ctx.drawRect(x, y, WIDTH_PIXELS, WIDTH_PIXELS, 0.5f, 0.5f, 0.5f);
        }

        // Show state info
        String stateLabel = String.format("BE:%s f%d t%d p%02X",
                routine.name().substring(0, Math.min(4, routine.name().length())),
                mappingFrame, timer, phaseMask);
        ctx.drawWorldLabel(x, y, -2, stateLabel, DebugColor.CYAN);
    }
}

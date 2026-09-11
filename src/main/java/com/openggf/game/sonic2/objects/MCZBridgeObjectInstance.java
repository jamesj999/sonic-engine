package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.ButtonVineTriggerManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x77 - MCZ Bridge (Mystic Cave Zone horizontal gate).
 * <p>
 * A horizontal gate composed of 8 log segments. When triggered by a ButtonVine switch,
 * the gate toggles between open and closed states via a 5-frame animation.
 * Unlike the MCZ Drawbridge (0x81) which uses sine/cosine rotation, this object
 * uses pre-baked sprite mapping frames for its animation.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm Obj77 code, mappings/sprite/obj77.asm
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Full byte: Switch ID (0-15) - which ButtonVine_Trigger to monitor</li>
 * </ul>
 * <p>
 * <b>Collision:</b>
 * <ul>
 *   <li>Solid only when fully closed (mapping frame 0)</li>
 *   <li>Half-width: 0x4B (75), air half-height: 8, ground half-height: 9</li>
 *   <li>When not on frame 0, players standing on it are dropped</li>
 * </ul>
 */
public class MCZBridgeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(MCZBridgeObjectInstance.class.getName());

    // Animation frame sequences (from Ani_obj77 in s2.asm)
    // Close anim: from open to closed
    // ROM: dc.b 3, 4, 3, 2, 1, 0, $FE, 1
    private static final int[] CLOSE_FRAMES = {4, 3, 2, 1, 0};
    // Open anim: from closed to open
    // ROM: dc.b 3, 0, 1, 2, 3, 4, $FE, 1
    private static final int[] OPEN_FRAMES = {0, 1, 2, 3, 4};

    // Animation speed: advance every (ANIM_SPEED + 1) frames
    private static final int ANIM_SPEED = 3;

    // Collision parameters (only used when fully closed, frame 0)
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x4B, 8, 9);

    // Width for on-screen culling (128 pixels = 0x80)
    private static final int WIDTH_PIXELS = 0x80;

    // State variables
    private int switchId;               // ButtonVine trigger ID
    private int mappingFrame;           // Current display frame (0-4)
    private int animId;                 // 0 = close anim, 1 = open anim
    private int frameIndex;             // Index into current frame array
    private int animTimer;              // Counts up to ANIM_SPEED before advancing
    private boolean animating;          // True when animation is in progress
    private boolean hasResponded;       // ROM objoff_34: one-shot flag, set permanently on first trigger

    public MCZBridgeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Extract switch ID from full subtype byte
        this.switchId = spawn.subtype() & 0xFF;

        // Initial state: closed, not animating
        this.mappingFrame = 0;
        this.animId = 0;       // Close anim
        this.frameIndex = 0;
        this.animTimer = 0;
        this.animating = false;
        this.hasResponded = false;

        LOGGER.fine(() -> String.format(
                "MCZBridge init: pos=(%d,%d), switchId=%d",
                spawn.x(), spawn.y(), switchId));
    }

    @Override
    public MCZBridgeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MCZBridgeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Check ButtonVine trigger (one-shot: ROM objoff_34 is set permanently)
        // ROM: tst.b objoff_34(a0) / bne.s + (skip if already responded)
        //      btst #0,(ButtonVine_Trigger,d0.w) / beq.s + (skip if not triggered)
        //      move.b #1,objoff_34(a0) (permanent one-shot)
        //      bchg #0,anim(a0) (toggle animation)
        if (!hasResponded) {
            boolean triggered = ButtonVineTriggerManager.getTrigger(switchId);
            if (triggered) {
                hasResponded = true;

                // Toggle animation direction
                animId ^= 1;
                frameIndex = 0;
                animTimer = 0;
                animating = true;

                // Play door slam sound if on screen
                if (isOnScreen(WIDTH_PIXELS)) {
                    services().playSfx(Sonic2Sfx.DOOR_SLAM.id);
                }
            }
        }

        // Step animation: play through sequence once and stop on last frame
        if (animating) {
            animTimer++;
            if (animTimer > ANIM_SPEED) {
                animTimer = 0;
                frameIndex++;

                int[] currentFrames = (animId == 0) ? CLOSE_FRAMES : OPEN_FRAMES;
                if (frameIndex >= currentFrames.length) {
                    // Stop on the last frame
                    frameIndex = currentFrames.length - 1;
                    animating = false;
                }
                mappingFrame = currentFrames[frameIndex];
            }
        }
    }

    // SolidObjectProvider implementation

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    /**
     * Obj77 calls the generic {@code SolidObject} routine ({@code JmpTo20_SolidObject}
     * at s2.asm:55824-55829: {@code d1=$4B, d2=8, d3=d2+1=9, d4=x_pos}), NOT
     * {@code PlatformObject}. A new airborne landing therefore resolves through
     * {@code SolidObject_Landed} (s2.asm:35582-35621), whose centre is
     * {@code playerY - distY + 3} (== {@code anchorY - (airHalfHeight + y_radius) - 1});
     * {@code resolveContactInternal} already produces that value exactly. The
     * default {@code PlatformObject_ChkYRange} snap
     * ({@code anchorY - groundHalfHeight - y_radius - 1}, s2.asm:35696-35712) is the
     * wrong formula here: it uses {@code groundHalfHeight=9} and the post-unroll
     * standing {@code y_radius=$13}, landing the player one pixel too low
     * (MCZ trace f1085: 0x056B vs ROM 0x056C). Opt this SolidObject-based gate out
     * of the platform snap so the SolidObject_Landed result is preserved.
     */
    @Override
    public boolean usesPlatformObjectLandingSnap() {
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Only solid when fully closed (frame 0)
        // When not frame 0, returning false causes SolidContacts to auto-drop standing players
        return !isDestroyed() && mappingFrame == 0;
    }

    // SolidObjectListener implementation

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Get renderer from art provider
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MCZ_BRIDGE);
        if (renderer == null) return;

        // Render the current frame at object position
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = getX();
        int y = getY();

        // Draw object center (yellow cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 1.0f, 0.0f);

        // Draw solid collision bounds (green box) only when solid (frame 0)
        if (mappingFrame == 0) {
            int halfWidth = SOLID_PARAMS.halfWidth();
            int airHalfHeight = SOLID_PARAMS.airHalfHeight();
            int groundHalfHeight = SOLID_PARAMS.groundHalfHeight();

            int left = x - halfWidth;
            int right = x + halfWidth;
            int top = y - airHalfHeight;
            int bottom = y + groundHalfHeight;

            ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(right, top, right, bottom, 0.0f, 0.7f, 0.0f);
            ctx.drawLine(right, bottom, left, bottom, 0.0f, 0.7f, 0.0f);
            ctx.drawLine(left, bottom, left, top, 0.0f, 0.7f, 0.0f);
        }
    }

}

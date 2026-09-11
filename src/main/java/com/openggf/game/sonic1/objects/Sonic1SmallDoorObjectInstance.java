package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Object 0x2A - Small Vertical Door (SBZ).
 * <p>
 * An automatic door that opens when Sonic approaches from the correct side
 * and closes when he moves away. The door is solid when closed (frame 0),
 * blocking Sonic's path.
 * <p>
 * The approach direction is controlled by {@code obStatus} bit 0, which
 * comes from the spawn render flags x-flip bit:
 * <ul>
 *   <li>bit 0 = 0: door opens when Sonic approaches from the LEFT</li>
 *   <li>bit 0 = 1: door opens when Sonic approaches from the RIGHT</li>
 * </ul>
 * <p>
 * Detection range: Sonic must be within $40 (64) pixels horizontally
 * of the door's X position.
 * <p>
 * Animation: 9 mapping frames (0=closed, 8=fully open). The two door
 * halves slide apart vertically. Frame delay is 0 (every frame).
 * <p>
 * SolidObject params (when closed): d1=$11, d2=$20, d3=$21.
 * <p>
 * ROM reference: docs/s1disasm/_incObj/2A SBZ Small Door.asm
 */
public class Sonic1SmallDoorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // Detection range for door opening: move.w #$40,d1
    private static final int DETECTION_RANGE = 0x40;

    // obActWid from ROM: ADoor_Main writes move.b #16/2,obActWid(a0) = 8
    // (docs/s1disasm/_incObj/2A SBZ Small Door.asm:20-21). Read by
    // getOnScreenHalfWidth(), and through it by Sonic_Balance.
    private static final int ACT_WIDTH = 8;

    // SolidObject parameters when door is closed (frame 0):
    // move.w #$11,d1  ; half-width
    // move.w #$20,d2  ; air half-height (top)
    // move.w d2,d3 / addq.w #1,d3  ; ground half-height (bottom) = $21
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x11, 0x20, 0x21);

    // Ani_ADoor animation 0 (close): dc.b 0, 8, 7, 6, 5, 4, 3, 2, 1, 0, afBack, 1
    // Frame delay 0, descending frames, holds on frame 0.
    private static final int[] CLOSE_SEQUENCE = {8, 7, 6, 5, 4, 3, 2, 1, 0};

    // Ani_ADoor animation 1 (open): dc.b 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, afBack, 1
    // Frame delay 0, ascending frames, holds on frame 8.
    private static final int[] OPEN_SEQUENCE = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    // Frame delay from animation script: dc.b 0 (every-frame update)
    private static final int FRAME_DELAY = 0;

    // obStatus bit 0: determines which side the door opens from.
    // Set from spawn renderFlags x-flip bit.
    private boolean openFromRight;

    // Animation state
    private int animationId;         // 0 = closing, 1 = opening
    private int prevAnimationId;     // Tracks previous animation for change detection (AnimateSprite behavior)
    private int animationFrameIndex;
    private int animationTimer;
    private int mappingFrame;

    // Whether the door is currently acting as a solid object
    private boolean solidActive;

    private static final DebugColor DEBUG_COLOR = new DebugColor(200, 140, 60);

    public Sonic1SmallDoorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SmallDoor");
        // obStatus bit 0 comes from spawn renderFlags bit 0 (x-flip)
        this.openFromRight = (spawn.renderFlags() & 0x1) != 0;
        this.animationId = 0;
        this.prevAnimationId = -1; // Force initial reset on first frame
        this.animationFrameIndex = 0;
        this.animationTimer = 0;
        this.mappingFrame = 0;
        this.solidActive = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ADoor_OpenShut logic:
        // clr.b obAnim(a0)  ; default to closing animation (0)
        int newAnimId = 0;

        if (player != null) {
            int playerX = player.getCentreX();
            int doorX = getX();

            // Check if Sonic is within detection range ($40 pixels)
            // move.w (v_player+obX).w,d0 / add.w d1,d0 / cmp.w obX(a0),d0
            int testHigh = playerX + DETECTION_RANGE;
            if (testHigh >= doorX) {
                // sub.w d1,d0 / sub.w d1,d0 / cmp.w obX(a0),d0
                int testLow = playerX - DETECTION_RANGE;
                if (testLow < doorX) {
                    // Sonic is within range. Check which side he's on.
                    // add.w d1,d0 -> d0 = playerX
                    // cmp.w obX(a0),d0 -> if playerX < doorX, Sonic is left
                    if (playerX < doorX) {
                        // Sonic is left of door
                        // btst #0,obStatus(a0) / bne.s ADoor_Animate
                        // If openFromRight is false (bit 0 clear), open; else close
                        if (!openFromRight) {
                            newAnimId = 1;
                        }
                    } else {
                        // Sonic is right of (or at) door (loc_899A)
                        // btst #0,obStatus(a0) / beq.s ADoor_Animate
                        // If openFromRight is true (bit 0 set), open; else close
                        if (openFromRight) {
                            newAnimId = 1;
                        }
                    }
                }
            }
        }

        // AnimateSprite detects animation changes and resets when the ID differs
        animationId = newAnimId;
        if (animationId != prevAnimationId) {
            animationFrameIndex = 0;
            animationTimer = 0;
            prevAnimationId = animationId;
        }

        animate();

        // Door is solid only when fully closed (obFrame == 0)
        // tst.b obFrame(a0) / bne.s .remember
        solidActive = (mappingFrame == 0);
    }

    /**
     * Animates the door using the current animation sequence.
     * Matches AnimateSprite behavior with frame delay from Ani_ADoor.
     */
    private void animate() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }
        animationTimer = FRAME_DELAY;

        int[] sequence = (animationId == 0) ? CLOSE_SEQUENCE : OPEN_SEQUENCE;
        if (animationFrameIndex >= sequence.length) {
            // afBack,1: loop back to the last frame in the sequence (hold)
            animationFrameIndex = sequence.length - 1;
        }
        mappingFrame = sequence[animationFrameIndex];
        animationFrameIndex++;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_SMALL_DOOR);
        if (renderer == null) return;

        // ori.b #4,obRender(a0) -> screen-relative rendering, no flip from render flags
        // The door uses the same art regardless of x-flip status; x-flip only
        // controls obStatus bit 0 for approach direction logic.
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    // ---- SolidObjectProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    /**
     * The door's ROM {@code obActWid}.
     *
     * <p>{@code ADoor_Main} writes {@code move.b #16/2,obActWid(a0)} = 8
     * (docs/s1disasm/_incObj/2A SBZ Small Door.asm:20-21). That is half the
     * shared default, so the inherited 16 made the balance window wider than the
     * ROM's rather than narrower: {@code Sonic_Balance} forms
     * {@code d1 = obActWid + dx} and {@code d2 = 2*obActWid - 4}, so at 8 the
     * ROM balances outside {@code |dx| >= 4} while the engine balanced only
     * outside {@code |dx| >= 12} (docs/s1disasm/_incObj/01 Sonic.asm:422-431) —
     * a band the player crosses on a top surface that only reaches
     * {@code $11} either side.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers want the byte, and the class is full-solid so the
     * balance accessor inherits this one. {@code ADoor_Animate}'s separately
     * authored {@code d1 = #12/2+sonic_solid_width} = {@code $11} at
     * {@code :62} is unchanged.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return ACT_WIDTH;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return solidActive;
    }

    // ---- SolidObjectListener ----

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // SolidObject handles the collision response; no extra per-contact behavior.
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // ROM: ADoor_OpenShut ends with `bra.w RememberState`, which deletes the
        // door via the plain `out_of_range` macro -- there is no extended
        // persistence window. Returning false lets ObjectManager's standard
        // out_of_range check (isObjectOutOfRange -> isOutOfRangeS1) govern the
        // unload, matching the ROM frame exactly.
        //
        // The previous `isOnScreenX(160)` symmetric margin kept the door alive
        // ~32px too long off the left edge (ROM's chunk-aligned out_of_range
        // unloads at ~camX-128; the 160px margin held it to ~camX-160), so the
        // door's SST slot was freed one batch late. That inverted the slot
        // allocation of objects spawned afterward and, downstream, the SBZ2
        // conveyor handoff processing order at f2323 (two adjacent conveyors
        // with opposite push directions resolve order-dependently).
        //
        // docs/s1disasm/_incObj/2A SBZ Small Door.asm:55 (bra.w RememberState)
        // docs/s1disasm/_incObj/sub RememberState.asm:8-9 (out_of_range.w)
        return false;
    }

    // ---- Debug Rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int objX = getX();
        int objY = getY();
        // Draw solid bounds when door is closed
        float r = solidActive ? 0.8f : 0.3f;
        float g = solidActive ? 0.5f : 0.8f;
        float b = solidActive ? 0.2f : 0.4f;
        ctx.drawRect(objX, objY, SOLID_PARAMS.halfWidth(), SOLID_PARAMS.airHalfHeight(), r, g, b);
        ctx.drawWorldLabel(objX, objY, -1,
                String.format("Door frm=%d anim=%d side=%s",
                        mappingFrame, animationId,
                        openFromRight ? "R" : "L"),
                DEBUG_COLOR);
    }
}

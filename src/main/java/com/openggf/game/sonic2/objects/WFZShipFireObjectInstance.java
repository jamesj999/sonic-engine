package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xBC - Fire coming out of Robotnik's ship in Wing Fortress Zone.
 * <p>
 * A decorative flame effect attached to Robotnik's getaway ship. The flame's
 * X position tracks the background camera X offset (Camera_BG_X_offset),
 * scrolling with the ship as it moves across the screen. The flame flickers
 * by displaying only every other frame (toggling objoff_2A bit 0).
 * <p>
 * When Camera_BG_X_offset reaches 0x380 or higher, the ship has scrolled
 * far enough off-screen that the fire object is deleted.
 * <p>
 * <b>Subtype:</b> 0x7C (all instances use this subtype, which maps to
 * ObjBC_SubObjData2 in SubObjData_Index).
 * <p>
 * <b>SubObjData properties:</b>
 * <ul>
 *   <li>Mappings: ObjBC_MapUnc_3BC08 (single frame, 2 pieces of 4x2 tiles)</li>
 *   <li>Art tile: make_art_tile(ArtTile_ArtNem_WfzThrust, 2, 0) - palette line 2</li>
 *   <li>Render flags: level_fg (positioned relative to camera)</li>
 *   <li>Priority: 4</li>
 *   <li>Width: 0x10</li>
 *   <li>Collision: 0 (no collision)</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79894-79932 (ObjBC)
 */
public class WFZShipFireObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    /**
     * BG X offset threshold at which the ship fire is deleted.
     * From disassembly: cmpi.w #$380,d1 / bhs.w JmpTo65_DeleteObject
     */
    private static final int BG_X_OFFSET_DELETE_THRESHOLD = 0x380;

    /** Initial X position saved during init (ROM: objoff_2C). */
    private int initialX;

    /** Current computed X position (initialX + Camera_BG_X_offset). */
    private int currentX;

    /** Current Y position (constant, from spawn). */
    private int currentY;

    /**
     * Flicker toggle - alternates each frame to create flickering effect.
     * ROM: bchg #0,objoff_2A(a0) / beq.w return_37A48 (skip display when 0)
     */
    private boolean flickerVisible = true;
    private boolean renderThisFrame;
    private boolean initialized;

    public WFZShipFireObjectInstance(ObjectSpawn spawn) {
        super(spawn, "WFZShipFire");
        // ROM: ObjBC_Init - move.w x_pos(a0),objoff_2C(a0)
        this.initialX = spawn.x();
        this.currentX = spawn.x();
        this.currentY = spawn.y();
    }

    @Override
    public WFZShipFireObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WFZShipFireObjectInstance(ctx.spawn());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority=4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isPersistent() {
        // ObjBC has no MarkObjGone; its BG offset check owns deletion.
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            // ROM ObjBC_Init runs LoadSubObject, saves objoff_2C, then rts.
            // ObjBC_Main's BG offset and flicker logic starts on the next frame.
            initialized = true;
            renderThisFrame = false;
            return;
        }

        // ROM: ObjBC_Main (s2.asm line 79915)
        // move.w objoff_2C(a0),d0          ; d0 = initial X
        // move.w (Camera_BG_X_offset).w,d1 ; d1 = BG scroll offset
        int bgXOffset = getWfzBgXOffset();

        // cmpi.w #$380,d1 / bhs.w JmpTo65_DeleteObject
        // Delete when BG offset has scrolled too far (ship off-screen)
        if (bgXOffset >= BG_X_OFFSET_DELETE_THRESHOLD) {
            setDestroyed(true);
            return;
        }

        // add.w d1,d0 / move.w d0,x_pos(a0)
        // X position = initial position + BG camera offset
        currentX = initialX + bgXOffset;

        // bchg #0,objoff_2A(a0) / beq.w return_37A48
        // Toggle flicker bit each frame. When result is 0 (beq), skip display.
        // When result is 1, fall through to DisplaySprite.
        flickerVisible = !flickerVisible;
        renderThisFrame = flickerVisible;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Only render on frames where the flicker toggle is active
        // ROM: bchg #0,objoff_2A(a0) / beq.w return_37A48 (skip when 0)
        // jmpto JmpTo45_DisplaySprite (display when 1)
        if (!renderThisFrame) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_THRUST);
        if (renderer == null) return;

        // Single mapping frame (index 0), no flip
        renderer.drawFrameIndex(0, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int bgXOffset = getWfzBgXOffset();
        ctx.drawCross(currentX, currentY, 4, 1.0f, 0.5f, 0.0f);
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("BC fire bgX=%d %s",
                        bgXOffset,
                        flickerVisible ? "VIS" : "HID"),
                DebugColor.ORANGE);
    }

    private int getWfzBgXOffset() {
        if (services().levelEventProvider() instanceof Sonic2LevelEventManager events) {
            return events.getWfzEvents().getBgXOffset();
        }
        return services().parallaxManager() != null ? services().parallaxManager().getCameraBgXOffset() : 0;
    }
}

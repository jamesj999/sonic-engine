package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x5C - Metal Pylons in foreground (SLZ).
 * <p>
 * A tall decorative metal pylon that renders in the foreground of Star Light Zone.
 * Uses parallax scrolling to create a depth effect: the pylon moves faster than
 * the background, appearing to be between the player and the camera.
 * <p>
 * The pylon has no collision, no animation, and no subtypes. It recalculates
 * its position every frame based on camera position.
 * <p>
 * <b>Parallax calculation from disassembly:</b>
 * <pre>
 * X: move.l (v_screenposx).w,d1  ; Load camera X as 16.16 fixed-point
 *    add.l  d1,d1                  ; Double it
 *    swap   d1                     ; Take high word = 2 * cameraX
 *    neg.w  d1                     ; Negate
 *    move.w d1,obX(a0)            ; obX = -(2 * cameraX)
 *
 * Y: move.l (v_screenposy).w,d1  ; Load camera Y as 16.16 fixed-point
 *    add.l  d1,d1                  ; Double it
 *    swap   d1                     ; Take high word = 2 * cameraY
 *    andi.w #$3F,d1               ; Mask to 0-63
 *    neg.w  d1                     ; Negate
 *    addi.w #$100,d1              ; Add base offset
 *    move.w d1,obScreenY(a0)      ; Screen-relative Y
 * </pre>
 * <p>
 * DisplaySprite then renders at:
 * - Screen X = obX - cameraX = -(2*cameraX) - cameraX = -(3*cameraX)
 * - Screen Y = obScreenY (used directly, not camera-relative)
 * <p>
 * Reference: docs/s1disasm/_incObj/5C Pylon.asm
 */
public class Sonic1PylonObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10;

    // From disassembly: andi.w #$3F,d1 — Y parallax mask
    private static final int CAMERA_Y_MASK = 0x3F;

    // From disassembly: addi.w #$100,d1 — base Y screen offset
    private static final int BASE_SCREEN_Y = 0x100;

    public Sonic1PylonObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Pylon");
    }

    /**
     * ROM Pyl_Display recomputes obX/obScreenY from the camera every frame and
     * ends in {@code bra.w DisplaySprite} — there is no out_of_range test, no
     * MarkObjGone and no DeleteObject anywhere in the object
     * (docs/s1disasm/_incObj/5C SLZ Foreground Pylon.asm:28-43), so Obj5C holds
     * its SST slot for the whole act. Its obX is a screen-fixed parallax value
     * rather than a level position, so the shared camera-distance unload test
     * has nothing meaningful to measure against and would free a slot the ROM
     * keeps occupied — which shifts every later dynamic allocation, and with it
     * the slot-indexed Obj37 floor-probe cadence.
     */
    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    /**
     * Calculates the screen-space X position using parallax.
     * From disassembly: obX = -(2 * cameraX), then DisplaySprite subtracts cameraX,
     * giving effective screen X = -(3 * cameraX).
     * <p>
     * The add.l/swap sequence extracts the integer part of (2 * camera_16.16),
     * which includes subpixel carry for smooth scrolling.
     */
    private int getScreenX() {
        Camera camera = services().camera();
        int cameraX = camera.getX();
        // Screen X = -(3 * cameraX)
        // Wraps at 16-bit boundary as in original hardware
        return (short) (-(3 * cameraX));
    }

    /**
     * Calculates the screen-space Y position using parallax.
     * From disassembly: screenY = 0x100 - ((2 * cameraY) & 0x3F)
     * <p>
     * The masking to 0x3F creates a small vertical oscillation (0-63 pixels)
     * that repeats as the camera scrolls vertically.
     */
    private int getScreenY() {
        Camera camera = services().camera();
        int cameraY = camera.getY();
        // (2 * cameraY) masked to 6 bits, negated, offset by 0x100
        int yOffset = (2 * cameraY) & CAMERA_Y_MASK;
        return BASE_SCREEN_Y - yOffset;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Purely decorative — no update logic needed
    }

    @Override
    public boolean isHighPriority() {
        // The pylon uses VDP high-priority bit (priority=1 in make_art_tile),
        // rendering in front of high-priority level tiles.
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // Render at highest bucket (furthest back among high-priority sprites)
        // to layer behind other foreground sprites but in front of tiles.
        return RenderPriority.MAX;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_PYLON);
        if (renderer == null) return;

        Camera camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        // Convert screen-space positions to world-space for the renderer.
        // The renderer subtracts camera position internally, so:
        // worldX = cameraX + screenX, worldY = cameraY + screenY
        int screenX = getScreenX();
        int screenY = getScreenY();
        int worldX = cameraX + screenX;
        int worldY = cameraY + screenY;

        // Render frame 0 (the single frame containing all 9 pieces)
        renderer.drawFrameIndex(0, worldX, worldY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        Camera camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        int screenX = getScreenX();
        int screenY = getScreenY();
        int worldX = cameraX + screenX;
        int worldY = cameraY + screenY;

        ctx.drawCross(worldX, worldY, 4, 0.8f, 0.5f, 1.0f);
        ctx.drawWorldLabel(worldX, worldY, -1,
                String.format("Pylon sx=%d sy=%d", screenX, screenY),
                DebugColor.MAGENTA);
    }
}

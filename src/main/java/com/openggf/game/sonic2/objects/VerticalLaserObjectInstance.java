package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0xB7 - Unused huge vertical laser from Wing Fortress Zone.
 * Spawned as a child by ObjB6 (TiltingPlatform) during the countdown fire behavior.
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 79632-79663 (ObjB7).
 * <p>
 * Behavior:
 * - Init (ObjB7_Init): LoadSubObject with subtype $72, set objoff_2A = $20 (32 frames)
 * - Main (ObjB7_Main): Each frame, decrement timer. Delete when timer reaches 0.
 *   Toggle objoff_2B bit 0 each frame: when clear, skip rendering (invisible);
 *   when set, call MarkObjGone (visible). This creates a flashing effect.
 * <p>
 * SubObjData (ObjB7_SubObjData at s2.asm line 79661):
 * - mappings = ObjB7_MapUnc_3B8E4
 * - art_tile = make_art_tile(ArtTile_ArtNem_WfzVrtclLazer,2,1) (palette 2, priority)
 * - render_flags = level_fg
 * - priority = 4
 * - width_pixels = $18
 * - collision_flags = $A9
 * <p>
 * Mappings (1 frame from Map_objB7):
 * - 16 pieces: 8 pairs of 3x4 tiles forming a tall vertical beam spanning Y -$70 to $90.
 */
public class VerticalLaserObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {

    // From ObjB7_SubObjData: priority = 4, width_pixels = $18
    private static final int PRIORITY = 4;
    private static final int WIDTH_PIXELS = 0x18;

    // collision_flags = $A9: HURT (bit 7 set) + size index 0x29
    private static final int COLLISION_FLAGS = 0xA9;

    // ObjB7_Init: move.b #$20,objoff_2A(a0) - 32 frame lifetime
    private static final int LIFETIME_FRAMES = 0x20;

    // State
    private int posX;
    private int posY;
    private int timer;              // objoff_2A - countdown timer
    private boolean visibleToggle;  // objoff_2B bit 0 - alternates each frame
    private boolean initialized;    // First frame is Init (routine 0), skip render

    private VerticalLaserObjectInstance() {
        this(new ObjectSpawn(0, 0, Sonic2ObjectIds.VERTICAL_LASER, 0x72, 0, false, 0), 0, 0);
    }

    public VerticalLaserObjectInstance(ObjectSpawn parentSpawn, int x, int y) {
        super(createLaserSpawn(parentSpawn, x, y), "VerticalLaser");
        this.posX = x;
        this.posY = y;
        this.timer = LIFETIME_FRAMES;
        this.visibleToggle = false;
        this.initialized = false;
    }

    private static ObjectSpawn createLaserSpawn(ObjectSpawn parent, int x, int y) {
        return new ObjectSpawn(
                x, y,
                Sonic2ObjectIds.VERTICAL_LASER,
                0x72, // ObjB7 subtype
                parent.renderFlags(),
                false, // Don't track respawn for dynamic children
                parent.rawYWord());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ObjB7_Init runs on first frame (routine 0 -> routine 2)
        if (!initialized) {
            initialized = true;
            return;
        }

        // ObjB7_Main:
        //   subq.b #1,objoff_2A(a0)
        //   beq.w JmpTo65_DeleteObject
        timer--;
        if (timer <= 0) {
            setDestroyed(true);
            return;
        }

        //   bchg #0,objoff_2B(a0)
        //   beq.w return_37A48    ; if bit was set and is now clear -> skip render (invisible)
        //   jmpto JmpTo39_MarkObjGone  ; if bit was clear and is now set -> visible
        visibleToggle = !visibleToggle;
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getX() {
        return posX;
    }

    @Override
    public int getY() {
        return posY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzVrtclLazer,2,1)
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM: alternates visibility each frame via bchg/beq pattern
        // When visibleToggle is false, the object is invisible (skip render)
        if (!visibleToggle || !initialized) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_VERTICAL_LASER);
        if (renderer == null) {
            appendDebugBox(commands);
            return;
        }

        // Single mapping frame (frame 0)
        renderer.drawFrameIndex(0, posX, posY, false, false);
    }

    private void appendDebugBox(List<GLCommand> commands) {
        int halfW = WIDTH_PIXELS;
        int halfH = 0x70; // Approximate half-height from mappings (-$70 to $90)
        int left = posX - halfW;
        int right = posX + halfW;
        int top = posY - halfH;
        int bottom = posY + halfH;

        float r = 1.0f, g = 0.6f, b = 0.1f;
        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }
}

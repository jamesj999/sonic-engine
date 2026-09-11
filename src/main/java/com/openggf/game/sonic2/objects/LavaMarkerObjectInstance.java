package com.openggf.game.sonic2.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;

/**
 * HTZ/MTZ Lava Marker Object (Obj31) - Invisible collision hazard marker.
 * <p>
 * Defines invisible hazard areas used in Hill Top Zone (HTZ) and Metropolis Zone (MTZ)
 * to mark lava/hazard regions. The object damages the player on contact but has no
 * visible representation during normal gameplay.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 46010-46089
 * <ul>
 *   <li>Obj31_Init: line 46029 (sets collision flags from subtype lookup)</li>
 *   <li>Obj31_Main: line 46058 (only performs off-screen culling, no rendering)</li>
 * </ul>
 *
 * <h3>Subtypes</h3>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Collision Area</th><th>Collision Flags</th><th>Description</th></tr>
 *   <tr><td>0</td><td>64×64 pixels</td><td>0x96</td><td>Small lava marker (HURT | size 0x16)</td></tr>
 *   <tr><td>1</td><td>128×64 pixels</td><td>0x94</td><td>Medium lava marker (HURT | size 0x14)</td></tr>
 *   <tr><td>2</td><td>256×64 pixels</td><td>0x95</td><td>Large lava marker (HURT | size 0x15)</td></tr>
 * </table>
 *
 * <h3>Collision Flags Encoding</h3>
 * <pre>
 * Byte format: [CC][SSSSSS]
 * - CC (bits 6-7): Category - 0x80 = HURT
 * - SSSSSS (bits 0-5): Size index into TouchResponseTable
 * </pre>
 *
 * <h3>Usage Statistics</h3>
 * Total: 50 instances (HTZ1: 9, HTZ2: 21, MTZ2: 12, MTZ3: 8)
 *
 * @see BlueBallsObjectInstance Another HURT-category TouchResponseProvider
 */
public class LavaMarkerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // ========================================================================
    // ROM Constants - Collision Flags by Subtype
    // From s2.asm Obj31_SubtypeData lookup table (line 46076)
    // ========================================================================

    /**
     * Subtype 0: Small lava marker (64×64 pixels).
     * Collision flags = 0x96 (HURT category 0x80 | size index 0x16).
     */
    private static final int COLLISION_FLAGS_SMALL = 0x96;

    /**
     * Subtype 1: Medium lava marker (128×64 pixels).
     * Collision flags = 0x94 (HURT category 0x80 | size index 0x14).
     */
    private static final int COLLISION_FLAGS_MEDIUM = 0x94;

    /**
     * Subtype 2: Large lava marker (256×64 pixels).
     * Collision flags = 0x95 (HURT category 0x80 | size index 0x15).
     */
    private static final int COLLISION_FLAGS_LARGE = 0x95;

    /**
     * Lookup table for collision flags by subtype.
     * ROM: Obj31_SubtypeData at s2.asm line 46076
     */
    private static final int[] COLLISION_FLAGS_TABLE = {
            COLLISION_FLAGS_SMALL,   // Subtype 0
            COLLISION_FLAGS_MEDIUM,  // Subtype 1
            COLLISION_FLAGS_LARGE    // Subtype 2
    };

    /**
     * Debug rendering dimensions for each subtype (width, height in pixels).
     * Used only when DEBUG_VIEW_ENABLED is true.
     */
    private static final int[][] DEBUG_DIMENSIONS = {
            {64, 64},   // Subtype 0: 64×64
            {128, 64},  // Subtype 1: 128×64
            {256, 64}   // Subtype 2: 256×64
    };

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Cached collision flags based on subtype. */
    private int collisionFlags;

    /** Subtype index (0, 1, or 2). */
    private int subtypeIndex;

    public LavaMarkerObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Clamp subtype to valid range (0-2)
        this.subtypeIndex = Math.min(spawn.subtype() & 0xFF, COLLISION_FLAGS_TABLE.length - 1);
        this.collisionFlags = COLLISION_FLAGS_TABLE[subtypeIndex];
    }

    @Override
    public LavaMarkerObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LavaMarkerObjectInstance(ctx.spawn(), getName());
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM Obj31_Main (line 46058) only does off-screen culling via MarkObjGone.
        // The ObjectManager.Placement system handles this automatically,
        // so no explicit logic is needed here.
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible during normal gameplay - only render in debug mode
        var config = services().configuration();
        if (!config.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        // Debug rendering: orange box showing collision area
        int[] dims = DEBUG_DIMENSIONS[subtypeIndex];
        int width = dims[0];
        int height = dims[1];

        // Center the box on the object position
        int x1 = spawn.x() - width / 2;
        int y1 = spawn.y() - height / 2;
        int x2 = x1 + width;
        int y2 = y1 + height;

        // Orange color for lava/fire hazard
        float r = 1.0f;
        float g = 0.4f;
        float b = 0.0f;

        // Use built-in RECTI which handles Y coordinate flipping correctly
        // Semi-transparent fill
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                r, g, b, 0.5f,
                x1, y1, x2, y2));

        // Solid border (draw as 4 lines using RECTI with 1-pixel height/width)
        // Top edge
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y1, x2, y1 + 1));
        // Bottom edge
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y2 - 1, x2, y2));
        // Left edge
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x1, y1, x1 + 1, y2));
        // Right edge
        commands.add(new GLCommand(
                GLCommand.CommandType.RECTI,
                GL_TRIANGLE_FAN,
                r, g, b,
                x2 - 1, y1, x2, y2));
    }

    @Override
    public int getPriorityBucket() {
        // Low priority since it's usually invisible
        return 0;
    }
}

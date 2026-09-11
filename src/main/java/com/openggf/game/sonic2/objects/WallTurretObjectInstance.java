package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object B8 - Wall turret from Wing Fortress Zone.
 * <p>
 * A wall-mounted turret that detects the player vertically, aims in the player's
 * horizontal direction, and fires small projectiles downward at regular intervals.
 * <p>
 * Behavior from disassembly (s2.asm lines 79665-79776):
 * <ul>
 *   <li>Routine 0: Init via LoadSubObject (subtype 0x74)</li>
 *   <li>Routine 2 (Idle): Waits until player is below and within 0x60 pixels horizontally</li>
 *   <li>Routine 4 (Tracking/Shooting): Aims at player, fires every 0x60 frames</li>
 *   <li>Mapping frame 0 = barrel pointing straight down</li>
 *   <li>Mapping frame 1 = barrel angled left</li>
 *   <li>Mapping frame 2 = barrel angled right</li>
 *   <li>Spawns Obj98 projectile with WallTurretShotMove behavior</li>
 * </ul>
 * <p>
 * SubObjData (ObjB8_SubObjData at 0x3BA36):
 * mappings = ObjB8_Obj98_MapUnc_3BA46, art_tile = ArtTile_ArtNem_WfzWallTurret (palette 0),
 * render_flags = level_fg, priority = 4, width_pixels = 0x10, collision_flags = 0.
 */
public class WallTurretObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(WallTurretObjectInstance.class.getName());

    // From ObjB8_SubObjData: priority = 4, width_pixels = $10
    private static final int PRIORITY = 4;
    private static final int WIDTH_PIXELS = 0x10;

    // Routine 2 detection: addi.w #$60,d2 / cmpi.w #$C0,d2
    // d2 = horizontal distance (signed), add 0x60 then check < 0xC0
    // This effectively checks if abs(dx) < 0x60 (96 pixels)
    private static final int DETECTION_RANGE = 0x60;

    // Routine 4 initial timer: move.w #2,objoff_2A(a0)
    private static final int INITIAL_FIRE_DELAY = 2;

    // Routine 4 reload timer: move.w #$60,objoff_2A(a0)
    private static final int FIRE_INTERVAL = 0x60; // 96 frames

    // Aiming threshold: addi.w #$20,d2 / cmpi.w #$40,d2
    // If adjusted dx < 0x40, player is nearly centered -> aim straight down (frame 0)
    private static final int AIM_CENTER_THRESHOLD = 0x20;

    // Projectile spawn offsets per turret frame, from byte_3BA2A:
    // Each entry is 4 bytes: x_offset, y_offset, x_vel_hi, y_vel_hi
    // Frame 0 (straight down):  x=0,    y=$18,  xv=0,    yv=1
    // Frame 1 (angled left):    x=$EF,  y=$10,  xv=$FF,  yv=1
    // Frame 2 (angled right):   x=$11,  y=$10,  xv=1,    yv=1
    private static final int[][] PROJECTILE_SPAWN_DATA = {
            {  0x00, 0x18, 0x00, 0x01 },  // frame 0: straight down
            { -0x11, 0x10, -0x01, 0x01 },  // frame 1: angled left  (0xEF = -17, 0xFF = -1)
            {  0x11, 0x10, 0x01, 0x01 },  // frame 2: angled right
    };
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // State
    private int currentX;
    private int currentY;
    private int routine;         // 0=init, 2=idle/detect, 4=tracking/shooting
    private int mappingFrame;    // 0=straight, 1=left, 2=right
    private int fireTimer;       // countdown timer for next shot (objoff_2A)

    public WallTurretObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.routine = 2;  // LoadSubObject advances routine to 2
        this.mappingFrame = 0;
        this.fireTimer = 0;
    }

    @Override
    public WallTurretObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WallTurretObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        PlayableEntity player = closestOrientationPlayer(playerEntity);
        switch (routine) {
            case 2:
                updateIdle(player);
                break;
            case 4:
                updateTracking(player);
                break;
        }
    }

    /**
     * Routine 2 (loc_3B980): Idle/Detect state.
     * <p>
     * Waits until player is below the turret (d1 != 0) AND within horizontal
     * detection range (0x60 pixels). When detected, advances to routine 4.
     * <p>
     * Disassembly:
     *   bsr.w   Obj_GetOrientationToPlayer
     *   tst.w   d1          ; d1=0 if player above, d1=2 if below
     *   beq.s   +           ; skip if player above
     *   addi.w  #$60,d2     ; add offset to horizontal distance
     *   cmpi.w  #$C0,d2     ; check if within range
     *   blo.s   ++          ; branch to activate if in range
     */
    private void updateIdle(PlayableEntity player) {
        if (!isOnScreen(480)) {
            return;
        }
        if (player == null) {
            return;
        }

        // Obj_GetOrientationToPlayer returns:
        // d0 = 0 if player is left, 2 if right
        // d1 = 0 if player is above, 2 if below
        // d2 = horizontal distance (signed, object - player)
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();

        // d1: 0 if player above (dy >= 0 means player is above or at same height)
        // In ROM: d3 = y_pos(a0) - y_pos(a1), bhs -> d1 stays 0 (player below or equal)
        // Actually: sub.w y_pos(a1),d3 => d3 = turret_y - player_y
        // bhs (branch if higher or same) = branch if d3 >= 0 = turret is below or at player
        // So d1=2 when d3 < 0 (turret above player = player is below turret)
        boolean playerBelow = dy < 0;

        if (!playerBelow) {
            return;
        }

        // Check horizontal range: addi.w #$60,d2 / cmpi.w #$C0,d2
        // d2 is signed distance. Adding 0x60 shifts range from [-0x60..+0x60) to [0..0xC0)
        // So this checks if horizontal distance is within 0x60 pixels
        int adjustedDx = dx + DETECTION_RANGE;
        if (adjustedDx < 0 || adjustedDx >= (DETECTION_RANGE * 2)) {
            return;
        }

        // Player detected - advance to tracking state
        // addq.b #2,routine(a0)
        routine = 4;
        // move.w #2,objoff_2A(a0)
        fireTimer = INITIAL_FIRE_DELAY;
    }

    /**
     * Routine 4 (loc_3B9AA): Tracking/Shooting state.
     * <p>
     * Aims at the player based on horizontal position and fires projectiles
     * when the timer expires.
     * <p>
     * Disassembly:
     *   bsr.w   Obj_GetOrientationToPlayer
     *   moveq   #0,d6
     *   addi.w  #$20,d2     ; center the range
     *   cmpi.w  #$40,d2     ; if dx+0x20 < 0x40, player is centered
     *   blo.s   loc_3B9C0   ; keep d6=0 (straight down)
     *   move.w  d0,d6       ; d0 = 0 (left) or 2 (right)
     *   lsr.w   #1,d6       ; d6 = 0 or 1
     *   addq.w  #1,d6       ; d6 = 1 (left) or 2 (right)
     */
    private void updateTracking(PlayableEntity player) {
        if (player == null) {
            return;
        }

        // Obj_GetOrientationToPlayer
        int dx = currentX - player.getCentreX();

        // Determine aim direction (mapping frame)
        // d0 = 0 if player is to turret's left (dx > 0), 2 if to right (dx < 0)
        int d0 = (dx < 0) ? 2 : 0;

        int d6 = 0;
        // addi.w #$20,d2 / cmpi.w #$40,d2
        int adjustedDx = dx + AIM_CENTER_THRESHOLD;
        if (adjustedDx < 0 || adjustedDx >= (AIM_CENTER_THRESHOLD * 2)) {
            // Player is off-center; aim left or right
            // move.w d0,d6 / lsr.w #1,d6 / addq.w #1,d6
            d6 = (d0 >> 1) + 1;
        }

        // move.b d6,mapping_frame(a0)
        mappingFrame = d6;

        // subq.w #1,objoff_2A(a0) / bne.s +
        fireTimer--;
        if (fireTimer <= 0) {
            // move.w #$60,objoff_2A(a0)
            fireTimer = FIRE_INTERVAL;
            fireProjectile();
        }
    }

    /**
     * Fire a projectile (loc_3B9D8).
     * <p>
     * Spawns an Obj98 (Projectile) with WallTurretShotMove behavior.
     * Position and velocity are determined by the current mapping frame
     * using the byte_3BA2A lookup table.
     * <p>
     * Disassembly:
     *   jsrto   JmpTo25_AllocateObjectAfterCurrent
     *   move.b  #ObjID_Projectile,id(a1)
     *   move.b  #3,mapping_frame(a1)
     *   move.b  #$8E,subtype(a1)      ; references ObjB8_SubObjData2
     *   move.w  x_pos(a0),x_pos(a1)
     *   move.w  y_pos(a0),y_pos(a1)
     *   lea_    Obj98_WallTurretShotMove,a2
     *   move.l  a2,objoff_2A(a1)
     *   moveq   #0,d0
     *   move.b  mapping_frame(a0),d0
     *   lsl.w   #2,d0                 ; index * 4
     *   lea     byte_3BA2A(pc,d0.w),a2
     *   move.b  (a2)+,d0 / ext.w d0 / add.w d0,x_pos(a1)
     *   move.b  (a2)+,d0 / ext.w d0 / add.w d0,y_pos(a1)
     *   move.b  (a2)+,x_vel(a1)       ; high byte of x velocity
     *   move.b  (a2)+,y_vel(a1)       ; high byte of y velocity
     */
    private void fireProjectile() {
        int frameIdx = Math.max(0, Math.min(mappingFrame, 2));
        int[] spawnData = PROJECTILE_SPAWN_DATA[frameIdx];

        int projX = currentX + spawnData[0];
        int projY = currentY + spawnData[1];
        // ROM writes to high byte of x_vel/y_vel, so multiply by 256 for fixed-point 8.8
        int projXVel = spawnData[2] * 256;
        int projYVel = spawnData[3] * 256;

        spawnChild(() -> new WallTurretShotInstance(
                spawn, projX, projY, projXVel, projYVel));
    }

    private PlayableEntity closestOrientationPlayer(PlayableEntity updatePlayer) {
        var nearest = services().playerQuery().nearestByRomX(PLAYER_PARTICIPATION, currentX);
        if (nearest.player() != null) {
            return nearest.player();
        }
        return updatePlayer;
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
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.WFZ_WALL_TURRET);
        if (renderer == null || !renderer.isReady()) {
            appendDebugBox(commands);
            return;
        }

        // Mapping frames: 0=straight down, 1=barrel left, 2=barrel right
        int frame = Math.max(0, Math.min(mappingFrame, 2));
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }

    private void appendDebugBox(List<GLCommand> commands) {
        int halfWidth = WIDTH_PIXELS;
        int halfHeight = WIDTH_PIXELS;
        int left = currentX - halfWidth;
        int right = currentX + halfWidth;
        int top = currentY - halfHeight;
        int bottom = currentY + halfHeight;

        float r = 0.6f, g = 0.3f, b = 0.8f;
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

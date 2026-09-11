package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * AIZ2 end boss debris child (ROM: loc_47880 → Obj_FlickerMove).
 *
 * <p>Spawned as 6 pieces when the boss is defeated (ROM: ChildObjDat_47BBC).
 * Each piece uses a unique mapping frame ($32-$37) from Map_AIZEndBoss,
 * flies outward at a fixed velocity, and flickers (draws every other frame).
 *
 * <p>ROM attributes: word_47B68 — priority $180 (bucket 3), size $10x$10.
 * Inherits art_tile from parent (ArtTile_AIZEndBoss, priority=1).
 */
public class AizEndBossDebrisChild extends AbstractObjectInstance
        implements SpawnCoordinateZeroScalarArgsRewindRecreatable {

    // ROM: Obj_VelocityIndex entries 0-5, used with Set_IndexedVelocity d0=0
    // Each child's subtype (0,2,4,6,8,$A) indexes as subtype*2 bytes
    private static final int[][] DEBRIS_VELOCITIES = {
            {-0x100, -0x100},  // subtype 0: entry 0
            { 0x100, -0x100},  // subtype 2: entry 1
            {-0x200, -0x200},  // subtype 4: entry 2
            { 0x200, -0x200},  // subtype 6: entry 3
            {-0x300, -0x200},  // subtype 8: entry 4
            { 0x300, -0x200},  // subtype $A: entry 5
    };

    // ROM: ChildObjDat_47BBC offsets from parent position
    static final int[][] DEBRIS_OFFSETS = {
            {-0x10, -0x10},
            { 0x10, -0x10},
            {-0x10,  0x08},
            { 0x10,  0x08},
            {-0x0C,  0x18},
            { 0x0C,  0x18},
    };

    // ROM: mapping_frame = $32 + (subtype / 2) = frames $32-$37
    private static final int FRAME_BASE = 0x32;

    // ROM: MoveSprite applies addi.w #$38,y_vel(a0) each frame
    private static final int GRAVITY = 0x38;

    // mappingFrame/xVel are non-final so the rewind field capturer reapplies them
    // after spawn-coordinate recreate uses placeholder index 0.
    private int mappingFrame;
    private int xVel;  // 8:8 fixed-point
    private int yVel;        // 8:8 fixed-point, modified by gravity each frame
    private int posX;        // 16-bit pixel
    private int posY;
    private int xSub;        // fractional
    private int ySub;
    private int flickerCounter;

    AizEndBossDebrisChild(int x, int y, int index) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "AIZEndBossDebris");
        this.mappingFrame = FRAME_BASE + index;
        this.xVel = DEBRIS_VELOCITIES[index][0];
        this.yVel = DEBRIS_VELOCITIES[index][1];
        this.posX = x;
        this.posY = y;
        this.xSub = 0;
        this.ySub = 0;
        this.flickerCounter = 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM: MoveSprite — apply 8:8 fixed-point velocity then add gravity
        int xPos24 = (posX << 8) | (xSub & 0xFF);
        xPos24 += xVel;
        posX = xPos24 >> 8;
        xSub = xPos24 & 0xFF;

        int yPos24 = (posY << 8) | (ySub & 0xFF);
        yPos24 += yVel;
        posY = yPos24 >> 8;
        ySub = yPos24 & 0xFF;
        yVel += GRAVITY;  // ROM: addi.w #$38,y_vel(a0)

        flickerCounter++;

        // ROM: Sprite_CheckDeleteXY — delete when off-screen
        if (Math.abs(posX - services().camera().getX() - 160) > 320
                || Math.abs(posY - services().camera().getY() - 112) > 256) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        // ROM: Obj_FlickerMove toggles bit 6 of $38 each frame, draws only when
        // the bit transitions 1→0 (every other frame). Equivalent: draw on odd frames.
        if ((flickerCounter & 1) == 0) return;

        ObjectRenderManager rm = services().renderManager();
        if (rm == null) return;
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, posX, posY, false, false);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: Inherits art_tile from parent — make_art_tile(ArtTile_AIZEndBoss,0,1), priority=1
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: word_47B68 dc.w $180 → $180/$80 = bucket 3
        return 3;
    }
}

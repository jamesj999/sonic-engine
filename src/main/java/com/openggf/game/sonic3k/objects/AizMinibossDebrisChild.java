package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroPairRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * AIZ Miniboss (0x90) - Miniature background aircraft.
 * Six instances spawned at trigger, each flying right across the sky.
 *
 * ROM: loc_68DFA (init) → loc_68E7E (Obj_Wait) → loc_68E8C (MoveSprite2)
 * - Init sets $2E = x_vel (staggered wait: faster craft wait LONGER)
 * - After wait: MoveSprite2 applies velocity + gravity ($38/frame)
 * - Slower aircraft (x_vel=$100) appear first, fastest ($200) last
 */
public class AizMinibossDebrisChild extends AbstractObjectInstance
        implements SpawnCoordinateZeroPairRewindRecreatable {
    private int worldX;
    private int worldY;
    private int xFixed;
    private int yFixed;
    // xVel/mappingFrame are non-final so the rewind field capturer reapplies them
    // after spawn-coordinate recreate uses placeholders 0.
    private int xVel;
    private int waitTimer;
    private boolean moving;
    private int mappingFrame;

    public AizMinibossDebrisChild(int x, int y, int xVel, int mappingFrame) {
        super(new ObjectSpawn(x, y, 0x90, 0, 0, false, 0), "AIZMinibossDebris");
        this.worldX = x;
        this.worldY = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.xVel = xVel;
        // ROM: move.w word_68E60(pc,d0.w),$2E(a0) — wait = x_vel value
        this.waitTimer = xVel;
        this.moving = false;
        this.mappingFrame = mappingFrame;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!moving) {
            // ROM: loc_68E7E → Obj_Wait, decrements $2E each frame
            waitTimer--;
            if (waitTimer < 0) {
                moving = true;
            }
            return;
        }

        // ROM: loc_68E8C → MoveSprite2 (no gravity — horizontal flight only)
        xFixed += (xVel << 8);
        worldX = xFixed >> 16;

        if (!isOnScreen(256)) {
            setDestroyed(true);
        }
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjDat3_6904A priority $380 / $80 = 7
        return 7;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!moving) {
            return; // Not visible during wait phase
        }
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS_SMALL);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, worldX, worldY, false, false);
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }
}

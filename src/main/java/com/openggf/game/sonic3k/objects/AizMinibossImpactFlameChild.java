package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateSubtypeDefaultArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * AIZ miniboss missile-impact flame burst child (ROM: loc_68D88).
 * Uses boss-explosion style frames with staggered subtype-based start delay.
 */
public class AizMinibossImpactFlameChild extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateSubtypeDefaultArgsRewindRecreatable {
    private static final int COLLISION_FLAGS = 0x97;
    // byte_6916F
    private static final int[] FRAMES = {0, 0, 1, 2, 3, 4};
    private static final int[] DURATIONS = {1, 1, 2, 2, 4, 4};

    private int worldX;
    private int worldY;
    // hazardous is non-final so the rewind field capturer reapplies it after the
    // recreate hook rebuilds the impact flame from its spawn (placeholder false).
    private boolean hazardous;

    private int delayTimer;
    private boolean active;
    private int sequenceIndex;
    private int frameTimer;

    public AizMinibossImpactFlameChild(int x, int y, int subtype, boolean hazardous) {
        super(new ObjectSpawn(x, y, 0x90, subtype & 0xFF, 0, false, 0), "AIZMinibossImpactFlame");
        this.worldX = x;
        this.worldY = y;
        this.hazardous = hazardous;

        int rawDelay = (0x0C - (subtype & 0xFF)) * 2;
        this.delayTimer = Math.max(0, rawDelay);
        this.active = false;
        this.sequenceIndex = 0;
        this.frameTimer = DURATIONS[0];
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!active) {
            delayTimer--;
            if (delayTimer >= 0) {
                return;
            }
            active = true;
            frameTimer = DURATIONS[0];
            return;
        }

        frameTimer--;
        if (frameTimer > 0) {
            return;
        }

        sequenceIndex++;
        if (sequenceIndex >= FRAMES.length) {
            setDestroyed(true);
            return;
        }
        frameTimer = DURATIONS[sequenceIndex];
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!active || isDestroyed()) {
            return;
        }
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(ObjectArtKeys.EXPLOSION);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(FRAMES[sequenceIndex], worldX, worldY, false, false);
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }

    @Override
    public int getCollisionFlags() {
        if (!hazardous || !active || isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getPriorityBucket() {
        return 2;
    }
}

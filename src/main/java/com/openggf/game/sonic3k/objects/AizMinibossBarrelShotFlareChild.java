package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * AIZ miniboss barrel-shot muzzle flare child.
 *
 * ROM: loc_6880A / loc_68828 (ChildObjDat_6909A / ChildObjDat_690A8 first child)
 * - Uses Map_AIZMiniboss frames 7..B with per-frame delays from byte_6913F
 * - Palette line 0 (word_69022 make_art_tile(...,0,0))
 * - Lifetime is one short raw-animation sequence, then delete
 */
public class AizMinibossBarrelShotFlareChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int PALETTE_OVERRIDE = 0;
    private static final int Y_OFFSET = 4;

    // ROM byte_6913F (Animate_RawMultiDelay, first pair skipped on initial play):
    // Visible sequence: frame 7 (timer 1=2t), 8 (timer 1=2t), 9 (timer 3=4t),
    //                   10 (timer 3=4t), 11 (timer 3=4t) = 16 ticks
    private static final int[] FRAMES = {7, 8, 9, 10, 11};
    private static final int[] DURATIONS = {2, 2, 4, 4, 4};
    private final AbstractObjectInstance anchor;
    private int currentX;
    private int currentY;
    private int sequenceIndex;
    private int frameTimer;

    public AizMinibossBarrelShotFlareChild(AbstractObjectInstance anchor) {
        super(new ObjectSpawn(
                anchor != null ? anchor.getX() : 0,
                anchor != null ? anchor.getY() + Y_OFFSET : 0,
                0x90,
                0,
                0,
                false,
                0),
                "AIZMinibossBarrelShotFlare");
        this.anchor = anchor;
        this.sequenceIndex = 0;
        this.frameTimer = DURATIONS[0];
        syncToAnchor();
    }

    private AizMinibossBarrelShotFlareChild(ObjectSpawn spawn) {
        super(spawn, "AIZMinibossBarrelShotFlare");
        this.anchor = null;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.sequenceIndex = 0;
        this.frameTimer = DURATIONS[0];
    }

    @Override
    public AizMinibossBarrelShotFlareChild recreateForRewind(RewindRecreateContext ctx) {
        AizMinibossFlameBarrelChild anchor = AizMinibossRewindLinks.nearestBarrel(ctx);
        return anchor == null ? null : new AizMinibossBarrelShotFlareChild(anchor);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (anchor == null || anchor.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        syncToAnchor();

        if (--frameTimer > 0) {
            return;
        }

        sequenceIndex++;
        if (sequenceIndex >= FRAMES.length) {
            setDestroyed(true);
            return;
        }
        frameTimer = DURATIONS[sequenceIndex];
    }

    private void syncToAnchor() {
        currentX = anchor.getX();
        currentY = anchor.getY() + Y_OFFSET;
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
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(FRAMES[sequenceIndex], currentX, currentY, false, false, PALETTE_OVERRIDE);
    }

    @Override
    public int getPriorityBucket() {
        return 2;
    }
}

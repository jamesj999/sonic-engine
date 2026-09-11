package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * S3K SKL object $91 - MHZ miniboss tree.
 *
 * <p>ROM reference: {@code Obj_MHZMinibossTree}. The tree is a companion
 * component for the Act 1 miniboss encounter; it mirrors the boss' scratch
 * mapping frame and emits the falling log chips used during the trunk break
 * animation.
 */
public final class MhzMinibossTreeInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int BOSS_TREE_FRAME_OFFSET = 0x42;
    private static final int DEFAULT_MAPPING_FRAME = 5;
    private static final int PRIORITY_BUCKET = 7; // ObjDat_MHZMinibossTree priority $380
    private static final int RENDER_HALF_WIDTH = 0x14;
    private static final int RENDER_HALF_HEIGHT = 0x90;

    private int mappingFrame = DEFAULT_MAPPING_FRAME;

    public MhzMinibossTreeInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMinibossTree");
    }

    @Override
    public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        MhzMinibossInstance miniboss = findActiveMiniboss();
        if (miniboss == null) {
            return;
        }
        if (!isOnScreen()) {
            miniboss.setCustomFlag(BOSS_TREE_FRAME_OFFSET, DEFAULT_MAPPING_FRAME);
        }
        int nextFrame = miniboss.getCustomFlag(BOSS_TREE_FRAME_OFFSET) & 0xFF;
        if (nextFrame == mappingFrame) {
            return;
        }
        mappingFrame = nextFrame;
        if (nextFrame < DEFAULT_MAPPING_FRAME) {
            boolean slowBounceChip = shouldSpawnSlowBounceChip(nextFrame);
            spawnChild(() -> new MhzMinibossTreeChipInstance(spawn.x(), spawn.y(), nextFrame, slowBounceChip));
        }
    }

    private boolean shouldSpawnSlowBounceChip(int treeFrame) {
        ObjectServices svc = tryServices();
        int random = svc == null || svc.rng() == null ? 0 : svc.rng().nextRaw();
        if (treeFrame == 0 || !isKnucklesRuntime(svc)) {
            return false;
        }
        return (random & 1) != 0;
    }

    private static boolean isKnucklesRuntime(ObjectServices svc) {
        return svc != null
                && svc.zoneRuntimeState() instanceof MhzZoneRuntimeState state
                && state.playerCharacter() == PlayerCharacter.KNUCKLES;
    }

    private MhzMinibossInstance findActiveMiniboss() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return null;
        }
        ObjectManager objectManager = svc.objectManager();
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof MhzMinibossInstance miniboss && !miniboss.isDestroyed()) {
                return miniboss;
            }
        }
        return null;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_TREE);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    private static final class MhzMinibossTreeChipInstance extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnRewindRecreatable {
        private static final int[] Y_OFFSETS = { 0x40, 0x20, 0x00, -0x20, -0x40, 0x00 };
        private static final int[] ANIMATION_FRAMES = { 0, 1, 2, 3, 4, 5, 6, 7 };
        private static final int ANIMATION_DELAY = 1;
        private static final int DEFAULT_X_VELOCITY = -0x400;
        private static final int SLOW_X_VELOCITY = -0x200;
        private static final int BOUNCE_Y_RADIUS = 0x18;
        private static final int PRIORITY_BUCKET = 7;
        private static final int RENDER_HALF_WIDTH = 0x14;
        private static final int RENDER_HALF_HEIGHT = 0x14;
        private static final int COLLISION_FLAGS = 0x86;
        private final SubpixelMotion.State motion;
        private boolean bounceEnabled;
        private int animationTimer = ANIMATION_DELAY;
        private int animationIndex;
        private int chipMappingFrame = ANIMATION_FRAMES[0];

        private MhzMinibossTreeChipInstance(int parentX, int parentY, int parentFrame, boolean bounceEnabled) {
            super(new ObjectSpawn(parentX, parentY + yOffset(parentFrame),
                    Sonic3kObjectIds.MHZ_MINIBOSS_TREE, parentFrame, bounceEnabled ? 1 : 0, false, parentY),
                    "MHZMinibossTreeChip");
            this.bounceEnabled = bounceEnabled;
            this.motion = new SubpixelMotion.State(
                    parentX, parentY + yOffset(parentFrame), 0, 0,
                    bounceEnabled ? SLOW_X_VELOCITY : DEFAULT_X_VELOCITY, 0);
        }

        private MhzMinibossTreeChipInstance(ObjectSpawn spawn) {
            super(spawn, "MHZMinibossTreeChip");
            this.bounceEnabled = (spawn.renderFlags() & 1) != 0;
            this.motion = new SubpixelMotion.State(
                    spawn.x(), spawn.y(), 0, 0,
                    bounceEnabled ? SLOW_X_VELOCITY : DEFAULT_X_VELOCITY, 0);
        }

        private static int yOffset(int parentFrame) {
            int index = Math.max(0, Math.min(parentFrame, Y_OFFSETS.length - 1));
            return Y_OFFSETS[index];
        }

        @Override
        public int getX() {
            return motion.x;
        }

        @Override
        public int getY() {
            return motion.y;
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
            animateRawNoSst();
            if (bounceEnabled) {
                updateBounceVelocity();
            }
            SubpixelMotion.moveSprite2(motion);
            updateDynamicSpawn(motion.x, motion.y);
        }

        private void updateBounceVelocity() {
            motion.yVel += 0x20;
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(motion.x, motion.y, BOUNCE_Y_RADIUS);
            if (floor.hasCollision() && floor.distance() < 0) {
                motion.yVel = -motion.yVel;
            }
        }

        private void animateRawNoSst() {
            animationTimer--;
            if (animationTimer >= 0) {
                return;
            }
            animationTimer = ANIMATION_DELAY;
            animationIndex = (animationIndex + 1) % ANIMATION_FRAMES.length;
            chipMappingFrame = ANIMATION_FRAMES[animationIndex];
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(chipMappingFrame, motion.x, motion.y, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return PRIORITY_BUCKET;
        }

        @Override
        public int getOnScreenHalfWidth() {
            return RENDER_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return RENDER_HALF_HEIGHT;
        }

        @Override
        public int getCollisionFlags() {
            return COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }
}

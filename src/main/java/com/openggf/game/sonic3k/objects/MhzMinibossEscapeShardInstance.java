package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * MHZ miniboss child spawned by {@code ChildObjDat_75E9E}.
 */
final class MhzMinibossEscapeShardInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int INITIAL_PRIORITY_BUCKET = 6; // word_75E7E priority $300
    private static final int RETURN_PRIORITY_BUCKET = 3; // loc_7594C priority $180
    private static final int Y_RADIUS = 6;
    private static final int[] LOG_ANIMATION_SCRIPT = {
            0x18, 0x03,
            0x19, 0x03,
            0x1A, 0x03,
            0x1B, 0x03,
            0x1C, 0x0B,
            0xFC
    };

    private MhzMinibossInstance parent;
    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;
    private int mappingFrame;
    private int animFrame;
    private int animFrameTimer;
    private int routine;
    private int timer;
    private int priorityBucket;
    private boolean initialized;
    private boolean touchedFloor;

    MhzMinibossEscapeShardInstance(int x, int y, MhzMinibossInstance parent) {
        super(new ObjectSpawn(x, y, Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0),
                "MHZMinibossEscapeShard");
        this.x = x;
        this.y = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.parent = parent;
        this.mappingFrame = 0;
        this.priorityBucket = INITIAL_PRIORITY_BUCKET;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzMinibossInstance liveParent = findNearestLiveParentForRewind(ctx);
        if (liveParent == null || ctx == null || ctx.spawn() == null) {
            return null;
        }
        return new MhzMinibossEscapeShardInstance(ctx.spawn().x(), ctx.spawn().y(), liveParent);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (!initialized) {
            initializeLaunch(player);
            initialized = true;
        }
        if (routine == 0) {
            moveWithLightGravity();
            checkFloorBounce();
        } else if (routine == 4) {
            timer--;
            if (timer < 0) {
                startUpwardWaitAndSpawnSplinter();
            }
        } else if (routine == 6) {
            moveWithoutGravity();
            timer--;
            if (timer < 0) {
                startHoverWait();
            }
        } else if (routine == 8) {
            timer--;
            if (timer < 0) {
                startReturnFall();
            }
        } else if (routine == 0xA) {
            updateReturnFall();
        }
        animateLogShard();
    }

    private void initializeLaunch(PlayableEntity player) {
        yVel = -0x300;
        if (player == null) {
            xVel = 0;
            return;
        }

        int delta = (player.getCentreX() & 0xFFFF) - x;
        int magnitude = Math.abs(delta);
        xVel = (magnitude << 16) / 0x3800;
        if (delta < 0) {
            xVel = -xVel;
        }
    }

    private void moveWithLightGravity() {
        xFixed += xVel << 8;
        yFixed += yVel << 8;
        yVel += 0x20;
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    private void moveWithoutGravity() {
        xFixed += xVel << 8;
        yFixed += yVel << 8;
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    private void checkFloorBounce() {
        if (yVel < 0) {
            return;
        }
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, Y_RADIUS);
        if (!floor.hasCollision()) {
            return;
        }
        if (touchedFloor) {
            y += floor.distance();
            yFixed += floor.distance() << 16;
            routine = 4;
            timer = 0x1F;
            return;
        }

        touchedFloor = true;
        y += floor.distance();
        yFixed += floor.distance() << 16;
        xVel >>= 1;
        yVel = -(yVel >> 1);
    }

    private void startUpwardWaitAndSpawnSplinter() {
        routine = 6;
        xVel = 0;
        yVel = -0x200;
        timer = 0x1F;
        spawnChild(() -> new MhzMinibossEscapeShardSplinterInstance(this));
    }

    private void startHoverWait() {
        routine = 8;
        timer = 0x1F;
    }

    private void startReturnFall() {
        routine = 0xA;
        priorityBucket = RETURN_PRIORITY_BUCKET;
        yVel = -0x300;

        int delta = parent.getX() - 6 - x;
        int magnitude = Math.abs(delta);
        xVel = (magnitude << 16) / 0x6100;
        if (delta < 0) {
            xVel = -xVel;
        }
    }

    private void updateReturnFall() {
        yVel += 0x10;
        moveWithoutGravity();
        if (yVel < 0) {
            return;
        }
        if (y >= parent.getY() - 8) {
            parent.setCustomFlag(0x38, parent.getCustomFlag(0x38) | 0x04);
            setDestroyed(true);
        }
    }

    private void animateLogShard() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }

        int command = LOG_ANIMATION_SCRIPT[animFrame] & 0xFF;
        if (command == 0xFC) {
            animFrame = 0;
            command = LOG_ANIMATION_SCRIPT[animFrame] & 0xFF;
        }
        mappingFrame = command;
        animFrameTimer = LOG_ANIMATION_SCRIPT[animFrame + 1] & 0xFF;
        animFrame += 2;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    private static MhzMinibossInstance findNearestLiveParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        MhzMinibossInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof MhzMinibossInstance candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (spawn == null) {
                return candidate;
            }
            long dx = candidate.getX() - spawn.x();
            long dy = candidate.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}

/**
 * Attached MHZ miniboss shard visual spawned by {@code ChildObjDat_75EA6}.
 */
final class MhzMinibossEscapeShardSplinterInstance extends AbstractObjectInstance {
    private static final int PRIORITY_BUCKET = 5;
    private static final int CHILD_DX = 1;
    private static final int CHILD_DY = 0x13;
    private static final int MAPPING_FRAME = 0x16;

    private final MhzMinibossEscapeShardInstance parent;
    private int x;
    private int y;

    MhzMinibossEscapeShardSplinterInstance(MhzMinibossEscapeShardInstance parent) {
        super(new ObjectSpawn(parent.getX() + CHILD_DX, parent.getY() + CHILD_DY,
                        Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0),
                "MHZMinibossEscapeShardSplinter");
        this.parent = parent;
        refreshPosition();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        if ((vIntRunCount & 1) == 0) {
            refreshPosition();
        }
    }

    private void refreshPosition() {
        x = parent.getX() + CHILD_DX;
        y = parent.getY() + CHILD_DY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS_LOG);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(MAPPING_FRAME, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }
}

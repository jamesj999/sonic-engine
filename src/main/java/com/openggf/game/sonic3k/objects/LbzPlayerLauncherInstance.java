package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K S3KL object $15 - Launch Base player launcher.
 *
 * <p>ROM reference: {@code Obj_LBZPlayerLauncher} (sonic3k.asm:51811-52039).
 */
public final class LbzPlayerLauncherInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int FAST_LAUNCH_SPEED = 0x1000;
    private static final int SLOW_LAUNCH_SPEED = 0x0A00;
    private static final int DETECT_HALF_SIZE = 0x10;
    private static final int LAUNCH_COUNTER_FRAME = 4;
    private static final int MOVE_LOCK_FRAMES = 15;
    private static final int MIN_SKIP_LAUNCH_SPEED = 0x1000;

    private int launchSpeed;
    private boolean facingLeft;

    private int p1Counter;
    private int p2Counter;

    public LbzPlayerLauncherInstance(ObjectSpawn spawn) {
        super(spawn, "LBZPlayerLauncher");
        this.launchSpeed = (spawn.subtype() & 0x02) == 0 ? FAST_LAUNCH_SPEED : SLOW_LAUNCH_SPEED;
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        int left = spawn.x() - DETECT_HALF_SIZE;
        int right = spawn.x() + DETECT_HALF_SIZE;
        int top = spawn.y() - DETECT_HALF_SIZE;
        int bottom = spawn.y() + DETECT_HALF_SIZE;

        AbstractPlayableSprite p1 = playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        if (insideTrigger(p1, left, right, top, bottom)) {
            p1Counter = processPlayer(p1, p1Counter, true);
        }

        AbstractPlayableSprite p2 = nativeP2OrNull();
        if (insideTrigger(p2, left, right, top, bottom)) {
            p2Counter = processPlayer(p2, p2Counter, false);
        }

        if (!spriteOnScreenTestPasses()) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_PLAYER_LAUNCHER);
        if (renderer != null) {
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), facingLeft, false);
        }
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x20;
    }

    private boolean insideTrigger(AbstractPlayableSprite player, int left, int right, int top, int bottom) {
        if (player == null || player.getAir()) {
            return false;
        }
        int x = player.getCentreX() & 0xFFFF;
        int y = player.getCentreY() & 0xFFFF;
        return x >= left && x < right && y >= top && y < bottom;
    }

    private boolean spriteOnScreenTestPasses() {
        int cameraCoarseBack = ((services().camera().getX() & 0xFFFF) - 0x80) & 0xFF80;
        int objectCoarse = spawn.x() & 0xFF80;
        int unsignedDistance = (objectCoarse - cameraCoarseBack) & 0xFFFF;
        return unsignedDistance <= 0x280;
    }

    private int processPlayer(AbstractPlayableSprite player, int counter, boolean nativeP1) {
        if (counter == 0) {
            spawnChild(() -> new LauncherArmChild(
                    new ObjectSpawn(spawn.x(), spawn.y(), Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER,
                            spawn.subtype(), spawn.renderFlags(), false, 0),
                    this,
                    nativeP1));
        }

        counter++;
        if (counter != LAUNCH_COUNTER_FRAME) {
            dampenApproachVelocity(player);
            return counter;
        }

        maybeLaunch(player);
        services().playSfx(GameSound.SPRING);
        return counter;
    }

    private void dampenApproachVelocity(AbstractPlayableSprite player) {
        int xVel = player.getXSpeed();
        int facingAdjusted = facingLeft ? -xVel : xVel;
        if (facingAdjusted < 0) {
            player.setGSpeed((short) (player.getGSpeed() >> 1));
            player.setXSpeed((short) (xVel >> 1));
        }
    }

    private void maybeLaunch(AbstractPlayableSprite player) {
        int xVel = player.getXSpeed();
        int facingAdjusted = facingLeft ? -xVel : xVel;
        if (facingAdjusted >= MIN_SKIP_LAUNCH_SPEED) {
            return;
        }

        int launch = facingLeft ? -launchSpeed : launchSpeed;
        player.setXSpeed((short) launch);
        player.setGSpeed((short) launch);
        player.setDirection(facingLeft ? Direction.LEFT : Direction.RIGHT);
        player.setMoveLockTimer(MOVE_LOCK_FRAMES);
        // ROM sub_261F2 clears both of the launcher's own pushing bits before
        // the character's Status_Push (docs/skdisasm/sonic3k.asm:51930-51932).
        services().objectManager().solidContacts().releaseObjectPushLatchForAllPlayers(this);
        player.setPushing(false);
    }

    private AbstractPlayableSprite nativeP2OrNull() {
        try {
            PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
            return nativeP2 instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private void resetCounter(boolean nativeP1) {
        if (nativeP1) {
            p1Counter = 0;
        } else {
            p2Counter = 0;
        }
    }

    private static final class LauncherArmChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int FRAME_ARM = 1;
        private static final int CHILD_SPRITE_COUNT = 4;
        private static final int INITIAL_ANGLE = 0x80;
        private static final int EXPAND_STEP = 0x10;
        private static final int EXPAND_END = 0xD0;
        private static final int RETRACT_STEP = 4;

        private LbzPlayerLauncherInstance parent;
        private boolean nativeP1;
        private int baseX;
        private int baseY;
        private boolean facingLeft;
        private final int[] segmentX = new int[CHILD_SPRITE_COUNT + 1];
        private final int[] segmentY = new int[CHILD_SPRITE_COUNT + 1];

        private int routine;
        private int angle = INITIAL_ANGLE;

        private LauncherArmChild(ObjectSpawn spawn) {
            super(spawn, "LBZPlayerLauncherArm");
            this.parent = null;
            this.nativeP1 = false;
            this.baseX = spawn.x();
            this.baseY = spawn.y() + 0x10;
            this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
            updateSegmentPositions();
            updateDynamicSpawn(segmentX[CHILD_SPRITE_COUNT], segmentY[CHILD_SPRITE_COUNT]);
        }

        private LauncherArmChild(ObjectSpawn spawn, LbzPlayerLauncherInstance parent, boolean nativeP1) {
            super(spawn, "LBZPlayerLauncherArm");
            this.parent = parent;
            this.nativeP1 = nativeP1;
            this.baseX = spawn.x();
            this.baseY = spawn.y() + 0x10;
            this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
            updateSegmentPositions();
            updateDynamicSpawn(segmentX[CHILD_SPRITE_COUNT], segmentY[CHILD_SPRITE_COUNT]);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            LbzPlayerLauncherInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, LbzPlayerLauncherInstance.class);
            if (liveParent == null) {
                return null;
            }
            return new LauncherArmChild(ctx.spawn(), liveParent, false);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (routine == 0) {
                angle += EXPAND_STEP;
                if (angle == EXPAND_END) {
                    parent.resetCounter(nativeP1);
                    routine = 2;
                }
            } else {
                angle -= RETRACT_STEP;
                if (angle == INITIAL_ANGLE) {
                    updateDynamicSpawn(0x7F00, getY());
                    setDestroyedByOffscreen();
                    return;
                }
            }
            updateSegmentPositions();
            updateDynamicSpawn(segmentX[CHILD_SPRITE_COUNT], segmentY[CHILD_SPRITE_COUNT]);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_PLAYER_LAUNCHER);
            if (renderer == null) {
                return;
            }
            for (int i = 0; i < CHILD_SPRITE_COUNT; i++) {
                renderer.drawFrameIndex(FRAME_ARM, segmentX[i], segmentY[i], facingLeft, false);
            }
            renderer.drawFrameIndex(FRAME_ARM, segmentX[CHILD_SPRITE_COUNT],
                    segmentY[CHILD_SPRITE_COUNT], facingLeft, false);
        }

        @Override
        public int getOnScreenHalfHeight() {
            return 0x20;
        }

        private void updateSegmentPositions() {
            int renderAngle = facingLeft ? ((-angle + 0x80) & 0xFF) : (angle & 0xFF);
            int yStep = TrigLookupTable.sinHex(renderAngle) << 4;
            int xStep = TrigLookupTable.cosHex(renderAngle) << 4;
            int xAccum = xStep;
            int yAccum = yStep;
            for (int i = 0; i < segmentX.length; i++) {
                segmentX[i] = baseX + (xAccum >> 8);
                segmentY[i] = baseY + (yAccum >> 8);
                xAccum += xStep;
                yAccum += yStep;
            }
        }
    }
}

package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x5C — MGZ Top Launcher.
 * Owns the visible base and a passive Obj_MGZTopPlatform child until release.
 */
public class MGZTopLauncherObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_TOP_LAUNCHER;
    private static final int PRIORITY_BUCKET = 4;
    private static final int BASE_FRAME = 2;
    private static final int DROP_DISTANCE = 0x10;
    private static final int LAUNCH_TRIGGER_REMAINING = 4;
    private static final int LAUNCH_SPEED = 0x0C00;

    private boolean hFlip;
    private int posX;
    private int posY;
    private int remainingDrop = DROP_DISTANCE;
    private int launchVelocity;
    private MGZTopPlatformObjectInstance child;
    private boolean childSpawned;
    private boolean descending;

    public MGZTopLauncherObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZTopLauncher");
        this.posX = spawn.x();
        this.posY = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.launchVelocity = hFlip ? -LAUNCH_SPEED : LAUNCH_SPEED;
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
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureChild();
        if (child == null || child.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (!descending) {
            syncChild();
            if (child.isAnyPlayerGrabbed()) {
                descending = true;
            }
        } else {
            posY += 1;
            remainingDrop--;
            if (remainingDrop <= 0) {
                setDestroyed(true);
                return;
            }
            if (remainingDrop > LAUNCH_TRIGGER_REMAINING) {
                syncChild();
            } else if (remainingDrop == LAUNCH_TRIGGER_REMAINING) {
                child.activateFromLauncher(launchVelocity);
            }
        }

        updateDynamicSpawn(posX, posY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(BASE_FRAME, posX, posY, hFlip, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(posX, posY, 0x0C, 0x08, 0.8f, descending ? 0.4f : 0.8f, 0.2f);
    }

    private void ensureChild() {
        if (child != null && !child.isDestroyed()) {
            return;
        }
        if (childSpawned) {
            return;
        }
        child = spawnChild(() -> new MGZTopPlatformObjectInstance(
                new ObjectSpawn(posX, posY, Sonic3kObjectIds.MGZ_TOP_PLATFORM, 1,
                        spawn.renderFlags(), false, spawn.rawYWord(), spawn.layoutIndex())));
        childSpawned = true;
        syncChild();
    }

    private void syncChild() {
        child.syncFromLauncher(posX, posY);
        child.advanceAnimationTimer(4);
    }

    @Override
    public void onUnload() {
        if (child != null && !child.isDestroyed() && !child.isBodyDriven()) {
            child.setDestroyed(true);
        }
        child = null;
        childSpawned = false;
    }
}

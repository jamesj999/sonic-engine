package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * SlidingSpike (Obj43) - Oil Ocean paired sliding spike obstacle.
 */
public class SlidingSpikeObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int COLLISION_FLAGS = 0xA5;
    private static final int WIDTH_PIXELS = 0x18;
    private static final int HALF_HEIGHT = 0x28;

    private int originX;
    private int originY;
    private boolean child;
    private int childOffsetX;
    private int currentX;
    private int currentY;
    private int minX;
    private int maxX;
    private int direction;
    private boolean childSpawned;

    private SlidingSpikeObjectInstance peer;

    public SlidingSpikeObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, propertiesFor(spawn.subtype()), false, null);
    }

    private SlidingSpikeObjectInstance(ObjectSpawn spawn, String name, Properties properties,
                                       boolean child, SlidingSpikeObjectInstance peer) {
        super(spawn, name);
        this.originX = spawn.x();
        this.originY = spawn.y();
        this.child = child;
        this.peer = peer;
        this.childOffsetX = properties.childOffsetX;
        this.direction = child ? 1 : 0;

        this.minX = originX - properties.originSpan;
        this.maxX = originX + properties.originSpan;
        this.currentX = originX + (child ? properties.childOffsetX : properties.parentOffsetX);
        this.currentY = originY;
        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public SlidingSpikeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SlidingSpikeObjectInstance(ctx.spawn(), getName());
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
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        ensureChildSpawned();
        moveOnePixel();
        if (child) {
            reversePairWhenTouching();
        } else {
            deletePairWhenRangeLeavesScreen();
        }
        updateDynamicSpawn(currentX, currentY);
    }

    private void deletePairWhenRangeLeavesScreen() {
        ObjectServices services = tryServices();
        if (services == null || services.camera() == null || !isCustomRangeOutOfRange(services.camera().getX())) {
            return;
        }
        setDestroyedByOffscreen();
        if (peer != null && peer != this) {
            peer.setDestroyedByOffscreen();
        }
    }

    private void ensureChildSpawned() {
        if (child || childSpawned || childOffsetX == 0) {
            return;
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        childSpawned = true;
        ObjectSpawn childSpawn = new ObjectSpawn(originX, originY, spawn.objectId(), spawn.subtype(),
                spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord());
        SlidingSpikeObjectInstance childSpike = spawnChild(
                () -> new SlidingSpikeObjectInstance(childSpawn, getName(), propertiesFor(spawn.subtype()), true, this));
        childSpike.peer = this;
        this.peer = childSpike;
    }

    private void moveOnePixel() {
        if (direction != 0) {
            currentX++;
            if (currentX == maxX) {
                direction = 0;
                playSlideSound();
            }
            return;
        }
        currentX--;
        if (currentX == minX) {
            direction = 1;
            playSlideSound();
        }
    }

    private void reversePairWhenTouching() {
        if (peer == null) {
            return;
        }
        if (peer.currentX + WIDTH_PIXELS == currentX - WIDTH_PIXELS) {
            direction ^= 1;
            peer.direction ^= 1;
            playSlideSound();
        }
    }

    private void playSlideSound() {
        ObjectServices services = tryServices();
        if (services != null && isOnScreen()) {
            services.playSfx(Sonic2Sfx.SLIDING_SPIKE.id);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.OOZ_SLIDING_SPIKE);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndexForcedPriority(0, currentX, currentY, false, false, -1, true);
        }
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HALF_HEIGHT;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean checksOutOfRangeAfterRoutine() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    private boolean isCustomRangeOutOfRange(int cameraX) {
        return !child && outOfRange(minX, cameraX) && outOfRange(maxX, cameraX);
    }

    private static boolean outOfRange(int x, int cameraX) {
        int objectCoarse = x & 0xFF80;
        int cameraCoarse = cameraX & 0xFF80;
        int distance = (objectCoarse - cameraCoarse) & 0xFFFF;
        return distance > 0x280;
    }

    private static Properties propertiesFor(int subtype) {
        return switch (subtype & 0xFF) {
            // Obj43_Init: move.b originXOffset,d1 after moveq #0, so negative
            // table bytes become unsigned travel spans ($E8 / $A8).
            case 0x06 -> new Properties(0xE8, -0x18, 0x18);
            case 0x0C -> new Properties(0xA8, -0x58, -0x28);
            default -> new Properties(0x68, 0, 0);
        };
    }

    private record Properties(int originSpan, int parentOffsetX, int childOffsetX) {}
}

package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.AnimalType;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnDefaultArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MGZ2 floating egg-prison animal.
 *
 * <p>ROM: Obj_EggCapsule's MGZ routine keeps the released animals orbiting the
 * carried player while the level-results object is active, then sends them left
 * when the results flag clears and the fade-out/fly-off sequence begins.
 */
public class Mgz2CapsuleAnimalInstance extends AbstractObjectInstance
        implements SpawnDefaultArgsRewindRecreatable {
    private static final int FRAMES_PER_MAPPING = 3;
    private static final int ART_VARIANT_COUNT = 2;

    // Un-finaled for rewind: index (animal loop counter) and artVariant (RNG-derived
    // at spawn) are not spawn-derivable, so recreate uses placeholder values and
    // GenericFieldCapturer (which skips final scalars) reapplies the captured values
    // after recreate.
    private int index;
    private int artVariant;
    private int currentX;
    private int currentY;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int waitDelay;
    private int frameCounter;
    private int mappingSetIndex;
    private boolean released;

    public Mgz2CapsuleAnimalInstance(ObjectSpawn spawn, int delay, int artVariant, int index) {
        super(spawn, "MGZ2CapsuleAnimal");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.waitDelay = Math.max(0, delay);
        this.artVariant = artVariant & (ART_VARIANT_COUNT - 1);
        this.index = index;
        this.mappingSetIndex = AnimalType.RABBIT.mappingSet().ordinal();
    }

    private Mgz2CapsuleAnimalInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (waitDelay > 0) {
            waitDelay--;
            return;
        }

        frameCounter++;
        if (!released && resultsComplete()) {
            released = true;
        }

        if (released) {
            currentX -= 2;
            if (!isOnScreen(96)) {
                setDestroyed(true);
            }
            return;
        }

        if (player != null) {
            accelerateTowardPlayer(player);
            moveSprite2();
        } else {
            xVelocity = clampToSignedLimit(xVelocity - 0x10, 0x300);
            moveSprite2();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || waitDelay > 0) {
            return;
        }
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getAnimalRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        updateMappingSet(renderManager);
        boolean hFlip = released;
        renderer.drawFrameIndex(getFrameIndex(), currentX, currentY, hFlip, false);
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
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    private boolean resultsComplete() {
        GameStateManager gameState = services().gameState();
        return gameState != null && gameState.isEndOfLevelFlag();
    }

    private void accelerateTowardPlayer(PlayableEntity player) {
        int targetX = player.getCentreX();
        int targetY = player.getCentreY() - 0x30 - index;
        xVelocity = accelerateToward(currentX, targetX, xVelocity, 0x300, 0x10);
        yVelocity = accelerateToward(currentY, targetY, yVelocity, 0x100, 0x10);
    }

    private static int accelerateToward(int current, int target, int velocity, int limit, int acceleration) {
        int delta = target >= current ? acceleration : -acceleration;
        int next = velocity + delta;
        if (next > limit || next < -limit) {
            return velocity;
        }
        return next;
    }

    private static int clampToSignedLimit(int value, int limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private void moveSprite2() {
        int fixedX = (currentX << 8) | (xSubpixel & 0xFF);
        fixedX += xVelocity;
        currentX = fixedX >> 8;
        xSubpixel = fixedX & 0xFF;

        int fixedY = (currentY << 8) | (ySubpixel & 0xFF);
        fixedY += yVelocity;
        currentY = fixedY >> 8;
        ySubpixel = fixedY & 0xFF;
    }

    private void updateMappingSet(ObjectRenderManager renderManager) {
        int typeA = renderManager.getAnimalTypeA();
        int typeB = renderManager.getAnimalTypeB();
        int animalIndex = artVariant == 0 ? typeA : typeB;
        mappingSetIndex = AnimalType.fromIndex(animalIndex).mappingSet().ordinal();
    }

    private int getFrameIndex() {
        int animFrame = 1 + ((frameCounter >> 2) & 1);
        int base = ((mappingSetIndex * ART_VARIANT_COUNT) + artVariant) * FRAMES_PER_MAPPING;
        return base + animFrame;
    }

    @Override
    protected boolean isOnScreen(int margin) {
        Camera camera = services().camera();
        if (camera == null) {
            return true;
        }
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        return currentX >= cameraX - margin
                && currentX <= cameraX + viewportWidth() + margin
                && currentY >= cameraY - margin
                && currentY <= cameraY + viewportHeight() + margin;
    }
}

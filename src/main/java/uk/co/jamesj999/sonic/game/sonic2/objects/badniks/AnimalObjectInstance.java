package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.camera.Camera;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class AnimalObjectInstance extends uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance {
    private static final int GRAVITY = 0x38;
    private static final int FLY_GRAVITY = 0x18;
    private static final int INITIAL_POP_VEL = -0x400;
    private static final int ANIM_TIMER_INIT = 7;
    private static final int FRAMES_PER_MAPPING = 3;
    private static final int ART_VARIANT_COUNT = 2;

    private enum State {
        MAIN,
        WALK,
        FLY
    }

    private final PatternSpriteRenderer renderer;
    private final LevelManager levelManager;
    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int groundXVelocity;
    private int groundYVelocity;
    private int animFrameTimer;
    private int animFrame;
    private int mappingSetIndex;
    private int artVariant;
    private State state;
    private AnimalType definition;

    public AnimalObjectInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, "Animal");
        this.levelManager = levelManager;
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        this.renderer = renderManager != null ? renderManager.getAnimalRenderer() : null;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrameTimer = ANIM_TIMER_INIT;
        this.animFrame = 2;
        this.state = State.MAIN;

        int typeA = AnimalType.RABBIT.ordinal();
        int typeB = AnimalType.RABBIT.ordinal();
        if (renderManager != null) {
            typeA = renderManager.getAnimalTypeA();
            typeB = renderManager.getAnimalTypeB();
        }

        this.artVariant = java.util.concurrent.ThreadLocalRandom.current().nextInt(ART_VARIANT_COUNT);
        int animalIndex = artVariant == 0 ? typeA : typeB;
        this.definition = AnimalType.fromIndex(animalIndex);
        this.mappingSetIndex = definition.mappingSet().ordinal();
        this.groundXVelocity = definition.xVel();
        this.groundYVelocity = definition.yVel();
        this.xVelocity = 0;
        this.yVelocity = INITIAL_POP_VEL;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case MAIN -> updateMain();
            case WALK -> updateWalk();
            case FLY -> updateFly();
        }
    }

    private void updateMain() {
        objectMoveAndFall();
        if (yVelocity >= 0 && checkFloorCollision()) {
            xVelocity = groundXVelocity;
            yVelocity = groundYVelocity;
            animFrame = 1;
            state = definition.flying() ? State.FLY : State.WALK;
        }
        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void updateWalk() {
        objectMoveAndFall();
        animFrame = 1;
        if (yVelocity >= 0) {
            animFrame = 0;
            if (checkFloorCollision()) {
                yVelocity = groundYVelocity;
            }
        }
        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void updateFly() {
        objectMove();
        yVelocity += FLY_GRAVITY;
        if (yVelocity >= 0 && checkFloorCollision()) {
            yVelocity = groundYVelocity;
        }

        animFrameTimer--;
        if (animFrameTimer < 0) {
            animFrameTimer = 1;
            animFrame = (animFrame + 1) & 1;
        }

        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void objectMoveAndFall() {
        yVelocity += GRAVITY;
        objectMove();
    }

    private void objectMove() {
        currentX += (xVelocity >> 8);
        currentY += (yVelocity >> 8);
    }

    private boolean checkFloorCollision() {
        int checkX = currentX;
        int checkY = currentY + 12;

        uk.co.jamesj999.sonic.level.ChunkDesc chunk = levelManager.getChunkDescAt((byte) 0, checkX, checkY);
        if (chunk == null) {
            return false;
        }

        int solidityBit = 0;
        if (!chunk.isSolidityBitSet(solidityBit)) {
            return false;
        }

        uk.co.jamesj999.sonic.level.SolidTile tile = levelManager.getSolidTileForChunkDesc(chunk, solidityBit);
        if (tile == null) {
            return false;
        }

        int blockTop = (checkY & ~15);
        int indexInBlock = checkX & 15;
        if (chunk.getHFlip()) {
            indexInBlock = 15 - indexInBlock;
        }
        int height = tile.getHeightAt((byte) indexInBlock);
        if (height <= 0) {
            return false;
        }

        int surfaceY = blockTop + 16 - height;
        if (checkY < surfaceY) {
            return false;
        }

        currentY = surfaceY - 12;
        return true;
    }

    private int getFrameIndex() {
        int base = ((mappingSetIndex * ART_VARIANT_COUNT) + artVariant) * FRAMES_PER_MAPPING;
        return base + animFrame;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = xVelocity < 0;
        renderer.drawFrameIndex(getFrameIndex(), currentX, currentY, hFlip, false);
    }

    private boolean onScreen(int margin) {
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int screenWidth = 320;
        if (currentX < cameraX - margin || currentX > cameraX + screenWidth + margin)
            return false;
        return true;
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        return currentY;
    }

    public void setX(int x) {
        this.currentX = x;
    }

    public void setY(int y) {
        this.currentY = y;
    }
}

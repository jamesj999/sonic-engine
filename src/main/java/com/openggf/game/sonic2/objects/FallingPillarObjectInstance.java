package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 23 - Falling Pillar from ARZ.
 * <p>
 * A pillar that drops its lower section when the player gets close.
 * Consists of two parts:
 * <ul>
 *   <li>Top section (frame 0): static, solid platform at original position</li>
 *   <li>Bottom section (frame 1-2): spawned child that shakes, falls, and lands</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 51160-51294 (Obj23 code)
 * <p>
 * <b>Routine Secondary states:</b>
 * <ul>
 *   <li>0: Static top part (no child behavior)</li>
 *   <li>2: Child waiting for player proximity trigger</li>
 *   <li>4: Child shaking (8 frames)</li>
 *   <li>6: Child falling with gravity</li>
 * </ul>
 */
public class FallingPillarObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(FallingPillarObjectInstance.class.getName());

    private static final int PALETTE_INDEX = 1;
    private static final int TOP_HALF_WIDTH = 0x10;
    private static final int TOP_HALF_HEIGHT = 0x20;
    private static final int CHILD_HALF_WIDTH = 0x10;
    private static final int CHILD_HALF_HEIGHT = 0x10;
    private static final int CHILD_Y_OFFSET = 0x30;
    private static final int TRIGGER_DISTANCE = 0x80;
    private static final int SHAKE_DURATION = 8;
    private static final int GRAVITY = 0x38;
    private static final int OFFSCREEN_Y_MARGIN = 0x120;

    private static final byte[] SHAKE_OFFSETS = { 0, 1, -1, 1, 0, -1, 0, 1 };

    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    private boolean isChild;
    private int x;
    private int y;
    private int baseX;
    private int routineSecondary;
    private int shakeTimer;
    private int yVel;
    private int mappingFrame;
    private int yFixed;
    private FallingPillarObjectInstance childInstance;
    private boolean childSpawned;

    public FallingPillarObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, false, spawn.y());
    }

    @Override
    public FallingPillarObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new FallingPillarObjectInstance(ctx.spawn(), getName());
    }

    private FallingPillarObjectInstance(ObjectSpawn spawn, String name, boolean isChild, int childY) {
        super(spawn, name);
        this.isChild = isChild;
        this.x = spawn.x();
        this.baseX = spawn.x();

        if (isChild) {
            this.y = childY;
            this.yFixed = childY << 8;
            this.mappingFrame = 1;
            this.routineSecondary = 2;
        } else {
            this.y = spawn.y();
            this.yFixed = spawn.y() << 8;
            this.mappingFrame = 0;
            this.routineSecondary = 0;
        }

        updateDynamicSpawn(x, y);
    }

    public FallingPillarObjectInstance createChild() {
        int childY = spawn.y() + CHILD_Y_OFFSET;
        return new FallingPillarObjectInstance(spawn, name, true, childY);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }
    public FallingPillarObjectInstance getChildInstance() {
        return childInstance;
    }

    public boolean isChildSpawned() {
        return childSpawned;
    }

    public void markChildSpawned(FallingPillarObjectInstance child) {
        this.childInstance = child;
        this.childSpawned = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!isChild && !childSpawned) {
            spawnChild();
        }
        if (isChild) {
            updateChild(player);
        }
        updateDynamicSpawn(x, y);
    }

    private void spawnChild() {
        if (services().objectManager() == null) {
            return;
        }
        FallingPillarObjectInstance child = spawnChild(this::createChild);
        markChildSpawned(child);
    }

    private void updateChild(AbstractPlayableSprite player) {
        switch (routineSecondary) {
            case 2 -> updateWaitForTrigger(player);
            case 4 -> updateShaking();
            case 6 -> updateFalling();
        }
    }

    private void updateWaitForTrigger(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        int dx = x - player.getCentreX();
        if (dx < 0) {
            dx = -dx;
        }
        if (dx < TRIGGER_DISTANCE) {
            routineSecondary = 4;
            shakeTimer = SHAKE_DURATION;
        }
    }

    private void updateShaking() {
        shakeTimer--;
        if (shakeTimer < 0) {
            routineSecondary = 6;
            x = baseX;
            return;
        }
        int offset = SHAKE_OFFSETS[shakeTimer & 0x07];
        x = baseX + offset;
    }

    private void updateFalling() {
        yFixed += yVel;
        yVel += GRAVITY;
        y = yFixed >> 8;

        // Real-time floor check using ObjectTerrainUtils (mirrors ROM's ObjCheckFloorDist)
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, CHILD_HALF_HEIGHT);
        if (result.hasCollision()) {
            // Hit floor - snap to surface
            y = y + result.distance();
            yFixed = y << 8;
            yVel = 0;
            mappingFrame = 2;
            routineSecondary = 0;
            return;
        }

        // Off-screen cleanup
        int cameraMaxY = services().camera().getMaxY();
        if (y > cameraMaxY + OFFSCREEN_Y_MARGIN) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ23_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj23");
        if (mappings.isEmpty()) {
            return;
        }

        int frame = mappingFrame;
        if (frame < 0 || frame >= mappings.size()) {
            frame = 0;
        }

        SpriteMappingFrame mapping = mappings.get(frame);
        if (mapping == null || mapping.pieces().isEmpty()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        GraphicsManager graphicsManager = services().graphicsManager();
        List<SpriteMappingPiece> pieces = mapping.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    x,
                    y,
                    0,
                    PALETTE_INDEX,
                    hFlip,
                    vFlip,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (isChild) {
            return SolidObjectParams.of(
                    CHILD_HALF_WIDTH + 0x0B,
                    CHILD_HALF_HEIGHT,
                    CHILD_HALF_HEIGHT + 1
            );
        }
        return SolidObjectParams.of(
                TOP_HALF_WIDTH + 0x0B,
                TOP_HALF_HEIGHT,
                TOP_HALF_HEIGHT + 1
        );
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = isChild ? CHILD_HALF_WIDTH : TOP_HALF_WIDTH;
        int halfHeight = isChild ? CHILD_HALF_HEIGHT : TOP_HALF_HEIGHT;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(right, top, right, bottom, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(right, bottom, left, bottom, 0.6f, 0.4f, 0.2f);
        ctx.drawLine(left, bottom, left, top, 0.6f, 0.4f, 0.2f);
    }

}

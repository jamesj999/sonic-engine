package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class SpringObjectInstance extends BoxObjectInstance implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider {
    private static final int HALF_SIZE_VERTICAL = 0x10;
    private static final int HALF_SIZE_HORIZONTAL = 0x08;
    private static final byte[] SLOPE_DIAG_UP = {
            0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10,
            0x10, 0x10, 0x10, 0x10, 0x0E, 0x0C, 0x0A, 0x08,
            0x06, 0x04, 0x02, 0x00, (byte) 0xFE, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC,
            (byte) 0xFC, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC
    };
    private static final byte[] SLOPE_DIAG_DOWN = {
            (byte) 0xF4, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF2, (byte) 0xF4, (byte) 0xF6, (byte) 0xF8,
            (byte) 0xFA, (byte) 0xFC, (byte) 0xFE, 0x00,
            0x02, 0x04, 0x04, 0x04,
            0x04, 0x04, 0x04, 0x04
    };

    private static final int ANIM_VERTICAL_IDLE = 0;
    private static final int ANIM_VERTICAL_TRIGGER = 1;
    private static final int ANIM_HORIZONTAL_IDLE = 2;
    private static final int ANIM_HORIZONTAL_TRIGGER = 3;
    private static final int ANIM_DIAGONAL_IDLE = 4;
    private static final int ANIM_DIAGONAL_TRIGGER = 5;

    private final boolean redSpring;
    private final ObjectAnimationState animationState;
    private final int idleAnimId;
    private final int triggeredAnimId;
    private int mappingFrame;

    public SpringObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.85f, 0.1f, false);
        this.redSpring = (spawn.subtype() & 0x02) != 0;
        this.idleAnimId = resolveIdleAnimId();
        this.triggeredAnimId = resolveTriggeredAnimId();
        this.mappingFrame = resolveIdleMappingFrame();

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSpringAnimations() : null,
                idleAnimId,
                mappingFrame
        );
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (isDiagonalUp()) {
            if (!contact.standing()) {
                return;
            }
            applyDiagonalSpring(player, true);
            return;
        }
        if (isDiagonalDown()) {
            if (!contact.touchBottom()) {
                return;
            }
            applyDiagonalSpring(player, false);
            return;
        }
        if (isHorizontal()) {
            if (!contact.touchSide()) {
                return;
            }
            int strength = getStrength();
            if (isFlippedHorizontal()) {
                strength = -strength;
            }
            int xOffset = isFlippedHorizontal() ? 8 : -8;
            player.setX((short) (player.getX() + xOffset));
            player.setAir(true);
            player.setGSpeed((short) 0);
            player.setXSpeed((short) strength);
            player.setSpringing(16);
            player.setDirection(strength < 0 ? Direction.LEFT : Direction.RIGHT);
            animationState.setAnimId(triggeredAnimId);
            return;
        }
        if (isDown()) {
            if (!contact.touchBottom()) {
                return;
            }
            player.setY((short) (player.getY() - 8));
            player.setAir(true);
            player.setGSpeed((short) 0);
            player.setYSpeed((short) Math.abs(getStrength()));
            player.setSpringing(16);
            animationState.setAnimId(triggeredAnimId);
            return;
        }
        if (!contact.standing()) {
            return;
        }
        player.setY((short) (player.getY() + 8));
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setYSpeed((short) -Math.abs(getStrength()));
        player.setSpringing(16);
        animationState.setAnimId(triggeredAnimId);
    }

    @Override
    protected int getHalfWidth() {
        return isHorizontal() ? HALF_SIZE_HORIZONTAL : HALF_SIZE_VERTICAL;
    }

    @Override
    protected int getHalfHeight() {
        return isHorizontal() ? HALF_SIZE_HORIZONTAL : HALF_SIZE_VERTICAL;
    }

    private boolean isHorizontal() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x2;
    }

    private boolean isDiagonalUp() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x6;
    }

    private boolean isDiagonalDown() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x8;
    }

    private boolean isDown() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x4 || subtypeGroup == 0x8;
    }

    private int getStrength() {
        return (spawn.subtype() & 0x02) != 0 ? 0x0A00 : 0x1000;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    private void applyDiagonalSpring(AbstractPlayableSprite player, boolean up) {
        int strength = Math.abs(getStrength());
        boolean flipped = isFlippedHorizontal();
        int xSpeed = flipped ? -strength : strength;
        int ySpeed = up ? -strength : strength;
        int xOffset = flipped ? 6 : -6;
        int yOffset = up ? 6 : -6;
        player.setX((short) (player.getX() + xOffset));
        player.setY((short) (player.getY() + yOffset));
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setXSpeed((short) xSpeed);
        player.setYSpeed((short) ySpeed);
        player.setSpringing(16);
        player.setDirection(xSpeed < 0 ? Direction.LEFT : Direction.RIGHT);
        animationState.setAnimId(triggeredAnimId);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(0x1B, 0x08, 0x10);
    }

    @Override
    public byte[] getSlopeData() {
        if (isDiagonalUp()) {
            return SLOPE_DIAG_UP;
        }
        if (isDiagonalDown()) {
            return SLOPE_DIAG_DOWN;
        }
        return null;
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        ObjectRenderManager.SpringVariant variant = resolveVariant();
        PatternSpriteRenderer renderer = renderManager.getSpringRenderer(variant, redSpring);
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = isDown() || (spawn.renderFlags() & 0x2) != 0;
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private ObjectRenderManager.SpringVariant resolveVariant() {
        if (isHorizontal()) {
            return ObjectRenderManager.SpringVariant.HORIZONTAL;
        }
        if (isDiagonalUp() || isDiagonalDown()) {
            return ObjectRenderManager.SpringVariant.DIAGONAL;
        }
        return ObjectRenderManager.SpringVariant.VERTICAL;
    }

    private int resolveIdleAnimId() {
        if (isHorizontal()) {
            return ANIM_HORIZONTAL_IDLE;
        }
        if (isDiagonalUp() || isDiagonalDown()) {
            return ANIM_DIAGONAL_IDLE;
        }
        return ANIM_VERTICAL_IDLE;
    }

    private int resolveTriggeredAnimId() {
        if (isHorizontal()) {
            return ANIM_HORIZONTAL_TRIGGER;
        }
        if (isDiagonalUp() || isDiagonalDown()) {
            return ANIM_DIAGONAL_TRIGGER;
        }
        return ANIM_VERTICAL_TRIGGER;
    }

    private int resolveIdleMappingFrame() {
        if (isHorizontal()) {
            return 3;
        }
        if (isDiagonalUp() || isDiagonalDown()) {
            return 7;
        }
        return 0;
    }
}

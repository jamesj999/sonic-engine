package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Flipper Object (Obj86).
 * <p>
 * Launches the player when activated. Two types exist:
 * <ul>
 *   <li><b>Vertical Flipper (subtype 0x00)</b>: Player stands on it, launches upward with angle-based velocity</li>
 *   <li><b>Horizontal Flipper (subtype 0x01)</b>: Player pushes against it, launches horizontally</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 57800-58058
 */
public class FlipperObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider {

    private static final int TYPE_VERTICAL = 0;
    private static final int TYPE_HORIZONTAL = 1;

    // Slope curves from s2.asm byte_2B3C6, byte_2B3EA, byte_2B40E
    private static final byte[] SLOPE_CURVE_0 = {
            7, 7, 7, 7, 7, 7, 7, 8, 9, 10, 11, 10, 9, 8, 7, 6,
            5, 4, 3, 2, 1, 0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10,
            -11, -12, -13, -14
    };

    private static final byte[] SLOPE_CURVE_1 = {
            6, 6, 6, 6, 6, 6, 7, 8, 9, 9, 9, 9, 9, 9, 8, 8,
            8, 8, 8, 8, 7, 7, 7, 7, 6, 6, 6, 6, 5, 5, 4, 4,
            4, 4, 4, 4
    };

    private static final byte[] SLOPE_CURVE_2 = {
            5, 5, 5, 5, 5, 6, 7, 8, 9, 10, 11, 11, 12, 12, 13, 13,
            14, 14, 15, 15, 16, 16, 17, 17, 18, 18, 17, 17, 16, 16, 16, 16,
            16, 16, 16, 16
    };

    private static final int ANIM_VERTICAL_IDLE = 0;
    private static final int ANIM_VERTICAL_TRIGGER = 1;
    private static final int ANIM_HORIZONTAL_IDLE = 2;
    private static final int ANIM_HORIZONTAL_TRIGGER_LEFT = 3;
    private static final int ANIM_HORIZONTAL_TRIGGER_RIGHT = 4;

    private final ObjectAnimationState animationState;
    private final int idleAnimId;
    private int mappingFrame;
    private int launchCooldown = 0;

    // Vertical flipper state tracking (per loc_2B20A in s2.asm)
    // 0 = not standing, 1 = standing/rolling on flipper
    private int playerFlipperState = 0;
    private boolean launchPending = false;

    public FlipperObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 0.8f, 0.4f, 0.2f, false);
        this.idleAnimId = isHorizontal() ? ANIM_HORIZONTAL_IDLE : ANIM_VERTICAL_IDLE;
        this.mappingFrame = isHorizontal() ? 4 : 0;

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getFlipperAnimations() : null,
                idleAnimId,
                mappingFrame);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null || launchCooldown > 0) {
            return;
        }

        if (isHorizontal()) {
            // Horizontal flipper: launch on push (loc_2B35C)
            if (contact.pushing()) {
                applyHorizontalLaunch(player);
            }
        } else {
            // Vertical flipper state machine (loc_2B20A - loc_2B288)
            if (contact.standing()) {
                if (playerFlipperState == 0) {
                    // First frame standing: enter rolling state (loc_2B20A)
                    // ROM: move.b #1,obj_control(a1) - locks player movement
                    // We use pinball_mode to prevent rolling from being cleared
                    player.setPinballMode(true);
                    // setRolling(true) handles radius change and Y adjustment internally
                    boolean wasRolling = player.getRolling();
                    player.setRolling(true);
                    if (!wasRolling) {
                        player.setY((short)(player.getY() + 5));
                    }
                    playerFlipperState = 1;
                } else {
                    // Already on flipper: check for jump button (loc_2B23C)
                    if (player.isJumpPressed()) {
                        launchPending = true;
                    } else {
                        // Slide player based on animation frame (loc_2B254)
                        applyFlipperSlide(player);
                    }
                }
            } else {
                // Player left flipper without jumping (loc_2B23C branch to clear)
                // ROM: move.b #0,obj_control(a1)
                if (playerFlipperState != 0) {
                    player.setPinballMode(false);
                }
                playerFlipperState = 0;
            }

            // Process pending launch (loc_2B290)
            if (launchPending) {
                launchPending = false;
                applyVerticalLaunch(player);
            }
        }
    }

    /**
     * Slides the player along the flipper surface based on animation frame.
     * ROM: loc_2B254 - applies small X velocity based on mapping_frame
     */
    private void applyFlipperSlide(AbstractPlayableSprite player) {
        int slideAmount = mappingFrame - 1;
        if (!isFlippedHorizontal()) {
            slideAmount = -slideAmount;
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
        }
        player.setX((short)(player.getX() + slideAmount));
        player.setXSpeed((short)(slideAmount << 8));
        player.setGSpeed((short)(slideAmount << 8));
        player.setYSpeed((short) 0);
    }

    private void applyVerticalLaunch(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();
        if (isFlippedHorizontal()) {
            dx = -dx;
        }

        int adjustedDistance = dx + 0x23;
        int cappedDistance = Math.min(adjustedDistance, 0x40);

        int velocityMagnitude = -(0x800 + (cappedDistance << 5));

        int angle = (adjustedDistance >> 2) + 0x40;

        double radians = (angle & 0xFF) * 2.0 * Math.PI / 256.0;

        int yVel = (int) ((velocityMagnitude * Math.sin(radians)) / 256.0);
        int xVel = (int) ((velocityMagnitude * Math.cos(radians)) / 256.0);

        if (isFlippedHorizontal()) {
            xVel = -xVel;
        }

        player.setYSpeed((short) yVel);
        player.setXSpeed((short) xVel);
        player.setAir(true);
        player.setGSpeed((short) 0);

        // Clear pinball mode when launching (ROM: move.b #0,obj_control(a1) at loc_2B2E2)
        player.setPinballMode(false);

        // Reset flipper state
        playerFlipperState = 0;

        triggerVerticalAnimation();
        playFlipperSound();
        launchCooldown = 16;
    }

    private void applyHorizontalLaunch(AbstractPlayableSprite player) {
        int xVel = -0x1000;

        int newX = player.getX() + 8;

        boolean launchRight = spawn.x() - player.getCentreX() < 0;

        if (!launchRight) {
            newX -= 16;
            xVel = -xVel;
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
        }

        player.setX((short) newX);
        player.setXSpeed((short) xVel);
        player.setGSpeed((short) xVel);

        player.setSpringing(15);
        player.setRolling(true);

        triggerHorizontalAnimation(launchRight);
        playFlipperSound();
        launchCooldown = 16;
    }

    private void triggerVerticalAnimation() {
        animationState.setAnimId(ANIM_VERTICAL_TRIGGER);
    }

    private void triggerHorizontalAnimation(boolean launchRight) {
        animationState.setAnimId(launchRight ? ANIM_HORIZONTAL_TRIGGER_RIGHT : ANIM_HORIZONTAL_TRIGGER_LEFT);
    }

    private void playFlipperSound() {
        try {
            if (AudioManager.getInstance() != null) {
                AudioManager.getInstance().playSfx(GameSound.FLIPPER);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    private boolean isHorizontal() {
        return (spawn.subtype() & 0x01) != 0;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (isHorizontal()) {
            // ROM: d1=#$13 (19), d2=#$18 (24), d3=#$19 (25) at loc_2B312
            return new SolidObjectParams(19, 24, 25);
        }
        // ROM: d1=#$23 (35), d2=#6 at loc_2B1B6
        return new SolidObjectParams(35, 6, 6);
    }

    @Override
    public byte[] getSlopeData() {
        if (isHorizontal()) {
            return null;
        }
        int frame = mappingFrame % 3;
        return switch (frame) {
            case 1 -> SLOPE_CURVE_1;
            case 2 -> SLOPE_CURVE_2;
            default -> SLOPE_CURVE_0;
        };
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (launchCooldown > 0) {
            launchCooldown--;
        }
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
        PatternSpriteRenderer renderer = renderManager.getFlipperRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}

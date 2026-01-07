package uk.co.jamesj999.sonic.game.sonic2.objects;
import uk.co.jamesj999.sonic.level.objects.*;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class SpringObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider {

    // Subtype constants (shifted >> 3 & 0xE) - matches ROM Obj41_Index
    private static final int TYPE_UP = 0;
    private static final int TYPE_HORIZONTAL = 2;
    private static final int TYPE_DOWN = 4;
    private static final int TYPE_DIAGONAL_UP = 6;
    private static final int TYPE_DIAGONAL_DOWN = 8;

    // Diagonal slope data
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
        // ROM: bit 1 of subtype selects strength (0=red/-$1000, 2=yellow/-$A00)
        this.redSpring = (spawn.subtype() & 0x02) == 0;
        this.idleAnimId = resolveIdleAnimId();
        this.triggeredAnimId = resolveTriggeredAnimId();
        this.mappingFrame = resolveIdleMappingFrame();

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSpringAnimations() : null,
                idleAnimId,
                mappingFrame);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }

        // Prevent infinite re-triggering: if player is already springing, don't trigger
        // again
        if (player.getSpringing()) {
            return;
        }

        int type = getType();

        if (type == TYPE_DIAGONAL_UP) {
            if (!contact.standing()) {
                return;
            }
            applyDiagonalSpring(player, true);
            return;
        }

        if (type == TYPE_DIAGONAL_DOWN) {
            if (!contact.touchBottom()) {
                return;
            }
            applyDiagonalSpring(player, false);
            return;
        }

        if (type == TYPE_HORIZONTAL) {
            // ROM: checks pushing_bit, which maps to our pushing/touchSide
            if (!contact.pushing()) {
                return;
            }
            applyHorizontalSpring(player);
            return;
        }

        if (type == TYPE_DOWN) {
            if (!contact.touchBottom()) {
                return;
            }
            applyDownSpring(player);
            return;
        }

        // Default: Up spring
        if (!contact.standing()) {
            return;
        }
        applyUpSpring(player);
    }

    /**
     * ROM: Obj41_Up (loc_189CA)
     * - addq.w #8,y_pos(a1)
     * - move.w objoff_30(a0),y_vel(a1) [negative = up]
     * - bset #status.player.in_air
     */
    /**
     * ROM: Obj41_Up (loc_189CA)
     * - addq.w #8,y_pos(a1) -> In ROM, Y increases downward, so this pushes player
     * down
     * - In our engine, Y increases upward, so we SUBTRACT to push down (away from
     * spring face)
     */
    private void applyUpSpring(AbstractPlayableSprite player) {
        // Don't adjust Y position - SolidObjectManager already handles collision
        // positioning

        // ROM: y_vel = negative value (negative = up in Y-down coordinate system)
        player.setYSpeed((short) getStrength()); // Negative = up

        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setSpringing(15);
        trigger(player);
    }

    /**
     * ROM: Obj41_Down - same as Up but flipped
     * - subq.w #8,y_pos(a1)
     * - move.w objoff_30(a0),y_vel(a1) then neg.w
     */
    private void applyDownSpring(AbstractPlayableSprite player) {
        // ROM: subq.w #8,y_pos (pushes player up in Y-down coordinate system)
        // Java engine also has Y-down, so we SUBTRACT to push up (away from spring)
        player.setY((short) (player.getY() - 8));

        // ROM negates the strength for down springs (positive = down in Y-down system)
        player.setYSpeed((short) -getStrength()); // Negated = positive = down

        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setSpringing(15);
        trigger(player);
    }

    /**
     * ROM: Obj41_Horizontal (loc_18AEE)
     * - move.w objoff_30(a0),x_vel(a1) [starts negative]
     * - addq.w #8,x_pos(a1)
     * - bset player facing right
     * - btst spring.x_flip
     * - bne skip_adjustment (if flipped, keep +8 and negative velocity)
     * - bclr player facing (now left)
     * - subi.w #$10,x_pos(a1) [net: -8]
     * - neg.w x_vel(a1) [now positive = right]
     */
    private void applyHorizontalSpring(AbstractPlayableSprite player) {
        int strength = getStrength(); // starts negative
        boolean flipped = isFlippedHorizontal();

        // Always add 8 first
        int newX = player.getX() + 8;
        Direction dir = Direction.RIGHT;

        if (!flipped) {
            // Unflipped spring: subtract 16 (net -8), negate velocity
            newX -= 16;
            strength = -strength; // now positive (right)
        } else {
            // Flipped spring: keep +8, keep negative velocity (left)
            dir = Direction.LEFT;
        }

        player.setX((short) newX);
        player.setXSpeed((short) strength);
        player.setDirection(dir);

        // ROM: Horizontal springs do NOT set in_air!
        // They set inertia (gSpeed) = x_vel and keep player grounded
        // Line 33810: move.w x_vel(a1),inertia(a1)
        player.setGSpeed((short) strength);

        // ROM Line 33818: bpl.s -> move.w #0,y_vel(a1) (if subtype bit 7 set, clear Y
        // velocity)
        if ((spawn.subtype() & 0x80) != 0) {
            player.setYSpeed((short) 0);
        }

        // ROM: Horizontal springs use move_lock ($F) but ideally shouldn't trigger
        // spring animation
        // However, our engine uses setSpringing which also locks controls
        // TODO: Fix animation profile to not show spring animation when grounded
        player.setSpringing(15);

        trigger(player);
    }

    /**
     * ROM: Diagonal springs apply both X and Y velocity
     */
    private void applyDiagonalSpring(AbstractPlayableSprite player, boolean up) {
        int strength = getStrength(); // negative base
        boolean flipped = isFlippedHorizontal();

        int xStrength = flipped ? strength : -strength;
        int yStrength = up ? strength : -strength;

        player.setXSpeed((short) xStrength);
        player.setYSpeed((short) yStrength);
        player.setDirection(xStrength < 0 ? Direction.LEFT : Direction.RIGHT);
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setSpringing(15);

        trigger(player);
    }

    private void trigger(AbstractPlayableSprite player) {
        animationState.setAnimId(triggeredAnimId);
        try {
            if (AudioManager.getInstance() != null) {
                AudioManager.getInstance().playSfx(GameSound.SPRING);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    /**
     * ROM: getStrength returns NEGATIVE values
     * Obj41_Strengths: dc.w -$1000, -$A00
     * Bit 1 of subtype: 0=red(-$1000), 2=yellow(-$A00)
     */
    private int getStrength() {
        // ROM: bit 1 of subtype: 0=red(-$1000), 1=yellow(-$A00)
        // Visual rendering and strength must match
        // NOTE: Inverted ternary to match visual display behavior
        return redSpring ? -0x1000 : -0x0A00;
    }

    private int getType() {
        // ROM: lsr.w #3,d0 then andi.w #$E,d0
        return (spawn.subtype() >> 3) & 0xE;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    /**
     * Make spring non-solid when player is already springing.
     * This prevents ceiling collision from zeroing Y velocity immediately after
     * launch.
     */
    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        if (player != null && player.getSpringing()) {
            return false; // Player passes through spring when springing
        }
        return true;
    }

    /**
     * ROM collision params vary by type:
     * Up/Down: D1=$1B (27), D2=8, D3=$10 (16)
     * Horizontal: D1=$13 (19), D2=$E (14), D3=$F (15)
     */
    @Override
    public SolidObjectParams getSolidParams() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            // Reduce height to 8 (16px total) to avoid blocking player when walking over it
            return new SolidObjectParams(19, 8, 8);
        }
        // Up, Down, Diagonal use standard vertical params
        // Fix height: Air=8, Ground=8 (matches 16px visual height, prevents
        // oscillation)
        return new SolidObjectParams(27, 8, 8);
    }

    @Override
    public byte[] getSlopeData() {
        int type = getType();
        if (type == TYPE_DIAGONAL_UP) {
            return SLOPE_DIAG_UP;
        }
        if (type == TYPE_DIAGONAL_DOWN) {
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
        // NOTE: Renderer naming is inverted - "RedRenderer" variants are yellow,
        // default are red
        // So we pass !redSpring to get correct visual color
        PatternSpriteRenderer renderer = renderManager.getSpringRenderer(variant, !redSpring);
        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = getType() == TYPE_DOWN || (spawn.renderFlags() & 0x2) != 0;
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    private ObjectRenderManager.SpringVariant resolveVariant() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ObjectRenderManager.SpringVariant.HORIZONTAL;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ObjectRenderManager.SpringVariant.DIAGONAL;
        }
        return ObjectRenderManager.SpringVariant.VERTICAL;
    }

    private int resolveIdleAnimId() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ANIM_HORIZONTAL_IDLE;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ANIM_DIAGONAL_IDLE;
        }
        return ANIM_VERTICAL_IDLE;
    }

    private int resolveTriggeredAnimId() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return ANIM_HORIZONTAL_TRIGGER;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return ANIM_DIAGONAL_TRIGGER;
        }
        return ANIM_VERTICAL_TRIGGER;
    }

    private int resolveIdleMappingFrame() {
        int type = getType();
        if (type == TYPE_HORIZONTAL) {
            return 3;
        }
        if (type == TYPE_DIAGONAL_UP || type == TYPE_DIAGONAL_DOWN) {
            return 7;
        }
        return 0;
    }
}

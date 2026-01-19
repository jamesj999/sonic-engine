package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Tipping Floor Object (Obj0B).
 * <p>
 * A platform that tips back and forth. It only acts as a solid platform
 * when in the flat position (frame 0). When tipped (frames 1-4), the player
 * falls through.
 * <p>
 * <b>Subtype format:</b>
 * <ul>
 *   <li>Bits 0-3: Delay multiplier -> {@code (value + 1) * 16} frames initial delay</li>
 *   <li>Bits 4-7: Duration -> {@code value + 0x10} frames between animation direction toggles</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 45379-45485 (Obj0B code)
 */
public class TippingFloorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    // Animation IDs
    private static final int ANIM_FORWARD = 0;  // Frames 0->1->2->3->4
    private static final int ANIM_REVERSE = 1;  // Frames 4->3->2->1->0

    // Routine states (matching ROM)
    private static final int ROUTINE_DELAY = 2;
    private static final int ROUTINE_MAIN = 4;

    private final ObjectAnimationState animationState;
    private int routine = ROUTINE_DELAY;
    private int mappingFrame = 0;

    // Timing from subtype
    private final int delay;           // Sync delay with global frame counter
    private final int durationInitial; // Duration between animation toggles
    private int durationCurrent;       // Current countdown

    // Animation direction: 0 = forward (0->4), 1 = reverse (4->0)
    private int animDirection = 0;

    public TippingFloorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Parse subtype
        // ROM: d0 = subtype & 0x0F, delay = (d0 + 1) * 16
        int delayMultiplier = (spawn.subtype() & 0x0F) + 1;
        this.delay = delayMultiplier * 16;

        // ROM: d0 = (subtype >> 4) + 0x10, then subq.w #1,d0
        int durationValue = ((spawn.subtype() >> 4) & 0x0F) + 0x10;
        this.durationInitial = durationValue - 1;  // ROM subtracts 1 before storing
        this.durationCurrent = durationValue - 1;

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getTippingFloorAnimations() : null,
                ANIM_FORWARD,
                0);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        switch (routine) {
            case ROUTINE_DELAY -> updateDelay(frameCounter);
            case ROUTINE_MAIN -> updateMain();
        }
    }

    /**
     * Delay phase: wait until synchronized with global frame counter.
     * ROM: loc_2AFE0 - waits until ((frameCounter + delay) & 0xFF) == 0
     */
    private void updateDelay(int frameCounter) {
        int sum = (frameCounter & 0xFF) + delay;
        if ((sum & 0xFF) == 0) {
            routine = ROUTINE_MAIN;
        }
    }

    /**
     * Main phase: animate between flat and tipped positions.
     * ROM: loc_2B002 - handles duration countdown and animation toggling
     */
    private void updateMain() {
        durationCurrent--;

        if (durationCurrent < 0) {
            // Duration expired - toggle animation direction
            // ROM: loc_2B01E - sets 0x7F first, then if anim!=0, uses initial instead
            if (animDirection != 0) {
                // anim != 0: use initial duration
                durationCurrent = durationInitial;
            } else {
                // anim == 0: set intermediate duration
                durationCurrent = 0x7F;
            }
            // Toggle direction
            animDirection ^= 1;
            animationState.setAnimId(animDirection);
        }

        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // No special handling - solid check already filters by mappingFrame
    }

    /**
     * Platform is only solid when flat (frame 0).
     * ROM: loc_2B036 - checks mappingFrame == 0 before calling SolidObject
     */
    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return mappingFrame == 0;
    }

    /**
     * Platform collision dimensions.
     * ROM: d1 = $10 (half-width 16), d2 = $11 (half-height 17), d3 = $11
     */
    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(0x10, 0x11, 0x11);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getTippingFloorRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }
}

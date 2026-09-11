package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.credits.Sonic1CreditsMappings;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 8B - Eggman on the "TRY AGAIN" and "END" screens.
 * <p>
 * State machine matching {@code _incObj/8B Try Again & End Eggman.asm}:
 * <pre>
 *   0: EEgg_Main     - initialize, check emerald count, spawn text/emeralds
 *   2: EEgg_Animate  - play animation via Ani_EEgg
 *   4: EEgg_Juggle   - flip emerald rotation direction, set timer
 *   6: EEgg_Wait     - countdown, toggle direction, return to animate
 * </pre>
 * <p>
 * Uses screen-space coordinates (ROM: obRender = 0).
 * <p>
 * Animation scripts (Ani_EEgg):
 * <ul>
 *   <li>Anim 0 (.tryagain1): delay=5, frame 0, then afRoutine (advance to routine 4)</li>
 *   <li>Anim 1 (.tryagain2): delay=5, frame 2, then afRoutine (advance to routine 4)</li>
 *   <li>Anim 2 (.end): delay=7, frames 4,5,6,5,4,5,6,5,4,5,6,5,7,5,6,5, afEnd (loop)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/8B Try Again & End Eggman.asm
 */
public class Sonic1TryAgainEggmanObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1TryAgainEggmanObjectInstance.class.getName());

    private static final int PRIORITY = 2;

    /** ROM: move.w #$120,obX(a0) */
    private static final int SCREEN_X = 0x120;

    /** ROM: move.w #$F4,obScreenY(a0) */
    private static final int SCREEN_Y = 0xF4;

    /** ROM: move.w #112,eegg_time(a0) */
    private static final int JUGGLE_TIMER = 112;

    /** S1 has 6 chaos emeralds. */
    private static final int TOTAL_EMERALDS = 6;

    // Animation data (Ani_EEgg)
    // Anim 0 (.tryagain1): delay=5, frame 0, then advance routine
    // Anim 1 (.tryagain2): delay=5, frame 2, then advance routine
    // Anim 2 (.end): delay=7, loop of frames 4,5,6,5,...,7,5,6,5
    private static final int[][] ANIM_FRAMES = {
            {0},                                                              // anim 0: frame 0 then afRoutine
            {2},                                                              // anim 1: frame 2 then afRoutine
            {4, 5, 6, 5, 4, 5, 6, 5, 4, 5, 6, 5, 7, 5, 6, 5}               // anim 2: END loop
    };
    private static final int[] ANIM_DELAYS = {5, 5, 7};

    private final PatternSpriteRenderer renderer;
    private final Sonic1TryAgainEmeraldsObjectInstance emeralds;
    private final Sonic1CreditsTextRendererRef textRenderer;
    private int routine;
    private int animIndex;
    private int animFrameIndex;
    private int animDelayCounter;
    private int frameId;
    private int juggleTimer;
    private boolean initialized;

    @SuppressWarnings("unused")
    private Sonic1TryAgainEggmanObjectInstance() {
        this(null, null, null);
    }

    /**
     * @param renderManager object render manager for art lookup
     * @param emeralds      the emeralds object (may be null if all 6 collected)
     * @param textRenderer  text renderer reference for "TRY AGAIN" text (may be null)
     */
    public Sonic1TryAgainEggmanObjectInstance(
            ObjectRenderManager renderManager,
            Sonic1TryAgainEmeraldsObjectInstance emeralds,
            Sonic1CreditsTextRendererRef textRenderer) {
        super(null, "EndEggman");
        this.renderer = renderManager != null ? renderManager.getRenderer(ObjectArtKeys.END_EGGMAN) : null;
        this.emeralds = emeralds;
        this.textRenderer = textRenderer;
    }

    @Override
    public Sonic1TryAgainEggmanObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectRenderManager renderManager = ctx.objectServices() != null
                ? ctx.objectServices().renderManager()
                : null;
        Sonic1TryAgainEmeraldsObjectInstance liveEmeralds =
                RewindRecreateObjectLinks.nearestLiveObject(ctx, Sonic1TryAgainEmeraldsObjectInstance.class);
        return new Sonic1TryAgainEggmanObjectInstance(renderManager, liveEmeralds, null);
    }

    /**
     * Lazy initialization: query game state for emerald count and set up animation.
     * Moved out of constructor to avoid calling services() during construction.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Routine 0: EEgg_Main
        int emeraldCount = services().gameState().getEmeraldCount();

        if (emeraldCount >= TOTAL_EMERALDS) {
            // All emeralds: use "END" animation (anim 2)
            animIndex = 2;
        } else {
            // Not all emeralds: use "TRY AGAIN" animation (anim 0)
            animIndex = 0;
        }

        // Advance to routine 2 (EEgg_Animate) - ROM falls through
        routine = 2;
        animFrameIndex = 0;
        animDelayCounter = ANIM_DELAYS[animIndex];
        frameId = ANIM_FRAMES[animIndex][0];
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        switch (routine) {
            case 2 -> updateAnimate();
            case 4 -> updateJuggle();
            case 6 -> updateWait();
            default -> { }
        }
    }

    /** Routine 2: EEgg_Animate - play animation. */
    private void updateAnimate() {
        animDelayCounter--;
        if (animDelayCounter >= 0) {
            return;
        }
        animDelayCounter = ANIM_DELAYS[animIndex];

        int[] frames = ANIM_FRAMES[animIndex];
        animFrameIndex++;

        if (animFrameIndex >= frames.length) {
            if (animIndex < 2) {
                // afRoutine: advance to EEgg_Juggle.
                // Do NOT change animIndex here — EEgg_Wait toggles it
                // via bchg #0,obAnim when the juggle timer expires.
                routine = 4;
                updateJuggle();
                return;
            } else {
                // afEnd: loop
                animFrameIndex = 0;
            }
        }
        frameId = frames[animFrameIndex];
    }

    /** Routine 4: EEgg_Juggle - signal emeralds to change direction. */
    private void updateJuggle() {
        routine = 6; // advance to EEgg_Wait

        if (emeralds != null) {
            // Determine velocity direction from bit 0 of animIndex
            // (matches ROM: btst #0,obAnim(a0))
            int velocity = 2;
            if ((animIndex & 1) != 0) {
                velocity = -2;
            }
            emeralds.signalJuggle(velocity);
        }

        // Increment frame (ROM: addq.b #1,obFrame(a0))
        frameId++;

        juggleTimer = JUGGLE_TIMER;
    }

    /** Routine 6: EEgg_Wait - countdown timer. */
    private void updateWait() {
        juggleTimer--;
        if (juggleTimer < 0) {
            // bchg #0,obAnim — toggle animation index bit 0.
            // In TRY AGAIN mode this alternates between anim 0 and 1;
            // in END mode routine 6 is never reached (anim 2 loops via afEnd).
            animIndex ^= 1;
            routine = 2;
            animFrameIndex = 0;
            animDelayCounter = ANIM_DELAYS[animIndex];
            frameId = ANIM_FRAMES[animIndex][0];
        }
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || renderer == null || !renderer.isReady()) {
            return;
        }
        // Screen-space rendering: convert to world coords by adding camera position
        Camera camera = services().camera();
        int worldX = SCREEN_X + camera.getX();
        int worldY = SCREEN_Y + camera.getY();
        renderer.drawFrameIndex(frameId, worldX, worldY, false, false);
    }

    /**
     * Interface for accessing the credits text renderer from the TryAgainEnd screen.
     * Avoids circular dependency on Sonic1CreditsTextRenderer.
     */
    public interface Sonic1CreditsTextRendererRef {
        void draw(int frameIndex);
    }
}

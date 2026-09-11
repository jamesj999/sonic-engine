package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameStateManager;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 8C - Chaos emeralds on the "TRY AGAIN" screen.
 * <p>
 * Spawns uncollected emeralds in an orbit that can be juggled
 * by the Eggman object (Object 8B) via {@link #signalJuggle(int)}.
 * <p>
 * Each sub-emerald orbits around a center point using sine/cosine
 * with a fixed radius. The Eggman object periodically signals them
 * to rotate, alternating direction.
 * <p>
 * Reference: docs/s1disasm/_incObj/8C Try Again Emeralds.asm
 */
public class Sonic1TryAgainEmeraldsObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1TryAgainEmeraldsObjectInstance.class.getName());

    private static final int PRIORITY = 1;
    private static final int TOTAL_EMERALDS = 6;

    /** ROM: move.w #$120,objoff_38(a1) - orbit center X */
    private static final int CENTER_X = 0x120;

    /** ROM: move.w #$EC,obScreenY(a1) / objoff_3A(a1) - orbit center Y */
    private static final int CENTER_Y = 0xEC;

    /** ROM: move.b #$1C,objoff_3C(a1) - radius multiplier */
    private static final int RADIUS = 0x1C;

    /** ROM: move.b #$80,obAngle(a1) - initial angle */
    private static final int INITIAL_ANGLE = 0x80;

    /** ROM: addi.w #10,d3 - delay increment per emerald */
    private static final int DELAY_INCREMENT = 10;

    /** Renderer for the emerald art (reuses END_EMERALDS / Map_ECha). */
    private final PatternSpriteRenderer renderer;

    /** Sub-emerald state arrays. */
    private int[] emeraldFrame;
    private int[] emeraldAngle;
    private int[] emeraldDelay;
    private int[] emeraldDelayInit;
    private int[] emeraldVelocity;
    private int[] emeraldX;
    private int[] emeraldY;
    private int count;
    private boolean initialized;

    @SuppressWarnings("unused")
    private Sonic1TryAgainEmeraldsObjectInstance() {
        this(null);
    }

    /**
     * Creates the TRY AGAIN emerald display.
     * Spawns (6 - emeraldCount) emerald sub-objects for the uncollected ones.
     *
     * @param renderManager object render manager for art lookup
     */
    public Sonic1TryAgainEmeraldsObjectInstance(ObjectRenderManager renderManager) {
        super(null, "TryChaos");
        this.renderer = renderManager != null ? renderManager.getRenderer(ObjectArtKeys.END_EMERALDS) : null;
    }

    @Override
    public Sonic1TryAgainEmeraldsObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectRenderManager renderManager = ctx.objectServices() != null
                ? ctx.objectServices().renderManager()
                : null;
        return new Sonic1TryAgainEmeraldsObjectInstance(renderManager);
    }

    /**
     * Lazy initialization: query game state for emerald data on first update.
     * Moved out of constructor to avoid calling services() during construction.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        GameStateManager gsm = services().gameState();
        int emeraldCount = gsm.getEmeraldCount();
        int uncollected = TOTAL_EMERALDS - emeraldCount;
        if (uncollected < 0) uncollected = 0;
        this.count = uncollected;

        emeraldFrame = new int[count];
        emeraldAngle = new int[count];
        emeraldDelay = new int[count];
        emeraldDelayInit = new int[count];
        emeraldVelocity = new int[count];
        emeraldX = new int[count];
        emeraldY = new int[count];

        // ROM: TCha_Main loop - find uncollected emeralds
        // d2 scans 0-5, skipping collected ones; d3 = delay accumulator
        int idx = 0;
        int delay = 0;
        int emeraldId = 0;
        while (idx < count && emeraldId < TOTAL_EMERALDS) {
            // ROM: checks v_emldlist to skip collected emeralds
            if (gsm.hasEmerald(emeraldId)) {
                emeraldId++;
                continue;
            }
            // Frame = emeraldId + 1 (ROM: move.b d2,obFrame; addq.b #1,obFrame)
            emeraldFrame[idx] = emeraldId + 1;
            emeraldAngle[idx] = INITIAL_ANGLE;
            emeraldDelay[idx] = delay;
            emeraldDelayInit[idx] = delay;
            emeraldVelocity[idx] = 0;

            // Initial position: orbit at INITIAL_ANGLE
            updateEmeraldPosition(idx);

            delay += DELAY_INCREMENT;
            emeraldId++;
            idx++;
        }

        LOGGER.fine("TryAgainEmeralds: spawned " + count + " uncollected emeralds");
    }

    /**
     * Called by Object 8B (EEgg_Juggle) to signal emeralds to change rotation direction.
     * ROM: sets objoff_3E and adjusts obAngle for each emerald.
     *
     * @param velocity rotation velocity (positive = clockwise, negative = counter-clockwise)
     */
    public void signalJuggle(int velocity) {
        for (int i = 0; i < count; i++) {
            emeraldVelocity[i] = velocity;
            // ROM: move.w d0,d2; asl.w #3,d2; add.b d2,obAngle(a1)
            int angleAdjust = velocity << 3;
            emeraldAngle[i] = (emeraldAngle[i] + angleAdjust) & 0xFF;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            updateSingleEmerald(i);
        }
    }

    /**
     * ROM: TCha_Move for a single emerald sub-object.
     */
    private void updateSingleEmerald(int idx) {
        if (emeraldVelocity[idx] == 0) {
            return;
        }

        // Delay countdown
        if (emeraldDelay[idx] > 0) {
            emeraldDelay[idx]--;
            if (emeraldDelay[idx] > 0) {
                updateEmeraldPosition(idx);
                return;
            }
        }

        // Add velocity to angle
        emeraldAngle[idx] = (emeraldAngle[idx] + emeraldVelocity[idx]) & 0xFF;

        // Check if angle has reached 0 or 0x80 (stop point)
        int angle = emeraldAngle[idx] & 0xFF;
        if (angle == 0 || angle == 0x80) {
            emeraldVelocity[idx] = 0;
            emeraldDelay[idx] = emeraldDelayInit[idx];
        }

        updateEmeraldPosition(idx);
    }

    /**
     * Calculate emerald position from angle and radius using sine/cosine.
     */
    private void updateEmeraldPosition(int idx) {
        int angle = emeraldAngle[idx] & 0xFF;

        // ROM: jsr (CalcSine).l — d0 = sin, d1 = cos
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        // ROM: muls.w d4,d1 / asr.l #8,d1
        int offsetX = (cos * RADIUS) >> 8;
        int offsetY = (sin * RADIUS) >> 8;

        emeraldX[idx] = CENTER_X + offsetX;
        emeraldY[idx] = CENTER_Y + offsetY;
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
        Camera camera = services().camera();
        int camX = camera.getX();
        int camY = camera.getY();
        for (int i = 0; i < count; i++) {
            int worldX = emeraldX[i] + camX;
            int worldY = emeraldY[i] + camY;
            renderer.drawFrameIndex(emeraldFrame[i], worldX, worldY, false, false);
        }
    }
}

package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState;
import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Bomb Prize Object (ObjD3).
 * <p>
 * A spike ball prize spawned by the slot machine that spirals inward toward the cage.
 * Subtracts 1 ring from the player when display delay expires (no knockback/invincibility).
 * <p>
 * <b>Behavior (from s2.asm lines 58208-58275):</b>
 * <ol>
 *   <li>Spawned at edge with initial angle</li>
 *   <li>Spirals inward toward cage center (1/16th distance per frame)</li>
 *   <li>When display delay expires, subtract 1 ring (Ring_Reduction)</li>
 *   <li>Plays spike sound when the shared payout-update counter reaches 5</li>
 *   <li>Destroyed when off-screen or display delay expires</li>
 * </ol>
 */
public class BombPrizeObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // Position tracking (16.16 fixed point for precision)
    private int currentX;      // 16.16 fixed point X
    private int currentY;      // 16.16 fixed point Y
    // Un-final for rewind: machineX/machineY are non-spawn-derivable scalars
    // reapplied by GenericFieldCapturer after spawn-based recreate.
    private int machineX; // Machine center X
    private int machineY; // Machine center Y

    // Display delay before active
    private int displayDelay;

    // Reference to parent counter (for decrementing). Un-final for rewind: the
    // shared array reference cannot be captured/relinked, so spawn-based recreate
    // uses a fresh placeholder; only the parent slot-machine bookkeeping decrement drifts.
    private int[] prizeCounter;
    @RewindTransient(reason = "Structural ObjD6 parent link; live prize children are spawned and owned by PointPokey.")
    private PointPokeyObjectInstance parent;

    // Reference to LevelManager for rendering

    /**
     * Creates a bomb prize object.
     *
     * @param x Initial X position
     * @param y Initial Y position
     * @param machineX Machine center X (target)
     * @param machineY Machine center Y (target)
     * @param displayDelay Frames before bomb becomes active
     * @param prizeCounter Reference to counter to decrement when destroyed
     */
    public BombPrizeObjectInstance(int x, int y, int machineX, int machineY,
                                   int displayDelay, int[] prizeCounter) {
        this(x, y, machineX, machineY, displayDelay, prizeCounter, null);
    }

    BombPrizeObjectInstance(int x, int y, int machineX, int machineY,
                            int displayDelay, int[] prizeCounter, PointPokeyObjectInstance parent) {
        super(new ObjectSpawn(x, y, 0xD3, 0, 0, false, 0), "BombPrize");
        this.currentX = x << 16;  // Convert to 16.16 fixed point
        this.currentY = y << 16;
        this.machineX = machineX;
        this.machineY = machineY;
        this.displayDelay = displayDelay;
        this.prizeCounter = prizeCounter;
        this.parent = parent;
    }

    BombPrizeObjectInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y(), 0, 0, 0, new int[]{0});
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Move position toward machine center (1/16th of distance per frame)
        // Based on ObjD3 movement in s2.asm
        long machineX32 = (long) machineX << 16;
        long machineY32 = (long) machineY << 16;

        long deltaX = currentX - machineX32;
        deltaX = deltaX >> 4;  // Divide by 16
        currentX -= (int) deltaX;

        long deltaY = currentY - machineY32;
        deltaY = deltaY >> 4;  // Divide by 16
        currentY -= (int) deltaY;

        // Check display delay
        if (displayDelay > 0) {
            displayDelay--;
            if (displayDelay == 0) {
                // Bomb display delay expired - remove a ring from player
                // (from s2.asm lines 58230-58253: subq.w #1,... then Ring_Reduction)
                if (player != null && player.getRingCount() > 0) {
                    player.addRings(-1);
                }

                // ObjD3 consumes Bonus_Countdown_3, advanced by ObjD6's
                // payout loop (loc_2BD48), regardless of remaining rings.
                var soundState = services().gameModule().getGameService(
                        CNZPrizeSoundState.class);
                if (soundState != null && soundState.consumeSpikeSound()) {
                    playSpikeSound();
                }

                // Decrement prize counter
                if (prizeCounter != null && prizeCounter.length > 0) {
                    prizeCounter[0]--;
                }
                setDestroyed(true);
            }
        }

        // Check if off-screen for cleanup
        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    @Override
    public int getX() {
        return currentX >> 16;
    }

    @Override
    public int getY() {
        return currentY >> 16;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic spawn with current position for collision detection
        return new ObjectSpawn(
                currentX >> 16,
                currentY >> 16,
                0xD3,
                spawn.subtype(),
                spawn.renderFlags(),
                false,
                spawn.rawYWord()
        );
    }

    /**
     * Play spike sound effect for bomb impact.
     */
    private void playSpikeSound() {
        try {
            // ObjD3 uses PlaySound2 (SFX1), not PlaySound (SFX0).
            services().audioManager().playSecondarySfx(GameSound.HURT_SPIKE);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        int screenX = currentX >> 16;
        int screenY = currentY >> 16;

        // Try to use the CNZ bonus spike renderer
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BONUS_SPIKE);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(0, screenX, screenY, false, false);
                return;
            }
        }

        // Fallback: render a debug box
        appendDebugBox(commands, screenX, screenY);
    }

    private void appendDebugBox(List<GLCommand> commands, int x, int y) {
        // Render a small red box representing the bomb prize
        float r = 1.0f, g = 0.0f, b = 0.0f;
        int halfSize = 8;

        int left = x - halfSize;
        int right = x + halfSize;
        int top = y - halfSize;
        int bottom = y + halfSize;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);

        // Draw X to indicate danger
        appendLine(commands, left, top, right, bottom, r, g, b);
        appendLine(commands, right, top, left, bottom, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

}

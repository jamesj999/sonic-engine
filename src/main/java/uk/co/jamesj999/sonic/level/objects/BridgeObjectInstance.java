package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Bridge object (0x11) - A multi-segment log bridge that can be walked on.
 * Renders multiple log segments horizontally based on subtype.
 * 
 * From disassembly:
 * - subtype & 0x1F = number of log segments
 * - Each log is 16 pixels wide (one 2x2 tile frame)
 * - Uses palette 2
 * - Art from ArtNem_EHZ_Bridge
 */
public class BridgeObjectInstance extends BoxObjectInstance implements SlopedSolidProvider, SolidObjectListener {
    private static final int LOG_WIDTH = 16; // pixels per log segment
    private static final int LOG_HALF_HEIGHT = 8; // half-height for collision (sprite is 16px tall)

    // Sagging physics fields
    private byte[] slopeData; // Cache for slope collision
    private int[] targetLogOffsets; // Target sag based on player
    private int[] currentLogOffsets; // Current smoothed sag (prevents jitter)

    public BridgeObjectInstance(ObjectSpawn spawn, String name) {
        // BoxObjectInstance constructor: spawn, name, halfWidth, halfHeight, r, g, b,
        // highPriority
        super(spawn, name, 32, LOG_HALF_HEIGHT, 0.6f, 0.4f, 0.2f, false);
    }

    /**
     * Gets the number of log segments from the subtype.
     */
    private int getLogCount() {
        int count = spawn.subtype() & 0x1F;
        return Math.max(1, count); // At least 1 log
    }

    @Override
    protected int getHalfWidth() {
        return (getLogCount() * LOG_WIDTH) / 2;
    }

    @Override
    protected int getHalfHeight() {
        return LOG_HALF_HEIGHT;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(getHalfWidth(), 0x04, 0x04);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        return slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Bridge is just a platform - no special interaction needed
        // Future: Could add sag physics here
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        int logCount = getLogCount();
        int totalWidth = logCount * 16;

        // Initialize arrays if needed
        if (slopeData == null || slopeData.length != totalWidth) {
            slopeData = new byte[totalWidth];
            targetLogOffsets = new int[logCount];
            currentLogOffsets = new int[logCount];
        }

        // Reset Targets
        for (int i = 0; i < logCount; i++) {
            targetLogOffsets[i] = 0;
        }

        // Calculate Target Sag if player is riding
        if (player != null) {
            uk.co.jamesj999.sonic.level.objects.SolidObjectManager solidManager = uk.co.jamesj999.sonic.level.LevelManager
                    .getInstance().getSolidObjectManager();

            if (solidManager != null && solidManager.isRidingObject(this)) {
                calculateTargetSag(player, logCount);
            }
        }

        // Apply Inertia
        int maxChange = 4;
        for (int i = 0; i < logCount; i++) {
            int current = currentLogOffsets[i];
            int target = targetLogOffsets[i];

            if (current < target) {
                current += maxChange;
                if (current > target)
                    current = target;
            } else if (current > target) {
                current -= maxChange;
                if (current < target)
                    current = target;
            }
            currentLogOffsets[i] = current;
        }

        // Fill Slope Data
        // Collision Box Start = spawn.x - halfWidth.
        // Visual Box Start = spawn.x - halfWidth - 8. (Shifted Left)
        // Therefore, Collision Pixel 0 corresponds to Visual Pixel 8 (Log 0 Center).
        for (int k = 0; k < slopeData.length; k++) {
            // k is pixel index relative to Collision Start.
            // visualPixel is relative to Visual Start (which is 8px Left of Collision).
            // visualPixel = k + 8.
            int visualPixel = k + 8;
            int logIndex = visualPixel / 16;

            if (logIndex >= 0 && logIndex < logCount) {
                int sag = currentLogOffsets[logIndex];
                slopeData[k] = (byte) -sag;
            } else {
                slopeData[k] = 0;
            }
        }
    }

    private void calculateTargetSag(AbstractPlayableSprite player, int logCount) {
        // Visual Start = spawn.x - halfWidth - 8.
        int startX = spawn.x() - getHalfWidth() - 8;
        int playerRelX = player.getX() - startX;

        // Find which log index the player is on
        int playerLogIndex = playerRelX / 16;
        if (playerLogIndex < 0)
            playerLogIndex = 0;
        if (playerLogIndex >= logCount)
            playerLogIndex = logCount - 1;

        int distLeft = playerLogIndex;
        int distRight = (logCount - 1) - playerLogIndex;
        int distPillar = Math.min(distLeft, distRight);

        int maxSag = (distPillar + 1) * 2;
        if (maxSag > 32)
            maxSag = 32;

        for (int i = 0; i < logCount; i++) {
            int sag;
            if (i <= playerLogIndex) {
                if (playerLogIndex == 0)
                    sag = 0;
                else
                    sag = (i * maxSag) / playerLogIndex;
            } else {
                int rightSideLength = (logCount - 1) - playerLogIndex;
                if (rightSideLength == 0)
                    sag = 0;
                else
                    sag = ((logCount - 1 - i) * maxSag) / rightSideLength;
            }
            targetLogOffsets[i] = sag;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer bridgeRenderer = renderManager.getBridgeRenderer();
        ObjectSpriteSheet bridgeSheet = renderManager.getBridgeSheet();

        if (bridgeRenderer != null && bridgeSheet != null && bridgeRenderer.isReady()) {
            // Visual Offset: Shift left by 8px based on user feedback.
            int startX = spawn.x() - getHalfWidth() - 8;
            int numLogs = getLogCount();

            for (int i = 0; i < numLogs; i++) {
                int x = startX + (i * 16) + 8; // +8 to center the 16px log
                int y = spawn.y();

                if (currentLogOffsets != null && i < currentLogOffsets.length) {
                    y += currentLogOffsets[i];
                }

                bridgeRenderer.drawFrameIndex(0, x, y, false, false);
            }
        } else {
            // Fallback
            super.appendRenderCommands(commands);
        }
    }
}

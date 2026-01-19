package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * CPZ Spin Tube (Object 0x1E).
 * Invisible tube transport system that guides the player through curved paths.
 * Based on obj1E from the Sonic 2 disassembly.
 *
 * The object manages path-following for both Sonic and Tails independently.
 * Players roll through entry paths (curved sections) and then through main
 * tube paths (level-specific routes).
 */
public class CPZSpinTubeObjectInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(CPZSpinTubeObjectInstance.class.getName());

    // Fixed rolling speed through tube (0x800 in ROM)
    private static final int TUBE_SPEED = 0x800;

    // Collision distance table (word_225BC)
    private static final int[] COLLISION_DISTANCES = {0xA0, 0x100, 0x120};

    // Entry/exit path variant lookup table (byte_2266E)
    // Values 0,1 = fixed path selection; value 2 = timer-based alternation
    private static final int[] PATH_VARIANT_TABLE = {
            2, 2, 2, 2, 2, 2, 2, 2,  // 0-7: normal (timer-based)
            2, 2, 0, 2, 0, 1, 2, 1   // 8-15: special cases
    };

    // Main tube path routing table (byte_227BE)
    // Index = (subtype & 0xFC) + entry_frame; Value = signed path ID (negative = reverse)
    private static final int[] MAIN_PATH_ROUTING = {
            2, 1, 0, 0,       // subtype 0x00-0x03
            -1, 3, 0, 0,      // subtype 0x04-0x07
            4, -2, 0, 0,      // subtype 0x08-0x0B
            -3, -4, 0, 0,     // subtype 0x0C-0x0F
            -5, -5, 0, 0,     // subtype 0x10-0x13
            7, 6, 0, 0,       // subtype 0x14-0x17
            -7, -6, 0, 0,     // subtype 0x18-0x1B
            8, 9, 0, 0,       // subtype 0x1C-0x1F
            -8, -9, 0, 0,     // subtype 0x20-0x23
            11, 10, 0, 0,     // subtype 0x24-0x27
            12, 0, 0, 0,      // subtype 0x28-0x2B
            -11, -10, 0, 0,   // subtype 0x2C-0x2F
            -12, 0, 0, 0,     // subtype 0x30-0x33
            0, 13, 0, 0,      // subtype 0x34-0x37
            -13, 14, 0, 0,    // subtype 0x38-0x3B
            0, -14, 0, 0      // subtype 0x3C-0x3F
    };

    // Entry/Exit paths (off_22980) - 12 paths
    // Each path is pairs of (X, Y) relative coordinates
    private static final int[][] ENTRY_PATHS = {
            // Path 0: word_22998
            {0x90, 0x10, 0x90, 0x70, 0x40, 0x70, 0x35, 0x6F, 0x28, 0x6A, 0x1E, 0x62,
                    0x15, 0x58, 0x11, 0x4A, 0x10, 0x40, 0x11, 0x35, 0x15, 0x27, 0x1E, 0x1E,
                    0x28, 0x15, 0x35, 0x11, 0x40, 0x10, 0x50, 0x10, 0x5E, 0x12, 0x68, 0x18,
                    0x6D, 0x24, 0x70, 0x30, 0x6D, 0x3D, 0x68, 0x48, 0x5E, 0x4E, 0x50, 0x50,
                    0x30, 0x50, 0x22, 0x52, 0x17, 0x5A, 0x11, 0x63, 0x10, 0x70},
            // Path 1: word_22A0E
            {0x90, 0x10, 0x90, 0x70, 0x40, 0x70, 0x2E, 0x6E, 0x1D, 0x62, 0x13, 0x53,
                    0x10, 0x40, 0x13, 0x2D, 0x1D, 0x1E, 0x2E, 0x13, 0x40, 0x10, 0x58, 0x10,
                    0x64, 0x14, 0x6C, 0x1A, 0x70, 0x28, 0x6C, 0x36, 0x64, 0x3C, 0x58, 0x40,
                    0x4B, 0x3D, 0x40, 0x38, 0x36, 0x32, 0x28, 0x30, 0x10, 0x30},
            // Path 2: word_22A6C
            {0x10, 0x70, 0x11, 0x63, 0x17, 0x5A, 0x22, 0x52, 0x30, 0x50, 0x50, 0x50,
                    0x5E, 0x4E, 0x68, 0x48, 0x6D, 0x3D, 0x70, 0x30, 0x6D, 0x24, 0x68, 0x18,
                    0x5E, 0x12, 0x50, 0x10, 0x40, 0x10, 0x35, 0x11, 0x28, 0x15, 0x1E, 0x1E,
                    0x15, 0x27, 0x11, 0x35, 0x10, 0x40, 0x11, 0x4A, 0x15, 0x58, 0x1E, 0x62,
                    0x28, 0x6A, 0x35, 0x6F, 0x40, 0x70, 0x90, 0x70, 0x90, 0x10},
            // Path 3: word_22AE2
            {0x10, 0x30, 0x28, 0x30, 0x36, 0x32, 0x40, 0x38, 0x4B, 0x3D, 0x58, 0x40,
                    0x64, 0x3C, 0x6C, 0x36, 0x70, 0x28, 0x6C, 0x1A, 0x64, 0x14, 0x58, 0x10,
                    0x40, 0x10, 0x2E, 0x13, 0x1D, 0x1E, 0x13, 0x2D, 0x10, 0x40, 0x13, 0x53,
                    0x1D, 0x62, 0x2E, 0x6E, 0x40, 0x70, 0x90, 0x70, 0x90, 0x10},
            // Path 4: word_22B40
            {0x10, 0x10, 0x10, 0x70, 0xC0, 0x70, 0xCA, 0x6F, 0xD4, 0x6C, 0xDB, 0x68,
                    0xE3, 0x62, 0xE8, 0x5A, 0xED, 0x52, 0xEF, 0x48, 0xF0, 0x40, 0xEF, 0x36,
                    0xED, 0x2E, 0xE8, 0x26, 0xE3, 0x1E, 0xDB, 0x17, 0xD4, 0x14, 0xCA, 0x12,
                    0xC0, 0x10, 0xB7, 0x11, 0xAF, 0x12, 0xA6, 0x17, 0x9E, 0x1E, 0x97, 0x26,
                    0x93, 0x2E, 0x91, 0x36, 0x90, 0x40, 0x90, 0x70},
            // Path 5: word_22BB2
            {0x10, 0x10, 0x10, 0x70, 0xC0, 0x70, 0xD2, 0x6E, 0xE3, 0x62, 0xED, 0x53,
                    0xF0, 0x40, 0xED, 0x2D, 0xE3, 0x1E, 0xD2, 0x13, 0xC0, 0x10, 0xA8, 0x10,
                    0x9C, 0x14, 0x94, 0x1A, 0x90, 0x28, 0x94, 0x36, 0x9C, 0x3C, 0xA8, 0x40,
                    0xB5, 0x3D, 0xC0, 0x38, 0xCA, 0x32, 0xD8, 0x30, 0xF0, 0x30},
            // Path 6: word_22C10
            {0x90, 0x70, 0x90, 0x40, 0x91, 0x36, 0x93, 0x2E, 0x97, 0x26, 0x9E, 0x1E,
                    0xA6, 0x17, 0xAF, 0x12, 0xB7, 0x11, 0xC0, 0x10, 0xCA, 0x12, 0xD4, 0x14,
                    0xDB, 0x17, 0xE3, 0x1E, 0xE8, 0x26, 0xED, 0x2E, 0xEF, 0x36, 0xF0, 0x40,
                    0xEF, 0x48, 0xED, 0x52, 0xE8, 0x5A, 0xE3, 0x62, 0xDB, 0x68, 0xD4, 0x6C,
                    0xCA, 0x6F, 0xC0, 0x70, 0x10, 0x70, 0x10, 0x10},
            // Path 7: word_22C82
            {0xF0, 0x30, 0xD8, 0x30, 0xCA, 0x32, 0xC0, 0x38, 0xB5, 0x3D, 0xA8, 0x40,
                    0x9C, 0x3C, 0x94, 0x36, 0x90, 0x28, 0x94, 0x1A, 0x9C, 0x14, 0xA8, 0x10,
                    0xC0, 0x10, 0xD2, 0x13, 0xE3, 0x1E, 0xED, 0x2D, 0xF0, 0x40, 0xED, 0x53,
                    0xE3, 0x62, 0xD2, 0x6E, 0xC0, 0x70, 0x10, 0x70, 0x10, 0x10},
            // Path 8: word_22CE0
            {0x110, 0x10, 0x110, 0x70, 0x40, 0x70, 0x35, 0x6F, 0x28, 0x6A, 0x1E, 0x62,
                    0x15, 0x58, 0x11, 0x4A, 0x10, 0x40, 0x11, 0x35, 0x15, 0x27, 0x1E, 0x1E,
                    0x28, 0x15, 0x35, 0x11, 0x40, 0x10, 0x50, 0x10, 0x5E, 0x12, 0x68, 0x18,
                    0x6D, 0x24, 0x70, 0x30, 0x6D, 0x3D, 0x68, 0x48, 0x5E, 0x4E, 0x50, 0x50,
                    0x30, 0x50, 0x22, 0x52, 0x17, 0x5A, 0x11, 0x63, 0x10, 0x70},
            // Path 9: word_22D56
            {0x110, 0x10, 0x110, 0x70, 0x40, 0x70, 0x2E, 0x6E, 0x1D, 0x62, 0x13, 0x53,
                    0x10, 0x40, 0x13, 0x2D, 0x1D, 0x1E, 0x2E, 0x13, 0x40, 0x10, 0x58, 0x10,
                    0x64, 0x14, 0x6C, 0x1A, 0x70, 0x28, 0x6C, 0x36, 0x64, 0x3C, 0x58, 0x40,
                    0x4B, 0x3D, 0x40, 0x38, 0x36, 0x32, 0x28, 0x30, 0x10, 0x30},
            // Path 10: word_22DB4
            {0x10, 0x70, 0x11, 0x63, 0x17, 0x5A, 0x22, 0x52, 0x30, 0x50, 0x50, 0x50,
                    0x5E, 0x4E, 0x68, 0x48, 0x6D, 0x3D, 0x70, 0x30, 0x6D, 0x24, 0x68, 0x18,
                    0x5E, 0x12, 0x50, 0x10, 0x40, 0x10, 0x35, 0x11, 0x28, 0x15, 0x1E, 0x1E,
                    0x15, 0x27, 0x11, 0x35, 0x10, 0x40, 0x11, 0x4A, 0x15, 0x58, 0x1E, 0x62,
                    0x28, 0x6A, 0x35, 0x6F, 0x40, 0x70, 0x110, 0x70, 0x110, 0x10},
            // Path 11: word_22E2A
            {0x10, 0x30, 0x28, 0x30, 0x36, 0x32, 0x40, 0x38, 0x4B, 0x3D, 0x58, 0x40,
                    0x64, 0x3C, 0x6C, 0x36, 0x70, 0x28, 0x6C, 0x1A, 0x64, 0x14, 0x58, 0x10,
                    0x40, 0x10, 0x2E, 0x13, 0x1D, 0x1E, 0x13, 0x2D, 0x10, 0x40, 0x13, 0x53,
                    0x1D, 0x62, 0x2E, 0x6E, 0x40, 0x70, 0x110, 0x70, 0x110, 0x10}
    };

    // Main tube paths (off_22E88) - 15 paths
    // Each path is pairs of (X, Y) absolute coordinates
    private static final int[][] MAIN_PATHS = {
            // Path 0/1: word_22EA6 (entries 0 and 1 both point here)
            {0x790, 0x3B0, 0x710, 0x3B0, 0x710, 0x6B0, 0xA90, 0x6B0, 0xA90, 0x670},
            // Path 2: word_22EBC
            {0x790, 0x3F0, 0x790, 0x4B0, 0xA00, 0x4B0, 0xC10, 0x4B0, 0xC10, 0x330,
                    0xD90, 0x330, 0xD90, 0x1B0, 0xF10, 0x1B0, 0xF10, 0x2B0, 0xF90, 0x2B0},
            // Path 3: word_22EE6
            {0xAF0, 0x630, 0xE90, 0x630, 0xE90, 0x6B0, 0xF90, 0x6B0, 0xF90, 0x670},
            // Path 4: word_22EFC
            {0xF90, 0x2F0, 0xF90, 0x4B0, 0xF10, 0x4B0, 0xF10, 0x630, 0xF90, 0x630},
            // Path 5: word_22F12
            {0x1410, 0x530, 0x1190, 0x530, 0x1190, 0x6B0, 0x1410, 0x6B0, 0x1410, 0x570},
            // Path 6: word_22F28
            {0x1AF0, 0x530, 0x1B90, 0x530, 0x1B90, 0x330, 0x1E10, 0x330},
            // Path 7: word_22F3A
            {0x1A90, 0x570, 0x1A90, 0x5B0, 0x1C10, 0x5B0, 0x1C10, 0x430, 0x1E10, 0x430, 0x1E10, 0x370},
            // Path 8: word_22F54
            {0x2490, 0x370, 0x2490, 0x3D0, 0x2390, 0x3D0, 0x2390, 0x5D0, 0x2510, 0x5D0, 0x2510, 0x570},
            // Path 9: word_22F6E
            {0x24F0, 0x330, 0x2590, 0x330, 0x2590, 0x530, 0x2570, 0x530},
            // Path 10: word_22F80
            {0x310, 0x330, 0x290, 0x330, 0x290, 0x230, 0x490, 0x230},
            // Path 11: word_22F92
            {0x310, 0x370, 0x310, 0x3B0, 0x410, 0x3B0, 0x410, 0x2B0, 0x490, 0x2B0, 0x490, 0x270},
            // Path 12: word_22FAC
            {0x490, 0x6F0, 0x490, 0x730, 0x690, 0x730, 0x890, 0x730, 0x890, 0x6F0},
            // Path 13: word_22FC2
            {0xBF0, 0x330, 0xD90, 0x330, 0xD90, 0x2F0},
            // Path 14: word_22FD0
            {0xD90, 0x2B0, 0xC90, 0x2B0, 0xC90, 0xB0, 0xE80, 0xB0, 0x1110, 0xB0, 0x1110, 0x230, 0x10F0, 0x230}
    };

    // Character state tracking
    // State modes: 0=waiting, 2=entry path, 4=main path, 6=exiting
    private int mainCharState = 0;
    private int mainCharFrame = 0;        // Animation/entry frame
    private int mainCharDuration = 0;     // Frames remaining in current segment
    private int mainCharPathIndex = 0;    // Current position in path data
    private int[] mainCharPath = null;    // Current path being followed
    private boolean mainCharReverse = false; // Traversing path in reverse

    // Expected route tracking for debugging
    private int expectedMainPathId = 0;         // Expected main path ID (0 = no main path)
    private boolean expectedReverse = false;    // Expected direction
    private int expectedSegmentCount = 0;       // Expected number of segments to traverse
    private int completedSegmentCount = 0;      // Number of segments actually completed
    private String expectedExitDirection = "";  // Expected exit direction (UP, DOWN, LEFT, RIGHT)

    // Collision distance for this tube instance
    private final int collisionDistance;

    // Game timer second value (used for path variant selection)
    private int timerSecond = 0;

    // Current frame counter (stored from update for use in sub-methods)
    private int currentFrameCounter = 0;

    // Number of frames to wait before allowing another tube to grab the player
    // Extended from 8 to 30 frames to give the player time to leave overlapping
    // tube capture zones, especially when they land and stop rolling
    private static final int TUBE_EXIT_COOLDOWN_FRAMES = 30;

    public CPZSpinTubeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Determine collision distance from subtype bits [1:0]
        // ROM: add.w d0,d0 / andi.w #6,d0 then word table lookup
        int subtypeIndex = spawn.subtype() & 3;  // Extract bits 0-1
        if (subtypeIndex < COLLISION_DISTANCES.length) {
            this.collisionDistance = COLLISION_DISTANCES[subtypeIndex];
        } else {
            this.collisionDistance = COLLISION_DISTANCES[0];
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Store frame counter for use in sub-methods
        this.currentFrameCounter = frameCounter;
        // Update timer second (used for path variant selection)
        timerSecond = (frameCounter / 60) & 0xFF;

        if (player != null) {
            updateCharacter(player);
        }
    }

    private void updateCharacter(AbstractPlayableSprite player) {
        if (mainCharState != 0) {
            LOGGER.fine("updateCharacter: state=" + mainCharState);
        }
        switch (mainCharState) {
            case 0:
                checkEntryCollision(player);
                break;
            case 2:
                updateEntryPath(player);
                break;
            case 4:
                updateMainPath(player);
                break;
            case 6:
                checkExitCollision(player);
                break;
        }
    }

    /**
     * Mode 0: Check if player enters the tube activation zone.
     */
    private void checkEntryCollision(AbstractPlayableSprite player) {
        // Skip if in debug placement mode
        // (Not implemented in this engine)

        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses center-based coordinates (x_pos, y_pos)
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // Check X range: player must be within collisionDistance of object
        int dx = playerX - objX;
        if (dx < 0 || dx >= collisionDistance) {
            return;
        }

        // Check Y range: player must be within 0x80 (128) pixels below object
        int dy = playerY - objY;
        if (dy < 0 || dy >= 0x80) {
            return;
        }

        // Debug logging to trace capture attempts
        boolean isRolling = player.getRolling();
        boolean isObjectControlled = player.isObjectControlled();
        boolean recentlyReleased = player.wasRecentlyObjectControlled(currentFrameCounter, TUBE_EXIT_COOLDOWN_FRAMES);

        // Skip if player is currently being controlled by another object
        if (isObjectControlled) {
            LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " skipped: player is object controlled");
            return;
        }

        // Determine entry parameters based on position
        int d3 = 0;  // Path table offset modifier

        // Check collision distance to determine tube type
        // This also determines if we need to adjust dx for entry type detection
        int adjustedDx = dx;
        if (collisionDistance == 0xA0) {
            d3 = 0;
        } else if (collisionDistance == 0x120) {
            d3 = 8;
        } else {
            // 0x100 collision distance - adjust dx
            d3 = 4;
            adjustedDx = 0x100 - dx;
        }

        // Determine if this is a NEAR entry (connected tube pass-through) or FAR entry
        boolean isNearEntry = adjustedDx < 0x80;

        // For NEAR entries: This is a connected tube pass-through (like exiting one tube
        // segment directly into another). Allow capture even if rolling/recently released.
        // The player will go through a short entry path and immediately exit (no main path).
        //
        // For FAR entries: This is a normal tube entry. Apply protections to prevent
        // unwanted recapture after drifting from an exit point.
        if (!isNearEntry) {
            // FAR entry - apply normal protections
            if (isRolling) {
                LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " skipped: FAR entry and player is rolling");
                return;
            }
            if (recentlyReleased) {
                LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " skipped: FAR entry and player recently released");
                return;
            }
        } else if (recentlyReleased) {
            // NEAR entry from recently released player - this is a connected tube pass-through
            LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " NEAR entry pass-through: " +
                    "capturing recently-released player for connected tube traversal");
        }

        LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " at (" + objX + "," + objY +
                ") capturing: playerPos=(" + playerX + "," + playerY +
                "), dx=" + dx + ", adjustedDx=" + adjustedDx + ", isNearEntry=" + isNearEntry +
                ", rolling=" + isRolling + ", recentlyReleased=" + recentlyReleased +
                ", frame=" + currentFrameCounter);

        // Determine entry frame based on position
        int d2;
        String entryReason;
        if (adjustedDx >= 0x80) {
            // Far entry - determine variant from subtype
            int subtypeVariant = (spawn.subtype() >> 2) & 0xF;
            d2 = PATH_VARIANT_TABLE[subtypeVariant];

            // If d2 == 2, use timer-based alternation
            if (d2 == 2) {
                int timerBit = timerSecond & 1;
                d2 = timerBit;
                entryReason = "FAR entry, timer-based: timerSecond=" + timerSecond +
                        " (" + (timerBit == 0 ? "EVEN" : "ODD") + ") -> d2=" + d2;
            } else {
                entryReason = "FAR entry, fixed variant: subtypeVariant=" + subtypeVariant + " -> d2=" + d2;
            }
        } else {
            // Near entry - determine by Y position
            if (dy >= 0x40) {
                d2 = 2;
                entryReason = "NEAR entry, dy >= 0x40 -> d2=2";
            } else {
                d2 = 3;
                entryReason = "NEAR entry, dy < 0x40 -> d2=3";
            }
        }
        LOGGER.fine("Entry frame selection: " + entryReason);

        // Store entry frame
        mainCharFrame = d2;

        // Calculate and log expected route BEFORE capturing
        calculateExpectedRoute(d2);

        // Calculate entry path index
        int pathIndex = (d2 + d3) & 0xF;
        if (pathIndex >= ENTRY_PATHS.length) {
            pathIndex = 0;
        }

        // Get the entry path
        mainCharPath = ENTRY_PATHS[pathIndex];
        mainCharReverse = false;

        // Initialize path position
        mainCharPathIndex = 0;
        mainCharDuration = (mainCharPath.length / 2) - 2;  // -2 for start position

        // Position player at first waypoint (center-based)
        int startX = mainCharPath[0] + objX;
        int startY = mainCharPath[1] + objY;
        player.setCentreX((short) startX);
        player.setCentreY((short) startY);

        // Move to second waypoint for velocity calculation
        mainCharPathIndex = 2;
        int nextX = mainCharPath[2] + objX;
        int nextY = mainCharPath[3] + objY;

        // Set player state for tube traversal
        // ROM: move.b #$81,obj_control(a1) - locks player to object control
        // This disables normal physics - the tube controls the player completely
        player.setObjectControlled(true);
        player.setControlLocked(true);
        player.setRolling(true);
        player.setAir(true);
        player.setGSpeed((short) TUBE_SPEED);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        // ROM: bclr #high_priority_bit,art_tile(a1) - render behind tube graphics
        player.setHighPriority(false);
        player.setPriorityBucket(RenderPriority.MIN);

        // Calculate velocity to next waypoint
        calculateVelocity(player, nextX, nextY, TUBE_SPEED);

        // Play rolling sound
        playSound(GameSound.ROLLING);

        // Advance to entry path mode
        mainCharState = 2;

        LOGGER.fine("Player entered spin tube: subtype=0x" + Integer.toHexString(spawn.subtype()) +
                ", entryFrame=" + mainCharFrame + ", pathIndex=" + pathIndex +
                ", collisionDist=0x" + Integer.toHexString(collisionDistance) +
                ", objPos=(" + objX + "," + objY + ")");
    }

    /**
     * Mode 2: Following the entry path.
     */
    private void updateEntryPath(AbstractPlayableSprite player) {
        mainCharDuration--;
        if (mainCharDuration >= 0) {
            // Continue moving along current segment
            moveCharacter(player);
            return;
        }

        // Reached current waypoint, advance to next
        int objX = spawn.x();
        int objY = spawn.y();

        // Get next waypoint
        int nextX = mainCharPath[mainCharPathIndex] + objX;
        int nextY = mainCharPath[mainCharPathIndex + 1] + objY;

        // Set player position to current target (center-based)
        player.setCentreX((short) nextX);
        player.setCentreY((short) nextY);

        // Check if we've reached the end of entry path
        mainCharPathIndex += 2;

        if (mainCharPathIndex >= mainCharPath.length) {
            // End of entry path - transition to main path
            // Exit velocity is from the final segment we just completed
            transitionToMainPath(player);
            return;
        }

        // Calculate velocity to next waypoint
        int targetX = mainCharPath[mainCharPathIndex] + objX;
        int targetY = mainCharPath[mainCharPathIndex + 1] + objY;
        calculateVelocity(player, targetX, targetY, TUBE_SPEED);
    }

    /**
     * Transition from entry path to main path or exit.
     */
    private void transitionToMainPath(AbstractPlayableSprite player) {
        // Check if entry frame indicates we should go to main path
        if (mainCharFrame >= 4) {
            // Exit tube
            LOGGER.fine("Exiting tube early: entryFrame >= 4");
            exitTube(player, currentFrameCounter);
            return;
        }

        // Calculate main path index from subtype and entry frame
        int routingIndex = (spawn.subtype() & 0xFC) + mainCharFrame;
        if (routingIndex >= MAIN_PATH_ROUTING.length) {
            LOGGER.fine("Exiting tube: routingIndex " + routingIndex + " out of bounds");
            exitTube(player, currentFrameCounter);
            return;
        }

        int pathId = MAIN_PATH_ROUTING[routingIndex];
        if (pathId == 0) {
            // No main path - exit
            LOGGER.fine("Exiting tube: no main path for routingIndex " + routingIndex +
                    " (subtype=0x" + Integer.toHexString(spawn.subtype()) + ", frame=" + mainCharFrame + ")");
            exitTube(player, currentFrameCounter);
            return;
        }

        // Set entry frame to 4 to indicate we're in main path
        mainCharFrame = 4;

        // Determine path direction and index
        if (pathId < 0) {
            // Negative = reverse traversal
            mainCharReverse = true;
            pathId = -pathId;
        } else {
            mainCharReverse = false;
        }

        // Adjust for 1-based indexing in routing table
        pathId--;
        if (pathId < 0 || pathId >= MAIN_PATHS.length) {
            exitTube(player, currentFrameCounter);
            return;
        }

        // Get the main path
        mainCharPath = MAIN_PATHS[pathId];

        if (mainCharReverse) {
            // Start from end of path
            mainCharPathIndex = mainCharPath.length - 4;
        } else {
            // Start from beginning
            mainCharPathIndex = 0;
        }
        // Note: mainCharDuration will be set by calculateVelocity

        // Position player at first waypoint (absolute coordinates, center-based)
        int startX, startY;
        if (mainCharReverse) {
            startX = mainCharPath[mainCharPathIndex + 2];
            startY = mainCharPath[mainCharPathIndex + 3];
        } else {
            startX = mainCharPath[0];
            startY = mainCharPath[1];
        }
        player.setCentreX((short) startX);
        player.setCentreY((short) startY);

        // Get next waypoint
        int nextX, nextY;
        if (mainCharReverse) {
            nextX = mainCharPath[mainCharPathIndex];
            nextY = mainCharPath[mainCharPathIndex + 1];
        } else {
            mainCharPathIndex = 2;
            nextX = mainCharPath[2];
            nextY = mainCharPath[3];
        }

        // Calculate velocity
        calculateVelocity(player, nextX, nextY, TUBE_SPEED);

        // Play rolling sound
        playSound(GameSound.ROLLING);

        // Advance to main path mode
        mainCharState = 4;

        LOGGER.fine("Transitioned to main path " + (pathId + 1) + " (reverse=" + mainCharReverse +
                "), routingIndex=" + routingIndex + ", pathLength=" + mainCharPath.length +
                ", duration=" + mainCharDuration + ", startPos=(" + startX + "," + startY +
                "), nextTarget=(" + nextX + "," + nextY + ")");
    }

    /**
     * Mode 4: Following the main tube path.
     */
    private void updateMainPath(AbstractPlayableSprite player) {

        mainCharDuration--;
        if (mainCharDuration >= 0) {
            // Continue moving along current segment
            moveCharacter(player);
            return;
        }

        // Reached current waypoint, advance to next
        int nextX, nextY;
        if (mainCharReverse) {
            nextX = mainCharPath[mainCharPathIndex];
            nextY = mainCharPath[mainCharPathIndex + 1];
        } else {
            nextX = mainCharPath[mainCharPathIndex];
            nextY = mainCharPath[mainCharPathIndex + 1];
        }

        // Set player position to current target (center-based)
        player.setCentreX((short) nextX);
        player.setCentreY((short) nextY);

        // Completed a segment
        completedSegmentCount++;

        // Advance path index
        if (mainCharReverse) {
            mainCharPathIndex -= 2;
            if (mainCharPathIndex < 0) {
                // End of main path - completed all segments
                LOGGER.fine("Main path complete: completed " + completedSegmentCount + "/" + expectedSegmentCount + " segments");
                exitTube(player, currentFrameCounter);
                return;
            }
        } else {
            mainCharPathIndex += 2;
            if (mainCharPathIndex >= mainCharPath.length) {
                // End of main path - completed all segments
                LOGGER.fine("Main path complete: completed " + completedSegmentCount + "/" + expectedSegmentCount + " segments");
                exitTube(player, currentFrameCounter);
                return;
            }
        }

        // Calculate velocity to next waypoint
        if (mainCharReverse) {
            nextX = mainCharPath[mainCharPathIndex];
            nextY = mainCharPath[mainCharPathIndex + 1];
        } else {
            nextX = mainCharPath[mainCharPathIndex];
            nextY = mainCharPath[mainCharPathIndex + 1];
        }
        calculateVelocity(player, nextX, nextY, TUBE_SPEED);
    }

    /**
     * Mode 6: Player has exited, check if they re-enter.
     */
    private void checkExitCollision(AbstractPlayableSprite player) {
        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses center-based coordinates
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // Check if player has left the tube area
        int dx = playerX - objX;
        if (dx < 0 || dx >= collisionDistance) {
            // Player has left - reset state
            mainCharState = 0;
            return;
        }

        int dy = playerY - objY;
        if (dy < 0 || dy >= 0x80) {
            // Player has left - reset state
            mainCharState = 0;
        }
    }

    /**
     * Exit the tube and restore player control.
     */
    private void exitTube(AbstractPlayableSprite player, int frameCounter) {
        // Check for early exit and log warning
        if (expectedSegmentCount > 0 && completedSegmentCount < expectedSegmentCount) {
            LOGGER.warning("EARLY EXIT WARNING: Completed " + completedSegmentCount + "/" + expectedSegmentCount +
                    " segments! Expected exit direction was " + expectedExitDirection +
                    ", subtype=0x" + Integer.toHexString(spawn.subtype()) +
                    ", pos=(" + player.getCentreX() + "," + player.getCentreY() + ")" +
                    ", xSpeed=" + player.getXSpeed() + ", ySpeed=" + player.getYSpeed());
        } else if (expectedSegmentCount > 0) {
            LOGGER.fine("Normal exit: Completed all " + completedSegmentCount + " segments, exit direction=" + expectedExitDirection);
        }

        // Clear Y position high bits (mask to 0x7FF) - using center coordinates
        int y = player.getCentreY() & 0x7FF;
        player.setCentreY((short) y);

        // Restore player control with cooldown to prevent immediate re-capture
        // ROM: clr.b obj_control(a1)
        player.releaseFromObjectControl(frameCounter);
        player.setControlLocked(false);

        // Enable pinball mode to preserve rolling on landing, but NOT for upward exits.
        // When exiting upward, the player should land normally without forced rolling.
        // For horizontal/downward exits, pinball mode keeps them rolling through the terrain.
        // Check actual velocity rather than expectedExitDirection since pass-through tubes
        // may not have a calculated expected direction.
        boolean exitingUpward = player.getYSpeed() < 0 && Math.abs(player.getYSpeed()) > Math.abs(player.getXSpeed());
        if (!exitingUpward) {
            player.setPinballMode(true);
        }

        // Set springing frames to give the player ceiling collision immunity.
        // This prevents the movement manager from immediately zeroing ySpeed when the
        // exit point is inside the tube's solid geometry. 15 frames matches springs.
        player.setSpringing(15);

        // Restore normal render priority
        player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);

        // Play spindash release sound
        playSound(GameSound.SPINDASH_RELEASE);

        // Move to exit check mode
        mainCharState = 6;

        LOGGER.fine("Player exited spin tube");
    }

    /**
     * Move character by current velocity.
     * Based on Obj1E_MoveCharacter from disassembly.
     *
     * The original ROM uses 16.16 fixed point positions and shifts velocity left by 8
     * before adding. Our engine uses 8.8 fixed point velocities with the move() method
     * handling the conversion correctly.
     */
    private void moveCharacter(AbstractPlayableSprite player) {
        // Use player.move() which correctly handles 8.8 fixed point velocities
        // (where 256 = 1 pixel per frame)
        player.move(player.getXSpeed(), player.getYSpeed());
    }

    /**
     * Calculate velocity to move from current position to target.
     * Based on loc_22902 from disassembly.
     *
     * The ROM uses 16.16 fixed point and calculates:
     * - duration = (dominant_distance << 16) / speed, stored as word, read as high byte
     * - So effective frames = (dominant_distance * 256) / speed
     * - cross_axis_vel = (cross_distance * speed) / dominant_distance
     *
     * Our engine uses 8.8 fixed point velocities (256 = 1 pixel/frame).
     * Speed of 0x800 = 2048 means 8 pixels per frame.
     *
     * @param player The player sprite
     * @param targetX Target X coordinate
     * @param targetY Target Y coordinate
     * @param speed Fixed movement speed (0x800 = 8 pixels/frame in 8.8 format)
     */
    private void calculateVelocity(AbstractPlayableSprite player, int targetX, int targetY, int speed) {
        // Use center coordinates to match ROM behavior
        int currentX = player.getCentreX();
        int currentY = player.getCentreY();

        int dx = targetX - currentX;
        int dy = targetY - currentY;

        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        int xVel, yVel, frames;

        if (absDy >= absDx) {
            // Y distance is dominant - move at fixed Y speed
            yVel = (dy >= 0) ? speed : -speed;

            // Calculate X velocity proportionally: xVel = (dx * speed) / abs(dy)
            if (absDy != 0) {
                xVel = (dx * speed) / absDy;
            } else {
                xVel = 0;
            }

            // Frame count = (distance * 256) / speed
            // This matches ROM: duration = (distance << 16) / speed, read high byte
            frames = (absDy * 256) / speed;
        } else {
            // X distance is dominant - move at fixed X speed
            xVel = (dx >= 0) ? speed : -speed;

            // Calculate Y velocity proportionally: yVel = (dy * speed) / abs(dx)
            if (absDx != 0) {
                yVel = (dy * speed) / absDx;
            } else {
                yVel = 0;
            }

            // Frame count = (distance * 256) / speed
            frames = (absDx * 256) / speed;
        }

        // Ensure at least 1 frame to prevent getting stuck
        if (frames < 1) {
            frames = 1;
        }

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
        mainCharDuration = frames;
    }

    /**
     * Calculates the expected route for debugging purposes.
     * This predicts what main path we'll use and what direction we'll exit.
     */
    private void calculateExpectedRoute(int entryFrame) {
        // Reset tracking
        completedSegmentCount = 0;

        // Check if entry frame indicates we'll skip main path
        if (entryFrame >= 4) {
            expectedMainPathId = 0;
            expectedReverse = false;
            expectedSegmentCount = 0;
            expectedExitDirection = "NONE (entry frame >= 4, early exit)";
            LOGGER.fine("EXPECTED ROUTE: No main path (entryFrame=" + entryFrame + " >= 4)");
            return;
        }

        // Calculate main path index from subtype and entry frame
        int routingIndex = (spawn.subtype() & 0xFC) + entryFrame;
        if (routingIndex >= MAIN_PATH_ROUTING.length) {
            expectedMainPathId = 0;
            expectedReverse = false;
            expectedSegmentCount = 0;
            expectedExitDirection = "NONE (routingIndex out of bounds)";
            LOGGER.fine("EXPECTED ROUTE: No main path (routingIndex=" + routingIndex + " out of bounds)");
            return;
        }

        int pathId = MAIN_PATH_ROUTING[routingIndex];
        if (pathId == 0) {
            expectedMainPathId = 0;
            expectedReverse = false;
            expectedSegmentCount = 0;
            expectedExitDirection = "NONE (pathId=0, no main path for this route)";
            LOGGER.fine("EXPECTED ROUTE: No main path for routingIndex=" + routingIndex +
                    " (subtype=0x" + Integer.toHexString(spawn.subtype()) + ", entryFrame=" + entryFrame + ")");
            return;
        }

        // Determine path direction and index
        expectedReverse = pathId < 0;
        int actualPathIndex = (expectedReverse ? -pathId : pathId) - 1;
        expectedMainPathId = pathId;

        if (actualPathIndex < 0 || actualPathIndex >= MAIN_PATHS.length) {
            expectedSegmentCount = 0;
            expectedExitDirection = "NONE (invalid path index)";
            LOGGER.warning("EXPECTED ROUTE: Invalid path index " + actualPathIndex);
            return;
        }

        // Calculate expected segment count and exit direction
        int[] path = MAIN_PATHS[actualPathIndex];
        int waypointCount = path.length / 2;
        expectedSegmentCount = waypointCount - 1;  // segments = waypoints - 1

        // Determine exit direction from final segment
        int exitStartX, exitStartY, exitEndX, exitEndY;
        if (expectedReverse) {
            // Reverse: exit from first waypoint, coming from second waypoint
            exitEndX = path[0];
            exitEndY = path[1];
            exitStartX = path[2];
            exitStartY = path[3];
        } else {
            // Forward: exit from last waypoint, coming from second-to-last
            exitEndX = path[path.length - 2];
            exitEndY = path[path.length - 1];
            exitStartX = path[path.length - 4];
            exitStartY = path[path.length - 3];
        }

        int exitDx = exitEndX - exitStartX;
        int exitDy = exitEndY - exitStartY;
        if (Math.abs(exitDx) > Math.abs(exitDy)) {
            expectedExitDirection = exitDx > 0 ? "RIGHT" : "LEFT";
        } else {
            expectedExitDirection = exitDy > 0 ? "DOWN" : "UP";
        }

        LOGGER.fine("EXPECTED ROUTE: Main path " + (actualPathIndex + 1) +
                " (" + (expectedReverse ? "REVERSE" : "FORWARD") + ")" +
                ", segments=" + expectedSegmentCount +
                ", exit direction=" + expectedExitDirection +
                ", exit pos=(" + exitEndX + "," + exitEndY + ")");
    }

    private void playSound(GameSound sound) {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(sound);
            }
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object - no rendering
    }

    @Override
    public boolean isPersistent() {
        // Keep this object active while it's controlling a player (state != 0 and state != 6)
        return mainCharState == 2 || mainCharState == 4;
    }
}

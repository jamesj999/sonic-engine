package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
public class CPZSpinTubeObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(CPZSpinTubeObjectInstance.class.getName());

    // Fixed rolling speed through tube (0x800 in ROM)
    private static final int TUBE_SPEED = 0x800;
    private static final int RELEASE_ROLL_ANIMATION_HOLD_FRAMES = 15;

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

    /**
     * Per-character tube traversal state.
     *
     * <p>The ROM Obj1E_Main runs the entire capture+path-follow routine once per
     * playable character each frame: once for MainCharacter using state slot
     * {@code objoff_2C(a0)} and once for Sidekick using state slot
     * {@code objoff_36(a0)} (docs/s2disasm/s2.asm:48447-48457). Each slot holds
     * that character's mode byte, entry frame, segment duration, segment count,
     * and path pointer independently, so each character is captured and routed
     * through the tube on its own. This class mirrors one such ROM state slot;
     * the object keeps one instance per playable so Tails is grabbed and forced
     * to the tube's 0x800 velocity exactly like Sonic instead of free-falling.
     */
    private static final class CharacterState {
        // State modes: 0=waiting, 2=entry path, 4=main path, 6=exiting
        int state = 0;
        int frame = 0;            // Animation/entry frame ((a4)+1)
        int duration = 0;         // Frames remaining in current segment ((a4)+2)
        int pathIndex = 0;        // Current position in path data
        int[] path = null;        // Current path being followed (6(a4))
        boolean reverse = false;  // Traversing path in reverse (sign of 1(a4))

        // Expected route tracking for debugging
        int expectedMainPathId = 0;        // Expected main path ID (0 = no main path)
        boolean expectedReverse = false;   // Expected direction
        int expectedSegmentCount = 0;      // Expected number of segments to traverse
        int completedSegmentCount = 0;     // Number of segments actually completed
        String expectedExitDirection = ""; // Expected exit direction (UP, DOWN, LEFT, RIGHT)
        int releaseRollAnimationHoldFrames = 0;
        boolean skipNextEntryMoveForOwnerOverwrite = false;
        boolean skipMoveAfterOwnerExit = false;
    }

    // One independent state slot per playable, mirroring ROM objoff_2C (main)
    // and objoff_36 (sidekick). Keyed on the player sprite identity so each
    // character runs the tube routine independently
    // (docs/s2disasm/s2.asm:48447-48457).
    private final java.util.Map<AbstractPlayableSprite, CharacterState> characterStates =
            new java.util.IdentityHashMap<>();
    private static final Map<AbstractPlayableSprite, CPZSpinTubeObjectInstance> activeTubeOwners =
            new WeakHashMap<>();

    // Collision distance for this tube instance
    private int collisionDistance;

    // Game timer second value (used for path variant selection)
    private int timerSecond = 0;

    // Current frame counter (stored from update for use in sub-methods)
    private int currentVIntRunCount = 0;

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
    public CPZSpinTubeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CPZSpinTubeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Store frame counter for use in sub-methods
        this.currentVIntRunCount = vIntRunCount;
        // Update timer second (used for path variant selection).
        // ROM loc_2265E reads the live level-time seconds via
        // move.b (Timer_second).w,d2 / andi.b #1,d2 (docs/s2disasm/s2.asm:48499-48503),
        // i.e. the on-screen TIME seconds digit, NOT a free-running global frame
        // counter. Deriving it from the raw replay frame counter (frames since
        // capture start) diverges from the ROM Timer_second whenever the act
        // begins with a non-zero level timer, which flips the timer-parity entry
        // path selection (byte_2266E value 2 -> Timer_second&1). Read the actual
        // game timer so the captured entry path matches the ROM. Because the
        // seconds value is only ever used as Timer_second & 1 and 60 is even,
        // (elapsedSeconds & 1) == (Timer_second & 1) exactly.
        var levelGamestate = services().levelGamestate();
        int timerSeconds = levelGamestate != null
                ? levelGamestate.getElapsedSeconds()
                : (vIntRunCount / 60);
        timerSecond = timerSeconds & 0xFF;

        // ROM Obj1E_Main runs the routine once per playable character each frame:
        // MainCharacter (objoff_2C) then Sidekick (objoff_36)
        // (docs/s2disasm/s2.asm:48447-48457). Mirror that two-pass dispatch by
        // running the same state machine against the main player and every
        // sidekick, each with its own independent CharacterState slot.
        if (playerEntity instanceof AbstractPlayableSprite mainPlayer) {
            updateCharacter(mainPlayer);
        }
        for (PlayableEntity sidekickEntity :
                services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (sidekickEntity == playerEntity) {
                continue;
            }
            if (sidekickEntity instanceof AbstractPlayableSprite sidekick) {
                updateCharacter(sidekick);
            }
        }
    }

    private CharacterState stateFor(AbstractPlayableSprite player) {
        return characterStates.computeIfAbsent(player, p -> new CharacterState());
    }

    private void updateCharacter(AbstractPlayableSprite player) {
        CharacterState cs = stateFor(player);
        // If player entered debug mode while in tube, reset tube state
        if (player.isDebugMode() && (cs.state == 2 || cs.state == 4)) {
            resetTubeState(cs);
            clearActiveOwner(player);
            return;
        }

        if (cs.state != 0) {
            LOGGER.fine("updateCharacter: state=" + cs.state);
        }
        preserveReleasedRollAnimation(player, cs);
        switch (cs.state) {
            case 0:
                checkEntryCollision(player, cs);
                break;
            case 2:
                updateEntryPath(player, cs);
                break;
            case 4:
                updateMainPath(player, cs);
                break;
            case 6:
                checkExitCollision(player, cs);
                break;
        }
    }

    /**
     * Resets tube internal state without modifying player.
     * Used when player enters debug mode while traversing the tube.
     */
    private void resetTubeState(CharacterState cs) {
        cs.state = 0;
        cs.pathIndex = 0;
        cs.duration = 0;
        cs.path = null;
        cs.completedSegmentCount = 0;
        cs.expectedSegmentCount = 0;
        cs.releaseRollAnimationHoldFrames = 0;
        cs.skipNextEntryMoveForOwnerOverwrite = false;
        cs.skipMoveAfterOwnerExit = false;
    }

    private void preserveReleasedRollAnimation(AbstractPlayableSprite player, CharacterState cs) {
        if (cs.releaseRollAnimationHoldFrames <= 0) {
            return;
        }
        cs.releaseRollAnimationHoldFrames--;
        if (player.isObjectControlled()) {
            return;
        }
        player.setAnimationId(Sonic2AnimationIds.ROLL);
    }

    /**
     * Mode 0: Check if player enters the tube activation zone.
     */
    private void checkEntryCollision(AbstractPlayableSprite player, CharacterState cs) {
        // The shipped S2 ROM gates Obj1E capture on the global
        // Debug_placement_mode byte (s2.asm:48526-48527). Native S2 debug
        // placement is not an engine capability: it also owns ring/item
        // placement and several other level-wide branches. The supported
        // engine debug mode is free-fly movement, and the shared touch/solid
        // controllers already exclude it. Keep this object on that same
        // capability boundary instead of letting a tube capture a free-fly
        // player; this does not claim native ring/item placement parity.
        if (player.isDebugMode()) {
            return;
        }

        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses center-based coordinates (x_pos, y_pos).
        //
        // ROM loc_225FC reads the character's LIVE x_pos/y_pos at the moment this
        // tube's slot runs -- move.w x_pos(a1),d0 / move.w y_pos(a1),d1
        // (docs/s2disasm/s2.asm:48529 and :48533). There is no frame-start
        // snapshot anywhere in Obj1E: ExecuteObjects walks the object list in slot
        // order, so a lower-slot Obj1E that already ran Obj1E_MoveCharacter this
        // frame (docs/s2disasm/s2.asm:48661-48671) has already written x_pos/y_pos
        // before a higher-slot tube evaluates this gate. Reading the live position
        // is what reproduces that ordering; any frame-start basis makes a handoff
        // between two overlapping tubes land a frame late, after which both tubes
        // hold the character and Obj1E_MoveCharacter is applied twice per frame
        // out of phase with the ROM.
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

        // ROM loc_225FC (docs/s2disasm/s2.asm:48526-48538) gates capture on:
        //   - not Debug_placement_mode (the engine debug boundary is checked above;
        //     native S2 placement mode remains unavailable)
        //   - x_pos(a1)-x_pos(a0) < objoff_2A (collisionDistance)  [checked above]
        //   - y_pos(a1)-y_pos(a0) < 0x80                           [checked above]
        //   - anim(a1) != $20 (raw S2 anim index: AniIDSonAni_Lying for Sonic,
        //     the Tails helicopter-fly anim for Tails — both correctly excluded)
        // There is deliberately NO rolling / obj_control / "recently released"
        // gate. Each character runs its own state slot (objoff_2C / objoff_36),
        // so re-capture after an exit is prevented by that character's mode-6
        // exit-collision state, not by a rolling/cooldown guard. The earlier
        // engine guards blocked the rolling sidekick (Tails) from ever entering
        // the tube, leaving it to free-fall under gravity instead of being pinned
        // to the tube's 0x800 velocity. Modelling the ROM gate fixes that without
        // any zone/route carve-out (Obj1E is S2-only).
        if (player.getAnimationId() == 0x20) {
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

        LOGGER.fine("Tube 0x" + Integer.toHexString(spawn.subtype()) + " at (" + objX + "," + objY +
                ") capturing: playerPos=(" + playerX + "," + playerY +
                "), dx=" + dx + ", adjustedDx=" + adjustedDx + ", isNearEntry=" + isNearEntry +
                ", frame=" + currentVIntRunCount);

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
        cs.frame = d2;

        // Calculate and log expected route BEFORE capturing
        calculateExpectedRoute(cs, d2);

        // Calculate entry path index
        int pathIndex = (d2 + d3) & 0xF;
        if (pathIndex >= ENTRY_PATHS.length) {
            pathIndex = 0;
        }

        // Get the entry path
        cs.path = ENTRY_PATHS[pathIndex];
        cs.reverse = false;

        // Initialize path position
        cs.pathIndex = 0;
        cs.duration = (cs.path.length / 2) - 2;  // -2 for start position

        // Position player at first waypoint (center-based).
        // ROM loc_22688 captures the player onto the entry path with
        // move.w d4,x_pos(a1) / move.w d5,y_pos(a1) (docs/s2disasm/s2.asm:48531-48545),
        // a word write that preserves the 16-bit subpixel low word. Use the
        // subpixel-preserving setters so the carried fraction is not zeroed; the
        // fraction is what the next loc_22902 velocity recompute integrates over.
        int startX = cs.path[0] + objX;
        int startY = cs.path[1] + objY;
        NativePositionOps.writeXPosPreserveSubpixel(player, startX);
        NativePositionOps.writeYPosPreserveSubpixel(player, startY);

        // Move to second waypoint for velocity calculation
        cs.pathIndex = 2;
        int nextX = cs.path[2] + objX;
        int nextY = cs.path[3] + objY;

        // Set player state for tube traversal.
        // ROM: move.b #$81,obj_control(a1) - player-local object control.
        // This suppresses normal physics while leaving global Control_Locked
        // untouched so Ctrl_1_Logical keeps refreshing during traversal.
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        markActiveOwner(player);
        // ROM loc_22688 writes only anim(a1)=AniIDSonAni_Roll
        // (docs/s2disasm/s2.asm:48612-48614); it does not set
        // status.player.rolling or change y_radius. Preserve the incoming rolling
        // status/radii and force the visual roll animation separately.
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setAir(true);
        // ROM loc_22688: move.b #0,jumping(a1). Tube traversal is an external
        // object launch; the following exit velocity must not be capped by
        // Tails_JumpHeight/Sonic_JumpHeight as if it were a held jump.
        player.setJumping(false);
        player.setGSpeed((short) TUBE_SPEED);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        // ROM: move.b #0,jumping(a1). Without clearing this latch, normal
        // airborne movement treats the tube release as a jump-button release
        // and clamps the upward exit speed to -$400 instead of preserving
        // Obj1E's -$800 launch velocity (docs/s2disasm/s2.asm:48130-48141).
        player.setJumping(false);
        // ROM: bclr #high_priority_bit,art_tile(a1) - render behind tube graphics
        player.setHighPriority(false);
        player.setPriorityBucket(RenderPriority.MIN);

        // Calculate velocity to next waypoint
        calculateVelocity(player, cs, nextX, nextY, TUBE_SPEED);

        // ROM loc_22688 ends the capture pass at the PlaySound tail
        // (docs/s2disasm/s2.asm:48612-48718): it writes the entry waypoint and the
        // first loc_22902 velocity, and never calls Obj1E_MoveCharacter itself.
        // The character is moved only by whichever Obj1E slots run afterwards, so
        // no catch-up move belongs here.
        cs.skipMoveAfterOwnerExit = false;

        // Play rolling sound
        playSound(GameSound.ROLLING);

        // Advance to entry path mode
        cs.state = 2;

        LOGGER.fine("Player entered spin tube: subtype=0x" + Integer.toHexString(spawn.subtype()) +
                ", entryFrame=" + cs.frame + ", pathIndex=" + pathIndex +
                ", collisionDist=0x" + Integer.toHexString(collisionDistance) +
                ", objPos=(" + objX + "," + objY + ")");
    }

    private void markActiveOwner(AbstractPlayableSprite player) {
        activeTubeOwners.put(player, this);
    }

    private void clearActiveOwner(AbstractPlayableSprite player) {
        if (activeTubeOwners.get(player) == this) {
            activeTubeOwners.remove(player);
        }
    }

    /**
     * Mode 2: Following the entry path.
     */
    private void updateEntryPath(AbstractPlayableSprite player, CharacterState cs) {
        cs.duration--;
        if (cs.duration >= 0) {
            if (shouldSkipMoveAfterPriorOwnerExit(player, cs)) {
                cs.skipNextEntryMoveForOwnerOverwrite = false;
                return;
            }
            if (cs.skipNextEntryMoveForOwnerOverwrite) {
                cs.skipNextEntryMoveForOwnerOverwrite = false;
                return;
            }
            // Continue moving along current segment
            moveCharacter(player);
            return;
        }

        // Reached current waypoint, advance to next
        int objX = spawn.x();
        int objY = spawn.y();

        // Get next waypoint
        int nextX = cs.path[cs.pathIndex] + objX;
        int nextY = cs.path[cs.pathIndex + 1] + objY;

        // Set player position to current target (center-based).
        // ROM loc_2271A writes the waypoint with move.w d4,x_pos(a1) /
        // move.w d5,y_pos(a1) (docs/s2disasm/s2.asm:48577-48586) -- a word write
        // that leaves the 16-bit subpixel fraction untouched. Use the
        // subpixel-preserving setters so the fraction carried across waypoints is
        // not lost; zeroing it drifts the tube position and shifts the cross-axis
        // velocity recomputed at loc_22902 (docs/s2disasm/s2.asm:48761-48815).
        NativePositionOps.writeXPosPreserveSubpixel(player, nextX);
        NativePositionOps.writeYPosPreserveSubpixel(player, nextY);
        markActiveOwner(player);

        // Check if we've reached the end of entry path
        cs.pathIndex += 2;

        if (cs.pathIndex >= cs.path.length) {
            // End of entry path - transition to main path
            // Exit velocity is from the final segment we just completed
            transitionToMainPath(player, cs);
            return;
        }

        // Calculate velocity to next waypoint
        int targetX = cs.path[cs.pathIndex] + objX;
        int targetY = cs.path[cs.pathIndex + 1] + objY;
        calculateVelocity(player, cs, targetX, targetY, TUBE_SPEED);
    }

    /**
     * Transition from entry path to main path or exit.
     */
    private void transitionToMainPath(AbstractPlayableSprite player, CharacterState cs) {
        // Check if entry frame indicates we should go to main path
        if (cs.frame >= 4) {
            // Exit tube
            LOGGER.fine("Exiting tube early: entryFrame >= 4");
            exitTube(player, cs, currentVIntRunCount);
            return;
        }

        // Calculate main path index from subtype and entry frame
        int routingIndex = (spawn.subtype() & 0xFC) + cs.frame;
        if (routingIndex >= MAIN_PATH_ROUTING.length) {
            LOGGER.fine("Exiting tube: routingIndex " + routingIndex + " out of bounds");
            exitTube(player, cs, currentVIntRunCount);
            return;
        }

        int pathId = MAIN_PATH_ROUTING[routingIndex];
        if (pathId == 0) {
            // No main path - exit
            LOGGER.fine("Exiting tube: no main path for routingIndex " + routingIndex +
                    " (subtype=0x" + Integer.toHexString(spawn.subtype()) + ", frame=" + cs.frame + ")");
            exitTube(player, cs, currentVIntRunCount);
            return;
        }

        // Set entry frame to 4 to indicate we're in main path
        cs.frame = 4;

        // Determine path direction and index
        if (pathId < 0) {
            // Negative = reverse traversal
            cs.reverse = true;
            pathId = -pathId;
        } else {
            cs.reverse = false;
        }

        // Adjust for 1-based indexing in routing table
        pathId--;
        if (pathId < 0 || pathId >= MAIN_PATHS.length) {
            exitTube(player, cs, currentVIntRunCount);
            return;
        }

        // Get the main path
        cs.path = MAIN_PATHS[pathId];

        if (cs.reverse) {
            // Start from end of path
            cs.pathIndex = cs.path.length - 4;
        } else {
            // Start from beginning
            cs.pathIndex = 0;
        }
        // Note: cs.duration will be set by calculateVelocity

        // Position player at first waypoint (absolute coordinates, center-based)
        int startX, startY;
        if (cs.reverse) {
            startX = cs.path[cs.pathIndex + 2];
            startY = cs.path[cs.pathIndex + 3];
        } else {
            startX = cs.path[0];
            startY = cs.path[1];
        }
        // ROM writes the initial main-path waypoint with move.w to x_pos(a1) /
        // y_pos(a1) (docs/s2disasm/s2.asm:48531-48545), preserving the subpixel
        // fraction. Use the subpixel-preserving setters here too.
        NativePositionOps.writeXPosPreserveSubpixel(player, startX);
        NativePositionOps.writeYPosPreserveSubpixel(player, startY);
        markActiveOwner(player);

        // Get next waypoint
        int nextX, nextY;
        if (cs.reverse) {
            nextX = cs.path[cs.pathIndex];
            nextY = cs.path[cs.pathIndex + 1];
        } else {
            cs.pathIndex = 2;
            nextX = cs.path[2];
            nextY = cs.path[3];
        }

        // Calculate velocity
        calculateVelocity(player, cs, nextX, nextY, TUBE_SPEED);

        // Play rolling sound
        playSound(GameSound.ROLLING);

        // Advance to main path mode
        cs.state = 4;

        LOGGER.fine("Transitioned to main path " + (pathId + 1) + " (reverse=" + cs.reverse +
                "), routingIndex=" + routingIndex + ", pathLength=" + cs.path.length +
                ", duration=" + cs.duration + ", startPos=(" + startX + "," + startY +
                "), nextTarget=(" + nextX + "," + nextY + ")");
    }

    /**
     * Mode 4: Following the main tube path.
     */
    private void updateMainPath(AbstractPlayableSprite player, CharacterState cs) {

        cs.duration--;
        if (cs.duration >= 0) {
            if (shouldSkipMoveAfterPriorOwnerExit(player, cs)) {
                return;
            }
            // Continue moving along current segment
            moveCharacter(player);
            return;
        }

        // Reached current waypoint, advance to next
        int nextX = cs.path[cs.pathIndex];
        int nextY = cs.path[cs.pathIndex + 1];

        // Set player position to current target (center-based).
        // ROM loc_227FE writes the main-path waypoint with move.w d4,x_pos(a1) /
        // move.w d5,y_pos(a1) (docs/s2disasm/s2.asm:48655-48662) -- preserving the
        // subpixel low word. Use the subpixel-preserving setters so the carried
        // fraction survives each waypoint snap and the loc_22902 velocity recompute
        // (docs/s2disasm/s2.asm:48761-48815) sees ROM-accurate integer-pixel input.
        NativePositionOps.writeXPosPreserveSubpixel(player, nextX);
        NativePositionOps.writeYPosPreserveSubpixel(player, nextY);
        markActiveOwner(player);

        // Completed a segment
        cs.completedSegmentCount++;

        // Advance path index
        if (cs.reverse) {
            cs.pathIndex -= 2;
            if (cs.pathIndex < 0) {
                // End of main path - completed all segments
                LOGGER.fine("Main path complete: completed " + cs.completedSegmentCount + "/" + cs.expectedSegmentCount + " segments");
                completeMainPathHandoff(player, cs);
                return;
            }
        } else {
            cs.pathIndex += 2;
            if (cs.pathIndex >= cs.path.length) {
                // End of main path - completed all segments
                LOGGER.fine("Main path complete: completed " + cs.completedSegmentCount + "/" + cs.expectedSegmentCount + " segments");
                completeMainPathHandoff(player, cs);
                return;
            }
        }

        // Calculate velocity to next waypoint
        nextX = cs.path[cs.pathIndex];
        nextY = cs.path[cs.pathIndex + 1];
        calculateVelocity(player, cs, nextX, nextY, TUBE_SPEED);
    }

    /**
     * Mode 6: Player has exited, check if they re-enter.
     */
    private void checkExitCollision(AbstractPlayableSprite player, CharacterState cs) {
        int objX = spawn.x();
        int objY = spawn.y();
        // ROM uses center-based coordinates
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // Check if player has left the tube area
        int dx = playerX - objX;
        if (dx < 0 || dx >= collisionDistance) {
            // Player has left - reset state
            cs.state = 0;
            return;
        }

        int dy = playerY - objY;
        if (dy < 0 || dy >= 0x80) {
            // Player has left - reset state
            cs.state = 0;
        }
    }

    /**
     * Exit the tube and restore player control.
     */
    private void exitTube(AbstractPlayableSprite player, CharacterState cs, int vIntRunCount) {
        // Check for early exit and log warning
        if (cs.expectedSegmentCount > 0 && cs.completedSegmentCount < cs.expectedSegmentCount) {
            LOGGER.warning("EARLY EXIT WARNING: Completed " + cs.completedSegmentCount + "/" + cs.expectedSegmentCount +
                    " segments! Expected exit direction was " + cs.expectedExitDirection +
                    ", subtype=0x" + Integer.toHexString(spawn.subtype()) +
                    ", pos=(" + player.getCentreX() + "," + player.getCentreY() + ")" +
                    ", xSpeed=" + player.getXSpeed() + ", ySpeed=" + player.getYSpeed());
        } else if (cs.expectedSegmentCount > 0) {
            LOGGER.fine("Normal exit: Completed all " + cs.completedSegmentCount + " segments, exit direction=" + cs.expectedExitDirection);
        }

        // ROM loc_227A6/loc_22858: andi.w #$7FF,y_pos(a1). This masks only
        // the native y_pos pixel word and leaves the sibling y_sub word intact.
        int y = player.getCentreY() & 0x7FF;
        NativePositionOps.writeYPosPreserveSubpixel(player, y);

        // ROM loc_227A6 directly clears obj_control(a1)
        // (docs/s2disasm/s2.asm:48683-48688). The main-path loc_22858 handoff is
        // handled separately because it deliberately leaves obj_control set.
        player.releaseFromObjectControl(vIntRunCount);
        clearActiveOwner(player);

        // ROM loc_22688 sets status.player.in_air and anim(a1)=AniIDSonAni_Roll
        // on capture (docs/s2disasm/s2.asm:48612-48616). loc_227A6 only masks
        // y_pos, clears obj_control, and plays the release sound; it does not set
        // status.player.rolling (docs/s2disasm/s2.asm:48683-48688). The engine
        // keeps a short release collision-immunity latch for tube geometry, so
        // preserve the ROM Roll anim byte separately for later same-frame S2
        // object gates such as Obj26 monitors. A CPZ1 BizHawk probe at trace
        // frames 3868-3874 captured anim=02, status=03, obj_control=00 after
        // this release.
        cs.releaseRollAnimationHoldFrames = RELEASE_ROLL_ANIMATION_HOLD_FRAMES;

        // Restore normal render priority
        player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);

        // Play spindash release sound
        playSound(GameSound.SPINDASH_RELEASE);

        // Move to exit check mode
        cs.state = 6;

        LOGGER.fine("Player exited spin tube");
    }

    private boolean shouldSkipMoveAfterPriorOwnerExit(AbstractPlayableSprite player, CharacterState cs) {
        if (!cs.skipMoveAfterOwnerExit) {
            return false;
        }
        if (player.isObjectControlled()) {
            return false;
        }
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        player.setAir(true);
        player.setHighPriority(false);
        player.setPriorityBucket(RenderPriority.MIN);
        cs.skipMoveAfterOwnerExit = false;
        return true;
    }

    /**
     * Complete a main-path segment without clearing object control.
     *
     * <p>ROM Obj1E has two distinct endings. {@code loc_227A6} masks y_pos,
     * sets mode 6, and clears {@code obj_control(a1)} (docs/s2disasm/s2.asm:
     * 48683-48688). The main-path ending at {@code loc_22858} only masks y_pos,
     * clears this tube's mode byte, and plays the release sound; it deliberately
     * leaves {@code obj_control(a1)} set for the neighbouring Obj1E handoff
     * (docs/s2disasm/s2.asm:48748-48752).
     */
    private void completeMainPathHandoff(AbstractPlayableSprite player, CharacterState cs) {
        if (cs.expectedSegmentCount > 0 && cs.completedSegmentCount < cs.expectedSegmentCount) {
            LOGGER.warning("EARLY MAIN-PATH HANDOFF WARNING: Completed " + cs.completedSegmentCount + "/" + cs.expectedSegmentCount +
                    " segments! Expected exit direction was " + cs.expectedExitDirection +
                    ", subtype=0x" + Integer.toHexString(spawn.subtype()) +
                    ", pos=(" + player.getCentreX() + "," + player.getCentreY() + ")" +
                    ", xSpeed=" + player.getXSpeed() + ", ySpeed=" + player.getYSpeed());
        }

        int y = player.getCentreY() & 0x7FF;
        NativePositionOps.writeYPosPreserveSubpixel(player, y);
        cs.state = 0;
        playSound(GameSound.SPINDASH_RELEASE);
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
        markActiveOwner(player);
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
    private void calculateVelocity(AbstractPlayableSprite player, CharacterState cs, int targetX, int targetY, int speed) {
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

        // No minimum-frame clamp: the ROM permits a zero-length segment.
        // loc_22902 stores the quotient with move.w d1,2(a4)
        // (docs/s2disasm/s2.asm:48846-48848 and :48864-48866) but loc_2271A and
        // loc_227FE read it back as a byte via subq.b #1,2(a4)
        // (docs/s2disasm/s2.asm:48574 and :48800), so the counter is the HIGH byte
        // of |dominant| * $10000 / $800 == |dominant| * 32, i.e. |dominant| >> 3.
        // Waypoints closer together than 8px on the dominant axis therefore yield a
        // counter of 0: subq.b drives it straight negative, and the very next frame
        // snaps to the waypoint and recomputes with no intervening
        // Obj1E_MoveCharacter frame at all. Clamping to 1 inserted a spurious
        // movement frame and left every later waypoint one frame late for the rest
        // of the ride.

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
        cs.duration = frames;
    }

    /**
     * Calculates the expected route for debugging purposes.
     * This predicts what main path we'll use and what direction we'll exit.
     */
    private void calculateExpectedRoute(CharacterState cs, int entryFrame) {
        // Reset tracking
        cs.completedSegmentCount = 0;

        // Check if entry frame indicates we'll skip main path
        if (entryFrame >= 4) {
            cs.expectedMainPathId = 0;
            cs.expectedReverse = false;
            cs.expectedSegmentCount = 0;
            cs.expectedExitDirection = "NONE (entry frame >= 4, early exit)";
            LOGGER.fine("EXPECTED ROUTE: No main path (entryFrame=" + entryFrame + " >= 4)");
            return;
        }

        // Calculate main path index from subtype and entry frame
        int routingIndex = (spawn.subtype() & 0xFC) + entryFrame;
        if (routingIndex >= MAIN_PATH_ROUTING.length) {
            cs.expectedMainPathId = 0;
            cs.expectedReverse = false;
            cs.expectedSegmentCount = 0;
            cs.expectedExitDirection = "NONE (routingIndex out of bounds)";
            LOGGER.fine("EXPECTED ROUTE: No main path (routingIndex=" + routingIndex + " out of bounds)");
            return;
        }

        int pathId = MAIN_PATH_ROUTING[routingIndex];
        if (pathId == 0) {
            cs.expectedMainPathId = 0;
            cs.expectedReverse = false;
            cs.expectedSegmentCount = 0;
            cs.expectedExitDirection = "NONE (pathId=0, no main path for this route)";
            LOGGER.fine("EXPECTED ROUTE: No main path for routingIndex=" + routingIndex +
                    " (subtype=0x" + Integer.toHexString(spawn.subtype()) + ", entryFrame=" + entryFrame + ")");
            return;
        }

        // Determine path direction and index
        cs.expectedReverse = pathId < 0;
        int actualPathIndex = (cs.expectedReverse ? -pathId : pathId) - 1;
        cs.expectedMainPathId = pathId;

        if (actualPathIndex < 0 || actualPathIndex >= MAIN_PATHS.length) {
            cs.expectedSegmentCount = 0;
            cs.expectedExitDirection = "NONE (invalid path index)";
            LOGGER.warning("EXPECTED ROUTE: Invalid path index " + actualPathIndex);
            return;
        }

        // Calculate expected segment count and exit direction
        int[] path = MAIN_PATHS[actualPathIndex];
        int waypointCount = path.length / 2;
        cs.expectedSegmentCount = waypointCount - 1;  // segments = waypoints - 1

        // Determine exit direction from final segment
        int exitStartX, exitStartY, exitEndX, exitEndY;
        if (cs.expectedReverse) {
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
            cs.expectedExitDirection = exitDx > 0 ? "RIGHT" : "LEFT";
        } else {
            cs.expectedExitDirection = exitDy > 0 ? "DOWN" : "UP";
        }

        LOGGER.fine("EXPECTED ROUTE: Main path " + (actualPathIndex + 1) +
                " (" + (cs.expectedReverse ? "REVERSE" : "FORWARD") + ")" +
                ", segments=" + cs.expectedSegmentCount +
                ", exit direction=" + cs.expectedExitDirection +
                ", exit pos=(" + exitEndX + "," + exitEndY + ")");
    }

    private void playSound(GameSound sound) {
        try {
            services().playSfx(sound);
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
        // Keep this object active while it's controlling ANY character
        // (state == 2 entry path or state == 4 main path). The ROM tracks a
        // separate state slot per character (objoff_2C / objoff_36), so the
        // object must stay live while either Sonic or a sidekick is mid-tube.
        for (CharacterState cs : characterStates.values()) {
            if (cs.state == 2 || cs.state == 4) {
                return true;
            }
        }
        return false;
    }
}

package com.openggf.game.sonic2.scroll;

import com.openggf.camera.Camera;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.CameraDrivenScrollHandler;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

/**
 * ROM-accurate implementation of SwScrl_SCZ (Sky Chase Zone scroll routine).
 * Reference: s2.asm SwScrl_SCZ at lines 17815-17876
 *
 * SCZ is unique because the camera is driven by the Tornado's velocity
 * rather than following the player. The scroll handler directly modifies
 * Camera_X_pos and Camera_Y_pos each frame.
 *
 * BG scroll behavior:
 * - BG X advances at 0.5px/frame (via 16.16 fixed point accumulator)
 *   whenever FG has any horizontal movement
 * - BG Y stays at 0 forever
 * - All 224 scanlines use the same FG/BG pair (no parallax bands)
 *
 * Level events (LevEvents_SCZ) control Tornado_Velocity_X and
 * Tornado_Velocity_Y based on camera position thresholds:
 *   Phase 0 (init): velX=1, velY=0
 *   Phase 1 (fly right): when cameraX >= $1180 -> velX=-1, velY=1
 *   Phase 2 (descend): when cameraY >= $500 -> velX=1, velY=0
 *   Phase 3 (resume right): when cameraX >= $1400 -> velX=0, velY=0
 */
public class SwScrlScz extends AbstractZoneScrollHandler implements CameraDrivenScrollHandler {

    // Tornado velocity (pixels per frame), controlled by level events
    private int tornadoVelocityX = 0;
    private int tornadoVelocityY = 0;

    // Camera_BG_X_pos as 32-bit accumulator (16.16 fixed point)
    // Word (high 16 bits) is the actual BG X position
    private int bgXPos32 = 0;

    // Level events routine index (ROM: Dynamic_Resize_Routine)
    private int routineIndex = 0;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public SwScrlScz() {
    }

    /**
     * Initialize SCZ state for a new level load.
     */
    public void init() {
        tornadoVelocityX = 0;
        tornadoVelocityY = 0;
        bgXPos32 = 0;
        routineIndex = 0;
    }

    @Override
    public boolean advanceCameraForFrame(Camera camera, int actId) {
        // ROM: LevEvents_SCZ is reached from SwScrl_SCZ before the camera words
        // are advanced by Tornado_Velocity_X/Y. Keep this in gameplay logic so
        // headless trace replay and rendering observe the same camera state.
        if (actId == 0) {
            updateLevelEvents(camera);
        }

        camera.setX((short) (camera.getX() + tornadoVelocityX));
        camera.setY((short) (camera.getY() + tornadoVelocityY));

        if (tornadoVelocityX != 0) {
            bgXPos32 += 0x8000;
        }
        return true;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        int camX = cameraX & 0xFFFF;

        // BG Y is always 0 (d5 = 0 in SetHorizVertiScrollFlagsBG)

        // ==================== Fill Scroll Buffer ====================
        // ROM: All 224 lines get the same packed (FG, BG) value
        // FG = neg.w Camera_X_pos
        // BG = neg.w Camera_BG_X_pos (word part of 32-bit accumulator)
        short fgScroll = M68KMath.negWord(camX);
        short bgXWord = (short) (bgXPos32 >> 16);
        short bgScroll = M68KMath.negWord(bgXWord);

        composer.fillPackedScrollWords(0, M68KMath.VISIBLE_LINES, fgScroll, bgScroll);
        composer.copyPackedScrollWordsTo(horizScrollBuf);

        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    /**
     * Update SCZ level events (ROM: LevEvents_SCZ Act 1).
     * Controls Tornado velocity based on camera position thresholds.
     *
     * Reference: s2.asm lines 21793-21847
     *
     * Routine 0 (init): Set velX=1, velY=0, advance immediately
     * Routine 1 (fly right): When cameraX >= $1180, set velX=-1, velY=1
     * Routine 2 (descend): When cameraY >= $500, set velX=1, velY=0
     * Routine 3 (resume right): When cameraX >= $1400, set velX=0, velY=0
     * Routine 4 (null): Do nothing
     */
    private void updateLevelEvents(Camera camera) {
        switch (routineIndex) {
            case 0 -> {
                // LevEvents_SCZ_Routine1: Initialize velocity
                tornadoVelocityX = 1;
                tornadoVelocityY = 0;
                routineIndex = 1;
            }
            case 1 -> {
                // LevEvents_SCZ_Routine2: Fly right until $1180
                if ((camera.getX() & 0xFFFF) >= 0x1180) {
                    tornadoVelocityX = -1;
                    tornadoVelocityY = 1;
                    // ROM also sets Camera_Max_Y_pos_target = $500
                    camera.setMaxYTarget((short) 0x500);
                    routineIndex = 2;
                }
            }
            case 2 -> {
                // LevEvents_SCZ_Routine3: Descend until cameraY >= $500
                if ((camera.getY() & 0xFFFF) >= 0x500) {
                    tornadoVelocityX = 1;
                    tornadoVelocityY = 0;
                    routineIndex = 3;
                }
            }
            case 3 -> {
                // LevEvents_SCZ_Routine4: Resume right until $1400
                if ((camera.getX() & 0xFFFF) >= 0x1400) {
                    tornadoVelocityX = 0;
                    tornadoVelocityY = 0;
                    routineIndex = 4;
                }
            }
            default -> {
                // LevEvents_SCZ_RoutineNull: Do nothing
            }
        }
    }

    /**
     * Get the current tornado X velocity (for external queries).
     */
    public int getTornadoVelocityX() {
        return tornadoVelocityX;
    }

    /**
     * Get the current tornado Y velocity (for external queries).
     */
    public int getTornadoVelocityY() {
        return tornadoVelocityY;
    }

    /**
     * SCZ scroll state advances in {@link #advanceCameraForFrame} (the logical
     * frame step), not render-time {@code update()}, and is not a pure function
     * of the frame counter — {@link #bgXPos32} integrates the tornado velocity,
     * and {@link #routineIndex} is a monotonic level-event routine. It must be
     * snapshotted for rewind so a restore reproduces it exactly rather than
     * re-simulating it forward from stale values (which left the Sky Chase
     * background drifting after a rewind).
     */
    private record SczScrollState(int tornadoVelocityX,
                                  int tornadoVelocityY,
                                  int bgXPos32,
                                  int routineIndex) {
    }

    @Override
    public Object captureRewindState() {
        return new SczScrollState(tornadoVelocityX, tornadoVelocityY, bgXPos32, routineIndex);
    }

    @Override
    public void restoreRewindState(Object state) {
        if (state instanceof SczScrollState s) {
            tornadoVelocityX = s.tornadoVelocityX();
            tornadoVelocityY = s.tornadoVelocityY();
            bgXPos32 = s.bgXPos32();
            routineIndex = s.routineIndex();
        }
    }
}

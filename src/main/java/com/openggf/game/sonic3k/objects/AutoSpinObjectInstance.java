package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.util.List;
import java.util.logging.Logger;

/**
 * AutoSpin trigger object (Object 0x26).
 * <p>
 * Invisible trigger zone that forces the player into rolling/spinning state when
 * they cross it. Used for tunnels, S-tubes, and vertical chutes across all zones.
 * <p>
 * Based on Obj_AutoSpin from the S3K disassembly (sonic3k.asm lines 42295-42595).
 *
 * <h3>Subtype Encoding:</h3>
 * <ul>
 *   <li>Bits 0-1: Size index into table: 0=0x20, 1=0x40, 2=0x80, 3=0x100</li>
 *   <li>Bit 2: Direction - 0=horizontal (trigger on X), 1=vertical (trigger on Y)</li>
 *   <li>Bit 4: No spin lock - if set, skip ground_vel/spin_dash_flag writes (cancel-only)</li>
 *   <li>Bit 5: Ground only - player must be on ground to trigger</li>
 *   <li>Bit 6: Snap to wall (vertical only) - clear air, set angle=0x40, transfer y_vel to ground_vel</li>
 *   <li>Bit 7: Lock controls - sets pinball mode + control lock instead of just pinball mode</li>
 * </ul>
 *
 * <h3>X-flip Behavior (render_flags bit 0):</h3>
 * <ul>
 *   <li>Not flipped: crossing L→R / top→bottom enables spin (+0x580 ground_vel)</li>
 *   <li>Flipped: crossing R→L / bottom→top enables spin (-0x580 ground_vel)</li>
 * </ul>
 */
public class AutoSpinObjectInstance extends BoxObjectInstance implements RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(AutoSpinObjectInstance.class.getName());

    // Size lookup table from disassembly word_1E854 (sonic3k.asm line 42327)
    private static final int[] SIZE_TABLE = {0x20, 0x40, 0x80, 0x100};

    // From disassembly: move.w #$580,ground_vel(a1)
    private static final short SPIN_GROUND_SPEED = 0x0580;

    // From disassembly: move.b #$E,y_radius(a1) / move.b #7,x_radius(a1)
    private static final int ROLL_Y_RADIUS = 0x0E;
    private static final int ROLL_X_RADIUS = 0x07;

    // Angle for wall snap (vertical chutes): 0x40 = right wall
    private static final byte WALL_ANGLE = 0x40;

    // Debug colors
    private static final float ENABLE_R = 0.0f, ENABLE_G = 1.0f, ENABLE_B = 0.0f;
    private static final float DISABLE_R = 1.0f, DISABLE_G = 0.0f, DISABLE_B = 0.0f;

    private boolean verticalMode;     // subtype bit 2
    private boolean noSpinLock;       // subtype bit 4
    private boolean groundOnly;       // subtype bit 5
    private boolean snapToWall;       // subtype bit 6 (vertical only)
    private boolean lockControls;     // subtype bit 7
    private boolean xFlipped;         // render_flags bit 0

    // Per-character crossing state (matches objoff_34/objoff_35 in disassembly)
    private boolean sonicPastTrigger;
    private boolean sidekickPastTrigger;

    private boolean initialized;
    private String lastTraceEvent = "init";

    public AutoSpinObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AutoSpin",
                SIZE_TABLE[spawn.subtype() & 0x03],
                SIZE_TABLE[spawn.subtype() & 0x03],
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_R : DISABLE_R,
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_G : DISABLE_G,
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_B : DISABLE_B,
                false);

        int subtype = spawn.subtype();
        this.verticalMode = (subtype & 0x04) != 0;
        this.noSpinLock = (subtype & 0x10) != 0;
        this.groundOnly = (subtype & 0x20) != 0;
        this.snapToWall = (subtype & 0x40) != 0;
        this.lockControls = (subtype & 0x80) != 0;
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public AutoSpinObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AutoSpinObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        if (!initialized) {
            initializeCrossingState(player);
            initialized = true;
        }

        checkPlayerCrossing(player, true);

        AbstractPlayableSprite nativeP2 = nativeP2OrNull(player);
        if (nativeP2 != null && !skipsNativeP2(nativeP2)) {
            checkPlayerCrossing(nativeP2, false);
        }
    }

    /**
     * Whether Player 2 is not checked for a crossing this frame.
     *
     * <p>Both main routines load Player 2 and then read
     * {@code Tails_CPU_routine} before the check, branching past it when the
     * word is 4 -- horizontal at docs/skdisasm/sonic3k.asm:42362-42364,
     * vertical at :42489-42491. Routine 4 is the {@code Tails_FlySwim_Unknown}
     * entry of {@code Tails_CPU_Control_Index} (:26368-26371): a carry or
     * flight owner is driving Tails, and the ROM leaves him to it rather than
     * rolling him out from under it.
     *
     * <p>The skipped branch is only the P2 check; both routines fall into the
     * same {@code Delete_Sprite_If_Not_In_Range}, so the object's own lifetime
     * is unaffected. The crossing state initialised at :42340-42348 is likewise
     * unconditional and is left alone.
     *
     * <p>S2's {@code Obj48} reads the same word for the same purpose
     * (docs/s2disasm/s2.asm:51316-51319), which
     * {@code LauncherBallObjectInstance} already honours -- this is the ROM's
     * general "a flight owner has Tails" gate, not an AutoSpin special case.
     */
    private boolean skipsNativeP2(AbstractPlayableSprite nativeP2) {
        if (!nativeP2.isCpuControlled()) {
            return false;
        }
        SidekickCpuController controller = nativeP2.getCpuController();
        return controller != null && controller.isInRomFlySwimCpuRoutine();
    }

    /**
     * Sets initial crossing state based on player position relative to trigger.
     * From disassembly init routine (sonic3k.asm lines 42329-42348).
     */
    private void initializeCrossingState(AbstractPlayableSprite player) {
        int objX = spawn.x();
        int objY = spawn.y();

        if (verticalMode) {
            sonicPastTrigger = player.getCentreY() > objY;
        } else {
            sonicPastTrigger = player.getCentreX() > objX;
        }

        AbstractPlayableSprite sidekick = nativeP2OrNull(player);
        if (sidekick != null) {
            if (verticalMode) {
                sidekickPastTrigger = sidekick.getCentreY() > objY;
            } else {
                sidekickPastTrigger = sidekick.getCentreX() > objX;
            }
        }
    }

    private AbstractPlayableSprite nativeP2OrNull(AbstractPlayableSprite nativeP1) {
        for (PlayableEntity candidate : services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate != nativeP1 && candidate instanceof AbstractPlayableSprite sprite) {
                return sprite;
            }
        }
        return null;
    }

    /**
     * Per-frame crossing check for one player.
     * Horizontal: sub_1E8C6 (sonic3k.asm lines 42372-42473)
     * Vertical: sub_1EA14 (sonic3k.asm lines 42499-42594)
     */
    private void checkPlayerCrossing(AbstractPlayableSprite player, boolean isSonic) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        boolean pastTrigger = isSonic ? sonicPastTrigger : sidekickPastTrigger;

        if (verticalMode) {
            checkVerticalCrossing(player, isSonic, objX, objY, playerX, playerY, pastTrigger);
        } else {
            checkHorizontalCrossing(player, isSonic, objX, objY, playerX, playerY, pastTrigger);
        }
    }

    /**
     * Horizontal trigger logic (sub_1E8C6).
     * Trigger line is vertical at objX; range check on Y axis.
     */
    private void checkHorizontalCrossing(AbstractPlayableSprite player, boolean isSonic,
                                          int objX, int objY, int playerX, int playerY,
                                          boolean pastTrigger) {
        if (!pastTrigger) {
            // Player was to the left - check if crossed rightward
            if (playerX >= objX) {
                setCrossingState(isSonic, true);
                if (!isWithinRange(playerY, objY)) return;
                if (groundOnly && player.getAir()) return;

                if (!xFlipped) {
                    // Crossing L→R, not flipped: enable spin with positive speed
                    enableSpin(player, SPIN_GROUND_SPEED);
                } else {
                    // Crossing L→R, flipped: disable spin
                    disableSpin(player);
                }
            }
        } else {
            // Player was to the right - check if crossed leftward
            if (playerX < objX) {
                setCrossingState(isSonic, false);
                if (!isWithinRange(playerY, objY)) return;
                if (groundOnly && player.getAir()) return;

                if (xFlipped) {
                    // Crossing R→L, flipped: enable spin with negative speed
                    enableSpin(player, (short) -SPIN_GROUND_SPEED);
                } else {
                    // Crossing R→L, not flipped: disable spin
                    disableSpin(player);
                }
            }
        }
    }

    /**
     * Vertical trigger logic (sub_1EA14).
     * Trigger line is horizontal at objY; range check on X axis.
     */
    private void checkVerticalCrossing(AbstractPlayableSprite player, boolean isSonic,
                                        int objX, int objY, int playerX, int playerY,
                                        boolean pastTrigger) {
        if (!pastTrigger) {
            // Player was above - check if crossed downward
            if (playerY >= objY) {
                setCrossingState(isSonic, true);
                if (!isWithinRange(playerX, objX)) return;
                if (groundOnly && player.getAir()) return;

                if (!xFlipped) {
                    // Crossing top→bottom, not flipped: enable spin
                    enableSpinVertical(player, true);
                } else {
                    // Crossing top→bottom, flipped: disable spin
                    disableSpin(player);
                }
            }
        } else {
            // Player was below - check if crossed upward
            if (playerY < objY) {
                setCrossingState(isSonic, false);
                if (!isWithinRange(playerX, objX)) return;
                if (groundOnly && player.getAir()) return;

                if (xFlipped) {
                    // Crossing bottom→top, flipped: enable spin
                    enableSpinVertical(player, false);
                } else {
                    // Crossing bottom→top, not flipped: disable spin
                    disableSpin(player);
                }
            }
        }
    }

    private void setCrossingState(boolean isSonic, boolean crossed) {
        if (isSonic) {
            sonicPastTrigger = crossed;
        } else {
            sidekickPastTrigger = crossed;
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format("sub=%02X flip=%s vert=%s no=%s ground=%s lock=%s past=%s/%s last=%s",
                spawn.subtype() & 0xFF,
                xFlipped,
                verticalMode,
                noSpinLock,
                groundOnly,
                lockControls,
                sonicPastTrigger,
                sidekickPastTrigger,
                lastTraceEvent);
    }

    private boolean isWithinRange(int pos, int center) {
        int delta = pos - center;
        return delta >= -getHalfWidth() && delta < getHalfWidth();
    }

    /**
     * Enables spin for horizontal triggers.
     * Sets ground_vel and spin_dash_flag, then forces roll state.
     * When noSpinLock is set, skips speed/flag writes but still forces roll.
     * From sonic3k.asm lines 42394-42472.
     *
     * <p>ROM: spin_dash_flag = 0x01 (pinball mode) or 0x81 (pinball + speed lock).
     * The 0x81 value causes Sonic_RollSpeed to skip its entire body (input,
     * friction, deceleration), preserving ground_vel. This is NOT a control lock
     * — slope gravity still modifies speed normally.
     */
    private void enableSpin(AbstractPlayableSprite player, short groundVel) {
        lastTraceEvent = String.format("enableH:%04X:%s", groundVel & 0xFFFF,
                player.isCpuControlled() ? "tails" : "sonic");
        if (!noSpinLock) {
            player.setGSpeed(groundVel);
            player.setPinballMode(true);
            if (lockControls) {
                player.setPinballSpeedLock(true);
            }
        }
        forceRoll(player);
    }

    /**
     * Enables spin for vertical triggers with optional snap-to-wall.
     * Vertical handler does NOT set ground_vel to 0x580 (unlike horizontal).
     * From sonic3k.asm lines 42520-42594.
     */
    private void enableSpinVertical(AbstractPlayableSprite player, boolean crossingDownward) {
        lastTraceEvent = String.format("enableV:%s:%s", crossingDownward ? "down" : "up",
                player.isCpuControlled() ? "tails" : "sonic");
        if (!noSpinLock) {
            player.setPinballMode(true);
            if (lockControls) {
                player.setPinballSpeedLock(true);
            }
        }

        // Snap to wall (subtype bit 6)
        if (snapToWall) {
            player.setAir(false);
            player.setAngle(WALL_ANGLE);
            if (crossingDownward) {
                // Top-to-bottom: full snap - transfer y_vel to ground_vel, clear x_vel
                // From sonic3k.asm lines 42530-42536
                player.setGSpeed(player.getYSpeed());
                player.setXSpeed((short) 0);
            }
            // Bottom-to-top: only clear air + set angle (lines 42579-42582)
        }

        forceRoll(player);
    }

    /**
     * Disables spin. Unconditionally clears spin_dash_flag to 0.
     * From sonic3k.asm: move.b #0,spin_dash_flag(a1)
     */
    private void disableSpin(AbstractPlayableSprite player) {
        if (noSpinLock) return;

        lastTraceEvent = "disable:" + (player.isCpuControlled() ? "tails" : "sonic");
        player.setPinballMode(false);
        player.setPinballSpeedLock(false);
    }

    /**
     * Forces player into rolling state if not already rolling.
     * From loc_1E9B6/loc_1E9C0 (sonic3k.asm lines 42458-42472):
     * - Check Status_Roll; if set, return
     * - Set Status_Roll, y_radius=0x0E, x_radius=0x07, anim=2
     * - Add 5 to y_pos
     * - Play sfx_Roll
     */
    private void forceRoll(AbstractPlayableSprite player) {
        if (player.getRolling()) {
            return;
        }

        short preRollCentreX = player.getCentreX();
        short preRollCentreY = player.getCentreY();
        player.setRolling(true);
        // ROM Obj_AutoSpin writes y_radius/x_radius and y_pos only; x_pos is
        // unchanged. Preserve centre X when the engine's top-left sprite box
        // changes width on wall modes.
        player.setCentreXPreserveSubpixel(preRollCentreX);
        // ROM Obj_AutoSpin hardcodes addq.w #5,y_pos after setting roll radii
        // (sonic3k.asm:42464-42469), independent of character height.
        player.setCentreYPreserveSubpixel((short) (preRollCentreY + 5));

        SpriteAnimationProfile profile = player.getAnimationProfile();
        if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
            player.setAnimationId(velocityProfile.getRollAnimId());
            player.setAnimationFrameIndex(0);
            player.setAnimationTick(0);
        }

        try {
            services().playSfx(GameSound.ROLLING);
        } catch (Exception e) {
            LOG.fine(() -> "AutoSpinObjectInstance.forceRoll: " + e.getMessage());
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj_AutoSpin is an invisible trigger.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int centerX = spawn.x();
        int centerY = spawn.y();
        float r = xFlipped ? DISABLE_R : ENABLE_R;
        float g = xFlipped ? DISABLE_G : ENABLE_G;
        float b = xFlipped ? DISABLE_B : ENABLE_B;

        ctx.drawRect(centerX, centerY, getHalfWidth(), getHalfHeight(), r, g, b);
        ctx.drawCross(centerX, centerY,
                Math.min(getHalfWidth(), getHalfHeight()) / 2, r, g, b);

        if (verticalMode) {
            ctx.drawLine(centerX - getHalfWidth(), centerY,
                    centerX + getHalfWidth(), centerY, r, g, b);
        } else {
            ctx.drawLine(centerX, centerY - getHalfHeight(),
                    centerX, centerY + getHalfHeight(), r, g, b);
        }
    }
}

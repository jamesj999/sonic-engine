package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;

import java.util.List;

/**
 * MHZ2 ship sequence controller.
 *
 * <p>ROM: {@code loc_5583E}, allocated by {@code MHZ2_ScreenEvent}
 * {@code loc_54E9C}. The object owns the ROM {@code Gradual_SwingOffset} state
 * and publishes the resulting ship H-int workspace values through the MHZ
 * event/runtime bridge.
 */
public class MhzShipSequenceControllerInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int OBJECT_ID = 0;
    private static final int MOTION_ACCELERATION = 0x000000C0;
    private static final int SWING_INITIAL_SPEED = 0x00002800;
    private static final int SWING_ACCELERATION = 0x000000C0;

    private int initialSwingSpeed;
    private int initialShipMotion;
    private int frameCounterByte;
    private int motionAccumulator;
    private int swingVelocity;
    private int swingOffset;
    private boolean swingReturning;

    public MhzShipSequenceControllerInstance(int initialSwingSpeed, int initialShipMotion) {
        super(new ObjectSpawn(0, 0, OBJECT_ID, 0, 0, false, 0), "MHZShipSequenceController");
        this.initialSwingSpeed = initialSwingSpeed & 0xFFFF;
        this.initialShipMotion = initialShipMotion & 0xFFFF;
        this.motionAccumulator = this.initialShipMotion;
        this.swingVelocity = this.initialSwingSpeed;
    }

    private MhzShipSequenceControllerInstance() {
        this(0x04C0, 0x4000);
    }

    public int getBombPortX() {
        return initialSwingSpeed;
    }

    public int getInitialSwingSpeed() {
        return initialSwingSpeed;
    }

    public int getInitialShipMotion() {
        return initialShipMotion;
    }

    public int getMotionAccumulator() {
        return motionAccumulator;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MhzShipSequenceControllerInstance(0x04C0, 0x4000);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        frameCounterByte = (frameCounterByte + 1) & 0xFF;
        motionAccumulator += MOTION_ACCELERATION;
        S3kRuntimeStates.currentMhz(services().zoneRuntimeRegistry()).ifPresent(state -> {
            int swingOffsetPixels = updateSwingOffset(state.isShipControllerSignalFlagSet());
            state.applyShipControllerFrame(motionAccumulator, swingOffsetPixels);
        });
        if (((vIntRunCount - 1) & 0x0F) == 0) {
            services().playSfx(Sonic3kSfx.LARGE_SHIP.id);
        }
    }

    private int updateSwingOffset(boolean shipControllerSignalSet) {
        if (shipControllerSignalSet && swingVelocity == SWING_INITIAL_SPEED) {
            int currentOffset = (short) (swingOffset >> 16);
            if ((frameCounterByte & 3) == 0) {
                swingOffset += 0x00010000;
            }
            return currentOffset;
        }

        int currentSpeed = swingVelocity;
        if (swingReturning) {
            swingOffset += currentSpeed;
            if (swingOffset < 0) {
                swingVelocity += SWING_ACCELERATION;
            } else {
                swingVelocity = SWING_INITIAL_SPEED;
                swingOffset = 0;
                swingReturning = false;
            }
        } else {
            swingOffset += currentSpeed;
            if (swingOffset <= 0) {
                swingVelocity = -SWING_INITIAL_SPEED;
                swingOffset = 0;
                swingReturning = true;
            } else {
                swingVelocity -= SWING_ACCELERATION;
            }
        }
        return (short) (swingOffset >> 16);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The ship body is an H-int/special render sequence, not a normal sprite.
    }
}

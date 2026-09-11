package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

final class RewindSpeedController {
    private final boolean coastEnabled;
    private final double minStepsPerTick;
    private final double accelerationPerTick;
    private final double decelerationPerTick;
    private final double maxStepsPerTick;
    private double speed;
    private double stepAccumulator;
    private double heldMultiplier = 1.0;

    static RewindSpeedController disabled() {
        return new RewindSpeedController(false, 1.0, 0.0, 0.0, 1.0);
    }

    static RewindSpeedController fromConfig(SonicConfigurationService config) {
        if (!config.getBoolean(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED)) {
            return disabled();
        }
        return new RewindSpeedController(
                true,
                config.getDouble(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MIN_STEPS),
                config.getDouble(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ACCELERATION),
                config.getDouble(SonicConfiguration.LIVE_REWIND_TAPE_COAST_DECELERATION),
                config.getDouble(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MAX_STEPS));
    }

    RewindSpeedController(
            boolean coastEnabled,
            double minStepsPerTick,
            double accelerationPerTick,
            double decelerationPerTick,
            double maxStepsPerTick) {
        this.coastEnabled = coastEnabled;
        this.minStepsPerTick = Math.max(0.0, minStepsPerTick);
        this.accelerationPerTick = Math.max(0.0, accelerationPerTick);
        this.decelerationPerTick = Math.max(0.0, decelerationPerTick);
        this.maxStepsPerTick = Math.max(this.minStepsPerTick, maxStepsPerTick);
    }

    /**
     * Held-modifier speed multiplier (e.g. half/double rewind keys). Multiplies
     * the effective step rate without compounding into the coast acceleration
     * curve. Non-positive values are treated as the unit multiplier.
     */
    void setHeldSpeedMultiplier(double multiplier) {
        this.heldMultiplier = multiplier > 0.0 ? multiplier : 1.0;
    }

    int stepsWhileHeld() {
        if (!coastEnabled) {
            speed = 1.0;
            return consumeAccumulator();
        }
        if (speed <= 0.0) {
            speed = minStepsPerTick;
        } else {
            speed = Math.min(maxStepsPerTick, speed + accelerationPerTick);
        }
        return consumeAccumulator();
    }

    int stepsAfterRelease() {
        if (!coastEnabled || decelerationPerTick <= 0.0 || speed <= 0.0) {
            reset();
            return 0;
        }
        speed = Math.max(0.0, speed - decelerationPerTick);
        if (speed <= 0.0) {
            reset();
            return 0;
        }
        return consumeAccumulator();
    }

    double currentSpeed() {
        if (!coastEnabled) {
            return speed > 0.0 ? heldMultiplier : 0.0;
        }
        return speed * heldMultiplier;
    }

    void reset() {
        speed = 0.0;
        stepAccumulator = 0.0;
        heldMultiplier = 1.0;
    }

    private int consumeAccumulator() {
        stepAccumulator += speed * heldMultiplier;
        int whole = (int) Math.floor(stepAccumulator);
        stepAccumulator -= whole;
        return whole;
    }
}

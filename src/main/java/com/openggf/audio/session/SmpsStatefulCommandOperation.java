package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsChannelOwnershipProjection;

import java.util.Objects;

/** Immutable host operation prepared from one locked ownership projection. */
public record SmpsStatefulCommandOperation(
        Input input,
        Handling handling,
        SmpsWriteProgram writes) {
    public SmpsStatefulCommandOperation {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(handling, "handling");
        Objects.requireNonNull(writes, "writes");
        if (handling != Handling.STOP_SMPS_SFX && !writes.writes().isEmpty()) {
            throw new IllegalArgumentException(
                    "only a handled stop-SMPS-SFX operation may write");
        }
    }

    public static SmpsStatefulCommandOperation none(Input input) {
        return new SmpsStatefulCommandOperation(input, Handling.NONE,
                SmpsWriteProgram.EMPTY);
    }

    public static SmpsStatefulCommandOperation reject(Input input) {
        return new SmpsStatefulCommandOperation(input, Handling.REJECTED,
                SmpsWriteProgram.EMPTY);
    }

    public static SmpsStatefulCommandOperation stopSmpsSfx(
            Input input, SmpsWriteProgram writes) {
        if (!(Objects.requireNonNull(input, "input").command()
                instanceof SmpsSessionCommand.StopSmpsSfx)) {
            throw new IllegalArgumentException(
                    "stateful stop operation requires StopSmpsSfx command");
        }
        return new SmpsStatefulCommandOperation(input,
                Handling.STOP_SMPS_SFX, writes);
    }

    public boolean handled() {
        return handling != Handling.NONE;
    }

    public boolean rejected() {
        return handling == Handling.REJECTED;
    }

    public enum Handling {
        NONE,
        REJECTED,
        STOP_SMPS_SFX
    }

    public record Input(
            SmpsSessionCommand command,
            SmpsChannelOwnershipProjection ownership) {
        public Input {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(ownership, "ownership");
        }
    }
}

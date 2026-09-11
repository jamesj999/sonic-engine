package com.openggf.audio.synth;

/**
 * Diagnostic sink for resolved writes entering the emulated sound chips.
 * Implementations observe immutable byte values and cannot alter a write.
 */
public interface ChipWriteObserver {
    /** Native clock used by a physical-write callback; domains are never comparable by value alone. */
    enum ChipClockDomain {
        YM2612_INTERNAL_CYCLE,
        PSG_GENERATOR_TICK
    }

    /** Why a raw YM bus strobe exists in the engine model. */
    enum PhysicalWriteOrigin {
        /** A sequencer, session policy, or other external engine bus write. */
        EXTERNAL_BUS,
        /** A decoded byte the ROM DAC playback loop presents to YM register 2Ah. */
        DAC_STREAM,
        /** Presentation-only interpolated DAC value, never hardware-reference evidence. */
        DAC_INTERPOLATION,
        /**
         * A pending DAC data strobe resumed from a snapshot. Its original
         * engine source is deliberately not retained in production state.
         */
        RESTORED_UNKNOWN
    }

    /** A state change which cannot be reconstructed from raw chip bus writes alone. */
    enum PhysicalTimelineBoundary {
        RESET,
        SNAPSHOT_RESTORE,
        MODEL_MUTATION,
        /**
         * Only the session's final PCM silence gate changed. Raw chip state and
         * clocks are unchanged; raw-pin replay may cross this boundary, but a
         * presentation-PCM consumer cannot reconstruct the gate from bus writes.
         */
        OUTPUT_GATE_CHANGE,
        /**
         * A live session discarded unpublished physical writes after restoring
         * chip state. Consumers must start a new raw-replay segment.
         */
        TRANSACTION_ROLLBACK
    }

    ChipWriteObserver NONE = new ChipWriteObserver() {
        @Override
        public void onYm2612Write(int port, int register, int value) {
        }

        @Override
        public void onPsgWrite(int value) {
        }
    };

    void onYm2612Write(int port, int register, int value);

    void onPsgWrite(int value);

    /**
     * Whether this observer needs the opt-in physical bus trace. The default
     * keeps ordinary logical observers allocation-free during dense DAC playback.
     */
    default boolean observesPhysicalWrites() {
        return false;
    }

    /**
     * One raw YM bus strobe after {@code core.write}, before the next core
     * clock. {@code busPort} is the native Nuked port 0..3, preserving address
     * and data strobes separately; {@code cycle} is a YM internal-cycle count.
     */
    default void onYm2612BusWrite(long cycle, int busPort, int value,
            PhysicalWriteOrigin origin) {
    }

    /** One PSG data-bus byte at the current native generator-tick count. */
    default void onPsgBusWrite(long tick, int value) {
    }

    /** Delimits a raw-replay segment without fabricating a chip bus write. */
    default void onPhysicalTimelineBoundary(ChipClockDomain domain,
            long clock, PhysicalTimelineBoundary boundary) {
    }
}

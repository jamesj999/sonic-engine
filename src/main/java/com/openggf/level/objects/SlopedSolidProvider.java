package com.openggf.level.objects;

public interface SlopedSolidProvider extends SolidObjectProvider {
    byte[] getSlopeData();

    boolean isSlopeFlipped();

    /**
     * Returns the signed slope sample at the already-transformed byte index.
     * <p>
     * Most callers use a bounded table, but the original 68000 helpers perform
     * 16-bit index arithmetic before reading {@code (a2,d5.w)}. Objects that
     * intentionally depend on wrapped ROM bytes can override this method and
     * source those bytes from the loaded ROM.
     */
    default Integer sampleSlopeByte(int sampleIndex) {
        byte[] data = getSlopeData();
        if (data == null || sampleIndex < 0 || sampleIndex >= data.length) {
            return null;
        }
        return (int) (byte) data[sampleIndex];
    }

    default SlopedSolidRoutineProfile getSlopedSolidRoutineProfile() {
        return SlopedSolidRoutineProfile.fromProvider(this);
    }

    /**
     * Whether a player who is not already riding this object should resolve new
     * top contact against slope samples instead of the object's flat solid bounds.
     */
    default boolean usesSlopeForNewLanding() {
        return true;
    }

    /**
     * Whether grounded players may treat the slope catch window as standing before the
     * generic side-vs-top classifier would otherwise push them away.
     */
    default boolean usesGroundedStandingCatchWindow() {
        return false;
    }

    /**
     * Whether the sloped contact's vertical overlap value includes the helper's
     * slope catch range before side/top-bottom classification.
     * <p>
     * Sonic 1 SolidObject2F does this explicitly:
     * {@code add.w d3,d2} where {@code d2} is the object catch range, followed by
     * {@code add.w d2,d3} before branching to the generic solid classifier.
     */
    default boolean addsSlopeCatchRangeToVerticalOverlap() {
        return false;
    }

    /**
     * Returns the baseline value to subtract from slope samples.
     * Default implementation returns slopeData[0] (existing behavior for most objects).
     * Override to return 0 for objects like Seesaw where the ROM uses absolute slope values.
     */
    default int getSlopeBaseline() {
        byte[] data = getSlopeData();
        return (data != null && data.length > 0) ? (byte) data[0] : 0;
    }
}

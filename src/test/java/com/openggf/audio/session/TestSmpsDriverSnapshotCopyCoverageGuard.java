package com.openggf.audio.session;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every component of the driver snapshot must survive the session's copy.
 *
 * <p>The copy exists to swap the session-owned metadata, the saved overrides
 * and the pending service, and to carry everything else through untouched. It
 * lists its arguments positionally, so a component added to the record and not
 * added there is dropped in silence: the capture keeps it, the copy zeroes it,
 * and a rewind round trip loses whatever it held. That is how the driver's four
 * fade counters and its driver-owned-fade flag were lost, freezing a fade's
 * attenuation wherever a capture happened to land.
 *
 * <p>This is the same shape of guard as
 * {@code TestSmpsSequencerConfigCopyCoverageGuard}, and for the same reason: a
 * positional copy of a growing record cannot be trusted to a reviewer's eye.
 */
class TestSmpsDriverSnapshotCopyCoverageGuard {
    @Test
    void everySnapshotComponentSurvivesTheSessionCopy() {
        SmpsDriverSnapshot distinctive = distinctiveSnapshot();
        SmpsDriverSnapshot copied = SmpsDriverSession.copyWithSessionState(
                distinctive, distinctive.savedOverrides(),
                distinctive.pendingService());

        List<String> dropped = new ArrayList<>();
        for (RecordComponent component
                : SmpsDriverSnapshot.class.getRecordComponents()) {
            Object before = read(component, distinctive);
            Object after = read(component, copied);
            if (!Objects.deepEquals(before, after)) {
                dropped.add(component.getName() + " (copied " + before
                        + " as " + after + ")");
            }
        }

        assertEquals(List.of(), dropped,
                "these driver-snapshot components do not survive"
                        + " SmpsDriverSession's copy, so a capture and restore"
                        + " round trip silently loses them. Add each to"
                        + " copyWithSessionState");
    }

    /**
     * A snapshot whose every component differs from the record's zero value,
     * so a dropped component shows up as a difference rather than matching by
     * luck.
     */
    private static SmpsDriverSnapshot distinctiveSnapshot() {
        return new SmpsDriverSnapshot(
                SmpsSequencer.Region.PAL,
                SmpsDriver.ReadMode.HYBRID,
                0x51,
                true,
                3,
                4,
                List.of(),
                new int[] {1, 2, 3, 4, 5, 6},
                new int[] {7, 8, 9, 10},
                List.of(),
                null,
                6,
                5,
                0x28,
                0x40,
                true);
    }

    private static Object read(
            RecordComponent component, SmpsDriverSnapshot snapshot) {
        try {
            return component.getAccessor().invoke(snapshot);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "cannot read " + component.getName(), failure);
        }
    }
}

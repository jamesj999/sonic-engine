package com.openggf.game.sonic3k.resources;

import com.openggf.game.timing.HardwareWorkHandle;

import java.util.List;
import java.util.Objects;

/** Physical direct-FIFO membership, separate from timing payload ownership. */
public record S3kKosDecompressionQueueSnapshot(
        List<Entry> entries,
        boolean deferNewHeadPreparationVisibility) {
    public S3kKosDecompressionQueueSnapshot {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public record Entry(HardwareWorkHandle handle, S3kKosDecompressionDescriptor descriptor,
                        boolean physical, boolean prepared) {
        public Entry {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}

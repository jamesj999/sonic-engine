package com.openggf.level.objects;

import java.util.function.Supplier;

/** Installs one ROM fixed dynamic object in an exact absolute SST slot. */
@FunctionalInterface
public interface FixedSstSlotSink {
    AbstractObjectInstance install(
            int absoluteSlot,
            Supplier<? extends AbstractObjectInstance> factory);
}

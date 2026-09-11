package com.openggf.level.objects;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Owns exact-slot construction for ROM fixed SST occupants. */
final class FixedSstObjectInstaller implements FixedSstSlotSink {
    private final ObjectRegistry registry;
    private final ObjectServices services;
    private final ObjectSlotLayout slotLayout;
    private final SlotAllocator slotAllocator;
    private final Consumer<ObjectInstance> registrar;
    private final IntConsumer slotReleaser;

    FixedSstObjectInstaller(
            ObjectRegistry registry,
            ObjectServices services,
            ObjectSlotLayout slotLayout,
            SlotAllocator slotAllocator,
            Consumer<ObjectInstance> registrar,
            IntConsumer slotReleaser) {
        this.registry = registry;
        this.services = services;
        this.slotLayout = slotLayout;
        this.slotAllocator = slotAllocator;
        this.registrar = registrar;
        this.slotReleaser = slotReleaser;
    }

    void installConfiguredObjects() {
        if (registry != null && services != null) {
            registry.installFixedSstObjects(services.romZoneId(), services.currentAct(), this);
        }
    }

    @Override
    public AbstractObjectInstance install(
            int absoluteSlot,
            Supplier<? extends AbstractObjectInstance> factory) {
        AbstractObjectInstance installed = create(factory, absoluteSlot);
        if (installed == null) {
            throw new IllegalStateException(
                    "ROM fixed SST slot " + absoluteSlot
                            + " is unavailable during object-manager reset");
        }
        return installed;
    }

    <T extends ObjectInstance> T create(Supplier<T> factory, int slotIndex) {
        if (!slotLayout.isDynamicSlot(slotIndex) || !slotAllocator.reserve(slotIndex)) {
            return null;
        }
        return ObjectConstructionContext.construct(services, () -> {
            T object;
            try {
                object = factory.get();
            } catch (RuntimeException | Error ex) {
                slotReleaser.accept(slotIndex);
                throw ex;
            }
            if (object == null) {
                slotReleaser.accept(slotIndex);
                return null;
            }
            if (!(object instanceof AbstractObjectInstance instance)) {
                slotReleaser.accept(slotIndex);
                throw new IllegalArgumentException(
                        "Fixed-slot dynamic objects must extend AbstractObjectInstance");
            }
            instance.setSlotIndex(slotIndex);
            registrar.accept(object);
            return object;
        });
    }
}
